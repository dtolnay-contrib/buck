#!/usr/bin/env python3
# Copyright (c) Facebook, Inc. and its affiliates.
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

from asyncio import subprocess

import pytest
from buck_process import BuckProcess
from buck_result import BuckResult


@pytest.mark.asyncio
async def test_wait():
    awaitable_process = subprocess.create_subprocess_shell(
        "echo hello", stdout=subprocess.PIPE, stderr=subprocess.PIPE
    )
    bp = BuckProcess(awaitable_process, BuckResult, "utf-8")
    br: BuckResult = await bp.wait()
    assert br.get_stdout() == "hello\n"


@pytest.mark.asyncio
async def test_get_out():
    awaitable_process = subprocess.create_subprocess_shell(
        "echo hello", stdout=subprocess.PIPE, stderr=subprocess.PIPE
    )
    bp = BuckProcess(awaitable_process, BuckResult, "utf-8")
    stdout = await bp.get_stdout()
    assert (await stdout.readline()).decode("utf-8") == "hello\n"


@pytest.mark.asyncio
async def test_get_err():
    awaitable_process = subprocess.create_subprocess_shell(
        '>&2 echo "hello"', stdout=subprocess.PIPE, stderr=subprocess.PIPE
    )
    bp = BuckProcess(awaitable_process, BuckResult, "utf-8")
    stderr = await bp.get_stderr()
    assert (await stderr.readline()).decode("utf-8") == "hello\n"
