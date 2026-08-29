# Proposal: Split the ReviewAI backend from the Gerrit frontend

- **Status:** Proposal (under review)
- **Date:** 2026-08-29
- **Goal:** maintainability — make the AI review engine reusable and independently testable

## 1. Context and motivation

Today the plugin is a single Gerrit process. The AI review engine (LangChain4j
integration, prompt building, concern workflow, agents) is intertwined with the
Gerrit-specific integration (event listeners, Gerrit API clients, web endpoints,
plugin data storage).

Two forces make this painful:

1. **Every change to the AI logic is coupled to Gerrit.** The engine's entry
   point takes Gerrit types (`GerritChange`, `ChangeSetData`), so the engine
   cannot be built, tested, or reasoned about without the Gerrit environment.
2. **The engine could serve more than Gerrit.** The review capability is
   product-agnostic ("code context + prior review state" in, "comments /
   concerns / score" out). GitHub, GitLab, or an IDE could reuse it, but only if
   the engine is extracted behind a neutral contract.

This document proposes extracting the engine into a standalone service and
turning the Gerrit plugin into a thin adapter. The primary goal is
maintainability, not scale or performance.

## 2. Goals and non-goals

### Goals

- Decouple the AI engine from Gerrit types and Gerrit lifecycle.
- Define a stable, neutral contract (`ReviewRequest` / `ReviewResult`).
- Make the engine a pure function: all inputs in, all outputs out, no I/O mid-review.
- Allow the engine to be reused by other products via new adapters.
- Land the change incrementally, without a big-bang rewrite.

### Non-goals

- No change to the browser sidebar UX.
- No change to the review semantics (concerns, voting, feedback memory).
- No re-platforming of persistence or config in the first iteration.
- Not (yet) a multi-tenant or high-throughput service design.

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

4. **Persistence is Gerrit-tied.** The concern ledger, feedback memory, and
   OpenAI conversation id live in Gerrit plugin data (`ReviewAiDb` /
   `PluginDataHandler`).

5. **Config is Gerrit-sourced.** `Configuration` reads `gerrit.config` and
   project-level overrides.

6. **Listener and web layers are inherently Gerrit.** `GerritListener`, the
   `EventHandlerType*` classes, and `ChangeResource` / `ChangeApi` cannot be
   shared with another product.

Only `previousCommentId` (a Gerrit comment id) leaks into the otherwise-neutral
concern models; everything else in `ReviewConcern`, `ConcernStatus`,
`ReviewerConcerns`, `ConcernLedger`, and `ReviewFeedbackMemory` is already
product-agnostic.

## 4. Proposed architecture

### 4.1 Target topology

```
┌───────────────────────── Gerrit (frontend / adapter) ─────────────────────────┐
│  Browser sidebar  ──►  listener / web / command parser                        │
│                              │  fetch patch, concerns, feedback               │
│                              ▼                                                 │
│                     build ReviewRequest                                       │
│                              │                                                 │
└──────────────────────────────┼─────────────────────────────────────────────────┘
                               │  ReviewRequest (neutral contract)
                               ▼
┌───────────────────────── Review Engine (backend service) ─────────────────────┐
│  ReviewEngine.review(request) ──►  prompt, concern workflow, agents, LLM       │
│                                     │                                          │
│                                     ▼                                          │
│                              ReviewResult                                     │
└────────────────────────────────────┬───────────────────────────────────────────┘
                                    │  ReviewResult
                                    ▼
┌───────────────────────── Gerrit (frontend / adapter) ─────────────────────────┐
│  post comments, resolve threads, persist ledger, vote                        │
└────────────────────────────────────────────────────────────────────────────────┘
```

The engine is stateless. The adapter owns the concern ledger, feedback memory,
and conversation id, and passes them per request.

### 4.2 The interface boundary

```java
/** The review engine, decoupled from any code-hosting product. */
public interface ReviewEngine {
  ReviewResult review(ReviewRequest request) throws ReviewEngineException;

  // Future, for the interactive sidebar / streaming path:
  // Stream<ReviewEvent> reviewStream(ReviewRequest request);
}
```

This replaces `IAiClient.ask(ChangeSetData, GerritChange, String patchSet)`.

### 4.3 Input contract

```java
public record ReviewRequest(
    int schemaVersion,      // forward/backward compatibility across the boundary
    String requestId,       // opaque correlation id, assigned by the caller
    ReviewTarget target,    // what to review
    ReviewContext context,  // prior review state (empty on first review)
    ReviewIntent intent,    // how to review
    ModelConfig model       // which model and how to call it
) {}

public record ReviewTarget(
    String changeId,                 // opaque, correlation/logging only
    String patch,                    // the patch under review
    List<String> changedFiles,       // changed file paths
    String commitMessage,            // optional (COMMIT_MESSAGE scope)
    Map<String, String> fileContents // pre-fetched content for code-context tools
) {}

public record ReviewContext(
    ConcernLedger priorConcerns,     // null/empty on first review
    ReviewFeedbackMemory feedback,   // null/empty on first review
    String incrementalPatch          // null on first review (delta since last review)
) {}

public record ReviewIntent(
    ReviewScope scope,       // FULL, COMMIT_MESSAGE, ...
    boolean forced,          // forced/staged re-review
    boolean suggestMode,
    boolean debugReview,
    AgentSelection agent,    // SINGLE_AGENT | MULTI_AGENT | SPECIALIZED
    String specializedAgent, // optional, when SPECIALIZED
    boolean replyFilter      // filter repeated/duplicate/conflicting findings
) {}

public record ModelConfig(
    String provider,          // OPENAI | GEMINI | MOONSHOT | OLLAMA
    String model,
    double temperature,
    String instructions,      // optional project instructions
    String conversationId     // optional, frontend-owned, for stateful conversations
) {}
```

### 4.4 Output contract

```java
public record ReviewResult(
    List<Finding> findings,          // new inline comments
    ConcernLedger updatedConcerns,   // prior concerns with new statuses (or empty)
    String message,                  // optional change-level message
    Double score                     // optional aggregate score
) {}

public record Finding(
    String id,               // opaque; the caller maps it back to a thread
    String filename,
    Integer lineNumber,
    String codeSnippet,
    String message,
    String concernId,        // links a finding to a concern
    Double score,
    Double relevance,
    boolean repeated,
    boolean duplicated,
    boolean conflicting,
    String repeatedReason,
    String duplicatedReason,
    String conflictingReason
) {}
```

### 4.5 Error handling

```java
public class ReviewEngineException extends Exception {
  // categories: MODEL_UNAVAILABLE, INVALID_REQUEST, INTERNAL
}
```

The adapter maps `MODEL_UNAVAILABLE` to the existing user-facing
"Unable to connect to AI server" message; other categories map to an internal
error path.

### 4.6 Mapping: current → new

| Current | New |
|---|---|
| `IAiClient.ask(ChangeSetData, GerritChange, String)` | `ReviewEngine.review(ReviewRequest)` |
| `GerritChange` | `ReviewTarget.changeId` (opaque) |
| `patchSet` string | `ReviewTarget.patch` |
| `ChangeSetData.reviewScope / forcedReview / suggestMode / debugReviewMode / …` | `ReviewIntent` |
| `ChangeSetData.previousReviewConcernLedger / incrementalPatchSet / reviewFeedbackMemory` | `ReviewContext` |
| `Configuration` (provider/model/temp/instructions) | `ModelConfig` |
| `AiResponseContent` | `ReviewResult` |
| `AiReplyItem` | `Finding` |
| `ReviewConcern.previousCommentId` | `threadId` (the one Gerrit leak to rename) |
| `GitRepoFiles` / `repositoryManager` | `ReviewTarget.fileContents` |
| `ChangeSetData.parsedCommands / hideAiReview / reviewSystemMessage / …` | stays in the adapter |

The concern models (`ReviewConcern`, `ConcernStatus`, `ConcernReviewerId`,
`ConcernLocation`, `ReviewerConcerns`, `ConcernLedger`, `ReviewFeedbackMemory`)
move into the contract mostly as-is; only `previousCommentId` is renamed to
`threadId`.

## 5. Maintainability principles

1. **Pure engine.** `fileContents` is pre-fetched into the request, so the
   engine performs no repo I/O mid-review. `review` becomes
   `request → result`, trivially unit-testable and deterministic. This is the
   single biggest maintainability win.

2. **Immutable records.** No shared mutable state crosses the boundary; no
   accidental aliasing between adapter and engine.

3. **Small, single-purpose DTOs** replace the `ChangeSetData` grab-bag — each
   field has exactly one home.

4. **No transport or framework leaks.** The contract is plain Java. Jackson /
   Protobuf annotations live only in the RPC adapter, never in the contract.

5. **Stateless backend.** The adapter owns the concern ledger, feedback memory,
   and `conversationId`, passing them per request. Adding a new product means
   writing a new adapter, not touching the engine.

6. **Typed enums, not strings**, for `ReviewScope` and `AgentSelection`.

7. **Versioned contract.** `schemaVersion` on `ReviewRequest` allows
   forward/backward compatibility once the process is split.

## 6. Migration plan (incremental, no big-bang)

1. **Add the contract.** Create a `reviewai/contract` package with the records
   above and `ReviewEngine`. Zero Gerrit dependencies.

2. **Add the adapter.** `GerritReviewEngineAdapter` maps
   `ChangeSetData` / `GerritChange` → `ReviewRequest`, and `ReviewResult` →
   `AiResponseContent` + Gerrit posting. Keep `IAiClient` delegating to the
   adapter so existing callers are unchanged.

3. **Refactor the engine internals.** `LangChainClient`, `AiPromptFactory`, and
   the concern workflow consume `ReviewRequest` / `ReviewResult` instead of
   `GerritChange` / `GerritClient`.

4. **Extract into a service.** Put `ReviewEngine` behind gRPC or REST
   (`POST /reviews` → job id, status polling or streaming). The adapter becomes
   a thin HTTP client.

Steps 1–3 are valuable even if step 4 never happens: they decouple the engine
and make it testable in isolation.

## 7. Risks and trade-offs

- **Payload size / latency.** The patch and pre-fetched `fileContents` can be
  large; serialization and transport become real concerns. Mitigation: start
  with changed files only, add a callback/file API later.
- **Async model.** Reviews are long-running and multi-LLM-call; a synchronous
  `review()` needs a job/status or streaming wrapper for the service.
- **Code-context relocation.** The on-demand tools are the trickiest piece to
  move. Pre-fetching is the pragmatic first cut; a callback/file-serving API
  is the later refinement.
- **State ownership.** Choosing a stateless backend (adapter owns state) is
  simpler and more reusable, but means larger requests per review.
- **Contract versioning.** Once two processes are deployed independently, the
  contract must be versioned and evolved carefully.
- **Metrics / cost tracking** must move server-side, or be reported back in
  `ReviewResult`.
- **Effort.** Multi-week when done properly, but landable incrementally.

## 8. Open questions

1. **Transport:** gRPC vs REST? (Leaning gRPC for typed contract + streaming,
   REST for simplicity/observability.)
2. **Code context:** pre-fetch snapshot vs. callback API vs. both?
3. **Async:** should the contract expose a streaming interface now, or defer it?
4. **Persistence ownership:** does the engine remain fully stateless, or does it
   eventually own conversation/concern storage for cross-product consistency?
5. **Deployment:** one engine instance shared by all products, or per-product
   tenants?
6. **Config:** per-request `ModelConfig` (current plan) vs. engine-side config
   keyed by an opaque project id?

## 9. Alternatives considered

- **A. In-process interface only (no service).** Add `ReviewEngine` + contract
  but keep it in the Gerrit JVM. Cheapest, achieves decoupling and testability,
  but does not enable reuse by other products. *This is a valid interim state
  (migration steps 1–3).*
- **B. Extract as a library (JAR), not a service.** Reusable by embedding, but
  couples versioning and deployment; no independent scaling or cross-language
  reuse.
- **C. Do nothing; keep improving in place.** Short-term easier, but the Gerrit
  coupling continues to grow and the engine stays unreusable.
- **D. Full microservice extraction in one step.** Highest value, highest risk;
  rejected in favor of the incremental path in section 6.
