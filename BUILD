load("//tools/bzl:plugin.bzl", "gerrit_plugin")
load("//tools/bzl:junit.bzl", "junit_tests")
load("@rules_java//java:defs.bzl", "java_library", "java_plugin", "java_import")
load(":defs.bzl", "plugin_package", "stamp_plugin_jar")

PRODUCTION_SRCS = glob(
    ["src/main/java/**/*.java"],
    exclude = [
        "src/main/java/**/logging/LoggerFilterDecider.java",
        "src/main/java/**/logging/LoggerFilterDeployed.java",
        "src/main/java/**/logging/LoggingConfigurationDeployed.java",
    ],
)

EXTERNAL_PLUGIN_DEPS = [
    "@reviewai_plugin_deps//:com_openai_openai_java_core",
    "@reviewai_plugin_deps//:dev_langchain4j_langchain4j_core",
    "@reviewai_plugin_deps//:dev_langchain4j_langchain4j",
    "@reviewai_plugin_deps//:dev_langchain4j_langchain4j_open_ai",
    "@reviewai_plugin_deps//:dev_langchain4j_langchain4j_google_ai_gemini",
    "@reviewai_plugin_deps//:dev_langchain4j_langchain4j_ollama",
    "@reviewai_plugin_deps//:com_openai_openai_java_client_okhttp",
    "@reviewai_plugin_deps//:com_h2database_h2",
    "@reviewai_plugin_deps//:org_apache_commons_commons_collections4",
]

PLUGIN_DEPS = [
    ":lombok",
    ":provided_deps",
] + EXTERNAL_PLUGIN_DEPS

PLUGIN_VERSION = "4.0.0"

gerrit_plugin(
    name = "reviewai-gerrit-plugin",
    srcs = PRODUCTION_SRCS,
    manifest_entries = [
        "Gerrit-PluginName: reviewai-gerrit-plugin",
        "Gerrit-Module: com.googlesource.gerrit.plugins.reviewai.Module",
        "Gerrit-HttpModule: com.googlesource.gerrit.plugins.reviewai.HttpModule",
        "Implementation-Vendor: Amarula Solutions",
        "Implementation-URL: https://github.com/amarula/reviewai-gerrit-plugin",
        "Implementation-Title: AI Code Review Gerrit Plugin",
        "Implementation-Version: " + PLUGIN_VERSION,
        "Gerrit-ApiType: plugin",
        "Gerrit-ApiVersion: 3.13.1",
    ],
    resources = glob(["src/main/resources/**/*"]),
    deps = PLUGIN_DEPS,
    target_suffix = "__bazlets",
)

stamp_plugin_jar(
    name = "reviewai-gerrit-plugin",
    src = ":reviewai-gerrit-plugin__bazlets",
    out = "reviewai-gerrit-plugin.jar",
    version = PLUGIN_VERSION,
    visibility = ["//visibility:public"],
    workspace_marker = ":defs.bzl",
)

gerrit_plugin(
    name = "reviewai-gerrit-plugin-dev",
    dir_name = "reviewai-gerrit-plugin",
    srcs = PRODUCTION_SRCS + glob(["src/dev/main/java/**/*.java"]),
    manifest_entries = [
        "Gerrit-PluginName: reviewai-gerrit-plugin",
        "Gerrit-Module: com.googlesource.gerrit.plugins.reviewai.DevModule",
        "Gerrit-HttpModule: com.googlesource.gerrit.plugins.reviewai.HttpModule",
        "Implementation-Vendor: Amarula Solutions",
        "Implementation-URL: https://github.com/amarula/reviewai-gerrit-plugin",
        "Implementation-Title: AI Code Review Gerrit Plugin Dev",
        "Implementation-Version: " + PLUGIN_VERSION + "-dev",
        "Gerrit-ApiType: plugin",
        "Gerrit-ApiVersion: 3.13.1",
    ],
    resources = glob([
        "src/main/resources/**/*",
    ]) + glob(["src/dev/main/resources/**/*"], allow_empty = True),
    deps = PLUGIN_DEPS,
    target_suffix = "__bazlets",
)

stamp_plugin_jar(
    name = "reviewai-gerrit-plugin-dev",
    src = ":reviewai-gerrit-plugin-dev__bazlets",
    out = "reviewai-gerrit-plugin-dev.jar",
    version = PLUGIN_VERSION + "-dev",
    visibility = ["//visibility:public"],
    workspace_marker = ":defs.bzl",
)

java_library(
    name = "provided_deps",
    exports = [
        "@reviewai_plugin_deps//:ch_qos_logback_logback_classic",
        "@reviewai_plugin_deps//:ch_qos_logback_logback_core",
        "@reviewai_plugin_deps//:com_google_gerrit_gerrit_plugin_api",
        "@reviewai_plugin_deps//:org_slf4j_slf4j_api",
    ],
    neverlink = True,
)

# java_import uses the ijar tool (not Turbine) for its hjar, so
# Lombok-generated public methods are preserved during test compilation.
# We copy the full jar out of __plugin via a genrule because java_import
# cannot accept java_library target labels directly.
genrule(
    name = "reviewai_plugin_jar",
    srcs = [":reviewai-gerrit-plugin__plugin"],
    outs = ["reviewai_plugin.jar"],
    cmd = "cp $(location :reviewai-gerrit-plugin__plugin) $@",
)

java_import(
    name = "reviewai_plugin_test_lib",
    jars = [":reviewai_plugin.jar"],
    visibility = ["//visibility:private"],
)

genrule(
    name = "reviewai_plugin_dev_jar",
    srcs = [":reviewai-gerrit-plugin-dev__plugin"],
    outs = ["reviewai_plugin_dev.jar"],
    cmd = "cp $(location :reviewai-gerrit-plugin-dev__plugin) $@",
)

java_import(
    name = "reviewai_plugin_dev_test_lib",
    jars = [":reviewai_plugin_dev.jar"],
    visibility = ["//visibility:private"],
)

TEST_DEPS = [
    ":reviewai_plugin_test_lib",
    ":lombok",
    ":provided_deps",
    "@reviewai_plugin_deps//:ch_qos_logback_logback_classic",
    "@reviewai_plugin_deps//:ch_qos_logback_logback_core",
    "@reviewai_plugin_deps//:com_google_gerrit_gerrit_plugin_api",
    "@reviewai_plugin_deps//:junit_junit",
    "@reviewai_plugin_deps//:org_mockito_mockito_core",
    "@reviewai_plugin_deps//:org_slf4j_slf4j_api",
    "@reviewai_plugin_deps//:org_wiremock_wiremock_standalone",
] + EXTERNAL_PLUGIN_DEPS

java_library(
    name = "reviewai_test_support",
    srcs = glob(
        ["src/test/java/**/*.java"],
        exclude = [
            "src/test/java/**/*IT.java",
            "src/test/java/**/*Test.java",
        ],
    ),
    deps = TEST_DEPS,
)

junit_tests(
    name = "reviewai_tests",
    srcs = glob(["src/test/java/**/*Test.java"]),
    data = glob(["src/test/resources/**"]),
    resource_strip_prefix = plugin_package() + "/src/test/resources",
    resources = glob(["src/test/resources/**"]),
    jvm_flags = ["-Dbazel.test.resourceBase=" + plugin_package() + "/src/test/resources"],
    deps = [
        ":reviewai_test_support",
    ] + TEST_DEPS,
)

java_library(
    name = "reviewai_dev_test_support",
    srcs = glob(
        ["src/dev/test/java/**/*.java"],
        exclude = [
            "src/dev/test/java/**/*IT.java",
            "src/dev/test/java/**/*Test.java",
        ],
    ),
    deps = [
        ":reviewai_test_support",
        ":reviewai_plugin_dev_test_lib",
    ] + TEST_DEPS,
)

junit_tests(
    name = "reviewai_dev_tests",
    srcs = glob(["src/dev/test/java/**/*Test.java"]),
    data = glob(["src/test/resources/**"]) + glob(["src/dev/test/resources/**"], allow_empty = True),
    resource_strip_prefix = plugin_package() + "/src/test/resources",
    resources = glob(["src/test/resources/**"]) + glob(["src/dev/test/resources/**"], allow_empty = True),
    jvm_flags = ["-Dbazel.test.resourceBase=" + plugin_package() + "/src/test/resources"],
    deps = [
        ":reviewai_dev_test_support",
        ":reviewai_test_support",
        ":reviewai_plugin_dev_test_lib",
    ] + TEST_DEPS,
)

java_plugin(
    name = "lombok_plugin",
    processor_class = "lombok.launch.AnnotationProcessorHider$AnnotationProcessor",
    deps = ["@reviewai_plugin_deps//:org_projectlombok_lombok"],
)

java_library(
    name = "lombok",
    exported_plugins = [":lombok_plugin"],
    exports = ["@reviewai_plugin_deps//:org_projectlombok_lombok"],
    neverlink = True, # Like Maven's 'provided' scope; prevents Lombok from being bundled in your final jar
)
