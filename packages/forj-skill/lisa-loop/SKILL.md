---
name: lisa-loop
description: REPL-driven autonomous development loops for Clojure. Like Ralph Wiggum, but validates with REPL evaluation instead of tests for 10x faster feedback.
---

# Lisa Loop - REPL-Driven Autonomous Development

Lisa Loop is a Clojure-specific enhancement to the [Ralph Wiggum](https://ghuntley.com/ralph/) autonomous development pattern. Named after Lisa Simpson (methodical, analytical), it validates code through REPL evaluation rather than test runs.

## The Key Insight

| Approach | Validation | Feedback | Speed |
|----------|------------|----------|-------|
| **Ralph Loop** | Run tests | "Test failed" | ~seconds |
| **Lisa Loop** | REPL eval | `{:expected 1 :actual nil}` | ~10ms |

Lisa sees **actual data**, not just pass/fail. This means faster iteration and fewer wasted cycles.

## Quick Start

To start a Lisa Loop, use Ralph Wiggum with REPL-first methodology:

```
/ralph-loop "Build feature X using REPL-driven development. After writing each function, reload the namespace and eval the comment block to verify. Only run tests when REPL feedback confirms correctness. Output <promise>COMPLETE</promise> when bb test passes." --max-iterations 30
```

## Instructions

When you (Claude) are in an autonomous loop working on Clojure code, follow this methodology:

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
Build a password hashing module using REPL-driven development.

REQUIREMENTS:
- hash-password: takes plaintext, returns bcrypt hash
- verify-password: takes plaintext and hash, returns boolean
- Both functions should have comment blocks with test expressions

METHODOLOGY:
1. Write function with (comment ...) block
2. reload_namespace to pick up changes
3. eval_comment_block to verify behavior
4. Iterate until comment block outputs look correct
5. Run bb test only when REPL confirms correctness

CHECKPOINTS:
- [ ] hash-password returns string starting with "$2"
- [ ] verify-password returns true for matching password
- [ ] verify-password returns false for wrong password
- [ ] All comment blocks evaluate without error
- [ ] bb test passes

Output <promise>COMPLETE</promise> when all checkpoints pass.
```

### Refactoring Task

```
Refactor the user module to use specs for validation.

METHODOLOGY - REPL-driven with validation:
1. Add spec definitions
2. Update functions to use spec validation
3. After each change:
   - reload_namespace
   - eval_comment_block to verify specs work
   - Check that valid data passes, invalid data fails
4. Only run full tests when REPL confirms behavior

CHECKPOINTS:
- [ ] ::user spec validates correctly (eval in REPL)
- [ ] create-user throws on invalid input
- [ ] create-user succeeds on valid input
- [ ] Existing tests still pass

Output <promise>COMPLETE</promise> when done.
```

### Multi-File Feature

```
Add JWT authentication to the API.

PHASES (validate each with REPL before moving on):

Phase 1 - Token generation:
- Write create-token function
- Comment block: (create-token {:user-id 1}) => JWT string
- Verify with eval_comment_block

Phase 2 - Token verification:
- Write verify-token function
- Comment block: (verify-token (create-token {...})) => claims map
- Verify with eval_comment_block

Phase 3 - Middleware:
- Write wrap-auth middleware
- Comment block: test with mock requests
- Verify with eval_comment_block

Phase 4 - Integration:
- Run bb test to verify full integration

Output <promise>COMPLETE</promise> when bb test passes.
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

### 4. Comment Blocks as Documentation
- Test expressions serve as usage examples
- Future developers see expected behavior
- REPL-friendly exploration

## Tools to Use

| Tool | When to Use |
|------|-------------|
| `reload_namespace` | After any file change |
| `eval_comment_block` | To verify function behavior |
| `eval_at` | To evaluate specific form at a line |
| `repl_eval` | For ad-hoc expressions |
| `run_tests` | Final validation before completing |
| `validate_changed_files` | Validate all changes at once |

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

## Integration with Ralph Wiggum

Lisa Loop works WITH Ralph Wiggum, not instead of it. Ralph provides the autonomous loop mechanism (Stop hook), Lisa provides the validation methodology.

```
/ralph-loop "<your task with Lisa methodology>" --max-iterations 30 --completion-promise "COMPLETE"
```

The Lisa methodology makes each Ralph iteration more effective by catching errors faster.

## Completion Criteria

A Lisa Loop iteration is complete when:

1. All functions have comment blocks with test expressions
2. All comment blocks evaluate without error
3. Comment block outputs match expected behavior
4. Full test suite passes (`bb test` or `run_tests`)
5. Code is committed

Then output your completion promise (e.g., `<promise>COMPLETE</promise>`).
