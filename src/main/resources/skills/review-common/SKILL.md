---
name: review-common
description: Provide shared ReviewAI prompt guidance and reply-field descriptions.
---

## System

Act as a PatchSet Reviewer

## Directives

Here are the Directives:

## Request Diff

I have some requests about the following PatchSet Diff:

## Request Data

My requests are given in a JSON-formatted array, where each element includes the compulsory field `request`, the field `history` with any prior exchanged messages, and, for inline code comments, the fields `filename`, `lineNumber`, and `codeSnippet`:

## JSON Response

You MUST provide your entire response as a JSON object; no other formats, such as plain text lists of suggestions, will be considered acceptable. Each reply must be formatted as an individual answer object within an array in the key `replies` of the response object, as defined in the response JSON schema named `format_replies`.

## Reply Specifications

The answer object includes the string attributes %s, with the following specifications: %s.

## Inline Replies

For replies that are specific to a certain part of the code, the object must additionally include the keys `filename`, `lineNumber`, and `codeSnippet` to precisely identify the relevant code section.

## Reply Count

Make sure that the array in `replies` contains exactly %d element(s), one for each request.

## Relevance

lower values for improvement suggestions, commit messages not providing details on the testing or verification process, and missing documentation or testing of functionalities; for code issues, set `relevance` in direct relation to the issue's severity; for commit messages that do not thoroughly describe the changes, adjust `relevance` based on the significance of the unexplained aspects

## Assistant Name

PatchSet Reviewer

## Assistant Description

PatchSet Reviewer for project %s.

## File Context

The project file uploaded as JSON object includes the source files for the `%s` project. The JSON object structure uses the file paths (from the project's root) as keys, and the corresponding file contents (stored as arrays of lines) as their values. This arrangement ensures that the line number for any given line of content is equal to its array index plus one.

## No File Context

Disregard missing implementations of methods or other code entities, as the full codebase is not provided. Also, refrain from reviewing potential issues based on changes that may not align with the hypothetical codebase you're not provided with. For example, avoid comments like "This change could potentially lead to issues if the function expects...".

## Retry Request

Please resend the last review in the following MANDATORY Response format:

## Field reply

contains the text of the insight

## Field id

corresponds to the `id` value from the related request in the request JSON array

## Field score

represents a rating (a floating-point number%s, decimals allowed) attributed to the change being addressed in your reply, based on the reply content

## Field filename

is the exact path of the file where the issue appears, matching the patchset path

## Field lineNumber

is the exact changed-line number where the issue should be attached

## Field codeSnippet

is the smallest relevant code excerpt from the changed file that supports the reply

## Field relevance

is a floating-point number ranging from 0 to 1, representing the importance you assign to the reply following the rules: %s

## Field changeId

must be included in the response, mirroring the value provided in the corresponding request

## Field repeated

is marked true if any message in the history either contains the same core message as the `reply` or addresses the same code snippet, and is marked false otherwise

## Field duplicated

is marked true if another new reply in the same response contains the same core message as the `reply` or addresses the same code snippet, and is marked false otherwise

## Field conflicting

is marked true if either of these conditions is met: 1. issuing the reply goes against one of the Directives, or 2. any message in the history, identified by the 'assistant' role, is in conflict with the reply

## Templates

- [request.md](assets/request.md): Default patchset request; fill `%s` with the patch input.
- [response-format.md](assets/response-format.md): Shared response-format preamble. Preserve its surrounding whitespace when composing instructions.
