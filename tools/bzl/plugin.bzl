load("@rules_java//java:defs.bzl", "java_binary", "java_library")

def _stamp_plugin_jar_impl(ctx):
    source_jar = ctx.file.src
    output_jar = ctx.outputs.out
    stable_status = ctx.info_file

    ctx.actions.run_shell(
        inputs = [source_jar, stable_status],
        outputs = [output_jar],
        arguments = [source_jar.path, stable_status.path, output_jar.path],
        command = """
set -euo pipefail

source_jar="$1"
stable_status="$2"
output_jar="$3"

cp "${source_jar}" "${output_jar}"
chmod u+w "${output_jar}"

manifest="$(mktemp)"
updated_manifest="$(mktemp)"
manifest_dir="$(mktemp -d)"
unzip -p "${output_jar}" META-INF/MANIFEST.MF > "${manifest}"

plugin_version="$(awk -F': ' '$1 == "Implementation-Version" { sub(/\\r$/, "", $2); print $2; exit }' "${manifest}")"
git_describe="$(awk '$1 == "STABLE_REVIEWAI_GIT_DESCRIBE" { sub($1 FS, ""); print; exit }' "${stable_status}")"
build_info=""
if [[ "${git_describe}" =~ ^(.+)(-[0-9]+-g[0-9a-f]+(-dirty)?)$ ]]; then
  build_info="${BASH_REMATCH[2]#-}"
elif [[ "${git_describe}" =~ ^([0-9a-f]+)(-dirty)?$ ]]; then
  build_info="g${BASH_REMATCH[1]}${BASH_REMATCH[2]:-}"
elif [[ -n "${git_describe}" && "${git_describe}" != "unknown" ]]; then
  build_info="${git_describe}"
fi

if [[ -n "${plugin_version}" && -n "${build_info}" ]]; then
  plugin_version_info="v${plugin_version#v}-${build_info}"
else
  plugin_version_info="${plugin_version}"
fi

awk -v plugin_version_info="${plugin_version_info}" '
  { lines[++line_count] = $0 }
  END {
    while (line_count > 0 && lines[line_count] ~ /^\\r?$/) {
      line_count--
    }
    for (line = 1; line <= line_count; line++) {
      if (lines[line] ~ /^Implementation-Version:/) {
        print "Implementation-Version: " plugin_version_info "\\r"
        found = 1
      } else {
        print lines[line]
      }
    }
    if (!found) {
      print "Implementation-Version: " plugin_version_info "\\r"
    }
    print "\\r"
  }
' "${manifest}" > "${updated_manifest}"
mkdir -p "${manifest_dir}/META-INF"
mv "${updated_manifest}" "${manifest_dir}/META-INF/MANIFEST.MF"
output_jar_abs="$(realpath "${output_jar}")"
(cd "${manifest_dir}" && zip -q -u "${output_jar_abs}" META-INF/MANIFEST.MF)
""",
        mnemonic = "StampPluginJar",
    )

_stamp_plugin_jar = rule(
    implementation = _stamp_plugin_jar_impl,
    attrs = {
        "src": attr.label(allow_single_file = True, mandatory = True),
        "out": attr.output(mandatory = True),
    },
)

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

    _stamp_plugin_jar(
        name = name,
        src = ":" + name + "_deploy_deploy.jar",
        out = "%s.jar" % name,
        visibility = visibility,
    )
