load("@rules_java//java:defs.bzl", "java_test")

def _test_prefix(src):
    if src.startswith("src/dev/test/java/"):
        return "src/dev/test/java/"
    return "src/test/java/"

def _test_class(src):
    prefix = _test_prefix(src)
    suffix = ".java"
    if not src.startswith(prefix) or not src.endswith(suffix):
        fail("JUnit test source must be under %s and end with %s: %s" % (prefix, suffix, src))
    return src[len(prefix):-len(suffix)].replace("/", ".")

def _test_name(src):
    prefix = _test_prefix(src)
    return src.replace(prefix, "").replace("/", "_").replace(".java", "")

def junit_tests(name, srcs, deps = [], data = [], resources = [], **kwargs):
    for src in srcs:
        java_test(
            name = "%s_%s" % (name, _test_name(src)),
            srcs = [src],
            test_class = _test_class(src),
            deps = deps,
            data = data,
            resources = resources,
            **kwargs
        )
