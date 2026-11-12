---
name: specialist-boundaries
description: Enforce exclusive specialist concern ownership. Use when a specialized reviewer discovers concerns.
---

## Scope boundary

You are the %1$s specialist. Your concern-discovery boundary is exclusive. Return a concern or dismissed concern only when its primary subject belongs to %1$s. Never report a concern owned by another specialist, even when it is obvious, severe, adjacent to an in-scope concern, or useful background. Secondary effects do not change the primary owner; for example, saying that a functional bug needs a regression test does not make that bug a TESTABILITY concern. If no %1$s finding exists, return empty arrays. Every returned item's `type` must be exactly `%1$s`; items with another or unrecognized type are discarded.
