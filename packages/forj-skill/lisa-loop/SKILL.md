---
name: lisa-loop
description: REPL-driven autonomous development loops for Clojure. Validates with REPL evaluation instead of tests for 10x faster feedback.
commands:
  - name: lisa-loop
    description: Start an autonomous development loop with REPL-first validation
    args: "<prompt> [--max-iterations N] [--completion-promise TEXT]"
  - name: cancel-lisa
    description: Cancel the active Lisa loop
---

# Lisa Loop - REPL-Driven Autonomous Development

Lisa Loop is forj's native autonomous development loop. Named after Lisa Simpson (methodical, analytical), it validates code through REPL evaluation rather than test runs.

## Commands

### /lisa-loop

Start an autonomous development loop:

```
/lisa-loop "Build a REST API for users with CRUD operations" --max-iterations 20
```

**Arguments:**
- `<prompt>` - The task description (required)
- `--max-iterations N` - Maximum iterations before stopping (default: 30)
- `--completion-promise TEXT` - Completion signal (default: "COMPLETE")

### /cancel-lisa

Stop the active loop immediately:

```
/cancel-lisa
```

## The Key Insight

| Approach | Validation | Feedback | Speed |
|----------|------------|----------|-------|
| **Test-based loops** | Run tests | "Test failed" | ~seconds |
| **Lisa Loop** | REPL eval | `{:expected 1 :actual nil}` | ~10ms |

Lisa sees **actual data**, not just pass/fail. This means faster iteration and fewer wasted cycles.

## How It Works

1. You invoke `/lisa-loop` with your task
2. Claude works on the task using REPL-driven development
3. When Claude tries to stop, the **Stop hook** intercepts:
   - Runs `validate_changed_files` automatically
   - Injects REPL feedback into context
   - Continues the loop if not complete
4. Loop ends when:
   - Claude outputs `<promise>COMPLETE</promise>` (or your custom promise)
   - Max iterations reached

**Between each iteration**, you automatically get:
- Namespace reload results
- Comment block evaluation outputs
- Actual return values, not just pass/fail

## Quick Start

When you (Claude) receive a `/lisa-loop` command:

1. **Parse the arguments** from the user's message
2. **Initialize the loop** using the `start_loop` MCP tool:
   ```
   start_loop with prompt="<the task>", max_iterations=20
   ```
3. **Start working** on the task using REPL-driven methodology
4. **Output completion** when done: `<promise>COMPLETE</promise>`

For `/cancel-lisa`:
```
cancel_loop (no arguments needed)
```

You can check loop status anytime with the `loop_status` tool.

## Methodology

### Step 1: Write Code with Comment Blocks

Every function should have a `(comment ...)` block with test expressions:

```clojure
(defn create-user [db user-data]
  (jdbc/insert! db :users user-data))

(comment
  ;; Test expressions - these get evaluated to verify the function
  (create-user test-db {:name "Alice" :email "alice@example.com"})
  ;; => {:id 1 :name "Alice" :email "alice@example.com"}

  (create-user test-db {})
  ;; => Should throw validation error
  )
```

### Step 2: Validate with REPL Before Tests

After writing or modifying code:

1. **Reload the namespace** to pick up changes:
   ```
   Use reload_namespace tool on the changed file
   ```

2. **Evaluate comment blocks** to see actual outputs:
   ```
   Use eval_comment_block tool on the file
   ```

3. **Check results** - you'll see actual return values, not just pass/fail

4. **Iterate if needed** - fix issues based on actual data, then repeat steps 1-3

5. **Run tests only when REPL feedback looks correct**:
   ```
   Use run_tests tool (or bb test)
   ```

### Step 3: Use Checkpoints

Structure your work around verifiable checkpoints:

```
CHECKPOINTS:
- [ ] create-user returns user map with :id
- [ ] get-user returns nil for missing user
- [ ] update-user returns updated map
- [ ] Comment blocks evaluate without error
- [ ] bb test passes
```

Each checkpoint should be verifiable via REPL evaluation.

## Example Prompts

### Basic Feature Development

```
/lisa-loop "Build a password hashing module.

REQUIREMENTS:
- hash-password: takes plaintext, returns bcrypt hash
- verify-password: takes plaintext and hash, returns boolean
- Both functions should have comment blocks with test expressions

CHECKPOINTS:
- [ ] hash-password returns string starting with '$2'
- [ ] verify-password returns true for matching password
- [ ] verify-password returns false for wrong password
- [ ] All comment blocks evaluate without error
- [ ] bb test passes" --max-iterations 15
```

### Refactoring Task

```
/lisa-loop "Refactor the user module to use specs for validation.

METHODOLOGY:
1. Add spec definitions
2. Update functions to use spec validation
3. After each change, REPL validates specs work
4. Only run full tests when REPL confirms behavior

CHECKPOINTS:
- [ ] ::user spec validates correctly
- [ ] create-user throws on invalid input
- [ ] create-user succeeds on valid input
- [ ] Existing tests still pass" --max-iterations 20
```

### Multi-File Feature

```
/lisa-loop "Add JWT authentication to the API.

PHASES (validate each with REPL before moving on):

Phase 1 - Token generation:
- Write create-token function with comment block
- Verify: (create-token {:user-id 1}) => JWT string

Phase 2 - Token verification:
- Write verify-token function with comment block
- Verify: (verify-token (create-token {...})) => claims map

Phase 3 - Middleware:
- Write wrap-auth middleware with comment block
- Test with mock requests

Phase 4 - Integration:
- Run bb test to verify full integration" --max-iterations 30
```

## Why Lisa Loop Works

### 1. Faster Feedback
- REPL eval: ~10ms
- Running tests: ~2-10 seconds
- Over 30 iterations, Lisa saves minutes of waiting

### 2. Richer Information
- Tests: "Expected 1, got nil"
- REPL: You see the actual data structure, stack traces, intermediate values

### 3. Incremental Validation
- Validate one function at a time
- Don't wait for entire test suite
- Catch errors earlier in the iteration

### 4. Automatic Between-Iteration Validation
- `validate_changed_files` runs automatically
- Results injected into your context
- You see what changed and whether it works

## Tools to Use

| Tool | When to Use |
|------|-------------|
| `reload_namespace` | After any file change |
| `eval_comment_block` | To verify function behavior |
| `eval_at` | To evaluate specific form at a line |
| `repl_eval` | For ad-hoc expressions |
| `run_tests` | Final validation before completing |
| `validate_changed_files` | Validate all changes at once (also runs automatically) |

## Anti-Patterns

### Don't: Run tests on every change
```
# Slow - runs full test suite each iteration
1. Write code
2. bb test
3. See failure
4. Fix code
5. bb test again
```

### Do: Validate with REPL first
```
# Fast - only run tests when confident
1. Write code with comment block
2. reload_namespace
3. eval_comment_block
4. See actual output, fix if needed
5. Repeat 2-4 until correct
6. bb test (once, at the end)
```

## Completion Criteria

A Lisa Loop is complete when:

1. All functions have comment blocks with test expressions
2. All comment blocks evaluate without error
3. Comment block outputs match expected behavior
4. Full test suite passes (`bb test` or `run_tests`)
5. Code is committed (optional)

Then output your completion promise:

```
<promise>COMPLETE</promise>
```
