load("//tools/bzl:plugin.bzl", "gerrit_plugin")
load("//tools/bzl:junit.bzl", "junit_tests")
load("@rules_java//java:defs.bzl", "java_library", "java_plugin")

gerrit_plugin(
    name = "reviewai-gerrit-plugin",
    srcs = glob(["src/main/java/**/*.java"]),
    manifest_entries = [
        "Gerrit-PluginName: reviewai-gerrit-plugin",
        "Gerrit-Module: com.googlesource.gerrit.plugins.reviewai.Module",
        "Gerrit-HttpModule: com.googlesource.gerrit.plugins.reviewai.HttpModule",
        "Implementation-Vendor: Amarula",
        "Implementation-URL: https://github.com/amarula/reviewai-gerrit-plugin",
        "Implementation-Title: ChatGPT Code Review Gerrit Plugin",
        "Implementation-Version: 4.1.0",
        "Gerrit-ApiType: plugin",
        "Gerrit-ApiVersion: 3.13.1",
    ],
    resources = glob(["src/main/resources/**/*"]),
    deps = [
        ":lombok",
        ":provided_deps",
        "@reviewai_plugin_deps//:com_openai_openai_java_core",
        "@reviewai_plugin_deps//:dev_langchain4j_langchain4j_core",
        "@reviewai_plugin_deps//:dev_langchain4j_langchain4j",
        "@reviewai_plugin_deps//:dev_langchain4j_langchain4j_open_ai",
        "@reviewai_plugin_deps//:dev_langchain4j_langchain4j_google_ai_gemini",
        "@reviewai_plugin_deps//:dev_langchain4j_langchain4j_ollama",
        "@reviewai_plugin_deps//:com_openai_openai_java_client_okhttp",
        "@reviewai_plugin_deps//:com_h2database_h2",
        "@reviewai_plugin_deps//:org_apache_commons_commons_collections4",
    ],
)

gerrit_plugin(
    name = "reviewai-gerrit-plugin-dev",
    srcs = glob([
        "src/main/java/**/*.java",
        "src/dev/main/java/**/*.java",
    ]),
    manifest_entries = [
        "Gerrit-PluginName: reviewai-gerrit-plugin",
        "Gerrit-Module: com.googlesource.gerrit.plugins.reviewai.DevModule",
        "Gerrit-HttpModule: com.googlesource.gerrit.plugins.reviewai.HttpModule",
        "Implementation-Vendor: Amarula",
        "Implementation-URL: https://github.com/amarula/reviewai-gerrit-plugin",
        "Implementation-Title: ChatGPT Code Review Gerrit Plugin Dev",
        "Implementation-Version: 4.1.0-dev",
        "Gerrit-ApiType: plugin",
        "Gerrit-ApiVersion: 3.13.1",
    ],
    resources = glob([
        "src/main/resources/**/*",
    ]) + glob(["src/dev/main/resources/**/*"], allow_empty = True),
    deps = [
        ":lombok",
        ":provided_deps",
        "@reviewai_plugin_deps//:com_openai_openai_java_core",
        "@reviewai_plugin_deps//:dev_langchain4j_langchain4j_core",
        "@reviewai_plugin_deps//:dev_langchain4j_langchain4j",
        "@reviewai_plugin_deps//:dev_langchain4j_langchain4j_open_ai",
        "@reviewai_plugin_deps//:dev_langchain4j_langchain4j_google_ai_gemini",
        "@reviewai_plugin_deps//:dev_langchain4j_langchain4j_ollama",
        "@reviewai_plugin_deps//:com_openai_openai_java_client_okhttp",
        "@reviewai_plugin_deps//:com_h2database_h2",
        "@reviewai_plugin_deps//:org_apache_commons_commons_collections4",
    ],
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

TEST_DEPS = [
    ":lombok",
    ":provided_deps",
    ":reviewai-gerrit-plugin_lib",
    "@reviewai_plugin_deps//:ch_qos_logback_logback_classic",
    "@reviewai_plugin_deps//:ch_qos_logback_logback_core",
    "@reviewai_plugin_deps//:com_google_gerrit_gerrit_plugin_api",
    "@reviewai_plugin_deps//:dev_langchain4j_langchain4j",
    "@reviewai_plugin_deps//:dev_langchain4j_langchain4j_core",
    "@reviewai_plugin_deps//:dev_langchain4j_langchain4j_google_ai_gemini",
    "@reviewai_plugin_deps//:dev_langchain4j_langchain4j_ollama",
    "@reviewai_plugin_deps//:dev_langchain4j_langchain4j_open_ai",
    "@reviewai_plugin_deps//:junit_junit",
    "@reviewai_plugin_deps//:org_mockito_mockito_core",
    "@reviewai_plugin_deps//:org_slf4j_slf4j_api",
    "@reviewai_plugin_deps//:org_wiremock_wiremock_standalone",
]

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
    resource_strip_prefix = "src/test/resources",
    resources = glob(["src/test/resources/**"]),
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
        ":reviewai-gerrit-plugin-dev_lib",
    ] + TEST_DEPS,
)

junit_tests(
    name = "reviewai_dev_tests",
    srcs = glob(["src/dev/test/java/**/*Test.java"]),
    data = glob(["src/test/resources/**"]) + glob(["src/dev/test/resources/**"], allow_empty = True),
    resource_strip_prefix = "src/test/resources",
    resources = glob(["src/test/resources/**"]) + glob(["src/dev/test/resources/**"], allow_empty = True),
    deps = [
        ":reviewai_dev_test_support",
        ":reviewai_test_support",
        ":reviewai-gerrit-plugin-dev_lib",
    ] + TEST_DEPS,
    src_prefix = "src/dev/test/java/",
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
