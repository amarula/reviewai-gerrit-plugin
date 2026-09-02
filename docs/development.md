# Development and Debugging

## Build

This version requires JDK 21 and Bazel. From the Gerrit checkout, link the plugin Bazel dependencies and update the
module lockfile:

```bash
cd gerrit/plugins
rm external_plugin_deps.MODULE.bazel
ln -s reviewai-gerrit-plugin/external_plugin_deps.MODULE.bazel external_plugin_deps.MODULE.bazel

cd ..
bazel mod deps --lockfile_mode=update
```

Build the desired plugin variant:

```bash
bazelisk build plugins/reviewai-gerrit-plugin:reviewai-gerrit-plugin
bazelisk build plugins/reviewai-gerrit-plugin:reviewai-gerrit-plugin-dev
```

`reviewai-gerrit-plugin.jar` is the standard build intended for production use. `reviewai-gerrit-plugin-dev.jar` is
the development build that includes features restricted to ReviewAI Administrators, such as mock AI models, the
`/configure`, `/show`, and `/directives` commands, the `/review --debug` option, and the
`selectiveLogLevelOverride` configuration setting.

The generated JAR files are available under Gerrit's `bazel-bin/plugins/reviewai-gerrit-plugin/` directory.

Gerrit's Bazlets packaging populates `Implementation-Version` from the nearest annotated `v*` Git tag. Create release
tags as annotated tags (for example, `git tag -a v4.1.0`); lightweight tags are ignored and the version falls back to
the commit SHA.

## Testing

### Overview

- You can run the unit tests in the project to familiarize yourself with the plugin's source code.
- If you want to individually test the Gerrit API or AI provider integrations, you can refer to the test cases in
  CodeReviewPluginIT.

Run the standard tests with:

```bash
bazelisk test plugins/reviewai-gerrit-plugin:reviewai_tests
```

Run both the standard and development-build tests with:

```bash
bazelisk test plugins/reviewai-gerrit-plugin:reviewai_tests plugins/reviewai-gerrit-plugin:reviewai_dev_tests
```

### Test Log Level Override

During tests, the default log level is set to DEBUG, which may result in a surplus of DEBUG messages. To manage this,
adjust the log level by setting the `GERRIT_AI_TEST_FILTER_LEVEL` environment variable. For instance, to set the
testing log level to INFO on a Linux-based OS:

```
$ export GERRIT_AI_TEST_FILTER_LEVEL=INFO
```

### Filtering Test Logs

To continue receiving certain DEBUG-leveled messages after elevating the test log level, use
the `GERRIT_AI_TEST_FILTER_VALUE` environment variable. For example, to keep seeing DEBUG messages from the
class `ClientMessage` even with the log level set to INFO:

```
$ export GERRIT_AI_TEST_FILTER_VALUE=ClientMessage
```

The syntax for the filter value is as follows:

```
export GERRIT_AI_TEST_FILTER_VALUE="[<class_name_1>]|[<message_1>], ..., [<class_name_N>]|[<message_N>]"
```

Double quotes are required when specifying multiple filter items. Each filter item can include a `className` and
a `message` filter. Since the filter uses a "contain" criterion, you can select multiple items with a common substring,
such as all DEBUG messages in classes containing `EventHandler`:

```
$ export GERRIT_AI_TEST_FILTER_VALUE=EventHandler
```

For example, to filter DEBUG messages containing the OpenAI Responses request log, the pipe ("|") prefix must be used:

```
$ export GERRIT_AI_TEST_FILTER_VALUE="|OpenAI Responses LangChain request"
```

For multiple items with spaces, enclose the settings string in double quotes and escape any internal double quotes:

```
$ export GERRIT_AI_TEST_FILTER_VALUE="|OpenAI Responses LangChain request, LangChainExecutor"
```

This setting shows the DEBUG log messages whose message starts with "OpenAI Responses LangChain request" and the ones
from classes containing `LangChainExecutor`.

## Debugging

In addition to standard testing tools, we provide additional resources to assist with live debugging of the AI
plugin when running on a Gerrit instance. These tools can be managed through both static configurations (such as
modifying `gerrit.config` and `project.config`) and dynamic configurations (using the `/configure` command in a message
addressed to the AI user).

For durable request states, queue-lane invariants, lease recovery, and expected cancellation behavior, see
[AI Request Coordination](architecture/request-coordination.md).

### Using the Review Debug Command

Users in the ReviewAI Administrator group can obtain additional useful debug information in each AI reply, such as
relevance and scores, by using the `--debug` command option. For example:

```
/review --debug
```

### Selective Production Logging

As with testing, setting the general log level to DEBUG in operational environments can lead to an excess of DEBUG
messages from various sources in the Gerrit log file. The `selectiveLogLevelOverride` configuration option functions
similarly to the `GERRIT_AI_TEST_FILTER_VALUE`, permitting the logging of specific messages below the current log
level threshold.

For instance, to log all DEBUG messages from the `ClientMessage` and `ClientCommandExecutor` classes for a specific
project, add the following to the related `project.config`:

```
selectiveLogLevelOverride = ClientMessage
selectiveLogLevelOverride = ClientCommandExecutor
```

This effect can also be achieved for actions performed on a specific Change Set by dynamically changing the
configuration:

```
/configure --selectiveLogLevelOverride="[ClientMessage, ClientCommandExecutor]"
```

The `selectiveLogLevelOverride` dynamic option uses the following general syntax:

```
selectiveLogLevelOverride = "[\"<class_name_1>|<message_1>\", ..., \"<class_name_N>|<message_N>\"]"
```

Note that it's mandatory to enclose the `selectiveLogLevelOverride` value in double quotes when specifying filters on
messages.

Each item's filter may consist of a `className` and a `message` filter, separated by a pipe ("|"). Since the filter uses
the "contain" criterion for `className` and the "startsWith" criterion for `message`, multiple items with a common
substring can be selected by setting that substring.

For example, all DEBUG messages whose log messages start with the OpenAI Responses request log can be elevated with:

```
/configure --selectiveLogLevelOverride="[\"|OpenAI Responses LangChain request\"]"
```

### Dynamically Changing Settings for Testing/Debugging

Settings can be locally modified for the current Change Set using the `/configure` command. For instance, to set the
review temperature to "1.0," you can use:

```
/configure --aiReviewTemperature=1.0
```

Following this configuration, a new Change Set review can be initiated with:

```
/review
```

It's also possible to make multiple changes at once:

```
/configure --agentSpecializationLevel=SCOPED_AGENTS --codeContextPolicy=ON_DEMAND
```
