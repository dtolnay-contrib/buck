""" Module docstring """

def _impl(_ctx):
    """ Function docstring """
    pass

some_rule = rule(
    attrs = {
        "attr1": attr.int(
            default = 2,
            mandatory = False,
        ),
        "name": attr.int(
            default = 1,
            mandatory = False,
        ),
    },
    implementation = _impl,
)
