#!/usr/bin/env fbpython
# Copyright (c) Meta Platforms, Inc. and affiliates.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import argparse
import json
import os
import subprocess
import tempfile


parser = argparse.ArgumentParser(description="Generate SDK dependencies")
parser.add_argument("--sdk", required=True)
parser.add_argument("--resource-dir", required=True)
parser.add_argument("--target", default="x86_64-apple-ios15.0-simulator")
args = parser.parse_args()


with open(os.path.join(args.sdk, "SDKSettings.json")) as f:
    sdk_version = json.load(f)["Version"]

frameworks_path = os.path.join(args.sdk, "System/Library/Frameworks")
swift_lib_path = os.path.join(args.sdk, "usr/lib/swift")

imports = []
for p in os.listdir(swift_lib_path):
    if p.endswith(".swiftmodule") and not p.startswith("_"):
        imports.append(p.split(".")[0])

for p in os.listdir(frameworks_path):
    if p.endswith(".framework") and not p.startswith("_"):
        imports.append(p.split(".")[0])


with tempfile.NamedTemporaryFile(mode="w+t", suffix=".swift") as src:
    for m in imports:
        src.write(f"import {m}\n")

    src.flush()

    with tempfile.NamedTemporaryFile(suffix=".json") as out:
        cmd = [
            "swiftc",
            "-sdk",
            args.sdk,
            "-target",
            args.target,
            "-scan-dependencies",
            src.name,
            "-module-name",
            "ignore",
            "-resource-dir",
            args.resource_dir,
            "-o",
            out.name,
        ]
        subprocess.run(cmd, check=True)
        j = json.load(out)


main_module_name = j["mainModuleName"]
module_iter = iter(j["modules"])
sdk_swiftmodules = []
sdk_clangmodules = []


def prefix_absolute_path(path):
    if path.startswith(args.sdk):
        return "$SDKROOT/" + os.path.relpath(path, start=args.sdk)
    elif path.startswith(args.resource_dir):
        return "$RESOURCEDIR/" + os.path.relpath(path, start=args.resource_dir)
    else:
        raise SystemExit("Unknown path prefix: " + path)


def get_clang_target(pcm_args):
    # Each swiftinterface defines its own compiler target which
    # needs to be propagated to all its clang module dependencies.
    return pcm_args[pcm_args.index("-target") + 2]


while True:
    try:
        module_dict = next(module_iter)
    except StopIteration:
        break

    module_type = list(module_dict.keys())[0]
    module_name = module_dict[module_type]
    details = next(module_iter)
    if module_name == main_module_name:
        # ignore the dummy module
        continue

    swift_deps = []
    clang_deps = []
    for d in details["directDependencies"]:
        if "swift" in d:
            swift_deps.append(d["swift"])
        else:
            clang_deps.append(d["clang"])

    if module_type == "swift":
        sdk_swiftmodules.append(
            {
                "is_framework": details["details"]["swift"]["isFramework"],
                "name": module_name,
                "swiftinterface": prefix_absolute_path(
                    details["details"]["swift"]["moduleInterfacePath"]
                ),
                "swift_deps": swift_deps,
                "target": get_clang_target(details["details"]["swift"]["extraPcmArgs"]),
                "clang_deps": clang_deps,
            }
        )
    else:
        assert len(swift_deps) == 0
        sdk_clangmodules.append(
            {
                "name": module_name,
                "modulemap": prefix_absolute_path(
                    details["details"]["clang"]["moduleMapPath"]
                ),
                "clang_deps": clang_deps,
            }
        )

sdk_deps = {
    "sdk_version": sdk_version,
    "swift": sdk_swiftmodules,
    "clang": sdk_clangmodules,
}
print(json.dumps(sdk_deps, indent=4))
