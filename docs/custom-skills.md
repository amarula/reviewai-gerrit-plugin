# Custom Skills

Use files under `.gerrit/skills/` on the change's target branch to customize user settings for review
stages. Skill guidance adds project instructions, while request templates replace the corresponding
bundled request text.

For plugin settings such as `agentSpecializationLevel`, see [Configuration](configuration.md).

## Project Skill Guidance

To add project-specific guidance to single-agent code reviews, commit
`.gerrit/skills/code-review/SKILL.md` to the change's target branch:

```markdown
---
name: code-review
description: Additional code review guidance for this project.
---

Check that every tenant-scoped query filters by tenant ID.
Flag logging of access tokens or customer credentials.
```

The file uses the same `SKILL.md` format as bundled skills. Its YAML front matter must contain a
`name` matching the skill directory and a nonempty `description`, followed by a nonempty Markdown
body. The body is appended as a separate project guidance section; metadata is not included in the
prompt. Bundled instructions, configured roles, response formats, and request templates are retained.

The plugin reads the committed file from the target branch for each request, using the same lookup
as `.gerrit/ai-instructions.md`. Unmerged patch set edits to the skill do not affect reviews. Project
guidance is not stored in the global bundled-skill cache. Missing files preserve existing behavior;
invalid files are ignored with a warning in the plugin log.

With `agentSpecializationLevel = SPECIALIZED_AGENTS`, the same convention supports the existing
specialist skills: `code-quality`, `correctness`, `documentation`, `security`, and `testability`.
For example, commit `.gerrit/skills/security/SKILL.md` to the target branch:

```markdown
---
name: security
description: Custom security review settings for this project.
---

Check that every tenant-scoped query filters by tenant ID.
Flag logging of access tokens or customer credentials.
```

Only a selected specialist receives its matching file's guidance. A security customization does not
affect testability or single-agent code reviews. Custom descriptions do not replace the bundled
agent identities or routing descriptions, and adding a file does not register or select an agent.
The bundled specialist boundaries and response formats are retained alongside triage-selected
instructions. Missing or invalid specialist files follow the same fallback behavior described above.

The same convention supports the other pipeline stages. Each prompt declares its applicable skills;
the plugin appends their project guidance after assembling the bundled prompt. Shared
`review-common` guidance comes first, and each skill is applied at most once per request.
Reviewer guidance also applies when that reviewer reassesses concerns or searches for new issues.

| Skill directory | Where its guidance applies |
| --- | --- |
| `review-common` | All review, chat, suggestion, feedback, and routing prompts |
| `code-review` | Single-agent and scoped code reviews, retries, and their concern workflows |
| `commit-message-review` | Reviews that include commit messages, commit-message specialists, and their concern workflows |
| `code-quality`, `correctness`, `documentation`, `security`, `testability` | The matching selected specialist and its concern workflows |
| `review-chat` | Comment request prompts, including routed follow-ups |
| `review-follow-up` | Follow-ups routed to a code or commit-message reviewer |
| `review-suggestions` | Suggested-edit generation |
| `review-feedback` | User feedback classification |
| `concern-review` | Reassessment of existing concerns |
| `new-issue-finder` | Searching for new concerns |
| `review-routing` | Selection of the reviewer for a follow-up message |
| `review-triage` | Selection and instruction of specialist agents |
| `specialist-boundaries` | Patchset and commit-message specialist prompts, including new-issue finding |
| `review-consolidation` | Consolidation of specialist concerns |
| `review-historical-repetition` | Checks against historical findings |
| `review-conflict-resolution` | Resolution of conflicting concerns |
| `review-verification` | Verification and final review replies |

For example, `.gerrit/skills/review-triage/SKILL.md` can guide specialist selection:

```markdown
---
name: review-triage
description: Custom specialist selection settings for this project.
---

Consider tenant isolation when deciding whether a security specialist is needed.
```

Skill guidance is additive. Output schemas, field definitions, and agent catalogs continue to come
from the plugin. The supported request templates can be replaced as described below; other skill assets
remain bundled.
The existing `.gerrit/ai-instructions.md` behavior for comment request prompts is unchanged.

## Custom Request Templates

Custom user request templates can replace these bundled Markdown files. Each path below is relative
to `.gerrit/skills/` and must be committed to the change's target branch:

| Custom file | Value inserted at `%s` |
| --- | --- |
| `review-common/assets/request.md` | Patch set for common review requests |
| `commit-message-review/assets/request.md` | Commit message for scoped and specialist commit-message reviews |
| `review-feedback/assets/request.md` | Classification input JSON, including comments, concerns, and current feedback memory |
| `concern-review/assets/request.md` | Concern reassessment input JSON |
| `new-issue-finder/assets/request.md` | New-issue search input JSON |
| `review-routing/assets/request.md` | Latest review-agent request text |
| `review-suggestions/assets/request.md` | Patch set with negative review replies |
| `review-triage/assets/request.md` | Patch set for specialist selection |
| `specialist-boundaries/assets/request.md` | Full patch set for the selected patch-set specialist |
| `review-consolidation/assets/request.md` | Specialist findings to consolidate |
| `review-historical-repetition/assets/request.md` | New concerns to check against historical findings |
| `review-conflict-resolution/assets/request.md` | Consolidated findings whose conflicts need resolution |
| `review-verification/assets/request.md` | Conflict-resolved findings for final verification |

To replace the default patch-set request with custom user settings, commit
`.gerrit/skills/review-common/assets/request.md` to the change's target branch:

```markdown
Review this patch using our project conventions. Coverage target: 90%%.

Patch set:
%s
```

This is a plain Markdown template, without YAML front matter. It must contain exactly one `%s`,
which receives the value listed above. Write literal percent signs as `%%`. Other format specifiers,
including indexed placeholders such as `%1$s`, are unsupported. Missing, empty, or invalid templates
fall back to the bundled request; invalid templates produce a warning in the plugin log.

The common override applies only when a prompt uses the common request template, including single-agent
and scoped code reviews, and chat prompts with no explicit user request. Scoped reviews still remove
commit-message content before inserting the patch set. Each stage reads only its own request
template: a common override does not replace the feedback, commit-message, or other stage request.
Explicit user messages and retry prompts retain their existing behavior. System instructions,
response schemas, and Markdown assets other than the listed request templates are unaffected.

The files are read from the target branch when constructing the request, using the same lookup as
project skill guidance. They do not require a custom `SKILL.md`. Unmerged patch set
edits do not affect the request, and project templates are not stored in the bundled-skill cache.

## Manual Verification

1. Install the plugin and select `agentSpecializationLevel = SINGLE_AGENT`. Enable DEBUG logging as
   described in [Development and Debugging](development.md).
2. Create `.gerrit/skills/review-common/SKILL.md` using the skill format above, with
   `CUSTOM-SKILL-TEST` in its Markdown body. Create
   `.gerrit/skills/review-common/assets/request.md` using the request example above, prefixed with
   `CUSTOM-REQUEST-TEST`.
3. Merge both files into the test project's target branch. Create a fresh change against that branch
   and run `/review`.
4. Check `LangChain system instructions for` in the DEBUG log for `CUSTOM-SKILL-TEST`, and
   `LangChain user prompt for` for `CUSTOM-REQUEST-TEST`. The user prompt should contain the actual
   patch set and show `90%` rather than `90%%`. Markers need not appear in published review comments.
5. Replace the request template's `%s` with `%d`, merge that edit, and review another fresh change.
   Expect `Ignoring invalid project request template` in the log and a successful review using the
   bundled request without the custom request marker.

Unmerged customization edits must have no effect. After merging an update or deletion, the next
request should use the updated content or the bundled fallback. To verify another stage, use its
matching file from the tables above and trigger that stage's workflow.
