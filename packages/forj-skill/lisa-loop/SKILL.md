---
name: lisa-loop
description: REPL-driven autonomous development loops for Clojure. Validates with REPL evaluation instead of tests for 10x faster feedback.
commands:
  - name: lisa-loop
    description: Start an autonomous development loop with planning and REPL-first validation
    args: "<prompt> [--max-iterations N] [--no-plan]"
  - name: cancel-lisa
    description: Cancel the active Lisa loop
  - name: ralph-loop
    description: Start a Lisa Loop with true outer orchestration (fresh Claude instances per iteration)
    args: "<prompt> [--max-iterations N]"
---

# Lisa Loop - REPL-Driven Autonomous Development

Lisa Loop is forj's native autonomous development loop. Named after Lisa Simpson (methodical, analytical), it validates code through REPL evaluation rather than test runs.

## Key Concepts (v2 Architecture)

1. **Planning Phase**: Generate `LISA_PLAN.md` with checkpoints before coding
2. **One Checkpoint Per Iteration**: Focus on ONE task at a time
3. **REPL as State**: Query REPL for ground truth, not LLM memory
4. **Pluggable Validation**: REPL eval, Chrome MCP, or LLM-as-judge based on task

## Commands

### /lisa-loop

Start an autonomous development loop with planning:

```
/lisa-loop "Build a REST API for users with CRUD operations" --max-iterations 20
```

**Arguments:**
- `<prompt>` - The task description (required)
- `--max-iterations N` - Maximum iterations before stopping (default: 30)
- `--no-plan` - Skip planning phase, start coding immediately (not recommended)

### /cancel-lisa

Stop the active loop immediately:

```
/cancel-lisa
```

### /ralph-loop

Start with true outer orchestration (spawns fresh Claude instances):

```
/ralph-loop "Build user authentication" --max-iterations 20
```

This runs the orchestrator which spawns fresh Claude instances for each iteration,
providing true context isolation per the original Ralph Wiggum methodology.

## How It Works (v2 Flow)

```
/lisa-loop "Build user authentication"
                    │
                    ▼
            ┌───────────────┐
            │ PLANNING PHASE │
            │               │
            │ Generate:     │
            │ - LISA_PLAN.md│
            │ - Checkpoints │
            │ - Validation  │
            └───────┬───────┘
                    │
                    ▼
            ┌───────────────┐
            │ WORK ON       │◄────────────────┐
            │ CHECKPOINT    │                  │
            │               │                  │
            │ - Write code  │                  │
            │ - REPL eval   │                  │
            │ - Validate    │                  │
            └───────┬───────┘                  │
                    │                          │
           Checkpoint complete?                │
           │                                   │
     ┌─────┴─────┐                            │
     Yes         No ──────────────────────────┘
     │
     ▼
    Mark DONE in LISA_PLAN.md
    Advance to next checkpoint
     │
     ▼
    All checkpoints done? ──Yes──► Complete!
     │
     No
     │
     └────────────────────────────┘
```

## Quick Start

### 1. Receive `/lisa-loop` Command

Parse arguments and determine task scope.

### 2. Planning Phase (Generate LISA_PLAN.md)

Use `lisa_create_plan` to generate a plan with checkpoints:

```
lisa_create_plan with:
  title: "Build user authentication"
  checkpoints: [
    {description: "Create password hashing module",
     file: "src/auth/password.clj",
     acceptance: "(verify-password \"test\" (hash-password \"test\")) => true"},
    {description: "Create JWT token module",
     file: "src/auth/jwt.clj",
     acceptance: "(verify-token (create-token {:user-id 1})) => {:user-id 1}"},
    ...
  ]
```

This creates `LISA_PLAN.md` in the project root.

### 3. Work on ONE Checkpoint at a Time

For each checkpoint:

1. **Read the plan** to understand the current checkpoint:
   ```
   lisa_get_plan
   ```

2. **Query REPL state** to understand what's already done:
   ```
   repl_snapshot with namespace: "myapp.auth"
   ```

3. **Write code** with comment blocks for validation

4. **Validate with REPL**:
   ```
   reload_namespace with ns: "myapp.auth"
   eval_comment_block with file: "src/auth/password.clj"
   ```

5. **When checkpoint criteria met**, mark it done:
   ```
   lisa_mark_checkpoint_done with checkpoint: 1
   ```

6. **Move to next checkpoint** (plan auto-advances)

### 4. Complete When All Checkpoints Done

Output completion when `lisa_get_plan` shows all checkpoints done:
```
<promise>COMPLETE</promise>
```

## LISA_PLAN.md Format

```markdown
# Lisa Loop Plan: Build user authentication

## Status: IN_PROGRESS
## Current Checkpoint: 2

## Checkpoints

### 1. [DONE] Create password hashing module
- File: src/auth/password.clj
- Acceptance: (verify-password "test" (hash-password "test")) => true
- Completed: 2026-01-16T10:30:00Z

### 2. [IN_PROGRESS] Create JWT token module
- File: src/auth/jwt.clj
- Acceptance: (verify-token (create-token {:user-id 1})) => {:user-id 1}
- Started: 2026-01-16T10:31:00Z

### 3. [PENDING] Create auth middleware
- File: src/middleware/auth.clj
- Acceptance: Middleware extracts user from valid token
```

## Tools Reference

### Planning Tools

| Tool | Purpose |
|------|---------|
| `lisa_create_plan` | Create LISA_PLAN.md with checkpoints |
| `lisa_get_plan` | Read current plan status |
| `lisa_mark_checkpoint_done` | Mark checkpoint complete |

### REPL Tools

| Tool | Purpose |
|------|---------|
| `repl_snapshot` | Query REPL for loaded namespaces, vars, server state |
| `reload_namespace` | Reload namespace after code changes |
| `eval_comment_block` | Evaluate test expressions in comment blocks |
| `eval_at` | Evaluate specific form at a line |
| `repl_eval` | Ad-hoc REPL evaluation |
| `validate_changed_files` | Bulk validation of all changed files |
| `run_tests` | Final test validation |

### Signs (Guardrails) Tools

| Tool | Purpose |
|------|---------|
| `lisa_append_sign` | Record a failure/learning for future iterations |
| `lisa_get_signs` | Read signs summary and recent learnings |
| `lisa_clear_signs` | Clear signs file (at loop start or completion) |

### Validation Tools

| Tool | Purpose |
|------|---------|
| `lisa_run_validation` | Run validation checks (REPL, Chrome, Judge) |
| `lisa_check_gates` | Check if all gates pass before advancing |

### Loop Management

| Tool | Purpose |
|------|---------|
| `start_loop` | Initialize loop state (legacy) |
| `cancel_loop` | Cancel active loop |
| `loop_status` | Check loop iteration count |

## The REPL Advantage

### REPL as Persistent State Layer

The REPL survives across iterations and holds ground truth:

| Layer | What It Holds |
|-------|---------------|
| Git | Committed code |
| LISA_PLAN.md | Task progress, checkpoints |
| **REPL** | Loaded namespaces, running servers, defined vars |
| Claude context | Current reasoning (resets each iteration) |

Use `repl_snapshot` to query what's actually defined rather than trusting memory:

```clojure
;; What's defined?
(ns-publics 'myapp.auth)
;; => {hash-password #'myapp.auth/hash-password}

;; Is server running?
@myapp.core/server
;; => #object[org.eclipse.jetty.server.Server ...]
```

### Faster Feedback

| Approach | Validation | Feedback Time |
|----------|------------|---------------|
| Test-based loops | Run tests | ~2-10 seconds |
| **Lisa Loop** | REPL eval | ~10ms |

Lisa sees **actual data**, not just pass/fail.

## Example Prompt

```
/lisa-loop "Build a user authentication module.

REQUIREMENTS:
- hash-password: takes plaintext, returns bcrypt hash
- verify-password: takes plaintext and hash, returns boolean
- create-token: creates JWT from user claims
- verify-token: validates JWT, returns claims

VALIDATION:
- Each function has comment block with test expressions
- All comment blocks evaluate without error
- bb test passes at the end"
```

Claude should:
1. Create a plan with 4-5 checkpoints (one per function + integration)
2. Work through each checkpoint sequentially
3. Use REPL eval to validate each checkpoint
4. Mark checkpoints done as completed
5. Run `bb test` only when all REPL validation passes

## Signs (Guardrails)

Signs record failures and learnings that persist across iterations. When something goes wrong, append a sign so future iterations don't repeat the mistake.

### LISA_SIGNS.md Format

```markdown
# Lisa Loop Signs (Learnings)

## Sign 1 (Iteration 3, 2026-01-16T10:30:00Z)
**Checkpoint:** 2 - Create JWT module
**Issue:** Forgot to require clojure.string in namespace
**Fix:** Always check requires when adding string functions
**Severity:** error
```

### When to Append Signs

- REPL evaluation fails with a fixable error
- Validation check fails due to missing setup
- Common mistake that might recur

### Reading Signs

At the start of each iteration, read signs to avoid known pitfalls:
```
lisa_get_signs
```

## Pluggable Validation

Validation supports three methods, combinable with `|`:

### REPL Validation
```
repl:(verify-password "test" hash) => true
repl:(count (get-users db))
```

### Chrome MCP Validation (UI)
```
chrome:screenshot /login
chrome:click #submit-button
```
Note: Chrome validations return `:pending` and require manual MCP execution.

### LLM-as-Judge (Subjective)
```
judge:Does this form look professional and clean?
judge:Is the error message clear and helpful?
```
Note: Judge validations return `:pending` and require LLM evaluation.

### Combined Validation Example

```markdown
### 2. [PENDING] Create login form
- Validation: repl:(render [login-form]) returns hiccup | chrome:screenshot /login | judge:Form has clean layout
- Gates: repl:(some? [login-form])
```

## Anti-Patterns

### Don't: Skip the planning phase
Without a plan, you lose checkpoint tracking and may work on multiple things at once.

### Don't: Run tests on every iteration
```
# Slow - full test suite each time
1. Write code
2. bb test
3. See failure, fix
4. bb test again
```

### Do: REPL first, tests last
```
# Fast - tests only at completion
1. Write code with comment block
2. reload_namespace
3. eval_comment_block
4. See actual output, iterate
5. Mark checkpoint done
6. bb test (once, at end)
```

## Completion Criteria

A Lisa Loop is complete when:

1. All checkpoints in LISA_PLAN.md are `[DONE]`
2. `lisa_get_plan` shows `all-complete: true`
3. Final test validation passes (`run_tests`)
4. Output completion: `<promise>COMPLETE</promise>`

## For /cancel-lisa

Simply call:
```
cancel_loop
```

This clears loop state and stops the iteration.
