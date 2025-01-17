# Copyright (c) Meta Platforms, Inc. and affiliates.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.

"""Module containing java macros."""

load("@buck_bazel_skylib//lib:collections.bzl", "collections")
load("//tools/build_rules:module_rules_for_tests.bzl", "convert_module_deps_to_test")

def _append_and_get_uniq_deps(kwargs, key, new_deps):
    return list(collections.uniq(list(kwargs.get(key, [])) + new_deps))

def _add_immutables(deps_arg, **kwargs):
    kwargs[deps_arg] = _append_and_get_uniq_deps(kwargs, deps_arg, [
        "//src/com/facebook/buck/core/util/immutables:immutables",
        "//third-party/java/errorprone:error-prone-annotations",
        "//third-party/java/immutables:immutables",
        "//third-party/java/guava:guava",
        "//third-party/java/jsr:jsr305",
    ])
    kwargs["plugins"] = _append_and_get_uniq_deps(kwargs, "plugins", [
        "//third-party/java/immutables:processor",
    ])
    return kwargs

def _maybe_add_java_version(**kwargs):
    if "source" not in kwargs and "target" not in kwargs and "java_version" not in kwargs:
        kwargs["java_version"] = "11"
    return kwargs

def _add_wrapper_label(**kwargs):
    if "labels" not in kwargs:
        kwargs["labels"] = []
    kwargs["labels"] += ["wrapped_with_buck_java_rules"]
    return kwargs

# Returns true if build.is_oss is set to true; this should indicate that the current build is
# being done for the purpose of an oss release.
def _is_oss_build():
    return native.read_config("build", "is_oss", "false") == "true"

def _add_os_labels(**kwargs):
    if "labels" not in kwargs:
        kwargs["labels"] = []

    if native.host_info().os.is_macos:
        kwargs["labels"] += ["tpx:platform:macos"]
    if native.host_info().os.is_linux:
        kwargs["labels"] += ["tpx:platform:linux"]
    if native.host_info().os.is_windows:
        kwargs["labels"] += ["tpx:platform:windows"]

    if _is_oss_build():
        kwargs["labels"] += ["tpx:is_oss_build"]

    return kwargs



def buck_java_library(name, **kwargs):
    kwargs = _add_wrapper_label(**kwargs)
    kwargs = _maybe_add_java_version(**kwargs)
    return native.java_library(
        name = name,
        **kwargs
    )

def buck_java_binary(name, **kwargs):
    kwargs = _add_wrapper_label(**kwargs)
    return native.java_binary(
        name = name,
        **kwargs,
    )

def buck_prebuilt_jar(name, **kwargs):
    kwargs = _add_wrapper_label(**kwargs)
    return native.prebuilt_jar(
        name = name,
        **kwargs,
    )

def java_immutables_library(name, **kwargs):
    return buck_java_library(
        name = name,
        **_add_immutables("deps", **kwargs)
    )

def _shallow_dict_copy_without_key(table, key_to_omit):
    """Returns a shallow copy of dict with key_to_omit omitted."""
    return {key: table[key] for key in table if key != key_to_omit}

def java_test(
        name,
        vm_args = None,
        labels = None,
        run_test_separately = False,
        has_immutable_types = False,
        module_deps = [],
        # deps, provided_deps and plugins are handled in kwargs so that immutables can be handled there
        **kwargs):
    """java_test wrapper that provides sensible defaults for buck tests.

    Args:
      name: name
      vm_args: vm_args
      labels: labels
      run_test_separately: run_test_separately
      has_immutable_types: has_immutable_types
      module_deps: A list of modules this test depends on
      **kwargs: kwargs
    """

    extra_labels = ["run_as_bundle"]
    if run_test_separately:
        extra_labels.append("serialize")

    if has_immutable_types:
        kwargs = _add_immutables("deps", **kwargs)

    if "deps" in kwargs:
        deps = kwargs["deps"]
        kwargs = _shallow_dict_copy_without_key(kwargs, "deps")
    else:
        deps = []

    if "env" in kwargs:
        env = kwargs["env"]
        kwargs = _shallow_dict_copy_without_key(kwargs, "env")
    else:
        env = {}

    kwargs = _add_wrapper_label(**kwargs)
    kwargs = _add_os_labels(**kwargs)
    kwargs["labels"] += extra_labels

    native.java_test(
        name = name,
        deps = deps + [
            # When actually running Buck, the launcher script loads the bootstrapper,
            # and the bootstrapper loads the rest of Buck. For unit tests, which don't
            # run Buck, we have to add a direct dependency on the bootstrapper in case
            # they exercise code that uses it.
            "//src/com/facebook/buck/cli/bootstrapper:bootstrapper_lib",
        ] + convert_module_deps_to_test(module_deps),
        vm_args = [
            # Don't use the system-installed JNA; extract it from the local jar.
            "-Djna.nosys=true",

            # Add -Dsun.zip.disableMemoryMapping=true to work around a JDK issue
            # related to modifying JAR/ZIP files that have been loaded into memory:
            #
            # http://bugs.sun.com/view_bug.do?bug_id=7129299
            #
            # This has been observed to cause a problem in integration tests such as
            # CachedTestIntegrationTest where `buck build //:test` is run repeatedly
            # such that a corresponding `test.jar` file is overwritten several times.
            # The CompiledClassFileFinder in JavaTestRule creates a java.util.zip.ZipFile
            # to enumerate the zip entries in order to find the set of .class files
            # in `test.jar`. This interleaving of reads and writes appears to match
            # the conditions to trigger the issue reported on bugs.sun.com.
            #
            # Currently, we do not set this flag in bin/buck_common, as Buck does not
            # normally modify the contents of buck-out after they are loaded into
            # memory. However, we may need to use this flag when running buckd where
            # references to zip files may be long-lived.
            #
            # Finally, note that when you specify this flag,
            # `System.getProperty("sun.zip.disableMemoryMapping")` will return `null`
            # even though you have specified the flag correctly. Apparently sun.misc.VM
            # (http://www.docjar.com/html/api/sun/misc/VM.java.html) saves the property
            # internally, but removes it from the set of system properties that are
            # publicly accessible.
            "-Dsun.zip.disableMemoryMapping=true",
        ] + (vm_args or []),
        env = env,
        run_test_separately = run_test_separately,
        **kwargs
    )

def standard_java_test(
        name,
        run_test_separately = False,
        vm_args = None,
        fork_mode = "none",
        labels = None,
        with_test_data = False,
        **kwargs):
    test_srcs = native.glob(["*Test.java"])

    if len(test_srcs) > 0:
        java_test(
            name = name,
            srcs = test_srcs,
            resources = native.glob(["testdata/**"]) if with_test_data else [],
            vm_args = vm_args,
            run_test_separately = run_test_separately,
            fork_mode = fork_mode,
            labels = labels or [],
            **kwargs
        )

def standard_java_benchmark(
        name,
        deps):
    buck_java_library(
        name = name,
        srcs = native.glob(["*Benchmark.java"]),
        plugins = ["//third-party/java/jmh:jmh-generator-annprocess-plugin"],
        deps = deps + [
            "//third-party/java/jmh:jmh",
        ],
    )

def _add_pf4j_plugin_framework(**kwargs):
    kwargs["provided_deps"] = _append_and_get_uniq_deps(kwargs, "provided_deps", [
        "//third-party/java/pf4j:pf4j",
    ])
    kwargs["plugins"] = _append_and_get_uniq_deps(kwargs, "plugins", [
        "//third-party/java/pf4j:processor",
    ])
    kwargs["annotation_processor_params"] = _append_and_get_uniq_deps(kwargs, "annotation_processor_params", [
        "pf4j.storageClassName=org.pf4j.processor.ServiceProviderExtensionStorage",
    ])
    return kwargs

def _add_buck_modules_annotation_processor(**kwargs):
    kwargs["plugins"] = _append_and_get_uniq_deps(kwargs, "plugins", [
        "//src/com/facebook/buck/core/module/annotationprocessor:annotationprocessor",
    ])
    return kwargs

def java_library_with_plugins(name, **kwargs):
    kwargs_with_immutables = _add_immutables("provided_deps", **kwargs)
    kawgs_with_plugins = _add_pf4j_plugin_framework(**kwargs_with_immutables)
    kawgs_with_buck_modules_annotation_processor = _add_buck_modules_annotation_processor(**kawgs_with_plugins)
    return buck_java_library(
        name = name,
        **kawgs_with_buck_modules_annotation_processor
    )
