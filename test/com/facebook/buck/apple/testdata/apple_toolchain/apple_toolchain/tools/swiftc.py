#!/usr/bin/env python3

import sys

from tools import impl


parser = impl.argparser()

parser.add_argument("-emit-object", dest="compile", action="store_true")
parser.add_argument("-o", dest="output", action=impl.StripQuotesAction)
parser.add_argument("-target")
parser.add_argument("-module-name")
parser.add_argument("-emit-module", action="store_true")
parser.add_argument("-emit-module-path")
parser.add_argument("-emit-objc-header-path")
parser.add_argument("-enable-objc-interop", action="store_true")
parser.add_argument("-parse-as-library", action="store_true")
parser.add_argument("-serialize-debugging-options", action="store_true")
parser.add_argument("-test-arg1", action="store_true")
parser.add_argument("-test-arg2", action="store_true")
parser.add_argument("-prefix-serialized-debugging-options", action="store_true")
parser.add_argument("-sdk")
parser.add_argument("-resource-dir")

(options, args) = parser.parse_known_args()

impl.log(args)
impl.log(options.output)

assert options.compile
assert options.target.startswith("arm64-apple-ios")
assert options.test_arg1
assert options.test_arg2
assert options.emit_module

sources = [arg for arg in args if arg.endswith(".swift")]

with open(options.output, "w") as output:
    for source in sources:
        output.write("swift compile: ")
        with open(source) as inputfile:
            output.write(inputfile.read())

    if options.sdk:
        output.write(f"swift flags: -sdk {options.sdk}\n")

    if options.resource_dir:
        output.write(f"swift flags: -resource-dir {options.resource_dir}\n")

    if options.prefix_serialized_debugging_options:
        output.write("swift flags: -prefix-serialized-debugging-options\n")

with open(options.emit_module_path, "w") as module:
    module.write("Module: " + options.module_name + "\n")

sys.exit(0)
