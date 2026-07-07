"""Plugin-local helpers."""

def plugin_package():
    """Return the Bazel package path of this plugin."""
    return native.package_name()
