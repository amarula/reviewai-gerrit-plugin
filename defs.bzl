"""Plugin-local helpers."""

def _stamp_plugin_jar_impl(ctx):
    source_jar = ctx.file.src
    output_jar = ctx.outputs.out
    stable_status = ctx.info_file
    workspace_marker = ctx.file.workspace_marker

    ctx.actions.run_shell(
        inputs = [source_jar, stable_status, workspace_marker],
        outputs = [output_jar],
        arguments = [
            source_jar.path,
            stable_status.path,
            output_jar.path,
            ctx.attr.version,
            workspace_marker.path,
        ] + ctx.attr.status_keys,
        command = """
set -euo pipefail

source_jar="$1"
stable_status="$2"
output_jar="$3"
plugin_version="$4"
workspace_marker="$5"
shift 5

git_describe=""
plugin_dir="$(dirname "$(realpath "${workspace_marker}")")"
git_root="$(git -C "${plugin_dir}" rev-parse --show-toplevel 2>/dev/null || true)"
if [[ -n "${git_root}" && "$(realpath "${git_root}")" == "$(realpath "${plugin_dir}")" ]]; then
  git_describe="$(git -C "${plugin_dir}" describe --tags --always --dirty --long 2>/dev/null || true)"
fi

for status_key in "$@"; do
  if [[ -n "${git_describe}" ]]; then
    break
  fi
  git_describe="$(awk -v key="${status_key}" '$1 == key { sub($1 FS, ""); print; exit }' "${stable_status}")"
done

build_info=""
if [[ "${git_describe}" =~ ^(.+)(-[0-9]+-g[0-9a-f]+(-dirty)?)$ ]]; then
  build_info="${BASH_REMATCH[2]#-}"
elif [[ "${git_describe}" =~ ^([0-9a-f]+)(-dirty)?$ ]]; then
  build_info="g${BASH_REMATCH[1]}${BASH_REMATCH[2]:-}"
fi

plugin_version_info="${plugin_version}"
if [[ -n "${plugin_version}" && -n "${build_info}" ]]; then
  plugin_version_info="v${plugin_version#v}-${build_info}"
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
        "version": attr.string(mandatory = True),
        "status_keys": attr.string_list(mandatory = True),
        "workspace_marker": attr.label(allow_single_file = True, mandatory = True),
    },
)

def plugin_package():
    """Return the Bazel package path of this plugin."""
    return native.package_name()
