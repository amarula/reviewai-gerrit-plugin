# Proposal: Split the ReviewAI engine from the host plugins

- **Status:** Proposal (under review)
- **Date:** 2026-08-29, revised 2026-09-19
- **Goal:** maintainability and reuse — a product-neutral review engine that scales independently

> **Revision note (2026-09-19).** This document supersedes the 2026-08-29 proposal. That proposal
> already called for a product-neutral engine reusable by Gerrit, GitHub, GitLab, or an IDE, with an
> incremental path from an in-process interface to a service. It proposed a **stateless** engine, with
> the Gerrit adapter owning all review state, and pre-fetched code context with no I/O mid-review. It
> left transport, asynchronous execution, persistence ownership, deployment topology, and configuration
> ownership open.
>
> This revision retains product neutrality, reuse, and incremental delivery, while resolving those open
> choices differently: the remote engine is a horizontally scalable Spring Boot service, owns review
> state, and obtains on-demand code context through adapter callbacks. References below to the "prior
> proposal" mean this baseline; no earlier document is required to understand the current design.
>
> A further decision shapes the packaging: the engine service is developed as a **separate,
> proprietary repository** (`reviewai-backend`), while this repository stays Apache-2.0 and **keeps its
> in-process engine**. The split is therefore **optional** — a site may run the engine in-process as it
> does today, or point the adapter at a remote engine. Nothing is shared as a Java artifact: the two
> sides agree on the wire format, which this repository publishes as a JSON Schema with conformance
> vectors. §14.1 and §14.2 record that.

## 1. Context and motivation

Today the plugin is a single Gerrit process. The AI review engine (LangChain4j integration, prompt
building, concern workflow, agents) is intertwined with the Gerrit-specific integration (event
listeners, Gerrit API clients, web endpoints, plugin data storage).

Three forces make this painful:

1. **Every change to the AI logic is coupled to Gerrit.** The engine's entry point takes Gerrit types
   (`GerritChange`, `ChangeSetData`), so the engine cannot be built, tested, or reasoned about without
   the Gerrit environment.
2. **The engine could serve more than Gerrit.** The review capability is product-agnostic ("code
   context + prior review state" in, "comments / concerns / score" out). GitHub, GitLab, or an IDE
   could reuse it, but only if the engine is extracted behind a neutral contract.
3. **Review work must scale independently of the host.** A review is long-running and makes many model
   calls. Today it competes for the same process and thread pools as Gerrit's request handling.

This document proposes extracting the engine into a standalone Spring Boot service and turning each
host plugin into an adapter. The primary goal remains maintainability; independent scaling and reuse
follow from the same boundary.

## 2. Goals and non-goals

### Goals

- Decouple the AI engine from Gerrit types and Gerrit lifecycle.
- Define a stable, neutral contract (`ReviewRequest` / `ReviewResult`) that names no host product.
- Run the engine as a horizontal-scaling service: N replicas behind a load balancer over one shared
  database, with any replica able to accept or serve any request.
- Allow the engine to be reused by other products via new adapters.
- **Keep this repository self-contained.** The plugin must remain buildable, releasable, and fully
  functional from public sources alone, with the in-process engine as a supported mode. The remote
  engine is additive, never a build requirement of the open plugin (§14.1).
- Land the change incrementally, without a big-bang rewrite.

### Non-goals

- No change to the browser sidebar UX. The sidebar's polling contract is preserved (see §12.4).
- No change to the *concern workflow* semantics (concerns, voting, feedback memory).
  **Deliberate exception:** host comment *rendering* moves to the adapter (§6.3). This changes how the
  final comment text is composed and is expected to carry a comment-quality risk until measured.
- No multi-tenancy. Per-install deployment (§4.1) makes it unnecessary.

> **Changed from the prior proposal.** It listed "Not (yet) a multi-tenant or high-throughput
> service design" as a non-goal. Scale-out is now a requirement, not a non-goal. Multi-tenancy remains
> out of scope, but now for a stated reason rather than as a deferral: each install gets its own
> engine deployment.
>
> The prior proposal also listed "No re-platforming of persistence or config in the first iteration".
> That no longer applies: persistence moves to the engine's own database (§13), and configuration is
> split between
> adapter-resolved and engine-owned (§5.4).

## 3. Current architecture and coupling points

Logical layers that exist today:

| Layer | Package / location | Role |
|---|---|---|
| Browser sidebar | `static/reviewai/*` | UI, talks to the plugin's REST endpoints |
| Gerrit integration | `listener/`, `web/` | Gerrit events, REST endpoints, command parsing |
| Orchestration | `review/` | `PatchSetReviewer`, `TopicPatchSetReviewer`, lifecycle |
| AI engine | `aibackend/langchain/` | `LangChainClient`, providers, prompt factory, concern workflow, agents |
| Shared models | `aibackend/common/` | `AiResponseContent`, `ChangeSetData`, concern models |
| Persistence | `data/` | `ReviewAiDb`, concern/feedback/status stores |
| Config | `config/` | Gerrit-sourced `Configuration` |
| Metrics | `metrics/` | request/cost tracking |

The engine is coupled to Gerrit in these concrete ways:

1. **Gerrit types in the engine entry point.**
   `IAiClient.ask(ChangeSetData, GerritChange, String patchSet)`. `LangChainClient`,
   `AiPromptFactory`, and the concern workflow all take `GerritChange` / `GerritClient`.
2. **`ChangeSetData` is a grab-bag.** It mixes engine state
   (`incrementalPatchSet`, `concernWorkflowInput`, `reviewFeedbackMemory`,
   `previousReviewConcernLedger`) with Gerrit command/UI state (`forcedReview`,
   `parsedCommands`, `suggestMode`, `reviewScope`, `hideAiReview`,
   `reviewSystemMessage`, …).
3. **On-demand code-context tools reach into the git repo.**
   `treeTool` / `getContentTool` / `grepTool` read files via
   `GitRepoFiles` / `repositoryManager`, which only exist inside Gerrit.
4. **Persistence is Gerrit-tied.** The concern ledger, feedback memory, and OpenAI conversation id live
   in Gerrit plugin data (`ReviewAiDb` / `PluginDataHandler`).
5. **Config is Gerrit-sourced.** `Configuration` reads `gerrit.config` and project-level overrides.
6. **Listener and web layers are inherently Gerrit.** `GerritListener`, the `EventHandlerType*`
   classes, and `ChangeResource` / `ChangeApi` cannot be shared with another product.

The prior proposal correctly identified `previousCommentId` as a Gerrit leak, but treated renaming it
as sufficient. It did not account for the following additional couplings and consequences:

7. **Cancellation is in-process.** `AiRequestCancellation` carries request-scoped cancellation through
   a `ThreadLocal`, and the durable `SUPERSEDE_REQUESTED` state is polled by the *same* worker that
   holds the lease. Correctness depends on worker and lease owner being one process.
8. **The identified field is persisted, so changing it requires data migration.** `previousCommentId` is
   serialized inside `concern_json` under the Gson names `past_comment_id` / `previous_comment_id`
   (`aibackend/common/model/review/ReviewConcern.java:55-56`). This is **not** cosmetic — see §5.1.
9. **Feedback comment state stores Gerrit comment ids.**
   `review_feedback_comments.comment_id` (`data/ReviewAiDb.java:263-271`) is a Gerrit comment id in a
   table the engine would otherwise own.
10. **Prompts read Gerrit types.** `ChangeSetData.permittedVotingRange` (`GerritPermittedVotingRange`)
    and `conditionLabels` (`GerritConditionLabel`) are consumed by prompt construction.

## 4. Target architecture

### 4.1 Topology

There are **two supported modes**, and the adapter chooses between them by configuration. This is what
keeps the open plugin self-contained while allowing a scaled deployment.

| Mode | Engine | Review state lives in | Available to |
|---|---|---|---|
| **In-process** (default) | `aibackend/`, in the host JVM — today's behaviour | The plugin's own database | Anyone building this repository |
| **Remote** | The Spring Boot service, N replicas over one PostgreSQL | The engine's database (§9) | Deployments that want independent scaling |

The diagram below shows **remote mode**. In-process mode is the current architecture with the
`ReviewEngine` contract introduced as an internal seam (migration Steps 1–3, §15).

```
┌──────────────── host plugin = adapter (one per product install) ───────────────┐
│  Gerrit / GitHub / GitLab                                                      │
│                                                                               │
│   UI ──► host-facing REST ──► intake queue (durable) ──► job client            │
│             (host-specific)     (existing design)         │                    │
│                                                           │                    │
│   tool server  ◄──────────────────────────────────────────┼──────┐            │
│   (per-host impl of tree / read / grep)                    │      │            │
└────────────────────────────────────────────────────────────┼──────┼────────────┘
                                                             │      │
                                    ReviewRequest (neutral)  │      │  tool-RPC
                                                             ▼      │  (neutral)
┌──────────────── review engine (Spring Boot, N replicas) ──────────┼────────────┐
│                                                                   │            │
│   POST /v1/reviews ──► job store (lanes, leases) ──► worker ──────┘            │
│                            │                          │                        │
│                            ▼                          ▼                        │
│                    review_job_events          prompt / concern workflow / LLM  │
│                    (progress log)                      │                        │
│                            │                          ▼                        │
│                            └───────────────►    ReviewResult                   │
└────────────────────────────────────┬───────────────────────────────────────────┘
                                     │  pull (GET /v1/reviews/{id})
                                     ▼
┌──────────────── host plugin = adapter ─────────────────────────────────────────┐
│  render comments in host syntax, post, map concernId → thread id, vote          │
└────────────────────────────────────────────────────────────────────────────────┘
```

Key properties:

- **One engine deployment per host install** (a Gerrit site, a GitHub org, a GitLab group), running the
  same engine code and contract. Isolation comes from the deployment.
- **N engine replicas over one shared PostgreSQL.** Any replica can accept a submission, run a job, or
  report a result.
- **The adapter keeps its durable intake queue.** It absorbs host events before the network call, so an
  engine outage does not lose work. There are therefore **two durable queues in series** (§8).

> **Changed from the prior proposal.** It stated: *"The engine is stateless. The adapter owns the
> concern ledger, feedback memory, and conversation id, and passes them per request."* This is now
> **reversed** — the engine owns them (§9). The reason is not preference: two writers to one ledger
> reintroduce exactly the split-brain the concern ledger exists to prevent, and a stale request could
> overwrite newer engine state.

### 4.2 The three REST surfaces

"The API is the same for Gerrit, GitHub and GitLab" is true of exactly two of three surfaces. Being
precise about which is which is what keeps the engine host-agnostic.

| Surface | Shared? |
|---|---|
| **Engine API** — `POST /v1/reviews`, `GET /v1/reviews/{id}`, `POST /v1/reviews/{id}/cancel`, SSE | **Identical.** One contract, one engine, three adapters. |
| **Tool-RPC protocol** — engine → adapter, for code context | **Identical interface, per-host implementation.** Gerrit runs JGit against the local repository; GitHub uses the contents/search/trees API or a clone; GitLab likewise. |
| **Host-facing REST** — what each product's UI calls | **Not shared and necessarily different.** Gerrit's endpoints are `ChangeResource`-scoped; GitHub's are webhooks plus GitHub App auth; GitLab's are its own. Entirely adapter-internal. |

The engine never learns which host it is serving. That is the test of whether the neutrality is real.

## 5. The contract

The contract is the **wire format**, not a shared Java artifact: two independently-written types, one
per side, agreeing on JSON (§14.1). It names no host product, and — for a non-obvious reason — the
types on both sides carry no Jackson or Gson annotations (§14.5).

### 5.1 What the contract must carry, and why `ChangeRef` is not what you'd guess

**`ChangeRef` must not be Gerrit-shaped.** A natural first draft carries `project`, `branch`,
`changeKey`, `patchSetNumber`, `fullChangeId`, `topic` — every one of which is Gerrit vocabulary. None
exist on GitHub, where a change is `repo + PR number + head SHA`.

The neutral shape is a small core plus an **opaque locator**:

```java
public record ChangeRef(
    String hostId,      // opaque host install identity, e.g. a Gerrit instance id or a GitHub App installation
    String changeId,    // opaque change identity — a Gerrit change number, a PR number, an MR iid
    String revision,    // opaque revision identity — the commit the review targets
    String locator      // opaque to the engine; echoed back verbatim on tool-RPC
) {}
```

The engine uses `hostId` + `changeId` as its **lane key** (the unit of per-change serialization) and
`revision` for freshness checks and `lastReviewedCommit`. It never interprets `locator`; the adapter
puts whatever it needs to resolve the change back into a host object, and gets it back on every
tool-RPC call. This is what lets Gerrit carry `project~branch~Change-Id`, GitHub carry
`owner/repo#number`, and GitLab carry `group/project!iid` through one field.

**Historical concern correlation is a data migration, not a field rename.** The current implementation
uses `ReviewConcern.previousCommentId` as a historical correlation reference. Despite its name, the
value has two meanings:

- a host comment id when the concern was matched against `past_comments`;
- a prior concern id when the match came from conversation history.

The prior proposal mapped `ReviewConcern.previousCommentId` to a neutral `threadId` and treated that as
the only required change. That is incomplete because:

- it is persisted inside `concern_json` (`ReviewConcern.java:55-56`);
- it is a **required** field in two LLM output schemas
  (`config/formatSpecializedHistoricalRepetitionSchema.json:15,18` and
  `config/formatSpecializedConflictResolutionSchema.json:34,58`);
- it is the join key from review feedback back to concerns
  (`aibackend/langchain/client/api/LangChainReviewFeedbackClassifier.java:145-146`);
- consumers cannot interpret it as a thread id without first knowing which of the two meanings applies
  (`agents/level2/SpecializedReviewRepetitionMerger.java:198`).

Resolution: the historical correlation reference leaves the engine's **persisted** state, but still
flows **through** the engine on each request because the schemas require it and changing them would
change concern-workflow behaviour. Here the "no semantic change" non-goal holds — unlike §6.3, where
it is deliberately relaxed. The adapter supplies `priorPublications` and owns the mapping; the engine
returns `concernId`s.

### 5.2 Request

```java
public record ReviewRequest(
    int schemaVersion,          // major; the engine rejects unknown majors
    String adapterInstanceId,   // idempotency key part 1
    String requestId,           // the adapter's durable request id — key part 2
    int attempt,                // key part 3; increments on re-dispatch after a terminal non-COMPLETED
    long queueSequence,         // preserves the adapter's lane FIFO order across the boundary
    ChangeRef change,
    ReviewTarget target,
    ReviewIntent intent,
    ResolvedConfig config,
    StateBootstrap bootstrap,   // nullable; migration only (§13)
    Instant requestedAt,
    String traceId) {}

public record ReviewTarget(
    String patch,                    // the diff under review
    List<String> changedFiles,       // scopes on-demand code context
    String commitMessage,            // nullable; adapter resolves any host pseudo-file into this
    List<String> commitMessageLines) {}

public record StateBootstrap(        // nullable; present only while migrating a change (§13)
    LedgerSnapshot priorConcerns,
    FeedbackSnapshot feedback,
    String incrementalPatch,
    int schemaVersion) {}

public record ReviewIntent(
    ReviewAssistantStage stage,
    ReviewScope scope,
    boolean forced,
    boolean forcedStagedReview,
    boolean suggestMode,
    boolean debugReview,
    boolean replyFilterEnabled,
    boolean commentEvent,
    AgentSpecialization agent,          // SINGLE_AGENT | SCOPED_AGENTS | SPECIALIZED_AGENTS
    SpecializedAgent specializedAgent,  // nullable
    String conversationSuffix,          // nullable; feeds the memory scope
    ScoreRange permittedScoreRange,     // NEUTRAL form of the host's voting model (§6.2)
    Map<String, ConditionLabel> conditionLabels,
    List<String> dataPrompt) {}
```

**`priorConcerns` and `feedback` are gone from the steady-state request.** The engine is the single
writer and loads them from its own store. The prior proposal carried them on every request; doing so
would let stale requests compete with newer engine state. The one exception is `StateBootstrap`, which is
explicit and nullable rather than implicit — see §13.1 for why "no ledger row means first review" is an
unsafe default.

> **Changed from the prior proposal.** Its `ReviewContext` carried `priorConcerns`, `feedback`, and
> `incrementalPatch` on every request, while `ModelConfig` carried a `conversationId` described as
> *"optional, frontend-owned, for stateful conversations"*. Both are reversed: the engine owns that
> state. `conversationId` leaves the contract entirely and moves to an engine table keyed by change and
> scope.

### 5.3 Response

```java
public record ReviewResult(
    int schemaVersion,
    String jobId,
    ReviewState state,               // COMPLETED | FAILED | SUPERSEDED | CANCELLED
    List<Finding> findings,
    String message,                  // optional change-level message
    Double score,                    // optional aggregate score
    CostReport cost,
    String failureReason) {}         // nullable

public record Finding(
    String findingKey,   // deterministic identity — see §8; load-bearing, not opaque
    String filename,
    Integer lineNumber,
    String codeSnippet,
    String message,      // prose; contains no host syntax (§6.3)
    String suggestion,   // nullable; structured replacement text, rendered by the adapter
    String concernId,    // the adapter maps this to a host thread
    Double score, Double relevance,
    boolean repeated, boolean duplicated, boolean conflicting,
    String repeatedReason, String duplicatedReason, String conflictingReason) {}
```

`ReviewResult.updatedConcerns` is **removed** — the engine already persisted them. `Finding.id` becomes
`findingKey` and is now a deterministic content hash rather than an opaque id; §8 explains why that is
required rather than merely convenient.

### 5.4 Config: resolved by the adapter, shipped flat

The adapter fully resolves configuration and sends effective values. The engine merges nothing.

This is not a stylistic choice. `ConfigCore`'s override semantics are subtle enough that porting them
would mean faithfully reimplementing a tricky function in a second process with no engine-side test
coverage:

| Key type | Actual semantics | Location |
|---|---|---|
| `getString` | project-first, else global | `config/ConfigCore.java:82-88` |
| `getInt` | project wins only if non-default, non-zero, *and* present as a string | `config/ConfigCore.java:164-175` |
| `getBoolean` | project wins iff the key exists as a string | `config/ConfigCore.java:177-183` |
| list keys | **additive concatenation**, global then project | `config/ConfigCore.java:211-214, 224-254` |
| `…WithProjectOverride` lists | project **replaces** global | `config/ConfigCore.java:216-222` |
| provider/model/token routes | project-override lists, additive token merge | `config/AiProviderConfiguration.java:107-109, 169-181` |
| dynamic per-change | merged from plugin data *before* `Configuration` is constructed | `config/ConfigCreator.java:73-79, 97-115` |

```java
public record ResolvedConfig(
    int schemaVersion,
    String provider, String model, String domain,
    double temperature,
    String instructions,             // resolved project instructions, including host-specific fragments
    int maxToolResponseRounds, int maxMemoryTokens,
    CodeContextPolicy codeContextPolicy,
    List<String> enabledFileExtensions, List<String> disabledFileExtensions,
    List<PricingEntry> pricing,      // resolved effective list; engine computes cost
    String credentialRef,            // OPAQUE. Never a token — see §14.6.
    List<AiModelRoute> modelRoutes,
    Map<String, String> hints) {}    // forward-compat escape hatch; unknown keys ignored
```

Two config planes, stated explicitly:

- **Adapter-resolved, per request:** everything host-, project-, and change-derived. Adding a config
  key requires no engine change.
- **Engine-owned, deployment-scoped:** database URL, thread pools, job TTL, tool-RPC endpoint and
  secret, global model gate, retention.

Trade-off to accept: the engine cannot validate adapter config keys, so a typo surfaces as behaviour
rather than a startup error. The adapter already has `unknownEnumSettings`
(`config/ConfigCore.java:57, 189-197`) as the place to warn.

## 6. Product neutrality

### 6.1 The three gaps

Neutral names on the wire are not enough. Three things make the sameness real, and the first was a
defect in the design review's own proposal:

1. **The state key** — `ChangeRef` must be a neutral core plus opaque locator (§5.1), not Gerrit's
   project/branch/patch-set vocabulary.
2. **Score semantics** — see §6.2.
3. **Comment syntax** — see §6.3.

### 6.2 Score semantics

Prompts currently consume Gerrit's `permittedVotingRange` (`ChangeSetData.java:54`), and Gerrit's
scoring model — a numeric label range — does not exist on GitHub, which has review *states*
(approve / request-changes), nor on GitLab, which has approvals.

The neutral form is a permitted score range as a plain number pair (`ScoreRange`), which each adapter
maps onto its own primitive. Whether a host can express the score at all becomes an adapter concern,
not an engine one.

### 6.3 Comment rendering moves to the adapter

Gerrit's ```suggestion block syntax is a host mechanic, and it is currently taught to the model by the
prompt assets. **Decision: findings stay structured and the adapter renders them.**

- The engine emits `Finding.message` as prose and `Finding.suggestion` as structured replacement text.
- Each adapter renders its host's syntax — Gerrit's ```suggestion blocks, GitHub's suggestion fences,
  GitLab's equivalent.
- The engine never names a host syntax.

Consequences to be honest about:

- **The model stops composing the final comment prose.** This is a deliberate relaxation of the "no
  review-semantics change" non-goal and carries a probable comment-quality risk. It should be measured
  against current output rather than assumed, and revisited only if the regression is real.
- **Prompt neutralisation becomes a required workstream, not a tidy-up.** There are 17 `Gerrit`
  references across 6 files under `src/main/resources/config/`, and they split into two classes:
  - *Essential host mechanics* — Suggested Edits syntax and the `/COMMIT_MSG` pseudo-file. Both leave
    the engine entirely: syntax to the adapter's renderer, `/COMMIT_MSG` to the adapter resolving it
    into the neutral `commitMessage` field.
  - *Vocabulary* — "Gerrit patch", "Gerrit review comment", "Gerrit comments". Cheap to neutralise.
- Product-specific instruction fragments that remain have a home in `ResolvedConfig.instructions`.

## 7. The service

### 7.1 Job API

| Method | Path | Behaviour |
|---|---|---|
| `POST` | `/v1/reviews` | Validate, admit, return `202 {jobId, state: QUEUED}`. **On an existing idempotency key, return `200` with the existing job — never 409.** |
| `GET` | `/v1/reviews/{jobId}` | Current state and, when terminal, the `ReviewResult`. |
| `GET` | `/v1/reviews/{jobId}/events` | SSE progress stream (§7.4). |
| `POST` | `/v1/reviews/{jobId}/cancel` | Cooperative cancellation (§10). |
| `GET` | `/v1/meta` | Supported contract versions, for adapter startup checks. |

`POST` must return `200` rather than `409` on a duplicate key because the adapter cannot distinguish
"my POST timed out" from "my POST never arrived". A conflict response is therefore unusable — it would
force the adapter either to retry blindly or to treat an unknown outcome as failure.

### 7.2 Job state machine

The engine's job store reuses the state machine the adapter already has in `data/AiRequest.java`:
`QUEUED, RUNNING, SUPERSEDE_REQUESTED, COMPLETED, FAILED, REJECTED, SUPERSEDED, ABANDONED`, with
`isTerminal()`.

### 7.3 Replica coordination

The multi-instance machinery largely **already exists and is already correct** in the adapter. The
engine reuses the *design*, not the tables (§14.7):

- `data/AiRequestStore.claimNext` (`data/AiRequestStore.java:75-136`) locks the lane, then flips
  `QUEUED → RUNNING` with owner and lease and sets the lane's active request **in one transaction**,
  asserting both updates affect exactly one row. This is precisely the claim primitive a multi-replica
  job store needs — no new concurrency design is required.
- `listener/AiRequestCoordinator.java` implements per-change serialization, lease renewal at
  `leaseMillis / 3`, owner-checked completion, and periodic recovery of expired leases.
- `docs/architecture/request-coordination.md` states the invariants in prose and they transfer
  unchanged.

The engine must also split its pools: intake, worker, and lease/recovery must not share a bounded pool,
or a long review can block event intake. This applies to the adapter as well (§15, Step 0).

### 7.4 Progress delivery

Events are persisted to `review_job_events` **in the same transaction as the state change that produced
them**. The row is authoritative; the stream is a projection. The SSE handler reads `seq > Last-Event-ID`
on connect and then polls for new rows.

No broker is required. Postgres `LISTEN/NOTIFY` is an optional latency optimisation only, and should be
treated as such: it is fire-and-forget, non-durable, needs a dedicated non-pooled connection, and has a
payload cap.

**The stream's consumer is the adapter, not the browser.** Exposing engine SSE to the browser would
need CORS, leak the engine URL and its auth model, and be broken by any reverse proxy that buffers
responses. The sidebar's existing contract — poll `ai-review-message-status` every 1000 ms for up to
120 s — is preserved unchanged, which is also what the stated non-goal requires. If a live-progress UI
is ever wanted, the adapter proxies the stream.

### 7.5 Graceful shutdown

Reviews must not run on the request thread. `POST` persists and returns; a worker claims. Shutdown is
then: stop claiming, stop renewing leases, and let in-flight jobs either finish within the drain window
or be reclaimed by another replica's recovery pass.

A mid-LLM-call worker cannot be interrupted, so reclaiming a job **re-runs the review from scratch**.
That is only safe because publication is idempotent (§8) — an ordering constraint, not a nicety. On
`SIGTERM` the engine must also abort in-flight tool-RPCs so the adapter's bounded tool executor is not
left holding requests whose caller is gone.

## 8. Idempotency and the at-least-once contract

This is the deepest consequence of the split and was not addressed by the prior proposal.

Today a review runs once; a crash loses the work. After the split, a re-dispatch — adapter restart,
engine replica restart, lease expiry, a retryable model outage — **re-runs the review**. Nothing in the
publication path is idempotent: `PatchSetReviewer` posts comments unconditionally
(`review/PatchSetReviewer.java:182-190`). Patch-set freshness is validated, but a re-run of the *same*
patch set is not filtered.

**Therefore idempotent publication must land before the split**, or graceful shutdown and retry become
duplicate-comment generators.

The mechanism:

- The engine assigns `findingKey = sha256(changeRef, revision, concernId, filename, line, normalizedMessage)`.
- The adapter keeps `published_findings(change_id, revision, finding_key, comment_id, published_at)`
  with a unique index, and skips findings already recorded, reusing the recorded `comment_id` for the
  thread.
- This table also provides decision 6's storage: `concern_comment_ids(change_id, concern_id, comment_id)`
  replaces the `previousCommentId` currently written into the engine's ledger
  (`data/ReviewConcernPublisher.java:70-83`).

### Boundary idempotency key

The adapter's existing key `(gerrit_instance_id, change_number, source_event_id)` is correct *inside*
the adapter but **unsafe across the boundary**:

- `AiRequestDescriptor.resolveSourceEventId` (`listener/AiRequestDispatcher.java:184-193`) **synthesizes**
  the id from `patchSetEventKey:eventType:eventCreatedOn` when the event has none, so two distinct
  requests can collide.
- The adapter deletes its rows on merge/abandon with no tombstone
  (`docs/architecture/request-coordination.md:188`). If the engine retains a job longer, a re-created
  adapter request for the same triple could match a **stale terminal** engine job and be handed an old
  result.

The adapter's own `requestId` — a fresh `UUID.randomUUID()` per admission
(`listener/AiRequestDispatcher.java:137`) — is the true identity of a unit of work. So:

**Engine idempotency key = `(adapterInstanceId, requestId, attempt)`.**

`ChangeRef` remains the engine's **lane** key (it must serialize per change) but is not its uniqueness
key. `attempt` increments only when the adapter re-dispatches after observing a terminal
non-`COMPLETED` job. Engine job TTL must be **≤** the adapter's, or the staleness collision returns.

### A deployment precondition not covered by the prior proposal

A durable adapter queue protects the handoff only when all adapter replicas share it. `ReviewAiDb`
defaults to an embedded H2 file over a TCP server pinned to
`localhost:9092` with `AUTO_SERVER=FALSE` (`data/ReviewAiDb.java:52-54, 126-129`); external PostgreSQL
is opt-in via `storeUrl`. If Gerrit runs more than one replica, there are two independent queues, the
unique index dedupes nothing, and the same event can be admitted twice.

**Shared PostgreSQL for the adapter's queue is a documented precondition of the handoff**, and should
be a startup check: refuse to enable the engine handoff when the dialect is H2.

## 9. Ownership matrix

Ownership is **mode-dependent**, which matters because in-process mode (§4.1) is still supported:

- **In-process mode** — adapter and engine are one process over one database. The matrix below
  collapses: everything is owned where it is owned today, and nothing in this section applies.
- **Remote mode** — the split below applies.

| State | Owner in remote mode | Authoritative | Who may delete |
|---|---|---|---|
| Adapter intake queue (`ai_requests`, `ai_request_lanes`) | Adapter | Adapter | Adapter (current lifecycle rules) |
| Engine jobs, lanes, events | Engine | Engine | Engine only |
| Concern ledger | Engine | Engine | Engine, on adapter request |
| Feedback memory | Engine | Engine | Engine, on adapter request |
| Chat memory, conversation id | Engine | Engine | Engine |
| Concern → thread id map | Adapter | Adapter | Adapter |
| Feedback comment handles | **Undecided** — see §17.1 | | |
| Sidebar conversation history | Adapter | Adapter | Adapter (unchanged) |

Two rules follow:

1. **The adapter must never delete engine state**, and the engine must never delete adapter state.
2. **Cleanup ordering** carries over from the existing design: deletion must run *after* scheduled work
   for the change is idle, so an in-flight model call cannot recreate memory after deletion
   (`docs/architecture/request-coordination.md:180-182`). The engine's equivalent is: delete only when
   the lane is unowned.

A consequence worth stating plainly: because both modes exist, a site that runs in-process pays none of
the migration cost in §13, and a site that switches to remote mode pays it once, per change, on first
contact. The two modes do not share review state — see §13.4.

## 10. Cancellation across the boundary

Cancellation correctness currently rests on the worker and the lease owner being the same process
(`listener/AiRequestCoordinator.java:166-176, 253-308`). After the split, that co-location is gone, and
the naive outcome is bad in a specific way: the adapter would mark the request `ABANDONED` after the
lease and tell the user the review was interrupted, **while the engine kept calling the model and then
posted the review**. Neither process is wrong by its own rules; the rules are no longer co-located.

The fix makes cancellation a durable cross-process protocol:

- The engine's job row carries the cancellation state and reason.
- The engine polls **its own row** at tool-round and agent-stage boundaries. A `ThreadLocal` cannot be
  pushed across a process boundary.
- The adapter must not terminate its request until the engine acknowledges a terminal state.
- **The engine declares the terminal state.** Only it knows when its stages have drained.

Cancellation remains cooperative — it prevents later stages and publication, but cannot abort a model
call already in flight. That is already true today and is worth restating rather than implying a
stronger guarantee.

Worth fixing while here: cancellation is currently checked only at the top of tool execution, not
between rounds. The engine should check at each round boundary.

## 11. Publication is a pull

The engine does **not** post comments. The adapter fetches the result and publishes.

Reasons, in order of weight:

1. `PatchSetReviewer` needs `gerritClient.getClientData(change).getCommentProperties()` and
   `GerritCommentRange` to map replies onto existing threads
   (`review/PatchSetReviewer.java:128-129, 236-237`). Moving that would mean moving Gerrit back into
   the engine.
2. Decision 6 requires the adapter to own the comment-id map, which it cannot do if the engine posts.
3. A push would add a second callback surface with *write* privileges, widening the tool-RPC auth
   problem (§12.2) for no gain.

A new adapter component, `EngineJobPoller`, reuses the existing recovery cadence
(`AiRequestCoordinator`, `recoveryIntervalMillis` default 60 s) to pick up engine-terminal jobs whose
local request is not terminal. This is what makes *"the adapter died and the engine finished anyway"*
resolve correctly instead of stranding the result.

## 12. Code context: the tool-RPC contract

### 12.1 The good news

The failure semantics are **already defined correctly** and the engine-side client must reproduce them
exactly: `OnDemandCodeContextTools.execute` catches everything and returns the literal string
`CONTEXT NOT PROVIDED` (`aibackend/common/client/code/context/ondemand/OnDemandCodeContextTools.java:99-105`).
A tool failure never fails a review. This is the single most important rule for the callback, and
exception propagation across the RPC boundary would violate it.

### 12.2 Authentication

The six sidebar endpoints are `RestModifyView<ChangeResource, …>`, which require a user. Tool-RPC has
no user. Use a dedicated servlet — the pattern already exists in `HttpModule` — authenticated by a
shared secret (HMAC over method, path, timestamp, and nonce; constant-time comparison; bounded replay
window), executing the repository work under `OneOffRequestContext.openAs(config.getUserId())`. The RPC
surface is read-only and scoped to the change in the request, so the engine can read only code it is
already reviewing.

### 12.3 Timeouts and backpressure

- **Timeout strictly inside the model timeout.** The tool loop runs synchronously inside the model call
  (`aibackend/langchain/client/api/LangChainExecutor.java:180-208`), so a hanging `grep` must not
  consume the model call's budget. Per-call timeout, hard-capped at the engine.
- **Circuit breaker.** If the adapter is unreachable, fail fast to `CONTEXT NOT PROVIDED` for the rest
  of the review rather than paying the timeout on every round.
- **Bounded executor with a per-change cap.** This is a **new amplification surface**: one review fans
  out across agents, each running up to `maxToolResponseRounds`, each round issuing network calls into
  the host's repository. In-process this was bounded by the agent executor; across the boundary it is
  not. Overflow must map to `CONTEXT NOT PROVIDED`, not to an error.

### 12.4 Degradation must be observable

With pre-fetch, the engine depended on the adapter only at request time. With tool-RPC it depends on
adapter availability *throughout*. A host restart mid-review degrades context rather than failing —
which is correct, but means reviews silently get worse unless degradation is measured. Emit a metric
and a log line.

## 13. State migration

State to move: the concern ledger (`review_concern_ledgers` / `_reviewers` / `_concerns`), feedback
memory (`review_feedback_memories` / `_comments`), chat memory (`langchain_chat_memory_messages`), and
conversation ids (in plugin data). All are keyed by `change_id` today
(`data/ReviewConcernStore.java:41-48`).

### 13.1 Strategy: lazy per-change bootstrap, engine-authoritative from first contact

Do **not** bulk-migrate. Changes are long-lived and a bulk copy needs a freeze that is not available.
Instead:

1. **Ask once per change.** On a job where the engine has no ledger row for the `ChangeRef`, it calls
   the adapter's authenticated callback for a `StateBootstrap`, inserts it, and proceeds.
2. **No dual-write window.** The engine writes only after the bootstrap read, inside the claimed job.
   Both lanes serialize per change, so there is no interleaving to get wrong.
3. **Bootstrap failure must fail the job as retryable — never proceed with an empty ledger.** This is
   the single most important safety rule in the migration. An empty ledger is *not* a safe default:
   `preexisting` and `repeated` are computed from the prior ledger
   (`review/PatchSetReviewer.java:190-200`), so the model would re-report every previously-raised
   concern as new.
4. **Write-back is forbidden.** After bootstrap the engine is authoritative.
5. **Back-fill the adapter's concern → comment map from the legacy ledger *during* the migration step**,
   not only going forward. Skip this and every pre-existing concern loses its thread linkage the first
   time the engine re-reports it.
6. **Engine-side strip.** The engine has no `previousCommentId` field. This is lossless *only* because
   step 5 retains the mapping.

### 13.2 A hazard to not rely on

`ReviewConcernStore.load()` returns `Optional.empty()` on a `CURRENT_SCHEMA_VERSION` mismatch, logging
only a warning (`data/ReviewConcernStore.java:62-68`). A naive migration would therefore **drop concern
history without failing**. Dual-read must be explicit; this fallback must not be treated as safety.

### 13.3 Rollback

Rollback points the adapter back at the in-process engine, so it is a **copy-back**:

1. **Do not drop the adapter's tables at cut-over.** The migration step's job is "stop writing, keep
   the data", with a per-change `legacy_state_frozen_at` watermark.
2. On rollback, export engine state for every change whose `lastReviewedCommit` is newer than the
   watermark, and restore `previousCommentId` from the adapter's `concern_comment_ids`.
3. The engine's schema must round-trip everything the plugin's schema holds **except**
   `previousCommentId`, which comes back from the adapter's map. That asymmetry is deliberate.
4. Engine-created conversations are host-side objects keyed by an id the engine holds; export them too.

**Dropping the legacy tables is the point of no return** and belongs last, after the rollback window
closes.

Crucially, because the in-process engine is retained (§14.1), rollback is simply **configuring the
adapter back to in-process mode**. That is what makes this migration far safer than a one-way
extraction: the fallback is the current, well-tested code path, not a reconstruction of it.

### 13.4 The two modes do not share review state

A site runs either in-process or remote, and the two keep review state in different places (§9). A
change reviewed in-process, then reviewed again after the site switches to remote, hits the bootstrap
path in §13.1 exactly once — the engine asks the adapter for the legacy ledger and adopts it.

Two consequences follow:

1. **Switching is per-site, not per-change, and it is one-way per change.** Once a change has been
   bootstrapped into the engine, that change's subsequent reviews are engine-owned. Flipping the site
   back to in-process returns to the adapter's copy, which has been frozen since the watermark.
2. **A mixed fleet is normal during rollout.** Different sites on different modes is the expected
   intermediate state, not an error. Nothing in the contract assumes the other side's mode, which is
   why the in-process engine must also run the conformance suite (§14.2) — a divergence between the
   two engines is otherwise invisible until someone switches.

## 14. Module layout

### 14.1 Two repositories, and what actually crosses between them

The engine service is developed in a **separate, proprietary repository**. This repository stays
Apache-2.0 and keeps its in-process engine, so a site can run either way. All adapters — Gerrit today,
GitHub and GitLab later — are proprietary, so there is no open consumer of the engine side.

**No Java artifact is shared.** The plugin keeps its own adapter-side types and maps them to the wire
format; the engine has its own types for the same format. What crosses the boundary is JSON, and the
format is the contract.

```
this repository (Apache-2.0)              reviewai-backend (proprietary)
  src/main/java/...                           reviewai-contract/   (this side's types)
    (adapter + in-process engine)             src/main/java/...    (Spring Boot engine)
  contract/schema/*.json   ─────────────►     validated against in its build
  contract/vectors/*.json  ─────────────►     run against in its build
  MODULE.bazel, BUILD, tools/bzl/
```

```
/                          pom.xml  ← UNCHANGED: still the plugin, still not an aggregator
  src/main/java/...        ← unchanged (57 Gerrit-coupled files stay, including aibackend/)
  MODULE.bazel, BUILD, tools/bzl/    ← unchanged
  contract/                ← normative wire format: JSON Schema plus conformance vectors
```

The root `pom.xml` stays exactly as it is — no `<modules>`, no parent, no BOM — so the plugin's
Checkstyle/PMD/CPD/SpotBugs gauntlet and the shade assembly are untouched. Turning the root into an
aggregator would force `<parent>`/`<modules>` into a pom that deliberately has neither, and would make
`createDependencyReducedPom`, the `Gerrit-ApiVersion` manifest entries, and the `build-helper` dev
wiring variables in a build nobody wants to debug mid-split.

> **Historical note about an intermediate revision.** A revision between the 2026-08-29 proposal
> and this document required the contract to live in this Apache-2.0 repository as a published
> Java artifact compiled by both sides. Its underlying constraint remains valid: the public plugin
> must never depend on a proprietary artifact to build. The shared-artifact conclusion, however,
> is unnecessary. The plugin and engine can define their Java types independently while agreeing
> on a public JSON Schema and conformance vectors. This history is retained because the valid
> licensing constraint can otherwise lead future reviews back to the same unnecessary conclusion.

Consequences to accept:

- Adapter-side and engine-side types are written twice. That duplication is the price of not sharing
  an artifact, and §14.2 is what makes it safe.
- **Code moved from `aibackend/` into the proprietary repository remains Apache-2.0** for the portions
  already published here, with attribution retained; only genuinely new work is proprietary. This is
  standard permissive-licence behaviour, but it is easy to overlook when starting a "closed" project
  from an open tree.
- CI in each repository is independent. This repository's CI must never require `reviewai-backend`, and
  its contract checks must build from what is in this tree alone.

### 14.2 How two independently-written sides stay in agreement

With no shared artifact, nothing in either build would otherwise notice if the two sides drifted, and
the failure mode is the bad one: a silently mis-parsed field, not an error. Two things prevent it, both
published from this repository under Apache-2.0 because they are specification rather than code:

- **A JSON Schema** for the wire format, which both sides validate their own types against.
- **Conformance vectors** — canonical payloads, edge cases, and the inputs that must be *rejected* —
  which both sides run in their own builds.

The vectors cover what the format promises rather than how either side is built:

- every request field round-trips without loss, including `StateBootstrap` and the historical
  correlation reference defined in §5.1, whose value may identify either a host comment or a prior
  concern;
- the job state machine's legal transitions and terminal-state semantics (§7.2);
- `POST` on an existing idempotency key returns the existing job rather than a conflict (§7.1);
- cancellation reaches a terminal state and never publishes afterwards (§10);
- `CONTEXT NOT PROVIDED` is returned — not an exception thrown — when code context is unavailable
  (§12.1);
- an unrecognised contract version is refused explicitly rather than silently accepted (§16).

Note that the third and fourth of those describe behaviour, not format, so they are tested by the
engine against its own implementation rather than by schema validation. Keeping the distinction clear
matters: a schema can only ever check shape.

### 14.3 Bazel builds the plugin; Maven builds the engine

Stated as a decision, not an omission. The existing `BUILD` globs `src/main/java/**/*.java`, so the new
directories are excluded by construction and the Bazel build is genuinely untouched. Reimplementing
Spring Boot repackaging, `application.yml` layout, and Flyway resource scanning under Bazel is not
worth it, and the release pipeline only ships the plugin.

### 14.4 Keeping Gerrit off the engine classpath — enforced, not intended

Layering alone will not hold; someone will add a convenience import. Enforce it:

1. `reviewai-contract` has zero dependencies, so it cannot leak Gerrit by construction.
2. `reviewai-engine` depends on the contract, LangChain4j, and Spring — **never** `gerrit-plugin-api`.
   That artifact is a 65 MB self-contained fat jar embedding Gerrit server classes, Guice, and Guava.
3. `maven-enforcer-plugin` `bannedDependencies` in the engine pom banning `com.google.gerrit:*`
   (and `com.google.guava:*`, `com.google.gson:*`, which the fat jar supplies).
4. An ArchUnit rule asserting nothing under the engine package references `com.google.gerrit`. This is
   the rule that actually catches regressions.

### 14.5 The Jackson relocation trap

The shade plugin relocates `com.fasterxml.jackson` to
`com.googlesource.gerrit.plugins.aireview.jackson` (`pom.xml:238-241`) — note `aireview`, not
`reviewai`; a pre-existing inconsistency worth fixing separately.

The plugin's adapter-side wire types are not in the shade `artifactSet` allowlist, so they would keep
literal `com.fasterxml.jackson.*` references while the plugin's own classes reference the relocated
package. At runtime inside Gerrit, the unrelocated references resolve against **Gerrit's own Jackson**
— a silent version-skew bug with no compile error.

Hence the hard rule: **zero Jackson *and* zero Gson annotations or types on either side of the
contract.** Field names are the wire names; the engine configures `SNAKE_CASE`, the adapter configures
`LOWER_CASE_WITH_UNDERSCORES`. No hand-written field mapping, and no annotation on either side.

A consequence to budget for: the wire format is **not** byte-compatible with today's `concern_json`,
which is acceptable because the engine gets fresh tables — the plugin's existing rows are read once,
during bootstrap. Four or more existing test classes use Lombok setters on the concern models and will
need mechanical rewriting. Wide but shallow.

### 14.6 Credentials

`aiTokens` is an additive global+project list, so a project token overrides the global one
(`config/AiProviderConfiguration.java:169-181`). The job payload is persisted in clear, so **a token
must never appear in it**. The contract carries an opaque `credentialRef`; the engine resolves the
secret from its own store. Where project credentials live post-split is unresolved (§17.2).

### 14.7 Reuse the design, not the tables

The engine gets its own tables — same lane/lease/owner design, separate schema, separate migration
ownership. Three reasons:

- The plugin manages schema with `CREATE TABLE IF NOT EXISTS` plus a `db_versions` table
  (`data/ReviewAiDb.java:154-180`) while the engine uses Flyway. Two migration owners on one schema is
  a race waiting to happen.
- Keys differ: the engine needs the adapter's `requestId`/`attempt` correlation, and retention differs
  (engine TTL ≤ adapter TTL).
- The Postgres upsert helper exists precisely because the plugin supports H2; the engine is
  Postgres-only, so sharing would drag dialect handling along for no benefit.

If both share one Postgres instance — likely, for a per-install deployment — use **two schemas**. Keep
the *design* identical so the invariants in `docs/architecture/request-coordination.md` transfer
unchanged.

### 14.8 Packaging detail

The PostgreSQL driver is `test`-scoped in Maven (`pom.xml:370-373`) and **absent from `MODULE.bazel`
entirely**. It must be promoted to a runtime dependency before any Postgres-backed engine can run.
`data/DbDialect.java:21` already anticipates this — its own documentation reads "H2 (default,
single-node) and PostgreSQL (multi-site)".

## 15. Migration sequence

Each step is independently landable and reversible. Steps 0–3 are valuable even if the split never
happens.

**Step 0 — Make re-execution safe.** No new process, no behaviour change.
- Idempotent publication (`findingKey` + `published_findings`) — §8.
- Retryable disposition: `attempt_count`, `next_attempt_at_millis`, `releaseForRetry(backoff)`, which
  must release the lane so queued work proceeds (`listener/AiRequestCoordinator.java:291-301`).
- Fix the `GitRepoFiles` mutable-instance-field race
  (`aibackend/common/client/api/git/GitRepoFiles.java:49-52`): `enabledFileExtensions`,
  `disabledFileExtensions` and `fileSize` are shared across concurrently running agent stages.
- De-static `AiModelRequestLimiter.REQUEST_GATE` behind an injected gate
  (`aibackend/langchain/client/api/AiModelRequestLimiter.java:26,30`).
- Split intake, worker, and lease pools.

*Proves it worked:* a fault-injection test fails a review after the model call and re-runs it,
asserting exactly one set of comments. A concurrency test asserts no cross-contamination of
file-extension filters — it fails on today's code.

**Step 1 — Extract the contract and the code-context port.** Move the concern and feedback models to
annotation-free records. Define `CodeContextPort` and implement it in the plugin over `GitRepoFiles`.
`IAiClient.ask` keeps its signature.
*Proves it worked:* all existing tests pass; an ArchUnit rule proves no Gerrit class is reachable from
the port-consuming package.

**Step 2 — Config resolution boundary.** `ConfigCreator` produces `ResolvedConfig`; engine internals
read only that.
*Proves it worked:* a golden test over a global/project/dynamic config matrix asserting resolved values
match `Configuration`'s current output.

**Step 3 — Job API in-process.** Introduce `ReviewJobService` with the POST/GET/cancel semantics against
the adapter's existing database; the listener calls it instead of `IAiClient.ask`. The async, queue,
lease and cancel semantics are now exercised through the new interface while still in one JVM.
*Proves it worked:* a second service instance over the same database; a job survives a simulated
restart.

**Step 4 — Add the remote transport; publish the wire format.** The adapter gains an engine-transport
selection (§4.1): in-process remains the default, and remote is opt-in per site. Concurrently, the
proprietary repository stands up the Spring Boot service against its own implementation of the format,
and the JSON Schema and conformance vectors land here (§14.2) so both sides are held to one
specification.
*Proves it worked:* (a) `SIGKILL` the remote engine mid-review → the review retries and completes,
comments appear exactly once; (b) `SIGKILL` the adapter mid-review → the engine finishes and
`EngineJobPoller` publishes on restart, exactly once; (c) all six sidebar endpoints keep working with
the remote engine down; (d) the bootstrap drill from §13; (e) both sides pass the conformance vectors,
and the schema validates payloads produced by each.

**Step 5 — Enable remote mode site by site.** Flip sites one at a time, each with its own bootstrap
migration (§13.1) and rollback window (§13.3). The in-process path is retained, so rollback is a
configuration change rather than a code change. Engine-side retention and metrics follow §16.

> **Changed from the prior proposal.** The prior migration plan treated the in-process contract
> as an intermediate step before service extraction, although it did not explicitly require its
> removal. This revision makes the decision explicit: the in-process engine remains a permanently
> supported deployment mode after remote mode is introduced.

**Ordering constraints.** Step 0 must precede Step 4 — idempotent publication is a precondition for the
split. Step 2 must precede Step 4. Steps 1 and 2 are independent of each other. The schema and vectors
(§14.2) must exist before Step 5 begins, since they are the only thing holding the two independently
written sides together.

## 16. Risks and trade-offs

Baseline risks retained or refined from the prior proposal:

- **Payload size.** The patch in the job payload can be megabytes. A `context.fetchPatch()` fallback
  and a request-size limit may be needed in v1.
- **Contract versioning.** Once two processes deploy independently, the contract must be versioned and
  evolved carefully. `GET /v1/meta` lets an adapter fail fast against an incompatible engine rather
  than erroring on every review.
- **Metric continuity.** The prior proposal already required metrics and cost tracking to move into the
  service or be returned with the result. In this design, `reviewai/*` metrics currently registered on
  Gerrit's `MetricMaker` need an explicit replacement or reporting path.
- **Effort.** Multi-week when done properly, but landable incrementally.

Risks introduced or made material by the revised design:

- **At-least-once execution** (§8). Mitigated by deterministic `findingKey` and a publication ledger —
  but only if Step 0 lands first.
- **Two queues in series** add latency and a second place for work to stall. The `EngineJobPoller`
  makes a stalled handoff recoverable rather than lost.
- **`aiMaxConcurrentRequests` silently multiplies by N.** It is a JVM-static semaphore
  (`aibackend/langchain/client/api/AiModelRequestLimiter.java:26`), so N replicas mean N× the load
  against a metered quota. Split the knob, or document the multiplication if the global gate is
  deferred.
- **Comment-quality risk** from moving rendering to the adapter (§6.3). Measure, do not assume.
- **Tool-RPC amplification** (§12.3) — a new denial-of-service surface with no in-process equivalent.
- **Config is no longer centrally validated**, since the adapter resolves it.
- **`AiReviewThreads` reads the concern ledger directly** (`web/AiReviewThreads.java:136,288`); once
  the engine owns the ledger, that sidebar endpoint needs a read-through or it renders stale data.

## 17. Open questions

Decided by this revision, and no longer open: transport (REST), code context (tool-RPC), async model
(job API + SSE), persistence ownership (engine), deployment (per-install, product-agnostic), config
(per-request), comment rendering (adapter).

Still open:

1. **Feedback memory's product-specific ids.** `review_feedback_comments.comment_id`
   (`data/ReviewAiDb.java:263-271`) is a Gerrit comment id in a table the engine would own, and the
   same applies to the three comment-id sets the engine currently reads off `ChangeSetData` —
   `pendingReviewFeedbackCommentIds`, `reviewFeedbackDismissalAuthorizedCommentIds`, and
   `reviewFeedbackControlAuthorizedCommentIds` (`ChangeSetData.java:63-65`). Decision 6 covers the
   *concern* map but not these. Either extend the adapter-owned mapping to feedback handles, or keep
   feedback classification adapter-side. **This is decision-6-sized and should be settled before
   Step 4.**
2. **Where per-project LLM credentials live** post-split, given `credentialRef` (§14.6). Requires an
   operator-facing decision about who holds secrets.
3. **Is shared PostgreSQL a hard requirement** for the adapter's queue, and if so is H2 support
   dropped from the plugin entirely?
4. **`aiMaxConcurrentRequests`**: per-replica, or a globally enforced gate?
5. **Does a `context.fetchPatch()` fallback land in v1**, or is a request-size limit sufficient?
6. **Metric-name continuity** (§16).
7. **SSE termination and `Last-Event-ID` semantics** across replica restarts.
8. **Does the engine's schema need a downgrade path**, or is the §13.3 export sufficient?

## 18. Alternatives considered

- **A. In-process interface only (no service).** Add the contract but keep it in the host JVM.
  Cheapest; achieves decoupling and testability but not reuse or independent scaling. *This remains a
  valid interim state and is literally Steps 1–3 of §15.*
- **B. Extract as a library (JAR), not a service.** Reusable by embedding, but couples versioning and
  deployment and gives no independent scaling.
- **C. Do nothing; keep improving in place.** Short-term easier, but the coupling grows and the engine
  stays unreusable.
- **D. Full microservice extraction in one step.** Highest value, highest risk; rejected in favour of
  the incremental path.
- **E. Pre-fetch code context into the request** instead of tool-RPC. Keeps the engine a pure function,
  but `grep` over unchanged files and `get_content` of unchanged files degrade or break — much of what
  the tools exist for. Rejected, with the honest note that tool-RPC trades purity for fidelity.
- **F. Repo bundle upload per review.** Full tool fidelity with no callback, but a per-review upload of
  potentially hundreds of megabytes. Rejected for per-install deployments where the callback is cheap.
