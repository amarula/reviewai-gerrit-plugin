load("@rules_java//java:defs.bzl", "java_binary", "java_library")

def gerrit_plugin(name, srcs, manifest_entries = [], resources = [], deps = [], runtime_deps = [], **kwargs):
    """Builds a deployable Gerrit plugin jar."""
    visibility = kwargs.pop("visibility", ["//visibility:public"])
    target_name = name + kwargs.pop("target_suffix", "")
    kwargs.pop("dir_name", None)

    java_library(
        name = target_name + "_lib",
        srcs = srcs,
        resources = resources,
        deps = deps,
        **kwargs
    )

    java_binary(
        name = target_name + "_deploy",
        main_class = "com.googlesource.gerrit.plugins.reviewai.Module",
        runtime_deps = [":" + target_name + "_lib"] + runtime_deps,
        deploy_manifest_lines = manifest_entries,
    )

    native.genrule(
        name = target_name,
        srcs = [":" + target_name + "_deploy_deploy.jar"],
        outs = ["%s.jar" % target_name],
        cmd = "cp $< $@",
        visibility = visibility,
    )
