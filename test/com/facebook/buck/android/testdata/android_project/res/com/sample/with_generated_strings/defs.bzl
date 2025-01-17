def get_fake_strings_content():
    content = '"'
    content += "<resources>\n"
    content += '  <string name=\\"gen_what\\">is this</string>\n'
    content += '  <string name=\\"gen_who\\">are you</string>\n'
    content += "</resources>"
    content += '"'
    return content

def fake_android_resource_strings(**kwargs):
    name = kwargs.get("name")
    res = kwargs.get("res")
    fake_strings_rule = "{0}_fake_strings".format(name)
    native.genrule(
        name = fake_strings_rule,
        out = "res",
        cmd = "".join([
            "mkdir -p ${OUT}/values && ",
            "echo " + get_fake_strings_content() + " > ${OUT}/values/strings.xml",
        ]),
    )
    kwargs["res"] = ":" + fake_strings_rule
    native.android_resource(**kwargs)
