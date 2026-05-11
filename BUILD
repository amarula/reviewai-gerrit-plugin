load("//tools/bzl:plugin.bzl", "gerrit_plugin")
load("//tools/bzl:junit.bzl", "junit_tests")

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
    ],
    resources = glob(["src/main/resources/**/*"]),
    deps = [
        ":lombok",
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
