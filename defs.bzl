"""Plugin-local helpers."""

def _stamp_plugin_jar_impl(ctx):
    source_jar = ctx.file.src
    output_jar = ctx.outputs.out
    pom = ctx.file.pom
    workspace_marker = ctx.file.workspace_marker

    ctx.actions.run_shell(
        inputs = [source_jar, pom, workspace_marker],
        outputs = [output_jar],
        arguments = [
            source_jar.path,
            output_jar.path,
            pom.path,
            ctx.attr.version_suffix,
            workspace_marker.path,
        ],
        command = """
set -euo pipefail

source_jar="$1"
output_jar="$2"
pom="$3"
version_suffix="$4"
workspace_marker="$5"

plugin_base_version="$(sed -n 's:^[[:space:]]*<version>\\([^<][^<]*\\)</version>[[:space:]]*$:\\1:p' "${pom}" | head -n 1)"
if [[ -z "${plugin_base_version}" ]]; then
  echo "Unable to determine plugin version from ${pom}" >&2
  exit 1
fi
plugin_version="${plugin_base_version}${version_suffix}"

git_describe=""
plugin_dir="$(dirname "$(realpath "${workspace_marker}")")"
git_root="$(git -C "${plugin_dir}" rev-parse --show-toplevel 2>/dev/null || true)"
if [[ -n "${git_root}" && "$(realpath "${git_root}")" == "$(realpath "${plugin_dir}")" ]]; then
  git_describe="$(git -C "${plugin_dir}" describe --tags --always --dirty --long 2>/dev/null || true)"
fi

build_info=""
if [[ "${git_describe}" =~ ^(.+)(-[0-9]+-g[0-9a-f]+(-dirty)?)$ ]]; then
  build_info="${BASH_REMATCH[2]#-}"
elif [[ "${git_describe}" =~ ^([0-9a-f]+)(-dirty)?$ ]]; then
  build_info="g${BASH_REMATCH[1]}${BASH_REMATCH[2]:-}"
fi

plugin_version_info="${plugin_version}"
if [[ -n "${plugin_version}" && -n "${build_info}" ]]; then
  plugin_version_info="${plugin_version}-${build_info}"
fi

cp "${source_jar}" "${output_jar}"
chmod u+w "${output_jar}"

manifest="$(mktemp)"
updated_manifest="$(mktemp)"
manifest_dir="$(mktemp -d)"
unzip -p "${output_jar}" META-INF/MANIFEST.MF > "${manifest}"

awk -v plugin_version_info="${plugin_version_info}" '
  { lines[++line_count] = $0 }
  END {
    while (line_count > 0 && lines[line_count] ~ /^\r?$/) {
      line_count--
    }
    for (line = 1; line <= line_count; line++) {
      if (lines[line] ~ /^Implementation-Version:/) {
        if (!found) {
          print "Implementation-Version: " plugin_version_info "\r"
          found = 1
        }
      } else {
        print lines[line]
      }
    }
    if (!found) {
      print "Implementation-Version: " plugin_version_info "\r"
    }
    print "\r"
  }
' "${manifest}" > "${updated_manifest}"

mkdir -p "${manifest_dir}/META-INF"
mv "${updated_manifest}" "${manifest_dir}/META-INF/MANIFEST.MF"
output_jar_abs="$(realpath "${output_jar}")"
(cd "${manifest_dir}" && zip -q -u "${output_jar_abs}" META-INF/MANIFEST.MF)
""",
        execution_requirements = {
            "local": "1",
            "no-cache": "1",
            "no-remote": "1",
            "no-sandbox": "1",
        },
        mnemonic = "StampPluginJar",
    )

stamp_plugin_jar = rule(
    implementation = _stamp_plugin_jar_impl,
    attrs = {
        "src": attr.label(allow_single_file = True, mandatory = True),
        "out": attr.output(mandatory = True),
        "pom": attr.label(allow_single_file = True, mandatory = True),
        "version_suffix": attr.string(default = ""),
        "workspace_marker": attr.label(allow_single_file = True, mandatory = True),
    },
)

def plugin_package():
    """Return the Bazel package path of this plugin."""
    return native.package_name()
