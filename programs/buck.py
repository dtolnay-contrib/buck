#!/usr/bin/env python
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


from __future__ import print_function

import errno
import logging
import os
import re
import signal
import subprocess
import sys
import threading
import time
import uuid
import zipfile
from multiprocessing import Queue
from subprocess import check_output

from programs.buck_logging import setup_logging
from programs.buck_project import BuckProject, NoBuckConfigFoundException
from programs.buck_sosreport import sosreport_main
from programs.buck_tool import (
    BuckDaemonErrorException,
    BuckStatusReporter,
    ExitCode,
    ExitCodeCallable,
    install_signal_handlers,
)
from programs.java_lookup import get_java_path
from programs.java_version import get_java_major_version
from programs.subprocutils import propagate_failure
from programs.tracing import Tracing

JPS_EXECUTABLE = "jps.exe"


if sys.version_info < (2, 7):
    import platform

    print(
        (
            "Buck requires at least version 2.7 of Python, but you are using {}."
            "\nPlease follow https://dev.buck.build/setup/getting_started.html "
            + "to properly setup your development environment."
        ).format(platform.version())
    )
    sys.exit(ExitCode.FATAL_BOOTSTRAP)


THIS_DIR = os.path.dirname(os.path.realpath(__file__))


# Kill all buck processes
def killall_buck(reporter):
    reporter.no_buckd_reason = "killall"
    # Linux or macOS
    if os.name != "posix" and os.name != "nt":
        message = "killall is not implemented on: " + os.name
        logging.error(message)
        reporter.status_message = message
        return ExitCode.COMMANDLINE_ERROR

    if os.name == "nt":
        # ensure jps.exe is installed and can be found
        full_jps_path, error = _find_jps_executable()
        if full_jps_path == "":
            message = error
            logging.error(message)
            reporter.status_message = message
            return ExitCode.FATAL_BOOTSTRAP
        # create the command line
        jps_command_line = '"' + full_jps_path + '"' + " -l"
    else:
        jps_command_line = "jps -l"

    for line in os.popen(jps_command_line):
        split = line.strip().split()
        if len(split) != 2:
            logging.warning("skipping irregular format line in jps -l: %s", repr(line))
            continue
        name = split[1]
        if name != "com.facebook.buck.cli.bootstrapper.ClassLoaderBootstrapper":
            continue
        try:
            pid = int(split[0])
        except ValueError:
            logging.warning("skipping irregular format line in jps -l: %s", repr(line))
            continue

        os.kill(pid, signal.SIGTERM)
        # TODO(buck_team) clean .buckd directories
    return ExitCode.SUCCESS


# Invoke SOS report
def sosreport(reporter):
    reporter.no_buckd_reason = "sosreport"
    if os.name == "nt":
        # TODO: windows platform version
        message = "sosreport is not implemented on: " + os.name
        logging.error(message)
        reporter.status_message = message
        return ExitCode.COMMANDLINE_ERROR
    # TODO: opt parse and pass project root to sosreport_main()
    return sosreport_main()


def _find_jps_executable():
    """
    Returns the full path of the jps.exe executable as long as it can
    be found in the most likely location.
    If it can't be found, the function returns an emptry string together
    with the error message
    """
    found_jdk = False
    try:
        # first, check if JAVA_HOME is set and file is there (common case)
        java_bin_path = os.path.join("$JAVA_HOME", "bin")
        full_jps_path = os.path.expandvars(os.path.join(java_bin_path, JPS_EXECUTABLE))
        if os.path.isfile(full_jps_path):
            return full_jps_path, ""
        # second, manually check for JDK dirs
        program_files_path = os.path.expandvars("$PROGRAMFILES")
        java_path = os.path.join(program_files_path, "Java")
        java_dir_list = os.listdir(java_path)
        for entry in java_dir_list:
            if entry.startswith("jdk"):
                found_jdk = True
                full_jps_path = os.path.join(java_path, entry, "bin", JPS_EXECUTABLE)
                if os.path.isfile(full_jps_path):
                    return full_jps_path, ""
        # third, check if it exists anywhere else in the PATH
        for path in os.environ["PATH"].split(os.pathsep):
            full_jps_path = os.path.join(path, JPS_EXECUTABLE)
            if os.path.isfile(full_jps_path):
                return full_jps_path, ""
        # return appropriate error if it wasn't found
        if found_jdk:
            return (
                "",
                'jps.exe could not be found on this system. Please run "choco install" to install it.',
            )
        else:
            return (
                "",
                'Could not find a JDK on this system. Please run "choco install -y openjdk11" to install it.',
            )

    except OSError as error:
        return "", "Unhandled error while searching for jps.exe: " + error


def _get_java_version(java_path):
    """
    Returns a Java version string (e.g. "7", "8").

    Information is provided by java tool and parsing is based on
    http://www.oracle.com/technetwork/java/javase/versioning-naming-139433.html
    """
    java_version = check_output(
        [java_path, "-version"], stderr=subprocess.STDOUT
    ).decode("utf-8")
    # extract java version from a string like 'java version "1.8.0_144"' or
    # 'openjdk version "11.0.1" 2018-10-16'
    match = re.search('(java|openjdk) version "(?P<version>.+)"', java_version)
    if not match:
        return None
    return get_java_major_version(match.group("version"))


def _try_to_verify_java_version(
    java_version_status_queue, java_path, required_java_version
):
    """
    Best effort check to make sure users have required Java version installed.
    """
    warning = None
    try:
        java_version = _get_java_version(java_path)
        if java_version and java_version != required_java_version:
            warning = "You're using Java {}, but Buck requires Java {}.".format(
                java_version, required_java_version
            )
            # warning += (
            #     " Please update JAVA_HOME if it's pointing at the wrong version of Java."
            #     + "\nPlease follow https://dev.buck.build/setup/getting_started.html"
            #     + " to properly setup your local environment and avoid build issues."
            # )
    except:
        # checking Java version is brittle and as such is best effort
        warning = "Cannot verify that installed Java version at '{}' \
is correct.".format(
            java_path
        )
    java_version_status_queue.put(warning)


def _try_to_verify_java_version_off_thread(
    java_version_status_queue, java_path, required_java_version
):
    """Attempts to validate the java version off main execution thread.
    The reason for this is to speed up the start-up time for the buck process.
    testing has shown that starting java process is rather expensive and on local tests,
    this optimization has reduced startup time of 'buck run' from 673 ms to 520 ms."""
    verify_java_version_thread = threading.Thread(
        target=_try_to_verify_java_version,
        args=(java_version_status_queue, java_path, required_java_version),
    )
    verify_java_version_thread.daemon = True
    verify_java_version_thread.start()


def _emit_java_version_warnings_if_any(java_version_status_queue):
    """Emits java_version warnings that got posted in the java_version_status_queue
    queus from the java version verification thread.
    There are 2 cases where we need to take special care for.
     1. The main thread finishes before the main thread gets here before the version testing
     thread is done. In such case we wait for 50 ms. This should pretty much never happen,
     except in cases where buck deployment or the VM is really badly misconfigured.
     2. The java version thread never testing returns. This can happen if the process that is
     called java is hanging for some reason. This is also not a normal case, and in such case
     we will wait for 50 ms and if still no response, ignore the error."""
    if java_version_status_queue.empty():
        time.sleep(0.05)

    if not java_version_status_queue.empty():
        warning = java_version_status_queue.get()
        if warning is not None:
            logging.warning(warning)


def main(argv, reporter):
    # Change environment at startup to ensure we don't have any other threads
    # running yet.
    # We set BUCK_ROOT_BUILD_ID to ensure that if we're called in a nested fashion
    # from, say, a genrule, we do not reuse the UUID, and logs do not end up with
    # confusing / incorrect data
    # TODO: remove ability to inject BUCK_BUILD_ID completely. It mostly causes
    #       problems, and is not a generally useful feature for users.
    if "BUCK_BUILD_ID" in os.environ and "BUCK_ROOT_BUILD_ID" not in os.environ:
        build_id = os.environ["BUCK_BUILD_ID"]
    elif "BUCK_WRAPPER_UUID" in os.environ:
        build_id = os.environ["BUCK_WRAPPER_UUID"]
    else:
        build_id = str(uuid.uuid4())
    if "BUCK_ROOT_BUILD_ID" not in os.environ:
        os.environ["BUCK_ROOT_BUILD_ID"] = build_id
    reporter.build_id = build_id

    java_version_status_queue = Queue(maxsize=1)

    def get_repo(p):
        # Try to detect if we're running a PEX by checking if we were invoked
        # via a zip file.
        if zipfile.is_zipfile(argv[0]):
            from programs.buck_package import BuckPackage

            return BuckPackage(p, reporter)
        else:
            from programs.buck_repo import BuckRepo

            return BuckRepo(THIS_DIR, p, reporter)

    def kill_buck(reporter):
        buck_repo = get_repo(BuckProject.from_current_dir())
        buck_repo.kill_buckd()
        reporter.no_buckd_reason = "kill"
        return ExitCode.SUCCESS

    # Execute wrapper specific commands
    wrapper_specific_commands = [
        ("kill", kill_buck),
        ("killall", killall_buck),
        ("sosreport", sosreport),
    ]
    if "--help" not in argv and "-h" not in argv:
        for command_str, command_fcn in wrapper_specific_commands:
            if len(argv) > 1 and argv[1] == command_str:
                return ExitCodeCallable(command_fcn(reporter))

    install_signal_handlers()
    try:
        tracing_dir = None
        with Tracing("main"):
            with BuckProject.from_current_dir() as project:
                tracing_dir = os.path.join(project.get_buck_out_log_dir(), "traces")
                with get_repo(project) as buck_repo:
                    required_java_version = buck_repo.get_buck_compiled_java_version()
                    java_path = get_java_path(required_java_version)
                    _try_to_verify_java_version_off_thread(
                        java_version_status_queue, java_path, required_java_version
                    )

                    return buck_repo.launch_buck(build_id, os.getcwd(), java_path, argv)
    except NoBuckConfigFoundException:
        from programs.buck_tool import CommandLineArgs

        parsed_args = CommandLineArgs(sys.argv)
        # If we're in a PEX, not in a buck project dir, and the version was requested,
        # print out the version
        if parsed_args.is_version() and zipfile.is_zipfile(argv[0]):
            from programs.buck_package import BuckPackage

            print(BuckPackage.get_buck_version())
            return ExitCodeCallable(ExitCode.SUCCESS)
        raise
    finally:
        if tracing_dir:
            Tracing.write_to_dir(tracing_dir, build_id)
        _emit_java_version_warnings_if_any(java_version_status_queue)


if __name__ == "__main__":
    exit_code = ExitCode.SUCCESS
    reporter = BuckStatusReporter(sys.argv)
    exit_code_callable = None
    exception = None
    exc_type = None
    exc_traceback = None
    try:
        setup_logging()
        exit_code_callable = main(sys.argv, reporter)
        # Grab the original exit code here for logging. If this callable does something
        # more advanced (like exec) we want to make sure that at least the original
        # code is logged
        exit_code = exit_code_callable.exit_code
    except NoBuckConfigFoundException as e:
        exception = e
        # buck is started outside project root
        exit_code = ExitCode.COMMANDLINE_ERROR
    except BuckDaemonErrorException:
        reporter.status_message = "Buck daemon disconnected unexpectedly"
        _, exception, _ = sys.exc_info()
        print(str(exception))
        exception = None
        exit_code = ExitCode.FATAL_GENERIC
    except IOError as e:
        exc_type, exception, exc_traceback = sys.exc_info()
        if e.errno == errno.ENOSPC:
            exit_code = ExitCode.FATAL_DISK_FULL
        elif e.errno == errno.EPIPE:
            exit_code = ExitCode.SIGNAL_PIPE
        else:
            exit_code = ExitCode.FATAL_IO
    except KeyboardInterrupt:
        reporter.status_message = "Python wrapper keyboard interrupt"
        exit_code = ExitCode.SIGNAL_INTERRUPT
    except Exception:
        exc_type, exception, exc_traceback = sys.exc_info()
        exit_code = ExitCode.FATAL_BOOTSTRAP

    if exception is not None:
        # If exc_info is non-None, a stacktrace is printed out, which we don't always
        # want, but we want the exception data for the reporter
        exc_info = None
        if exc_type and exc_traceback:
            exc_info = (exc_type, exception, exc_traceback)
        logging.error(exception, exc_info=exc_info)
        if reporter.status_message is None:
            reporter.status_message = str(exception)

    # report result of Buck call
    try:
        reporter.report(exit_code)
    except Exception as e:
        logging.debug(
            "Exception occurred while reporting build results. This error is "
            "benign and doesn't affect the actual build.",
            exc_info=True,
        )

    # execute 'buck run' target
    if exit_code_callable is not None:
        exit_code = exit_code_callable()

    propagate_failure(exit_code)
