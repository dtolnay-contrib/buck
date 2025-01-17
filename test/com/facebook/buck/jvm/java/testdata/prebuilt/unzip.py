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

import contextlib
import os
import sys
import zipfile


def extractall(zf, path):
    for name in zf.namelist():
        if name.endswith("/"):
            try:
                os.makedirs(os.path.join(path, name))
            except Exception:
                pass
        else:
            zf.extract(name, path)


def main():
    from_ = sys.argv[1]
    to = sys.argv[2]
    if sys.platform in ("win32", "cygwin"):
        to = "\\\\?\\" + to  # use long path names.

    with contextlib.closing(zipfile.ZipFile(from_)) as zf:
        extractall(zf, to)


if __name__ == "__main__":
    main()
