# ReviewAI Telemetry

This document describes the telemetry service available in the ReviewAI plugin. It covers ReviewAI review-run and AI
backend request telemetry.

ReviewAI registers Gerrit metrics with `MetricMaker`. When Gerrit's `metrics-reporter-prometheus` plugin is installed,
those metrics are exported at:

```text
/plugins/metrics-reporter-prometheus/metrics
```

Prometheus can scrape that endpoint periodically and store historical samples in its own time-series database.

## Installation

### Install the Gerrit Prometheus reporter

Install a `metrics-reporter-prometheus` plugin build that matches your Gerrit version and copy it into the Gerrit site
plugin directory:

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

| Gerrit metric | Type | Dimensions | Description |
| --- | --- | --- | --- |
| `reviewai/review_run/count` | Counter | `event_type`, `status` | Number of ReviewAI event processing attempts. |
| `reviewai/review_run/latency` | Timer | `event_type`, `status` | End-to-end ReviewAI event processing latency. |
| `reviewai/ai_request/count` | Counter | `provider`, `stage`, `status` | Number of AI backend requests made by ReviewAI. |
| `reviewai/ai_request/latency` | Timer | `provider`, `model`, `stage` | AI backend request latency. |

### Dimension Values

`reviewai/review_run/*` uses these dimensions:

- `event_type`: Gerrit event type, such as `patchset_created`, or `comment_added` if Gerrit does not provide it.
- `status`: `completed` when event processing succeeds, or `error` when processing throws an exception.

Review-run telemetry starts after event preprocessing succeeds. Unsupported events and events rejected during
preprocessing return `NOT_SUPPORTED` and do not increment these metrics.

`reviewai/ai_request/*` uses these dimensions:

- `provider`: AI provider name, such as `OPENAI`, `GEMINI`, `DEEPSEEK`, `MOONSHOT`, or `OLLAMA`.
- `model`: configured model name used for the request.
- `stage`: ReviewAI assistant stage, or `unknown` if no stage is available.
- `status`: `completed` for a non-null AI response, `empty` for a null AI response, or `error` when the request fails.

The no-value fallback for every dimension is `unknown`.

Current stage values are:

- `REVIEW_CODE`
- `REVIEW_COMMIT_MESSAGE`
- `REVIEW_REITERATED`
- `REVIEW_SPECIALIZED_TRIAGE`
- `REVIEW_SPECIALIZED_AGENT`
- `REVIEW_SPECIALIZED_CONSOLIDATION`
- `REVIEW_SPECIALIZED_HISTORICAL_REPETITION`
- `REVIEW_SPECIALIZED_CONFLICT_RESOLUTION`
- `REVIEW_SPECIALIZED_VERIFICATION`

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

| Prometheus metric | Meaning |
| --- | --- |
| `plugins_reviewai_gerrit_plugin_reviewai_review_run_count_total_total` | Aggregate ReviewAI review-run counter. |
| `plugins_reviewai_gerrit_plugin_reviewai_review_run_count_<event_type>_<status>_total` | ReviewAI review-run counter for one event type and status. |
| `plugins_reviewai_gerrit_plugin_reviewai_review_run_latency_total` | Aggregate ReviewAI review-run latency summary. |
| `plugins_reviewai_gerrit_plugin_reviewai_review_run_latency_total_count` | Aggregate ReviewAI review-run latency sample count. |
| `plugins_reviewai_gerrit_plugin_reviewai_review_run_latency_<event_type>_<status>` | ReviewAI review-run latency summary for one event type and status. |
| `plugins_reviewai_gerrit_plugin_reviewai_review_run_latency_<event_type>_<status>_count` | ReviewAI review-run latency sample count for one event type and status. |
| `plugins_reviewai_gerrit_plugin_reviewai_ai_request_count_total_total` | Aggregate AI backend request counter. |
| `plugins_reviewai_gerrit_plugin_reviewai_ai_request_count_<provider>_<stage>_<status>_total` | AI backend request counter for one provider, stage, and status. |
| `plugins_reviewai_gerrit_plugin_reviewai_ai_request_latency_total` | Aggregate AI backend request latency summary. |
| `plugins_reviewai_gerrit_plugin_reviewai_ai_request_latency_total_count` | Aggregate AI backend request latency sample count. |
| `plugins_reviewai_gerrit_plugin_reviewai_ai_request_latency_<provider>_<model>_<stage>` | AI backend request latency summary for one provider, model, and stage. |
| `plugins_reviewai_gerrit_plugin_reviewai_ai_request_latency_<provider>_<model>_<stage>_count` | AI backend request latency sample count for one provider, model, and stage. |

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
