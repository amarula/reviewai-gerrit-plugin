load("@rules_java//java:defs.bzl", "java_binary", "java_library")

def gerrit_plugin(name, srcs, manifest_entries = [], resources = [], deps = [], runtime_deps = [], **kwargs):
    """Builds a deployable Gerrit plugin jar."""
    visibility = kwargs.pop("visibility", ["//visibility:public"])

    java_library(
        name = name + "_lib",
        srcs = srcs,
        resources = resources,
        deps = deps,
        **kwargs
    )

    java_binary(
        name = name + "_deploy",
        main_class = "com.googlesource.gerrit.plugins.reviewai.Module",
        runtime_deps = [":" + name + "_lib"] + runtime_deps,
        deploy_manifest_lines = manifest_entries,
    )

    native.genrule(
        name = name,
        srcs = [":" + name + "_deploy_deploy.jar"],
        outs = ["%s.jar" % name],
        cmd = "cp $< $@",
        visibility = visibility,
    )
