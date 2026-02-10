# Lisa Loop: Post-Completion Review Step

## Problem

When a lisa-loop finishes, it prints cost/iteration stats and exits. There's no structured review of:
- **What was actually built** (diff summary across all checkpoints)
- **How it got there** (iteration quality, retries, signs generated)
- **What the input was** (original plan vs what was actually executed)

Users are left to manually `git log`, read signs, and piece together what happened.

## Proposal

Add an optional review step that runs after all checkpoints complete. The review would:

### 1. Gather Loop Artifacts
- Read `LISA_PLAN.edn` (final state with all checkpoints :done)
- Read signs file (learnings/failures accumulated during the loop)
- Read JSONL session logs for each checkpoint iteration
- Compute `git diff` from the loop's starting commit to HEAD

### 2. Produce a Structured Review

```
=== Lisa Loop Review ===

Plan: "Add JWT authentication"
Duration: 12 iterations, 8 checkpoints, $0.47
Started: 2026-01-31T10:00:00 | Finished: 2026-01-31T10:15:00

--- Input Assessment ---
Checkpoint granularity: Good (avg 1.5 iterations per checkpoint)
Blockers encountered: 1 (dependency issue with buddy.sign)
Signs generated: 3

--- Per-Checkpoint Summary ---
1. :password-hashing - 1 iteration, $0.04
   Files: src/auth/password.clj (new)

2. :jwt-tokens - 3 iterations, $0.12
   Files: src/auth/jwt.clj (new)
   Sign: "Wrong namespace - use buddy.sign.jwt not buddy.core.sign"

...

--- Code Quality ---
REPL compliance: Excellent (all checkpoints used REPL validation)
Anti-patterns: None detected
Test coverage: 4 new test functions added

--- Recommendations ---
- Checkpoint :jwt-tokens took 3 iterations - consider breaking into
  "create token" and "verify token" next time
- Sign about buddy.sign namespace should be added to project CLAUDE.md
```

### 3. Integration Points

**Where it runs:**
- After `plan-all-complete?` returns true in the orchestrator loop
- Before the terminal bell / completion message
- Controlled by config flag: `:review true` (default: true)

**How it's implemented:**
- New namespace: `forj.lisa.review`
- `(generate-review project-path loop-result)` - produces the review data
- `(format-review review-data)` - formats for terminal output
- `(review-with-llm review-data)` - optional Haiku pass for recommendations

**LLM usage:**
- The factual parts (files changed, iterations, signs) are pure data - no LLM needed
- Optional Haiku call for the "Recommendations" section (pattern detection, checkpoint sizing advice)
- Can be disabled with `:review-llm false`

### 4. Future Extensions

- **Conversation history analysis**: Parse JSONL session logs to detect patterns across loops (e.g., "Claude consistently struggles with X namespace")
- **Auto-update CLAUDE.md**: Promote recurring signs to project instructions
- **Review history**: Store reviews in `.forj/reviews/` for trend analysis across loops
- **Slack/Signal notification**: Send review summary when loop completes (integrate with jarvis proactive system)

## Implementation Notes

Key data sources already available:
- `plan_edn/read-plan` - final plan state
- `plan_edn/generate-completed-summary` - checkpoint summaries
- `analytics/format-iteration-summary` - per-iteration tool usage
- `analytics/score-repl-compliance` - compliance scoring
- Git log between auto-commit points (each checkpoint gets a commit)
- Signs file via `plan_edn/get-signs`

The main new work is:
1. Collecting per-checkpoint JSONL logs (session files are already written)
2. Correlating git diffs to checkpoints (auto-commits already tag checkpoint IDs)
3. Formatting the review output
4. Optional Haiku recommendation pass
