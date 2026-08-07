# Configuration

You have the option to establish global settings, or independently configure specific projects. If you choose
independent configuration, the corresponding project settings will override the global parameters.

## Global Configuration

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
    aiModelsDefault = OpenAI/gpt-5.2
    aiPricing = OpenAI/custom-model,input=1.00,cachedInput=0.10,output=5.00
    aiAdministratorsGroup = Administrators
    aiSystemPromptInstructions = {aiSystemPromptInstructions}
    ...
```

### Secure Configuration

It is highly recommended to store sensitive information such as `aiTokens` in the `secure.config` file. Please edit
`$gerrit_site/etc/secure.config` and include the following details:

```
[plugin "reviewai-gerrit-plugin"]
    aiTokens = OpenAI/{openAiToken}
    aiTokens = MoonShot/{moonShotToken}
```

If you wish to encrypt the information within the `secure.config` file, you can refer
to: https://gerrit.googlesource.com/plugins/secure-config

## Project Configuration

To add the following content, please edit the `project.config` file in `refs/meta/config`:

```
[plugin "reviewai-gerrit-plugin"]
    # Optional parameters
    aiProviders = {providerRoute}
    aiModels = {providerModelRoute}
    aiPricing = {providerModelPricing}
    aiSystemPromptInstructions = {aiSystemPromptInstructions}
    ...
```

### Secure Configuration

Please ensure **strict control over the access permissions of `refs/meta/config`** if sensitive information such as
`aiTokens` is configured in the `project.config` file within `refs/meta/config`.

## AI Provider Routes

The plugin supports multiple AI providers through LangChain. The Review Agent exposes each configured provider/model
combination using `/` syntax.

Supported providers are:

- OpenAI
- DeepSeek
- MoonShot
- Gemini
- Ollama

Model and token settings are grouped by the provider part of the route. If a provider is configured without explicit
models, the plugin exposes the built-in defaults for that provider.

```
[plugin "reviewai-gerrit-plugin"]
    aiProviders = OpenAI
    aiProviders = DeepSeek
    aiProviders = MoonShot
    aiProviders = Ollama

    aiModels = OpenAI/gpt-5.4
    aiModels = OpenAI/gpt-4.1
    aiModels = DeepSeek/deepseek-v4-flash
    aiModels = MoonShot/moonshot-v1-8k
    aiModels = llama3.2
    aiModelsDefault = OpenAI/gpt-5.4

    aiTokens = OpenAI/{openAiToken}
    aiTokens = DeepSeek/{deepSeekToken}
    aiTokens = MoonShot/{moonShotToken}
```

With this configuration, the Review Agent exposes `OpenAI/gpt-5.4`, `OpenAI/gpt-4.1`,
`DeepSeek/deepseek-v4-flash`, `MoonShot/moonshot-v1-8k`, and `Ollama/llama3.2`.
Ollama does not require an `aiTokens` entry. A bare model can also be configured as `aiModels = llama3.2`. When no
configured token-backed provider identifies the bare model route, the plugin guesses `Ollama/llama3.2`. If a bare model
matches a configured or default model for a token-backed provider that has a token, that provider route is used.

## AI Pricing Overrides

`aiPricing` overrides a built-in price or adds pricing for a custom model route used by estimated-cost telemetry. It is
a repeatable setting with one entry per exact provider/model route. All prices are in USD per one million tokens:

```ini
[plugin "reviewai-gerrit-plugin"]
    aiPricing = <Provider>/<model>,input=<price>,output=<price>[,cachedInput=<price>][,cacheWrite=<price>][,longThreshold=<tokens>,longInput=<price>,longCachedInput=<price>,longCacheWrite=<price>,longOutput=<price>]
```

Chiama| Field | Required | Meaning and default |
| --- | --- | --- |
| `input` | Yes | Regular input-token price. |
| `output` | Yes | Output-token price. |
| `cachedInput` | No | Cached-input price; defaults to `input`. |
| `cacheWrite` | No | Cache-write price; defaults to `input`. |
| `longThreshold` | No | Positive input-token boundary above which long-context prices apply. |
| `longInput` | No | Long-context input price; defaults to `input`. |
| `longCachedInput` | No | Long-context cached-input price; defaults to `cachedInput`. |
| `longCacheWrite` | No | Long-context cache-write price; defaults to `longInput`. |
| `longOutput` | No | Long-context output price; defaults to `output`. |

For example, the following entry adds a custom route with cache and long-context pricing:

```ini
[plugin "reviewai-gerrit-plugin"]
    aiModels = OpenAI/custom-model
    aiPricing = OpenAI/custom-model,input=1.00,cachedInput=0.10,cacheWrite=1.25,output=5.00,longThreshold=200000,longInput=2.00,longCachedInput=0.20,longCacheWrite=2.50,longOutput=7.50
```

The route is matched exactly. For example, pricing for `OpenAI/gpt-5.4` is not automatically applied to
`OpenAI/gpt-5.4-2026-06-15`; the snapshot needs its own entry. Prices must be zero or positive. An invalid entry is
ignored and logged, leaving any valid built-in price unchanged.

Entries from `gerrit.config` and a project's `project.config` are combined. If both scopes define the same route, the
project entry is applied last and takes precedence. Ollama and mock routes are excluded from cost tracking. See
[Telemetry](telemetry.md#cost-calculation) for the built-in catalog, calculation rules, and exported cost
metrics.

## Conditional AI Review Trigger

`aiReviewApplicableIf` delays automatic AI review until a Gerrit submit-requirement expression matches the change.
The expression uses Gerrit's submit-requirement query syntax, so Gerrit evaluates label votes and other change
conditions instead of the plugin implementing its own comparison rules.

If this option is unset or empty, Patch Sets are reviewed immediately as before. The option can be set globally or in
a project's configuration. For example, to wait until CI has voted `Verified+1` or higher:

```ini
[plugin "reviewai-gerrit-plugin"]
    aiReviewApplicableIf = label:Verified>=1
```

Multiple conditions can be combined in one expression. The following waits for both CI verification and a positive
Code-Review vote:

```ini
[plugin "reviewai-gerrit-plugin"]
    aiReviewApplicableIf = label:Verified>=1 AND label:Code-Review>=1
```

Gerrit label expressions can also check maximum and minimum votes, voter identity, and vote counts. For example, this
condition requires the maximum `Verified` vote and rejects a minimum-vote veto:

```ini
[plugin "reviewai-gerrit-plugin"]
    aiReviewApplicableIf = label:Verified=MAX AND -label:Verified=MIN
```

Branch, file, footer, author, and other predicates supported by Gerrit submit requirements can be combined with label
conditions. `is:submittable` cannot be used because Gerrit disallows recursive submit-requirement evaluation. Refer to
the [Gerrit submit-requirement expression documentation](https://gerrit-documentation.storage.googleapis.com/Documentation/3.13.1/config-submit-requirements.html#query_expression_syntax)
for the available operators and escaping rules.

When a Patch Set is created, the plugin evaluates the expression before starting its review. If it does not match, the
Patch Set is skipped. A later label vote causes the expression to be evaluated again; when it becomes applicable, the
plugin starts the deferred review automatically. Each change in a topic review is evaluated independently. Invalid
expressions and evaluation failures are logged and fail closed, so they do not start an AI review.

Currently, deferred reevaluation is driven by label-bearing `comment-added` events. Conditions that become true only
after another event, such as deleting a veto vote or changing WIP, topic, or hashtag state, are applied on the next
Patch Set or label-vote event rather than immediately.

## Optional Parameters

- `aiProviders`: Selects provider routes to expose. The default value is `OpenAI`.
- `aiModels`: Selects model routes by provider. When no models are configured for an exposed provider, the plugin
  exposes built-in defaults.
- `aiModelsDefault`: Selects the default model by provider/model route, such as `OpenAI/gpt-5.4`. This model is used
  for automatic Patch Set reviews and as the initial Review Agent dropdown value when no model has been selected yet.
  If unset or not found in the expanded `aiModels` list, the first available provider/model route is used.
- `aiPricing`: Repeatable exact provider/model pricing override used by estimated-cost telemetry. See
  [AI Pricing Overrides](#ai-pricing-overrides).
- `aiTokens`: Provides provider tokens. Configure these as `OpenAI/{token}`, `DeepSeek/{token}`,
  `MoonShot/{token}`, and so on. Ollama does not require a token.
- `aiDomain`: Defines the base endpoint for the selected provider. By default, it uses the provider’s standard domain:
  `https://api.openai.com` (OpenAI), `https://generativelanguage.googleapis.com` (Gemini),
  `https://api.deepseek.com` (DeepSeek), `https://api.moonshot.ai` (Moonshot), or `http://localhost:11434` (Ollama).
  Override only when you need a custom endpoint; leaving it unset lets the plugin pick the provider default
  automatically.
- `mockAiAddress`: Configures a custom address for a mock AI server. When set, a `mock-ai` model is added for each
  configured provider route, such as `OpenAI/mock-ai`, `MoonShot/mock-ai`, or `Ollama/mock-ai`. Selecting one of these
  models keeps the same provider and token behavior as the corresponding live model route, but sends AI requests to the
  configured mock server address instead. Because mock models are appended to the regular model list, they can be
  selected through `aiModelsDefault` like any other model.
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
- `aiReviewApplicableIf`: Gerrit submit-requirement expression that must match before an automatic AI review starts.
  See [Conditional AI Review Trigger](#conditional-ai-review-trigger).
- `aiAdministratorsGroup`: Gerrit group whose members can use administrator-only ReviewAI commands and view
  administrator-only details with the Development build. If this option is not set, or the configured group does not
  exist in Gerrit, the plugin falls back to the Gerrit Administrators group.
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
- `filterCommentsRelevanceThreshold`: Any review comment assigned a relevance score by AI below this threshold will not
  be shown. The default threshold is set at 0.6.
- `aiRelevanceRules`: This option allows customization of the rules AI uses to determine the relevance of a task.
- `patchSetCommentsAsResolved`: Initially set to false, this option leaves AI's Patch Set comments as unresolved,
  inviting further discussion. If activated, it marks AI's Patch Set comments as resolved.
- `inlineCommentsAsResolved`: Initially set to false, this option leaves AI's inline comments as unresolved, inviting
  further discussion. If activated, it marks AI's inline comments as resolved.
- `selectiveLogLevelOverride`: This setting allows for overriding the log level of specific messages, ensuring they are
  logged even if their level is above the current setting. This is useful for debugging without the need to set the
  overall log level to DEBUG, which could result in excessive DEBUG messages from sources like gerrit and other plugins.
  Some usage examples can be found at [Selective Production Logging](development.md#selective-production-logging) section.
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
- `aiMaxConcurrentRequests`: Maximum number of concurrent requests sent to AI models across review workflows. The
  default value is `0`, which means unlimited.
- `aiMaxMemoryTokens`: Maximum number of tokens retained in LangChain memory per Change, Patch Set, and review scope.
  The default value is 16K.
- `aiMaxToolResponseRounds`: Maximum number of tool-response continuation rounds allowed for one AI review request.
  This applies when ON_DEMAND code context tools are enabled and defaults to 3.
- `topicPatchSetWaitMs`: Time, in milliseconds, to wait when handling a Patch Set event for a change with a topic. This
  gives the plugin time to group related Patch Sets from the same topic and run an overall AI review. The default value
  is `3000` milliseconds.

### Optional Parameters Specific to Review Processing

- `multiAgentMode` (deprecated): This option allows for dividing the Patch Set review between two specialized agents:
  one focused to the Patch's code and another to the commit message. When this option is set to false (default value),
  the Patch Set review is unified into one single request processed by one agent instructed for both tasks.

- `agentSpecializationLevel`: Controls how review work is assigned to AI agents. This option overrides the deprecated
  `multiAgentMode` setting. Supported values are:
    * `SINGLE_AGENT`: Uses one agent to review both Patch Set code changes and the commit message. This is the default
      value and is equivalent to `multiAgentMode=false`.
    * `SCOPED_AGENTS`: Splits the review between dedicated Patch Set and Commit-Message agents. This is equivalent to
      `multiAgentMode=true`.
    * `SPECIALIZED_AGENTS`: Uses dedicated Patch Set review agents for correctness, testability, code quality,
      documentation, and security, based on prompts and workflow patterns imported from the Sashiko project
      (https://github.com/sashiko-dev/sashiko).

      When SPECIALIZED_AGENTS is selected, the Sashiko prompts override custom prompts, including those set through
      `aiRelevanceRules`, `aiSystemPromptInstructions`, `ai-instructions.md`, and prompts imported from Gerrit.

  See [Review Agent Architecture](agent-architecture.md) for the execution flow at each level, the concern lifecycle,
  and the differences between initial and subsequent reviews.

**NOTE**: Enabling these features may send multiple AI requests for a single review, which might increase AI API usage
costs.

### Optional Parameters Specific to OpenAI Provider

- `aiProviderZdr`: Enables Zero Data Retention (ZDR) mode for the OpenAI provider. When set to `true`, the plugin uses
  the Responses API with `store: false`, keeps conversation state in local plugin memory, and replays encrypted
  reasoning items between tool-call rounds. OpenAI Conversations are not used. This applies to every OpenAI model.
  The default value is `false`.

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
