# AI Code Review Gerrit Plugin

## Features

This plugin allows you to use AI for code review in Gerrit conveniently. After submitting a Patch Set, AI will provide
review feedback in the form of comments and, optionally, a vote.
You can continue to ask AI by @{gerritUserName} or @{gerritEmailAddress} (provided that `gerritEmailAddress` is in the
form "gerritUserName@<any_email_domain>") in the comments to further guide it in generating more targeted review
comments.
Reviews can be also triggered by directing a comment with the `/review` command to AI.

## Getting Started

1. **Build:** Requires JDK 11 or higher, Maven 3.0 or higher.

   ```bash
   mvn -U clean package
    ```

   If the user needs to disable test just run
   ```bash
   mvn -U -DskipTests=true clean package
   ```

2. **Install:** Upload the compiled jar file to the `$gerrit_site/plugins` directory.

3. **Configure:** First, you need to create an AI user in Gerrit.
   Then, set up the basic parameters in your `$gerrit_site/etc/gerrit.config` file under the section

   `[plugin "reviewai-gerrit-plugin"]`:

- `aiToken`: AI token.
- `gerritUserName`: Gerrit username of AI user.
- `globalEnable`: Default value is false. The plugin will only review specified repositories. If set to true, the plugin
  will by default review all pull requests.

  For enhanced security, consider storing sensitive information like aiToken in a secure location or file.
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
    aiToken = {aiToken}
    ...

    # Optional parameters
    aiModel = {aiModel}
    aiSystemPromptInstructions = {aiSystemPromptInstructions}
    ...
```

#### Secure Configuration

It is highly recommended to store sensitive information such as `aiToken` in the `secure.config` file. Please edit the
file at $gerrit_site/etc/`secure.config` and include the following details:

```
[plugin "reviewai-gerrit-plugin"]
    aiToken = {aiToken}
```

If you wish to encrypt the information within the `secure.config` file, you can refer
to: https://gerrit.googlesource.com/plugins/secure-config

### Project Configuration

To add the following content, please edit the `project.config` file in `refs/meta/config`:

```
[plugin "reviewai-gerrit-plugin"]
    # Required parameters
    gerritUserName = {gerritUserName}
    ...

    # Optional parameters
    aiBackend = {aiBackend}
    aiModel = {aiModel}
    aiSystemPromptInstructions = {aiSystemPromptInstructions}
    ...
```

#### Secure Configuration

Please ensure **strict control over the access permissions of `refs/meta/config`** since sensitive information such as
`aiToken` is configured in the `project.config` file within `refs/meta/config`.

## AI Backends

The plugin supports multiple Backends, giving flexibility in selecting an AI provider. Backends may represent AI
providers themselves or frameworks that connect to them.

### OpenAI Backend

OpenAI Backend uses the **Assistant** resource to maintain a richer interaction context. This backend is designed to:

- Leverage OpenAI Threads to preserve the memory of conversations related to each Change Set.
- Link these Threads with OpenAI Assistants that are specialized according to the response needed.
- Associate the Assistants with the complete Codebase of the Git project related to the Change, which is updated
  each time commits are merged in Gerrit.

### LangChain Backend

The LangChain backend relies on the LangChain framework to connect with an AI provider.
OpenAI is used by default, and you can select Google Gemini or Moonshot by configuring the `lcProvider` parameter.

## Optional Parameters

- `aiBackend`: Selects the AI Backend for request processing. The currently supported Backend options are:
    - **OPENAI** (The default value)
    - **LANGCHAIN**
- `aiDomain`: Defines the base endpoint for the selected provider, either direct or through LangChain. By default, it
  uses the provider’s standard domain: `https://api.openai.com` (OpenAI), `https://generativelanguage.googleapis.com`
  (Gemini), or `https://api.moonshot.ai` (Moonshot). Override only when you need a custom endpoint; leaving it unset
  lets the plugin pick the provider default automatically.
- `aiModel`: Each provider has its own default model. OpenAI uses `gpt-4o`, Gemini uses `gemini-2.5-flash` and Moonshot
  `moonshot-v1-8k`. You can override the setting with other compatible models such as `gpt-4.1` or `gemini-2.5-pro`.
- `aiSystemPromptInstructions`: You can customize the default instructions ("Act as a PatchSet Reviewer") to your
  preferred prompt.
- `aiReviewTemperature`: Specifies the temperature setting for AI when reviewing a Patch Set, with a default
  setting of 0.2. Higher values like 0.8 will make the output more random, while lower values like 0.2 will make it more
  focused and deterministic.
- `aiCommentTemperature`: Specifies the temperature setting for AI when replying to a comment, with a default
  setting of 1.0.
- `aiReviewPatchSet`: Set to true by default. When switched to false, it disables the automatic review of Patch Sets as
  they are created or updated.
- `aiReviewCommitMessages`: The default value is true. When enabled, this option also verifies if the commit message
  matches with the content of the Change Set.
- `enabledUsers`: By default, every user is enabled to have their Patch Sets and comments reviewed. To limit review
  capabilities to specific users, list their usernames in this setting, separated by commas.
- `disabledUsers`: Functions oppositely to enabledUsers.
- `enabledGroups`: By default, all groups are permitted to have their Patch Sets and comments reviewed. To restrict
  review access to certain groups, specify their names in this setting, separating them with commas.
- `disabledGroups`: Operates in reverse to `enabledGroups`, excluding specified groups from reviews.
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
- `codeContextPolicy`: Defines the code context policy to provide AI with the missing code context from the ChangeSet.
  The currently supported policies are:
    - **ON_DEMAND**: Fetches just the minimal set of code artifacts requested by the Model. These artifacts can be
      function signatures, type declarations, or similar entities that provide sufficient context for reasoning about
      the change.
    - **UPLOAD_ALL**: Uploads the entire codebase during each merge event, giving AI full access to the necessary
      context for its reviews.
    - **NONE**: Skips file uploads entirely, relying solely on the formatted patch for reviews and interactions with AI.

### Optional Parameters Specific to OpenAI Backend

- `taskSpecificAssistants`: This option allows for dividing the Patch Set review between two specialized assistants: one
  focused to the Patch's code and another to the commit message. When this option is set to false (default value), the
  Patch Set review is unified into one single request processed by one assistant instructed for both tasks.

  **NOTE**: Enabling this feature may result in duplicate requests to AI, potentially increasing the usage costs of the
  AI API.
- `forceCreateAssistant`: Forces the creation of a new assistant with each request instead of only when configuration
  settings change or Changes are merged.

  **NOTE**: Enabling this feature may increase AI API usage and should be used for **testing or debugging purposes
  only**.

### Optional Parameters Specific to LangChain Backend

- `lcProvider`: Selects the LangChain provider (requires `aiBackend = LANGCHAIN`). Supported providers are `OPENAI`
  (default), `GEMINI`, and `MOONSHOT`. When a provider different from `OPENAI` is selected, and `aiDomain` retains its
  default value, the default endpoint of the selected provider is applied automatically.
- `lcMaxMemoryTokens`: Maximum number of tokens retained in memory per Change. The default value is 16K.

### Optional Parameters for Global Configuration only

- `globalEnable`: Set to false by default, meaning the plugin will review only designated repositories. If enabled, the
  plugin will automatically review all pull requests by default (not recommended in production environments).
- `enabledProjects`: The default value is an empty string. If globalEnable is set to false, the plugin will only run in
  the repositories specified here. The value should be a comma-separated list of repository names, for example:
  "project1,project2,project3".

### Optional Parameters for Project Configuration only

- `isEnabled`: The default is false. If set to true, the plugin will review the Patch Set of this project.

### Advanced Connection Parameters for OpenAI

These parameters are specific to connecting with the OpenAI server and should only be modified by advanced users:

- `aiConnectionTimeout`: Defines the timeout for connections to the OpenAI server, with a default of 30 seconds.
- `aiPollingTimeout`: Sets the timeout for terminating OpenAI polling on requests, defaulting to 180 seconds.
- `getPollingInterval`: Sets the interval for OpenAI polling on requests, defaulting to 1 second.
- `aiConnectionRetryInterval`: Sets the interval between two connection attempts, with a default of 10 seconds.
- `aiConnectionMaxRetryAttempts`: Determines the maximum number of retry attempts, defaulting to 2.
- `aiUploadedChunkSizeMb`: When uploading project repositories to OpenAI, the repositories are packaged and split into
  chunk files. This setting specifies the maximum size of each chunk file, with a default of 5 MB.

## Commands

### Message

To send messages to AI, simply mention the AI user in plain English within the message. However, if the message includes
a string that begins with a slash followed by letters, it might be mistaken for a command and result in an "Unknown
command" error. To avoid this, use the `/message` command to ensure the text is processed as a direct message to AI,
rather than as a command.

#### Command Example

For example, sending

```
@gpt is it OK to use "and/or"?
```

might trigger the following system response

```
SYSTEM MESSAGE: Unknown command in comment `@gpt is it OK to use "and/or"?`
```

due to the interpretation of `/or` as a command. However, using

```
@gpt /message is it OK to use "and/or"?
```

ensures the message is correctly forwarded to AI.

### Review Commands

Reviewing a Change Set or the last Patch Set can occur automatically upon submission or be manually triggered using the
commands outlined in this section.

#### Basic Syntax

- `/review`: when used in a comment directed at AI on any Change Set, triggers a review of the full Change Set. A vote
  is cast on the Change Set if the voting feature is enabled and the AI Gerrit user is authorized to vote on it.
- `/review_last`: when used in a comment directed at AI on any Change Set, triggers a review of the last Patch Set of
  the Change Set. Unlike `/review`, this command does not result in casting or updating votes.

#### Command Options

- `--filter=[true/false]`: Controls the filtering of duplicate, conflicting and irrelevant comments, defaulting to
  "true" to apply filters.
- `--debug`: When paired with `/review` or `/review_last` commands, this option displays useful debug information in
  each AI reply, showing all replies as though the filter setting were disabled.

  **NOTE**: The usage of `--debug` option is disabled by default. To enable it, `enableMessageDebugging` setting must be
  set to true.

### Dynamic Configuration

You can now dynamically alter the plugin configuration via messages sent to the AI user, primarily for testing and
debugging purposes. This feature becomes available when the `enableMessageDebugging` configuration setting is enabled.

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

### Forgetting Thread History

Threads capture all prior interactions and evaluations involving AI within each Change Set. The history stored in these
threads can be removed with the `/forget_thread` command. This functionality is crucial for preventing AI from merely
recycling old responses, particularly following modifications to configuration parameters.

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

**NOTE**: This command is available when the `enableMessageDebugging` configuration setting is enabled.

#### Showing Prompting Parameters

The `/show` command also enables you to view the prompts and assistant instructions used with your current
configuration.

For example, running `@gpt /show --prompts` will return something like:

```
PROMPTS CURRENTLY USED

### Review Prompt
Review the following Patch Set:  ` ` `Subject: <COMMIT_MESSAGE> Change-Id: ... <PATCH_SET> ` ` `
```

Similarly, running `@gpt /show --instructions` will display something like:

```
INSTRUCTIONS CURRENTLY USED

### Assistant Instructions
Act as a PatchSet Reviewer. Disregard missing implementations of methods or other code entities, as the full ...
RULE #1: You MUST take into account of the messages previously exchanged in the thread for your review. ...
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
vectorStoreId: vs_XXXXXXXXXXXXXXXXXXXX
fileId: vs_XXXXXXXXXXXXXXXXXXXX

### Change Scope
threadId: thread_XXXXXXXXXXXXXXXXXXXX
dynamicConfig:
    aiBackend: OPENAI
    enabledVoting: true
assistantIdLog:
    2024-08-09 10:28:46.973108457: asst_XXXXXXXXXXXXXXXXXXXXXXXX
    2024-08-09 10:29:00.460755585: asst_YYYYYYYYYYYYYYYYYYYYYYYY
```

#### Showing Configuration Settings

The `/show` command allows you to dump relevant non-confidential configuration settings in a UI message as well:

```
/show --config
```

Example of the response:

```
CONFIGURATION SETTINGS

aiBackend: OPENAI
aiCommentTemperature: 1.0
aiDomain: https://api.openai.com
aiFullFileReview: true
aiModel: gpt-4-turbo
aiReviewCommitMessages: true
aiReviewPatchSet: true
aiReviewTemperature: 0.2
aiStreamOutput: false
directive:
    First directive
    Second directive
disabledGroups:
disabledTopicFilter:
disabledUsers:
enableMessageDebugging: true
enabledFileExtensions:
    py
    java
    js
    (...)
enabledGroups:
    ALL
enabledProjects:
enabledTopicFilter:
    ALL
enabledUsers:
    ALL
filterCommentsBelowScore: 0
filterCommentsRelevanceThreshold: 0.6
filterNegativeComments: true
filterRelevantComments: true
forceCreateAssistant: false
gerritUserName: gpt
ignoreOutdatedInlineComments: false
ignoreResolvedAiComments: true
inlineCommentsAsResolved: false
maxReviewFileSize: 20000
maxReviewLines: 1000
patchSetCommentsAsResolved: false
selectiveLogLevelOverride:
taskSpecificAssistants: false
votingMaxScore: 1
votingMinScore: -1
```

### Uploading Codebase

This command allows you to force the upload of the Git project's codebase to AI.

**NOTE**: Running this command, available when the `enableMessageDebugging` configuration setting is enabled, may
increase AI API usage and should be used for **testing or debugging purposes only**.

#### Basic Syntax

```
/upload_codebase
```

## Testing

### Overview

- You can run the unit tests in the project to familiarize yourself with the plugin's source code.
- If you want to individually test the Gerrit API or the OpenAI API, you can refer to the test cases in
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

For example, to filter DEBUG messages containing the OpenAI request and response bodies, the pipe ("|") prefix must be
used:

```
$ export GERRIT_AI_TEST_FILTER_VALUE="|OpenAI request body, |OpenAI response body"
```

For multiple items with spaces, enclose the settings string in double quotes and escape any internal double quotes:

```
$ export GERRIT_AI_TEST_FILTER_VALUE="|OpenAI request body, ChatGptRun|OpenAI Retrieve Run"
```

This setting shows the DEBUG log messages containing the string "OpenAI request body" and the ones in `OpenAiRun`
containing "OpenAI Retrieve Run".

## Debugging

In addition to standard testing tools, we provide additional resources to assist with live debugging of the AI
plugin when running on a Gerrit instance. These tools can be managed through both static configurations (such as
modifying `gerrit.config` and `project.config`) and dynamic configurations (using the `/configure` command in a message
addressed to OpenAI).

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
@gpt /review --debug
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
@gpt /configure --selectiveLogLevelOverride="[ClientMessage, ClientCommandExecutor]"
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

For example, all DEBUG messages in classes whose log messages start with the OpenAI request and response bodies can be
elevated with:

```
@gpt /configure --selectiveLogLevelOverride="[\"|OpenAI request body\", \"|OpenAI response body\"]"
```

### Dynamically Changing Settings for Testing/Debugging

Settings can be locally modified for the current Change Set using the `/configure` command. For instance, to set the
review temperature to "1.0," you can use:

```
@gpt /configure --aiReviewTemperature=1.0
```

Following this configuration, a new Change Set review can be initiated with:

```
@gpt /review
```

It's also possible to make multiple changes at once:

```
@gpt /configure --aiBackend=OPENAI --aiModel=gpt-4-turbo
```

## License

Apache License 2.0
