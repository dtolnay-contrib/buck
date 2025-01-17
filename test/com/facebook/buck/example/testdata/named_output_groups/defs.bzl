def menu(name, foods = []):
    """
    A rule that takes in a series of "foods" and produces associated text files.

    The output can be referred to in the target graph or built in the command line. For example, for a food named "sushi":
      buck build //:target_name[sushi]

    The String in the square brackets following the target name is referred to as an "output label."
      In the example above, the output label is "sushi."

    The set of outputs provided by a build target with output label is referred to as a "named output group."
      In the example above, the outputs produced from building //:target_name[sushi] would be a named output group.

    If no output label is specified, the outputs are referred to as the default output group. The default output group for this macro is empty.
      In the example above, the outputs produced from building //:target_name would be the default output group.

    To refer to a rule's named outputs, a mapping from an output's name to its outputs must be defined.

    For genrule, this mapping is defined in its 'outs' arg. The genrule contract enforces that any outputs declared in 'outs' must be created after executing the genrule.

    Genrule's default output group is defined by its 'default_outs' arg. This arg must be present if 'outs' is present. Otherwise it does not apply.

    Note that for both default and named output groups, each group can currently only hold one output. For example:
      default_outs = [ "output_one", ]
    is valid, whereas
      default_outs = [ "output_one", "output_two", ]
    is not valid.

    Params:
        name: name of the genrule to be created
        foods: list of arbitrary strings. An output label will be created for each of these items.
          e.g. for `menu(name = "fuud", foods=["saba"],)`, `//:fuud` would have the output label `saba`
    """
    if len(foods) == 0:
        fail("At least one food must be provided")
    outputs = {}
    bash = []
    cmd_exe = []
    for food in foods:
        file_name = food + ".txt"
        outputs[food] = [file_name]
        bash.append("echo \'{} \' > $OUT/{}".format(food, file_name))
        cmd_exe.append("(echo {} )> $OUT\\{}".format(food, file_name))
    native.genrule(
        name = name,
        outs = outputs,
        default_outs = [],
        bash = " && ".join(bash),
        cmd_exe = " && ".join(cmd_exe),
    )

def eat(name, srcs, out):
    """
    A rule that prepends an output text file with "I ate:", then appends the contents of the given srcs to the output text file.

    Params:
        name: name of the genrule to be created
        srcs: list of targets whose outputs should be text files to be appended after the phrase "I ate: "
        out: output file name that is also the default output as well as the output label to the genrule
    """
    bash = []
    cmd_exe = []
    bash.append("echo -n \'I ate \'>$OUT/{}".format(out))
    cmd_exe.append("(echo I ate )>>$OUT\\{}".format(out))
    for src in srcs:
        bash.append("cat $(location {})>>$OUT/{}".format(src, out))
        cmd_exe.append("type $(location {})>>$OUT\\{}".format(src, out))
    native.genrule(
        name = name,
        outs = {
            out: [out],
        },
        default_outs = [out],
        bash = " && ".join(bash),
        cmd_exe = " && ".join(cmd_exe),
    )
