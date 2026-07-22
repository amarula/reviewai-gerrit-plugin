# Command Reference

Commands can be sent directly from the Review Agent sidebar. The sidebar already addresses messages to AI, so command
examples in this section do not include a @{gerritUserName} prefix.

The `/configure`, `/show`, and `/directives` commands and the `/review --debug` option are available only in the
development build and are restricted to users in the ReviewAI Administrator group.

## Help

Use `/help` to display a summary of all supported commands and their main options. Use `/help <command>` or
`/help /<command>` to show detailed help for a single command.

Example:

```
/help
```

```
/help /review
```

## Plain Messages

To ask AI a question, type the message directly in the Review Agent sidebar. The sidebar handles plain chat messages
automatically by sending them as direct messages.

## Review Commands

Reviewing a Change Set can occur automatically upon submission or be manually triggered using the commands outlined in
this section.

### Basic Syntax

- `/review`: triggers a review of the full Change Set. A vote is cast on the Change Set if the voting feature is
  enabled and the AI Gerrit user is authorized to vote on it.

### Command Options

- `--filter=[true/false]`: Controls the filtering of duplicate, conflicting and irrelevant comments, defaulting to
  "true" to apply filters.
- `--scope=[patchset/commit_message]`: Limits the review scope. `patchset` reviews only the PatchSet code changes, and
  `commit_message` reviews only the commit message. If omitted, both are reviewed.
- `--topic`: Extends the review to all open changes with the same topic, using a single AI request.
- `--debug`: When paired with `/review`, this option displays useful debug information in each AI reply, showing all
  replies as though the filter setting were disabled.

  **NOTE**: The `--debug` option is reserved to users in the ReviewAI Administrator group.

## Suggest Command

The `/suggest` command generates native Gerrit suggested edits for negative review replies.

### Basic Syntax

* `/suggest`: Generates one or more native Gerrit suggested edits for each negative review reply in the selected scope,
  based on the available review results.

### Command Options

* `--scope=[patchset/commit_message]`: Limits the suggestion scope. `patchset` suggests fixes only for Patch Set code
  changes, and `commit_message` suggests fixes only for the commit message. If omitted, suggestions are generated for
  both.

## Dynamic Configuration

Users in the ReviewAI Administrator group can dynamically alter the plugin configuration for the current Change Set,
primarily for testing and debugging purposes.

### Basic Syntax

- `/configure` displays the current settings and their dynamically modified values in a response message.
- `/configure --<CONFIG_KEY_1>=<CONFIG_VALUE_1> [... --<CONFIG_KEY_N>=<CONFIG_VALUE_N>]` assigns new values to one or
  more configuration keys.

  **NOTE**: Values that include spaces, such as `aiSystemPromptInstructions`, must be enclosed in double quotes.

### Command Options

The `reset` option can be employed to restore modified settings to their original defaults. Its usage is detailed below:

- `/configure --reset` restores all modified settings to their default values.
- `/configure --reset --<CONFIG_KEY_1> [... --<CONFIG_KEY_N>]` specifically restores the indicated key(s) to their
  default values.

## Directives

Directives are mandatory instructions written in plain English that AI must adhere to during its reviews. In addition to
static directives, which can be specified in global and/or project configurations, directives can also be dynamically
managed using the `/directives` command.

Examples:

### Query Dynamic Directives

```
/directives
```

Example of the response:

```
1. First directive
2. Second directive
```

### Adding a Dynamic Directive

```
/directives Third directive with "quotation"
```

**NOTE**: In case of dynamic directives, double quotes do not need to be escaped.

### Removing a Dynamic Directive

The index in the response to `/directives` query can be used to remove single dynamic directives.

```
/directives --remove 1
```

### Removing All the Dynamic Directives

```
/directives --reset
```

## Forgetting Conversation History

For the OpenAI Responses backend, the plugin stores the OpenAI conversation ID for each Change Set so that forced or
reiterated reviews continue on the same durable conversation object. For the other LangChain providers, the plugin
stores chat memory locally by Change, Patch Set, and review scope. This history can be removed with the `/forget_thread`
command.
This functionality is crucial for preventing AI from merely recycling old responses, particularly following
modifications to configuration parameters.

### Basic Syntax

```
/forget_thread
```

## Showing Information

The `/show` command, followed by one or more options, can be used to display relevant information for debugging and
fine-tuning purposes. Below are the currently supported options and their associated objects:

- `prompts`: Shows the prompts currently used
- `instructions`: Shows the assistant instructions currently used
- `local_data`: Shows locally stored data
- `config`: Shows the current configuration
- `version`: Shows the plugin, build, Gerrit, and Java versions

For `--prompts` and `--instructions`, `--scope=full|patchset|commit_message` limits the output to a single review mode.
Use `--mode=suggest` to show the prompts or instructions used by `/suggest`.

**NOTE**: This command is reserved to users in the ReviewAI Administrator group.

### Showing Prompting Parameters

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
Running `/show --prompts --mode=suggest` will return the `/suggest` prompt block.

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
Running `/show --instructions --mode=suggest` will return the `/suggest` instructions block.

### Showing Locally Stored Data

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

### Showing Configuration Settings

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
aiMaxConcurrentRequests: 0
aiMaxMemoryTokens: 16384
aiMaxToolResponseRounds: 3
aiModels: [OpenAI/gpt-5.4, OpenAI/gpt-5.5, OpenAI/gpt-5.2, OpenAI/gpt-4.1, Gemini/gemini-3.1-pro, ...]
aiModelsDefault:
aiPollingInterval: 1000
aiPollingTimeout: 180
aiProviderZdr: false
aiProviders:
    OpenAI
    Gemini
    MoonShot
    Ollama
aiAdministratorsGroup: Administrators
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
enabledFileExtensions:
    py
    java
    js
    (...)
filterCommentsRelevanceThreshold: 0.6
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
```

## Traditional Gerrit Comments

The same messages and commands can also be sent through regular Gerrit comments instead of the Review Agent sidebar. In
that mode, the message must still be addressed to the AI user, for example `@gpt /review`, where `gpt` is the configured
`gerritUserName`.
