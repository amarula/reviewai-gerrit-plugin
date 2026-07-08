load("@rules_java//java:defs.bzl", "java_test")

def _test_class(src, prefix = "src/test/java/"):
    suffix = ".java"
    if not src.startswith(prefix) or not src.endswith(suffix):
        fail("JUnit test source must be under %s and end with %s: %s" % (prefix, suffix, src))
    return src[len(prefix):-len(suffix)].replace("/", ".")

def _test_name(src, prefix = "src/test/java/"):
    return src.replace(prefix, "").replace("/", "_").replace(".java", "")

def junit_tests(name, srcs, deps = [], data = [], resources = [], src_prefix = "src/test/java/", **kwargs):
    for src in srcs:
        java_test(
            name = "%s_%s" % (name, _test_name(src, src_prefix)),
            srcs = [src],
            test_class = _test_class(src, src_prefix),
            deps = deps,
            data = data,
            resources = resources,
            **kwargs
        )
