# ReviewAI Telemetry

This document describes the telemetry service available in the ReviewAI plugin. It covers ReviewAI review-run, AI
backend request, and estimated provider-cost telemetry.

ReviewAI registers Gerrit metrics with `MetricMaker`. When Gerrit's `metrics-reporter-prometheus` plugin is installed,
those metrics are exported at:

```text
/plugins/metrics-reporter-prometheus/metrics
```

Prometheus can scrape that endpoint periodically and store historical samples in its own time-series database.

## Installation

### Install the Gerrit Prometheus reporter

Download a `metrics-reporter-prometheus` plugin build that matches your Gerrit version from
https://gerrit-ci.gerritforge.com/plugin-manager/ and copy it into the Gerrit site plugin directory:

```bash
cp metrics-reporter-prometheus.jar "$GERRIT_SITE/plugins/metrics-reporter-prometheus.jar"
```

Configure a bearer token for Prometheus in `$GERRIT_SITE/etc/gerrit.config`:

```ini
[plugin "metrics-reporter-prometheus"]
    prometheusBearerToken = <prometheus-bearer-token>
```

Restart Gerrit, or reload/install the plugin using the Gerrit SSH plugin commands used by your deployment. Confirm that
the reporter is loaded:

```bash
ssh -p 29418 <admin>@<gerrit-host> gerrit plugin ls
```

Verify the endpoint with the configured token:

```bash
curl -sS \
  -H 'Authorization: Bearer <prometheus-bearer-token>' \
  http://localhost:9575/plugins/metrics-reporter-prometheus/metrics \
  | grep -i 'plugins_reviewai_gerrit_plugin_reviewai'
```

Common endpoint failures:

- `404 Not Found`: the Prometheus reporter plugin is not installed, is not loaded, or a reverse proxy is not forwarding
  the `/plugins/metrics-reporter-prometheus/metrics` path to Gerrit.
- `403 Forbidden`: the endpoint exists, but the request is missing a valid bearer token or the caller does not have
  permission to view Gerrit metrics.

### Install Prometheus

Create a Prometheus configuration that scrapes Gerrit:

```yaml
scrape_configs:
  - job_name: gerrit
    metrics_path: /plugins/metrics-reporter-prometheus/metrics
    static_configs:
      - targets:
          - localhost:9575
    authorization:
      type: Bearer
      credentials: <prometheus-bearer-token>
```

Run Prometheus with persistent storage. For example, with Docker:

```bash
docker run --name prometheus -p 9090:9090 \
  -v "$PWD/prometheus.yml:/etc/prometheus/prometheus.yml:ro" \
  -v prometheus-data:/prometheus \
  prom/prometheus
```

For a native service, make sure `--storage.tsdb.path` points to persistent storage:

```bash
prometheus \
  --config.file=/etc/prometheus/prometheus.yml \
  --storage.tsdb.path=/var/lib/prometheus \
  --storage.tsdb.retention.time=15d
```

## Metrics

ReviewAI registers metrics eagerly when the plugin loads, so the metric names are visible before the first review event
runs. Values remain zero until an instrumented ReviewAI path records activity.

| Gerrit metric                                | Type    | Dimensions                    | Description                                                      |
|----------------------------------------------|---------|-------------------------------|------------------------------------------------------------------|
| `reviewai/review_run/count`                  | Counter | `event_type`, `status`        | Number of ReviewAI event processing attempts.                    |
| `reviewai/review_run/latency`                | Timer   | `event_type`, `status`        | End-to-end ReviewAI event processing latency.                    |
| `reviewai/ai_request/count`                  | Counter | `provider`, `stage`, `status` | Number of backend requests made by ReviewAI.                     |
| `reviewai/ai_request/latency`                | Timer   | `provider`, `model`, `stage`  | AI backend request latency.                                      |
| `reviewai/ai_request/estimated_cost_nanousd` | Counter | `provider`, `model`           | Cumulative estimated provider cost in nanoUSD.                   |
| `reviewai/ai_request/pricing_missing`        | Counter | `provider`, `model`           | Responses whose exact provider/model route has no pricing entry. |

### Review Runs and Backend Requests

The two metric families observe different levels of the review workflow and do not have a one-to-one relationship:

- A **review run** is one supported Gerrit event-processing attempt, such as processing one `patchset_created` event. It
  starts after event preprocessing succeeds and finishes when the event handler completes or throws an exception. Its
  latency covers the complete instrumented workflow, including orchestration, all AI work performed by the event
  handler, and other processing inside that workflow.
- An **AI backend request** is one instrumented AI execution attempt for one review-assistant stage. A review run can
  make zero, one, or several of these attempts. For example, a specialized review can invoke triage, multiple agents,
  and consolidation, producing several backend-request observations while producing only one review-run observation.

The backend-request boundary wraps the complete AI execution for a stage, not just one provider HTTP round trip. Its
latency includes request preparation and the model/tool execution. If the model requests tools, the same execution can
contain an initial provider call followed by one or more tool-continuation calls; it is still one
`reviewai/ai_request/count` observation. Cost tracking is finer-grained and can add cost for every provider response in
that execution.

Review-run latency is therefore not the sum of backend-request latencies. Backend attempts can run concurrently, and the
review run also includes non-AI processing. Use review-run metrics to measure Gerrit-event throughput, success, and
end-to-end latency. Use backend-request metrics to compare AI providers, models, stages, and execution failures. Use
estimated-cost metrics to measure provider spend.

### Dimension Values

`reviewai/review_run/*` uses these dimensions:

- `event_type`: Gerrit event type, such as `patchset_created`, or `comment_added` if Gerrit does not provide it.
- `status`: `completed` when event processing succeeds, or `error` when processing throws an exception.

Review-run telemetry starts after event preprocessing succeeds. Unsupported events and events rejected during
preprocessing return `NOT_SUPPORTED` and do not increment these metrics.

`reviewai/ai_request/*` uses these dimensions:

- `provider`: AI provider name, such as `OPENAI`, `GEMINI`, `DEEPSEEK`, `MOONSHOT`, or `OLLAMA`.
- `model`: configured model name used for the request.
- `stage`: ReviewAI assistant stage, including the agent specialization for specialized-agent requests, or `unknown`
  if no stage is available.
- `status`: `completed` for a non-null AI response, `empty` for a null AI response, or `error` when the request fails.

The no-value fallback for every dimension is `unknown`.

Cost telemetry uses the configured provider/model route as its dimensions. For example, a request configured as
`OpenAI/gpt-5.4` is attributed to provider `OpenAI` and model `gpt-5.4`, even if the provider response identifies a
dated backend snapshot. Model routes are matched exactly for pricing; an arbitrary route such as
`OpenAI/gpt-5.4-2026-06-15` does not automatically inherit the `OpenAI/gpt-5.4` price.

Current stage values are:

- `REVIEW_CODE`
- `REVIEW_COMMIT_MESSAGE`
- `REVIEW_REITERATED`
- `REVIEW_SPECIALIZED_TRIAGE`
- `REVIEW_SPECIALIZED_AGENT_<agent>`, where `<agent>` is `CODE_QUALITY`, `CORRECTNESS`, `DOCUMENTATION`, `SECURITY`,
  or `TESTABILITY`
- `REVIEW_SPECIALIZED_CONSOLIDATION`
- `REVIEW_SPECIALIZED_HISTORICAL_REPETITION`
- `REVIEW_SPECIALIZED_CONFLICT_RESOLUTION`
- `REVIEW_SPECIALIZED_VERIFICATION`

The generic `REVIEW_SPECIALIZED_AGENT` value is used only as a fallback if a specialized-agent request has no agent
name. Each named specialized agent has its own request-count and latency series. Do not sum their latencies to estimate
the duration of the parallel agent phase; use review-run latency for end-to-end wall-clock duration.

## Cost Calculation

### Disclaimer

Cost tracking is provided as is for informational and monitoring purposes only. Reported costs are estimates derived
from token usage, configured pricing data, and other available metrics.

ReviewAI makes no representation or warranty, express or implied, regarding the accuracy, completeness, or reliability
of these estimates. Actual charges may differ because of pricing changes, provider-specific billing rules,
token-counting differences, rounding, cached-token pricing, discounts, credits, taxes, or incomplete telemetry data.

The AI provider's billing records and invoices remain the authoritative source for all charges. Cost-tracking data
should not be relied upon for accounting, invoicing, financial reporting, or other decisions requiring precise billing
information.

### Calculation Method

ReviewAI estimates cost for every physical AI response with complete input- and output-token usage. This includes the
initial request, each tool-continuation request, and multi-agent router requests. It is therefore normal for one logical
review counted by `reviewai/ai_request/count` to contribute more than once to the cost counter.

The metric unit is nanoUSD:

```text
1 USD = 1,000,000,000 nanoUSD
```

For one response, the plugin calculates:

```text
standard input tokens = input tokens - cached input tokens - cache-write tokens

cost in nanoUSD = 1,000 * (
    standard input tokens * input price in USD per million
  + cached input tokens * cached-input price in USD per million
  + cache-write tokens * cache-write price in USD per million
  + output tokens * output price in USD per million
)
```

Configured prices are expressed in USD per one million tokens. Multiplying such a price by one token is equivalent to
multiplying it by `1,000` to obtain nanoUSD. The complete response cost is calculated with decimal arithmetic and
rounded once to the nearest nanoUSD.

Additional behavior:

- The built-in catalog contains Standard-processing prices only. Batch, Flex, Priority, and regional processing are not
  included.
- OpenAI models above 272,000 input tokens and tiered Gemini models above 200,000 input tokens use their long-context
  tier. The tier is selected independently for each physical response using its complete reported input-token count,
  including cached and cache-write tokens.
- Cached-input pricing is used when the provider integration retains a reported cache-hit count. If that detail is not
  available, all input is charged at the regular input price, producing a conservative estimate.
- GPT-5.6 cache-write pricing is used only when the response explicitly reports `cache_write_tokens`. Otherwise,
  non-cached input is charged at the regular input price.
- A response for a known-priced route without complete token usage does not update the cost counter. Unknown exact model
  routes increment `pricing_missing` once per response, independently of token-usage availability.
- Ollama and ReviewAI mock-model routes are excluded from both cost metrics.

### Built-in pricing

The built-in prices are USD per one million tokens and were verified on 2026-07-22 against the official
[OpenAI](https://developers.openai.com/api/docs/pricing),
[Gemini](https://ai.google.dev/gemini-api/docs/pricing),
[DeepSeek](https://api-docs.deepseek.com/quick_start/pricing/), and
[Moonshot](https://platform.kimi.ai/docs/pricing) documentation. Administrators can override them without rebuilding the
plugin, as described below.

| Provider/model                                         | Input | Cached input | Cache write | Output | Long-context input / cached / write / output |
|--------------------------------------------------------|------:|-------------:|------------:|-------:|----------------------------------------------|
| `OpenAI/gpt-5.6-sol`                                   |  5.00 |         0.50 |        6.25 |  30.00 | 10.00 / 1.00 / 12.50 / 45.00 above 272K      |
| `OpenAI/gpt-5.6-terra`                                 |  2.50 |         0.25 |       3.125 |  15.00 | 5.00 / 0.50 / 6.25 / 22.50 above 272K        |
| `OpenAI/gpt-5.6-luna`                                  |  1.00 |         0.10 |        1.25 |   6.00 | 2.00 / 0.20 / 2.50 / 9.00 above 272K         |
| `OpenAI/gpt-5.5`                                       |  5.00 |         0.50 |        5.00 |  30.00 | 10.00 / 1.00 / 10.00 / 45.00 above 272K      |
| `OpenAI/gpt-5.4`                                       |  2.50 |         0.25 |        2.50 |  15.00 | 5.00 / 0.50 / 5.00 / 22.50 above 272K        |
| `OpenAI/gpt-4.1`                                       |  2.00 |         0.50 |        2.00 |   8.00 | —                                            |
| `Gemini/gemini-3.1-pro` and `gemini-3.1-pro-preview`   |  2.00 |         0.20 |        2.00 |  12.00 | 4.00 / 0.40 / 4.00 / 18.00 above 200K        |
| `Gemini/gemini-3.1-flash` and `gemini-3-flash-preview` |  0.50 |         0.05 |        0.50 |   3.00 | —                                            |
| `Gemini/gemini-2.5-pro`                                |  1.25 |        0.125 |        1.25 |  10.00 | 2.50 / 0.25 / 2.50 / 15.00 above 200K        |
| `Gemini/gemini-2.5-flash`                              |  0.30 |         0.03 |        0.30 |   2.50 | —                                            |
| `DeepSeek/deepseek-v4-pro`                             | 0.435 |     0.003625 |       0.435 |   0.87 | —                                            |
| `DeepSeek/deepseek-v4-flash`                           |  0.14 |       0.0028 |        0.14 |   0.28 | —                                            |
| `MoonShot/kimi-k3`                                     |  3.00 |         0.30 |        3.00 |  15.00 | —                                            |
| `MoonShot/kimi-k2.7-code`                              |  0.95 |         0.19 |        0.95 |   4.00 | —                                            |
| `MoonShot/kimi-k2.6`                                   |  0.95 |         0.16 |        0.95 |   4.00 | —                                            |
| `MoonShot/moonshot-v1-8k`                              |  0.20 |         0.20 |        0.20 |   2.00 | —                                            |

### Pricing override syntax

Add one repeatable `aiPricing` entry per exact provider/model route to the ReviewAI plugin configuration. Each entry is
a comma-separated route followed by `name=value` fields:

```ini
[plugin "reviewai-gerrit-plugin"]
    aiPricing = <Provider>/<model>,input=<USD-per-million>,output=<USD-per-million>[,cachedInput=<price>][,cacheWrite=<price>][,longThreshold=<tokens>,longInput=<price>,longCachedInput=<price>,longCacheWrite=<price>,longOutput=<price>]
```

Fields:

| Field             | Required | Meaning                                                                                |
|-------------------|----------|----------------------------------------------------------------------------------------|
| `input`           | Yes      | Regular input price in USD per one million tokens.                                     |
| `output`          | Yes      | Output price in USD per one million tokens.                                            |
| `cachedInput`     | No       | Cached-input price; defaults to `input`.                                               |
| `cacheWrite`      | No       | Cache-write price; defaults to `input`.                                                |
| `longThreshold`   | No       | Input-token boundary above which long-context rates apply; must be a positive integer. |
| `longInput`       | No       | Long-context input price; defaults to `input`.                                         |
| `longCachedInput` | No       | Long-context cached-input price; defaults to `cachedInput`.                            |
| `longCacheWrite`  | No       | Long-context cache-write price; defaults to `longInput`.                               |
| `longOutput`      | No       | Long-context output price; defaults to `output`.                                       |

The long-context fields have no effect unless `longThreshold` is present. Prices must be zero or positive. An invalid
entry is ignored and logged; it does not replace a valid built-in price.

`aiPricing` may be configured globally in `gerrit.config` or per project in `project.config`. Entries from both scopes
are combined; when both define the same exact provider/model route, the project entry is applied last and takes
precedence.

Override a built-in model:

```ini
[plugin "reviewai-gerrit-plugin"]
    aiPricing = OpenAI/gpt-5.4,input=2.50,cachedInput=0.25,output=15.00,longThreshold=272000,longInput=5.00,longCachedInput=0.50,longOutput=22.50
```

Add pricing for an explicitly configured snapshot:

```ini
[plugin "reviewai-gerrit-plugin"]
    aiModels = OpenAI/gpt-5.4-2026-06-15
    aiPricing = OpenAI/gpt-5.4-2026-06-15,input=2.50,cachedInput=0.25,output=15.00,longThreshold=272000,longInput=5.00,longCachedInput=0.50,longOutput=22.50
```

Define GPT-5.6 cache-write rates:

```ini
[plugin "reviewai-gerrit-plugin"]
    aiPricing = OpenAI/gpt-5.6-sol,input=5,cachedInput=.5,cacheWrite=6.25,output=30,longThreshold=272000,longInput=10,longCachedInput=1,longCacheWrite=12.5,longOutput=45
```

Multiple entries can be repeated in the same configuration section:

```ini
[plugin "reviewai-gerrit-plugin"]
    aiPricing = OpenAI/custom-model,input=1,cachedInput=.1,output=5
    aiPricing = Gemini/custom-model,input=.5,cachedInput=.05,output=3
```

## Prometheus Names

Gerrit's Prometheus reporter converts Gerrit metric names into Prometheus names. For plugin metrics, the exported names
are prefixed with the plugin name and sanitized for Prometheus:

```text
plugins_reviewai_gerrit_plugin_reviewai_...
```

The reporter flattens Gerrit metric fields into the metric name instead of exposing them as Prometheus labels. For
example, `patchset-created` can appear as `patchset_created` in the exported name. Exact names can vary by Gerrit and
reporter version, so verify them directly from the endpoint:

```bash
curl -sS \
  -H 'Authorization: Bearer <prometheus-bearer-token>' \
  http://localhost:9575/plugins/metrics-reporter-prometheus/metrics \
  | grep -i 'plugins_reviewai_gerrit_plugin_reviewai'
```

Typical exported names include:

| Prometheus metric                                                                              | Meaning                                                                     |
|------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------|
| `plugins_reviewai_gerrit_plugin_reviewai_review_run_count_total_total`                         | Aggregate ReviewAI review-run counter.                                      |
| `plugins_reviewai_gerrit_plugin_reviewai_review_run_count_<event_type>_<status>_total`         | ReviewAI review-run counter for one event type and status.                  |
| `plugins_reviewai_gerrit_plugin_reviewai_review_run_latency_total`                             | Aggregate ReviewAI review-run latency summary.                              |
| `plugins_reviewai_gerrit_plugin_reviewai_review_run_latency_total_count`                       | Aggregate ReviewAI review-run latency sample count.                         |
| `plugins_reviewai_gerrit_plugin_reviewai_review_run_latency_<event_type>_<status>`             | ReviewAI review-run latency summary for one event type and status.          |
| `plugins_reviewai_gerrit_plugin_reviewai_review_run_latency_<event_type>_<status>_count`       | ReviewAI review-run latency sample count for one event type and status.     |
| `plugins_reviewai_gerrit_plugin_reviewai_ai_request_count_total_total`                         | Aggregate AI backend request counter.                                       |
| `plugins_reviewai_gerrit_plugin_reviewai_ai_request_count_<provider>_<stage>_<status>_total`   | AI backend request counter for one provider, stage, and status.             |
| `plugins_reviewai_gerrit_plugin_reviewai_ai_request_latency_total`                             | Aggregate AI backend request latency summary.                               |
| `plugins_reviewai_gerrit_plugin_reviewai_ai_request_latency_total_count`                       | Aggregate AI backend request latency sample count.                          |
| `plugins_reviewai_gerrit_plugin_reviewai_ai_request_latency_<provider>_<model>_<stage>`        | AI backend request latency summary for one provider, model, and stage.      |
| `plugins_reviewai_gerrit_plugin_reviewai_ai_request_latency_<provider>_<model>_<stage>_count`  | AI backend request latency sample count for one provider, model, and stage. |
| `plugins_reviewai_gerrit_plugin_reviewai_ai_request_estimated_cost_nanousd_total`              | Aggregate estimated-cost counter in nanoUSD.                                |
| `plugins_reviewai_gerrit_plugin_reviewai_ai_request_estimated_cost_nanousd_<provider>_<model>` | Estimated-cost counter for one provider/model route.                        |
| `plugins_reviewai_gerrit_plugin_reviewai_ai_request_pricing_missing_total_total`               | Aggregate missing-pricing counter.                                          |
| `plugins_reviewai_gerrit_plugin_reviewai_ai_request_pricing_missing_<provider>_<model>_total`  | Missing-pricing counter for one provider/model route.                       |

For the cumulative estimated-cost metric, Gerrit names the aggregate bucket with a `_total` suffix. The individual
provider/model buckets do not have that suffix; the Prometheus reporter only converts the `/` separators in Gerrit's
bucket names to `_` characters.

Timer metrics are exported as summaries with quantile samples such as `0.5`, `0.75`, `0.95`, `0.98`, `0.99`, and
`0.999`, plus a `_count` series. They are not exported as histograms and do not provide a `_sum` series, so Prometheus
cannot calculate a true time-window average latency from `_sum / _count`.

The metric named like
`plugin_latency_reviewai_gerrit_plugin_com_google_gerrit_extensions_webui_JavaScriptPlugin_` is a Gerrit framework
metric for plugin UI latency. It is not emitted by ReviewAI telemetry.

## Useful PromQL

- List all ReviewAI metrics:

```promql
{__name__=~"plugins_reviewai_gerrit_plugin_reviewai_.*"}
```

- Check whether Prometheus is scraping Gerrit:

```promql
up{job="gerrit"}
```

- Show how many samples were scraped from Gerrit:

```promql
scrape_samples_scraped{job="gerrit"}
```

- ReviewAI review runs in the last 24 hours:

```promql
increase(plugins_reviewai_gerrit_plugin_reviewai_review_run_count_total_total[24h])
```

- AI backend requests in the last 24 hours:

```promql
increase(plugins_reviewai_gerrit_plugin_reviewai_ai_request_count_total_total[24h])
```

- ReviewAI review-run rate:

```promql
rate(plugins_reviewai_gerrit_plugin_reviewai_review_run_count_total_total[5m])
```

- AI backend request rate:

```promql
rate(plugins_reviewai_gerrit_plugin_reviewai_ai_request_count_total_total[5m])
```

- Estimated-cost counter in USD:

```promql
plugins_reviewai_gerrit_plugin_reviewai_ai_request_estimated_cost_nanousd_total / 1e9
```

- Estimated spending rate in USD per hour, calculated over a five-minute window:

```promql
rate(plugins_reviewai_gerrit_plugin_reviewai_ai_request_estimated_cost_nanousd_total[5m]) * 3600 / 1e9
```

- List per-provider/model cost counters converted to USD:

```promql
label_replace(
  {__name__=~"plugins_reviewai_gerrit_plugin_reviewai_ai_request_estimated_cost_nanousd_.+_.+"},
  "provider_model",
  "$1",
  "__name__",
  "plugins_reviewai_gerrit_plugin_reviewai_ai_request_estimated_cost_nanousd_(.+)"
) / 1e9
```

- Typical exact query for the configured `OpenAI/gpt-5.4` route:

```promql
plugins_reviewai_gerrit_plugin_reviewai_ai_request_estimated_cost_nanousd_OpenAI_gpt_5_4 / 1e9
```

- Failed ReviewAI review runs in the last 24 hours:

```promql
sum(increase({__name__=~"plugins_reviewai_gerrit_plugin_reviewai_review_run_count_.*_error_total"}[24h]))
```

- Failed AI backend requests in the last 24 hours:

```promql
sum(increase({__name__=~"plugins_reviewai_gerrit_plugin_reviewai_ai_request_count_.*_error_total"}[24h]))
```

- Aggregate ReviewAI review-run p95 latency:

```promql
plugins_reviewai_gerrit_plugin_reviewai_review_run_latency_total{quantile="0.95"}
```

- Aggregate AI backend request p95 latency:

```promql
plugins_reviewai_gerrit_plugin_reviewai_ai_request_latency_total{quantile="0.95"}
```

- Per-event ReviewAI review-run p95 latency:

```promql
{__name__=~"plugins_reviewai_gerrit_plugin_reviewai_review_run_latency_.*", quantile="0.95"}
```

- Per-provider/model/stage AI backend p95 latency:

```promql
{__name__=~"plugins_reviewai_gerrit_plugin_reviewai_ai_request_latency_.*", quantile="0.95"}
```

- Per-specialized-agent AI backend p95 latency:

```promql
{__name__=~"plugins_reviewai_gerrit_plugin_reviewai_ai_request_latency_.*_REVIEW_SPECIALIZED_AGENT_.+", quantile="0.95"}
```

- OpenAI `gpt-5.4` correctness-agent p95 latency:

```promql
plugins_reviewai_gerrit_plugin_reviewai_ai_request_latency_OPENAI_gpt_5_4_REVIEW_SPECIALIZED_AGENT_CORRECTNESS{quantile="0.95"}
```

- OpenAI `gpt-5.4` specialized consolidation p95 latency:

```promql
plugins_reviewai_gerrit_plugin_reviewai_ai_request_latency_OPENAI_gpt_5_4_REVIEW_SPECIALIZED_CONSOLIDATION{quantile="0.95"}
```

- OpenAI `gpt-5.4` specialized consolidation sample count:

```promql
plugins_reviewai_gerrit_plugin_reviewai_ai_request_latency_OPENAI_gpt_5_4_REVIEW_SPECIALIZED_CONSOLIDATION_count
```

- Detect missing recent ReviewAI activity:

```promql
increase(plugins_reviewai_gerrit_plugin_reviewai_review_run_count_total_total[1h]) == 0
```

## Restart Behavior

Gerrit metrics are in-memory. If Gerrit restarts, current counters and timer summaries reset to zero. Prometheus keeps
the samples it already scraped before the Gerrit restart.

Prometheus keeps scraped samples across a Prometheus restart when its TSDB directory is persistent. With Docker, use a
volume such as `prometheus-data:/prometheus`. With a native service, keep `--storage.tsdb.path` on persistent storage
and configure retention with `--storage.tsdb.retention.time`.

If Gerrit processes a ReviewAI event while Prometheus is stopped, Prometheus will not have a sample for that moment. It
will only see the current Gerrit metric values at the next successful scrape.

## Troubleshooting Empty Results

If ReviewAI metrics are present but all values are zero:

- Confirm the Prometheus reporter endpoint returns `plugins_reviewai_gerrit_plugin_reviewai_...` metrics when called
  with the bearer token.
- Trigger a real ReviewAI review event after Gerrit has loaded the plugin.
- Check Gerrit logs for `EventHandlerTask execution completed with result: OK`.
- Check that the event was supported. Unsupported events and preprocessing failures do not update review-run metrics.
- Query Prometheus with a window that includes a successful scrape after the ReviewAI event.

If request metrics increase but estimated cost remains zero or absent:

- Check `pricing_missing` for the configured provider/model route. Add an exact `aiPricing` entry when necessary.
- Confirm the provider returns both input- and output-token usage. A known-priced response with incomplete usage cannot
  be costed.
- Remember that Ollama and mock-model routes intentionally emit no cost telemetry.
- Inspect the reporter endpoint for the exact sanitized metric name before copying a PromQL example.
