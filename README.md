# AI Code Review Gerrit Plugin

## Features

This plugin adds ReviewAI support to Gerrit through the Review Agent sidebar, giving users a standard chatbot interface
inside the Gerrit change page. From the sidebar, users can ask questions about the Change, select the AI model, and keep
conversation history tied to the review.

ReviewAI can also review Patch Sets automatically, posting feedback as Gerrit comments and, optionally, a vote. Users
can continue the discussion in Gerrit comments by mentioning @{gerritUserName} or @{gerritEmailAddress} (provided that
`gerritEmailAddress` is in the form "gerritUserName@<any_email_domain>"), trigger reviews with `/review`, and view
command help with `/help` or `/help <command>`.

## Getting Started

1. **Build:** This version requires JDK 21 and Maven. The Maven build installs the configured Node.js version and runs
   the frontend lint step.

   ```bash
   mvn -U clean package
    ```

   To build without running tests:
   ```bash
   mvn -U -DskipTests=true clean package
   ```

2. **Install:** Upload the compiled jar file to the `$gerrit_site/plugins` directory.

3. **Configure:** First, create an AI user in Gerrit. Then set the basic parameters in
   `$gerrit_site/etc/gerrit.config` under the section

   `[plugin "reviewai-gerrit-plugin"]`:

    - `gerritUserName`: Gerrit username of AI user.
    - `aiTokens`: AI provider token routes for token-backed providers such as OpenAI, Gemini, and MoonShot.

   `aiProviders` and `aiModels` are optional. If they are omitted, the plugin exposes the default OpenAI model routes.
   Configure them when you want to expose specific provider/model routes or use providers such as Gemini, MoonShot, or
   Ollama.

   For enhanced security, consider storing sensitive information like `aiTokens` in a secure location or file.
   Detailed instructions on how to do this will be provided later in this document.

4. **Verify:** After restarting Gerrit, you can see the following information in Gerrit's logs:

   ```bash
   INFO com.google.gerrit.server.plugins.PluginLoader : Loaded plugin reviewai-gerrit-plugin, version ...
   ```

   You can also check the status of the reviewai-gerrit-plugin on Gerrit's plugin page as Enabled.

## Usage Examples

### Auto review on Patch Set submission

In the following example, a Patch Set receives a score of "-1" indicating a recommendation.

![Example of Vote](images/aireview_vote.png?raw=true)

**NOTE**: Voting is disabled by default. To use this feature, it needs to be activated either across all projects or on
a per-project basis via the `enabledVoting` configuration option, as described below.

### AI Score Adjustment Following User Interaction

In the example below, AI initially posits a potential unintended behavior in the code, assigning a "-1" score.
Upon receiving clarification, it resets the score to "0".

![Example of Dialogue](images/aireview_changed_mind.png?raw=true)

More examples of AI's code reviews and inline discussions are available at
https://wiki.amarulasolutions.com/opensource/products/chatgpt-gerrit.html

## Configuration Parameters

You have the option to establish global settings, or independently configure specific projects. If you choose
independent configuration, the corresponding project settings will override the global parameters.

### Global Configuration

To configure these parameters, you need to modify your Gerrit configuration file (`gerrit.config`). The file format is
as follows:

```
[plugin "reviewai-gerrit-plugin"]
    # Required parameters
    gerritUserName = {gerritUserName}
    aiTokens = OpenAI/{openAiToken}
    ...

    # Optional parameters
    aiProviders = OpenAI
    aiProviders = MoonShot
    aiModels = OpenAI/gpt-5.2
    aiModels = MoonShot/moonshot-v1-8k
    aiModelsDefaultIndex = 1
    aiSystemPromptInstructions = {aiSystemPromptInstructions}
    ...
```

#### Secure Configuration

It is highly recommended to store sensitive information such as `aiTokens` in the `secure.config` file. Please edit
`$gerrit_site/etc/secure.config` and include the following details:

```
[plugin "reviewai-gerrit-plugin"]
    aiTokens = OpenAI/{openAiToken}
    aiTokens = MoonShot/{moonShotToken}
```

If you wish to encrypt the information within the `secure.config` file, you can refer
to: https://gerrit.googlesource.com/plugins/secure-config

### Project Configuration

To add the following content, please edit the `project.config` file in `refs/meta/config`:

```
[plugin "reviewai-gerrit-plugin"]
    # Optional parameters
    aiProviders = {providerRoute}
    aiModels = {providerModelRoute}
    aiSystemPromptInstructions = {aiSystemPromptInstructions}
    ...
```

#### Secure Configuration

Please ensure **strict control over the access permissions of `refs/meta/config`** if sensitive information such as
`aiTokens` is configured in the `project.config` file within `refs/meta/config`.

## AI Provider Routes

The plugin supports multiple AI providers through LangChain. The Review Agent exposes each configured provider/model
combination using `/` syntax.

Supported providers are:

- OpenAI
- MoonShot
- Gemini
- Ollama

Model and token settings are grouped by the provider part of the route. If a provider is configured without explicit
models, the plugin exposes the built-in defaults for that provider. The current default route set for OpenAI is
`OpenAI/gpt-5.4`, `OpenAI/gpt-5.5`, `OpenAI/gpt-5.2`, and `OpenAI/gpt-4.1`, with the first model selected by default.

```
[plugin "reviewai-gerrit-plugin"]
    aiProviders = OpenAI
    aiProviders = MoonShot
    aiProviders = Ollama

    aiModels = OpenAI/gpt-5.4
    aiModels = OpenAI/gpt-4.1
    aiModels = MoonShot/moonshot-v1-8k
    aiModels = llama3.2
    aiModelsDefaultIndex = 1

    aiTokens = OpenAI/{openAiToken}
    aiTokens = MoonShot/{moonShotToken}
```

With this configuration, the Review Agent exposes `OpenAI/gpt-5.4`, `OpenAI/gpt-4.1`, `MoonShot/moonshot-v1-8k`, and
`Ollama/llama3.2`.
Ollama does not require an `aiTokens` entry. A bare model can also be configured as `aiModels = llama3.2`. When no
configured token-backed provider identifies the bare model route, the plugin guesses `Ollama/llama3.2`. If a bare model
matches a configured or default model for a token-backed provider that has a token, that provider route is used.

## Optional Parameters

- `aiProviders`: Selects provider routes to expose. The default value is `OpenAI`.
- `aiModels`: Selects model routes by provider. When no models are configured for an exposed provider, the plugin
  exposes built-in defaults.
- `aiModelsDefaultIndex`: Selects the default model by 1-based index from the expanded `aiModels` list. The default
  value is `1`. This model is used for automatic Patch Set reviews and as the initial Review Agent dropdown value
  when no model has been selected yet.
- `aiTokens`: Provides provider tokens. Configure these as `OpenAI/{token}`, `MoonShot/{token}`, and so on. Ollama
  does not require a token.
- `aiDomain`: Defines the base endpoint for the selected provider. By default, it uses the provider’s standard domain:
  `https://api.openai.com` (OpenAI), `https://generativelanguage.googleapis.com` (Gemini), `https://api.moonshot.ai`
  (Moonshot), or `http://localhost:11434` (Ollama). Override only when you need a custom endpoint; leaving it unset lets
  the plugin pick the provider default automatically.
- `mockAiAddress`: Configures a custom address for a mock AI server. When set, a `mock-ai` model is added for each
  configured provider route, such as `OpenAI/mock-ai`, `MoonShot/mock-ai`, or `Ollama/mock-ai`. Selecting one of these
  models keeps the same provider and token behavior as the corresponding live model route, but sends AI requests to the
  configured mock server address instead. Because mock models are appended to the regular model list, they can be
  selected through `aiModelsDefaultIndex` like any other model.
- `aiSystemPromptInstructions`: You can customize the default instructions ("Act as a PatchSet Reviewer") to your
  preferred prompt.
- `aiReviewTemperature`: Specifies the temperature setting for AI when reviewing a Patch Set, with a default
  setting of 0.2. Higher values like 0.8 will make the output more random, while lower values like 0.2 will make it more
  focused and deterministic. Some model families do not support temperature; for those models, the plugin omits the
  temperature parameter.
- `aiCommentTemperature`: Specifies the temperature setting for AI when replying to a comment, with a default setting of
  1.0.
- `aiReviewPatchSet`: Set to true by default. When switched to false, it disables the automatic review of Patch Sets as
  they are created or updated.
- `aiReviewCommitMessages`: The default value is true. When enabled, this option also verifies if the commit message
  matches with the content of the Change Set.
- `enabledTopicFilter`: Specifies a list of keywords that trigger AI reviews based on the topic of the Patch Set.
  When this setting is active, only Patch Sets and their associated comments containing at least one of these keywords
  in the topic are reviewed.
- `disabledTopicFilter`: Works in contrast to enabledTopicFilter, excluding Patch Sets and comments from review if their
  topics contain specified keywords.
- `directive`: Directives are mandatory instructions written in plain English that AI must adhere to during its reviews.
  You can provide a single directive or multiple directives.

  Example of multiple directive configuration:

```
directive = Be constructive, respectful and concise
directive = End each reply with \"Hope this helps!\"
```

**NOTE**: Double quotes need to be escaped in directives content.

- `enabledFileExtensions`: This limits the reviewed files to the given types. Default file extensions are "py, java, js,
  ts, html, css, cs, cpp, c, h, php, rb, swift, kt, r, jl, go, scala, pl, pm, rs, dart, lua, sh, vb, bat".

  **NOTE**: Extensions without a leading dot (e.g., 'py') are also accepted.
- `enabledVoting`: Initially disabled (false). If set to true, allows AI to cast a vote on each reviewed Patch Set by
  assigning a score.
- `convertNeutralReviewScoreToPositive`: Enabled by default (true). When enabled, a neutral final review score (`0`)
  is submitted as `+1` when the permitted voting range allows it. Set it to false to keep neutral reviews at `0`.
- `votingMinScore`: The lowest possible score that can be given to a Patch Set (Default value: -1).
- `votingMaxScore`: The highest possible score that can be given to a Patch Set (Default value: +1).
- `filterNegativeComments`: Activated by default (true), ensuring only negative review comments (scored below the
  `filterCommentsBelowScore` threshold outlined further) are displayed. Disabling this setting (false) will
  also show positive and neutral comments.
- `filterCommentsBelowScore`: With `filterNegativeComments` active, review comments with a score at or above this
  setting's value will not be shown (default is 0).
- `filterRelevantComments`: This setting is enabled by default (true) to display only those review comments considered
  relevant by AI, which means they have a relevance index at or above the `filterCommentsRelevanceThreshold` specified
  below. Turning off this option (false) allows the display of comments AI marks as irrelevant.
- `filterCommentsRelevanceThreshold`: When `filterRelevantComments` is enabled, any review comment assigned a relevance
  score by AI below this threshold will not be shown. The default threshold is set at 0.6.
- `aiRelevanceRules`: This option allows customization of the rules AI uses to determine the relevance of a task.
- `patchSetCommentsAsResolved`: Initially set to false, this option leaves AI's Patch Set comments as unresolved,
  inviting further discussion. If activated, it marks AI's Patch Set comments as resolved.
- `inlineCommentsAsResolved`: Initially set to false, this option leaves AI's inline comments as unresolved, inviting
  further discussion. If activated, it marks AI's inline comments as resolved.
- `enableMessageDebugging`: This setting controls the activation of debugging functionalities through messages (default
  value is false). When set to true, it enables commands and options like `--debug` for users as well as the Dynamic
  Configuration commands.
- `selectiveLogLevelOverride`: This setting allows for overriding the log level of specific messages, ensuring they are
  logged even if their level is above the current setting. This is useful for debugging without the need to set the
  overall log level to DEBUG, which could result in excessive DEBUG messages from sources like gerrit and other plugins.
  Some usage examples can be found at [Selective Log Level Override](#selective-log-level-override) section.
- `aiFullFileReview`: Enabled by default. Activating this option sends both unchanged lines and changes to AI for
  review, offering additional context information. Deactivating it (set to false) results in only the changed lines
  being submitted for review.
- `ignoreResolvedAiComments`: Determines if resolved comments from AI should be disregarded. The default setting is
  true, which means resolved AI comments are not used for generating new comments or identifying duplicate content. If
  set to false, resolved AI comments are factored into these processes.
- `ignoreOutdatedInlineComments`: Determines if inline comments made on non-latest Patch Sets should be disregarded. By
  default, this is set to false, meaning all inline comments are used for generating new responses and identifying
  repetitions. If enabled (true), inline comments from previous Patch Sets are excluded from these considerations.
- `maxReviewLines`: The default value is 1000. This sets a limit on the number of lines of code included in the review.
- `patchContextLines`: The default value is 3. This sets how many unchanged context lines are included around each
  changed hunk in the patch passed to AI. Set it to 0 to include only changed lines.
- `codeContextPolicy`: Defines the code context policy used when AI needs repository context outside the formatted
  patch. The default value is `NONE`.
  The currently supported policies are:
    - **ON_DEMAND**: Lets the model request repository context during review through tool calls for listing the file
      tree, searching references, and reading file content.
    - **NONE**: Does not expose repository context tools. Reviews and interactions rely on the formatted patch and
      Gerrit discussion history only.
- `aiMaxToolResponseRounds`: Maximum number of tool-response continuation rounds allowed for one AI review request.
  This applies when ON_DEMAND code context tools are enabled and defaults to 3.

### Optional Parameters Specific to Review Processing

- `multiAgentMode`: This option allows for dividing the Patch Set review between two specialized agents: one
  focused to the Patch's code and another to the commit message. When this option is set to false (default value), the
  Patch Set review is unified into one single request processed by one agent instructed for both tasks.

  **NOTE**: Enabling this feature may result in duplicate requests to AI, potentially increasing the usage costs of the
  AI API.

### Optional Parameters Specific to LangChain Provider Routes

- `aiMaxMemoryTokens`: Maximum number of tokens retained in LangChain memory per Change, Patch Set, and review scope.
  The default value is 16K. OpenAI uses durable Responses API conversations plus transient local memory; Gemini,
  MoonShot, and Ollama use the plugin's local LangChain memory store.

### Optional Parameters Specific to Ollama

- `ollamaDomain`: Defines the Ollama server endpoint. The default value is `http://localhost:11434`.
- `ollamaContextWindow`: Sets Ollama `num_ctx`, the model context window size. The default value is 16K.
- `ollamaResponseLength`: Sets Ollama `num_predict`, the maximum generated response length. The default value is `-1`.
- `ollamaThink`: Sets Ollama `think`, enabling thinking mode for supported models. The default value is `false`.

### Advanced Connection Parameters

These parameters should only be modified by advanced users:

- `aiConnectionTimeout`: Defines the timeout for connections to the OpenAI server, with a default of 30 seconds.
- `aiConnectionMaxRetryAttempts`: Determines the maximum number of retry attempts, defaulting to 2.
- `aiUploadedChunkSizeMb`: Sets the maximum size, in MB, of repository-content chunks built by the plugin when it needs
  to serialize repository files. The default value is 5 MB.

## Commands

Commands can be sent directly from the Review Agent sidebar. The sidebar already addresses messages to AI, so command
examples in this section do not include a @{gerritUserName} prefix.

### Help

Use `/help` to display a summary of all supported commands and their main options. Use `/help <command>` or
`/help /<command>` to show detailed help for a single command.

Example:

```
/help
```

```
/help /review
```

### Plain Messages

To ask AI a question, type the message directly in the Review Agent sidebar. The sidebar handles plain chat messages
automatically by sending them as direct messages.

### Review Commands

Reviewing a Change Set can occur automatically upon submission or be manually triggered using the commands outlined in
this section.

#### Basic Syntax

- `/review`: triggers a review of the full Change Set. A vote is cast on the Change Set if the voting feature is
  enabled and the AI Gerrit user is authorized to vote on it.

#### Command Options

- `--filter=[true/false]`: Controls the filtering of duplicate, conflicting and irrelevant comments, defaulting to
  "true" to apply filters.
- `--scope=[patchset/commit_message]`: Limits the review scope. `patchset` reviews only the PatchSet code changes, and
  `commit_message` reviews only the commit message. If omitted, both are reviewed.
- `--debug`: When paired with `/review`, this option displays useful debug information in each AI reply, showing all
  replies as though the filter setting were disabled.

  **NOTE**: The usage of `--debug` option is disabled by default. To enable it, `enableMessageDebugging` setting must be
  set to true.

### Dynamic Configuration

You can dynamically alter the plugin configuration for the current Change Set, primarily for testing and debugging
purposes. This feature becomes available when the `enableMessageDebugging` configuration setting is enabled.

#### Basic Syntax

- `/configure` displays the current settings and their dynamically modified values in a response message.
- `/configure --<CONFIG_KEY_1>=<CONFIG_VALUE_1> [... --<CONFIG_KEY_N>=<CONFIG_VALUE_N>]` assigns new values to one or
  more configuration keys.

  **NOTE**: Values that include spaces, such as `aiSystemPromptInstructions`, must be enclosed in double quotes.

#### Command Options

The `reset` option can be employed to restore modified settings to their original defaults. Its usage is detailed below:

- `/configure --reset` restores all modified settings to their default values.
- `/configure --reset --<CONFIG_KEY_1> [... --<CONFIG_KEY_N>]` specifically restores the indicated key(s) to their
  default values.

### Directives

Directives are mandatory instructions written in plain English that AI must adhere to during its reviews. In addition to
static directives, which can be specified in global and/or project configurations, directives can also be dynamically
managed using the `/directives` command.

Examples:

#### Query Dynamic Directives

```
/directives
```

Example of the response:

```
1. First directive
2. Second directive
```

#### Adding a Dynamic Directive

```
/directives Third directive with "quotation"
```

**NOTE**: In case of dynamic directives, double quotes do not need to be escaped.

#### Removing a Dynamic Directive

The index in the response to `/directives` query can be used to remove single dynamic directives.

```
/directives --remove 1
```

#### Removing All the Dynamic Directives

```
/directives --reset
```

### Forgetting Conversation History

For the OpenAI Responses backend, the plugin stores the OpenAI conversation ID for each Change Set so that forced or
reiterated reviews continue on the same durable conversation object. For the other LangChain providers, the plugin
stores chat memory locally by Change, Patch Set, and review scope. This history can be removed with the `/forget_thread`
command.
This functionality is crucial for preventing AI from merely recycling old responses, particularly following
modifications to configuration parameters.

#### Basic Syntax

```
/forget_thread
```

### Showing Information

The `/show` command, followed by one or more options, can be used to display relevant information for debugging and
fine-tuning purposes. Below are the currently supported options and their associated objects:

- `prompts`: Shows the prompts currently used
- `instructions`: Shows the assistant instructions currently used
- `local_data`: Shows locally stored data
- `config`: Shows the current configuration

For `--prompts` and `--instructions`, `--scope=full|patchset|commit_message` limits the output to a single review mode.

**NOTE**: This command is available when the `enableMessageDebugging` configuration setting is enabled.

#### Showing Prompting Parameters

The `/show` command also enables you to view the prompts and assistant instructions used with your current
configuration.

For example, running `/show --prompts` will return something like:

```
PROMPT FOR FULL REVIEW
Review the following Patch Set:  ` ` `Subject: <COMMIT_MESSAGE> Change-Id: ... <PATCH_SET> ` ` `
```

```
PROMPT FOR PATCH SET ONLY
Review the following Patch Set:  ` ` `Subject: <COMMIT_MESSAGE> Change-Id: ... <PATCH_SET> ` ` `
```

```
PROMPT FOR COMMIT MESSAGE ONLY
Review the following Commit Message:  ` ` `Subject: <COMMIT_MESSAGE> Change-Id: ... <PATCH_SET> ` ` `
```

Running `/show --prompts --scope=patchset` will return only the `PROMPT FOR PATCH SET ONLY` block.

Similarly, running `/show --instructions` will display something like:

```
INSTRUCTIONS FOR FULL REVIEW
Act as a PatchSet Reviewer. Disregard missing implementations of methods or other code entities, as the full ...
RULE #1: You MUST take into account of the messages previously exchanged in the conversation for your review. ...
RULE #2: You MUST only evaluate the code that has been modified in the patch, specifically the lines of the patch ...
Here are other guidelines for reviewing the patch: A. Identify any potential problems and offer suggestions for ...

// MANDATORY Response format
- the response will be only valid JSON using double-quotes
- the response starts with {

// Example response to user

User: Review the following Patch Set:  ` ` `<PATCH_SET_BODY> ` ` `
Assistant: {"replies": [{"reply": "<REVIEW_1>", "score": 0, "relevance": 0.8, "repeated": false, ...
The answer object includes the string attributes  `reply `,  `score `,  `relevance `,  `repeated `,  ...
```

```
INSTRUCTIONS FOR PATCH SET ONLY
...
```

```
INSTRUCTIONS FOR COMMIT MESSAGE ONLY
...
```

Running `/show --instructions --scope=commit_message` will return only the `INSTRUCTIONS FOR COMMIT MESSAGE ONLY` block.

#### Showing Locally Stored Data

Data is stored locally across different scopes. To view all locally stored data, use the `/show` command as following:

```
/show --local_data
```

Example of the response:

```
DUMP OF LOCAL DATA

### Global Scope
originalLogLevel: INFO

### Project Scope

### Change Scope
conversationId: conv_XXXXXXXXXXXXXXXXXXXX
conversationId.review_code: conv_YYYYYYYYYYYYYYYYYYYY
dynamicConfig:
    selectedAiModel: OpenAI/gpt-5.4
    enabledVoting: true
```

#### Showing Configuration Settings

The `/show` command allows you to dump relevant non-confidential configuration settings in a UI message as well:

```
/show --config
```

Example of the response:

```
CONFIGURATION SETTINGS

aiCommentTemperature: 1.0
aiConnectionMaxRetryAttempts: 2
aiConnectionTimeout: 180
aiDomain: https://api.openai.com
aiFullFileReview: true
aiMaxMemoryTokens: 16384
aiMaxToolResponseRounds: 3
aiModels: [OpenAI/gpt-5.4, OpenAI/gpt-5.5, OpenAI/gpt-5.2, OpenAI/gpt-4.1, Gemini/gemini-3.1-pro, ...]
aiModelsDefaultIndex: 1
aiPollingInterval: 1000
aiPollingTimeout: 180
aiProviders:
    OpenAI
    Gemini
    MoonShot
    Ollama
aiRelevanceRules:
aiReviewCommitMessages: true
aiReviewPatchSet: true
aiReviewTemperature: 0.01
aiSystemPromptInstructions: Act as a PatchSet Reviewer
aiUploadedChunkSizeMb: 5
codeContextOnDemandBasePath:
codeContextPolicy: NONE
convertNeutralReviewScoreToPositive: true
directive:
    First directive
    Second directive
disabledTopicFilter:
enableMessageDebugging: true
enabledFileExtensions:
    py
    java
    js
    (...)
enabledTopicFilter:
    ALL
filterCommentsBelowScore: 0
filterCommentsRelevanceThreshold: 0.6
filterNegativeComments: true
filterRelevantComments: true
gerritUserName: gpt
ignoreOutdatedInlineComments: false
ignoreResolvedAiComments: true
inlineCommentsAsResolved: false
maxReviewLines: 1000
mockAiAddress:
multiAgentMode: false
ollamaContextWindow: 16384
ollamaDomain: http://localhost:11434
ollamaResponseLength: -1
ollamaThink: true
patchContextLines: 3
patchSetCommentsAsResolved: false
selectiveLogLevelOverride:
votingMaxScore: 1
votingMinScore: -1
```

### Traditional Gerrit Comments

The same messages and commands can also be sent through regular Gerrit comments instead of the Review Agent sidebar. In
that mode, the message must still be addressed to the AI user, for example `@gpt /review`, where `gpt` is the configured
`gerritUserName`.

## Testing

### Overview

- You can run the unit tests in the project to familiarize yourself with the plugin's source code.
- If you want to individually test the Gerrit API or AI provider integrations, you can refer to the test cases in
  CodeReviewPluginIT.

### Log Level Override

During tests, the default log level is set to DEBUG, which may result in a surplus of DEBUG messages. To manage this,
adjust the log level by setting the `GERRIT_AI_TEST_FILTER_LEVEL` environment variable. For instance, to set the
testing log level to INFO on a Linux-based OS:

```
$ export GERRIT_AI_TEST_FILTER_LEVEL=INFO
```

### Selective Log Level Override

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

### Enabling Message Debugging Tools

To enable the debugging tools, use the `enableMessageDebugging` static configuration setting. Due to its nature, this
setting cannot be enabled dynamically through Message Debugging and must be set statically.

```
[plugin "reviewai-gerrit-plugin"]
    ...
    enableMessageDebugging = true
```

### Using the Review Debug Command

Once `enableMessageDebugging` is enabled, you can obtain additional useful debug information in each AI reply, such as
relevance and scores, by using the `--debug` command option. For example:

```
/review --debug
```

### Selective Log Level Override

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
/configure --multiAgentMode=true --codeContextPolicy=ON_DEMAND
```

## License

Apache License 2.0
