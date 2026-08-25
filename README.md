# AI Code Review Gerrit Plugin

## Features

This plugin adds ReviewAI support to Gerrit through the Review Agent sidebar, giving users a standard chatbot interface
inside the Gerrit change page. From the sidebar, users can ask questions about the Change, select the AI model, and keep
conversation history tied to the review.

ReviewAI can also review Patch Sets automatically, posting feedback as Gerrit comments and, optionally, a vote. Users
can continue the discussion in Gerrit comments by mentioning `@{gerritUserName}` or `@{gerritEmailAddress}`
(provided that `gerritEmailAddress` is in the form `gerritUserName@<any_email_domain>`), trigger reviews with
`/review`, and view command help with `/help` or `/help <command>`.

## Getting Started

### Build

This version requires JDK 21 and Bazel. From the Gerrit checkout, link the plugin Bazel dependencies, update the module
lockfile, and build the production plugin:

```bash
cd gerrit/plugins
rm external_plugin_deps.MODULE.bazel
ln -s reviewai-gerrit-plugin/external_plugin_deps.MODULE.bazel external_plugin_deps.MODULE.bazel

cd ..
bazel mod deps --lockfile_mode=update
bazelisk build plugins/reviewai-gerrit-plugin:reviewai-gerrit-plugin
```

The generated JAR is available under Gerrit's `bazel-bin/plugins/reviewai-gerrit-plugin/` directory. See
[Development and Debugging](docs/development.md) for the development build and test commands.

### Install

Upload `reviewai-gerrit-plugin.jar` to the `$gerrit_site/plugins` directory.

### Minimal Configuration

Create an AI user in Gerrit, then add the following settings to `$gerrit_site/etc/gerrit.config`:

```ini
[plugin "reviewai-gerrit-plugin"]
    gerritUserName = {gerritUserName}
    aiTokens = OpenAI/{openAiToken}
```

`aiProviders` and `aiModels` are optional. If omitted, the plugin exposes the default OpenAI model routes. Sensitive
values such as `aiTokens` should be stored in `$gerrit_site/etc/secure.config`. See
[Configuration](docs/configuration.md) for provider routes, project-level settings, and the complete parameter
reference.

### Verify

After restarting Gerrit, confirm that its logs contain an entry similar to:

```text
INFO com.google.gerrit.server.plugins.PluginLoader : Loaded plugin reviewai-gerrit-plugin, version ...
```

The Gerrit plugin page should also show `reviewai-gerrit-plugin` as enabled.

## Usage Examples

### Review Agent Sidebar

The Review Agent is available from the Gerrit change page sidebar. It provides quick actions for full reviews, as well
as scoped Patch Set and Commit Message reviews.

<kbd><img src="images/reviewai-sidebar.png?raw=true" alt="Review Agent sidebar"></kbd>

### Model Selection

The sidebar allows users to select the AI provider and model used for the review.

<kbd><img src="images/reviewai-sidebar-model_dropdown.png?raw=true" alt="ReviewAI model selection"></kbd>

### Automatic Reviews

When a Patch Set is submitted, ReviewAI can automatically review the change and publish findings. In this example, the
review produces a `Code-Review -1` recommendation.

See [Review Agent Architecture](docs/agent-architecture.md) for the agent-specialization levels and the concern
lifecycle across successive Patch Sets.

See [CI Integration](docs/ci-integration.md) to defer reviews until Jenkins, SonarQube, or another CI system has
voted on the Patch Set.

<kbd><img src="images/reviewai-sidebar-review.png?raw=true" alt="ReviewAI Patch Set review"></kbd>

Voting is disabled by default. Enable it globally or per project with the `enabledVoting` configuration option.

### Follow-up Interaction

Users can continue the conversation with ReviewAI from the sidebar. In this example, the user asks for a full commit
message based on the previous review, and ReviewAI replies in the same conversation.

<kbd><img src="images/reviewai-sidebar-message_reply.png?raw=true" alt="ReviewAI follow-up message"></kbd>

### Gerrit Change Log

ReviewAI comments are published directly in Gerrit, including patch-set-level feedback and inline comments on the
affected files. This keeps review feedback and further AI interactions available in the Gerrit change log.

<kbd><img src="images/reviewai-gerrit_change_log-review.png?raw=true" alt="ReviewAI comments in Gerrit change log"></kbd>

More examples of AI code reviews and inline discussions are available on the
[ReviewAI project page](https://wiki.amarulasolutions.com/opensource/products/chatgpt-gerrit.html).

## AI Providers

ReviewAI supports OpenAI, Gemini, DeepSeek, MoonShot, and Ollama through provider/model routes such as
`OpenAI/gpt-5.4` and `Ollama/llama3.2`. OpenAI is the default provider; OpenAI, Gemini, DeepSeek, and MoonShot require
provider tokens, while Ollama does not.

See [Configuration](docs/configuration.md#ai-provider-routes) for model selection, tokens, default routes, and custom
endpoints.

## Commands

Messages and commands can be entered directly in the Review Agent sidebar:

```text
> Explain the purpose of this change.

> /review

> /review --scope=commit_message

> /suggest

> /help
```

They can also be sent through traditional Gerrit comments by addressing the configured AI user, for example
`@gpt /review`.

See the [Command Reference](docs/commands.md) for every command, option, scope, and administrator-only operation.

## Documentation

- [Review Agent Architecture](docs/agent-architecture.md)
- [Configuration](docs/configuration.md)
- [Command Reference](docs/commands.md)
- [Development and Debugging](docs/development.md)
- [Telemetry](docs/telemetry.md)

## License

Apache License 2.0
