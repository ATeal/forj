---
name: lisa-loop
description: REPL-driven autonomous development loops for Clojure. Spawns fresh Claude instances per iteration with REPL validation for 10x faster feedback.
commands:
  - name: lisa-loop
    description: Start an autonomous development loop - spawns fresh Claude instances per checkpoint
    args: "<prompt> [--max-iterations N]"
  - name: cancel-lisa
    description: Cancel the active Lisa loop
---

# Lisa Loop - REPL-Driven Autonomous Development

Lisa Loop is forj's native autonomous development loop. It spawns **fresh Claude instances for each iteration**, using LISA_PLAN.md for state and REPL for validation.

## Key Concepts

1. **Fresh Context Per Iteration**: Each checkpoint gets a fresh Claude instance (no context bloat)
2. **LISA_PLAN.md as State**: Checkpoints persist progress across instances
3. **REPL as Ground Truth**: Query REPL for actual state, not LLM memory
4. **Signs for Learnings**: Failures are recorded so future iterations don't repeat mistakes

## Commands

### /lisa-loop

Start an autonomous development loop:

```
/lisa-loop "Build a REST API for users with CRUD operations" --max-iterations 20
```

**Arguments:**
- `<prompt>` - The task description (required)
- `--max-iterations N` - Maximum iterations before stopping (default: 20)
- `--prd <path>` - Path to PRD or specification document

### /cancel-lisa

Stop the loop:

```
/cancel-lisa
```

## How It Works

```
/lisa-loop "Build user auth"
         │
         ▼
┌─────────────────────┐
│ 1. PLANNING PHASE   │  ◄── Current Claude session
│                     │
│ - Read PRD if exists│
│ - Propose checkpoints│
│ - Get user approval │
│ - Create LISA_PLAN.md│
└─────────┬───────────┘
          │
          ▼
┌─────────────────────┐
│ 2. RUN ORCHESTRATOR │  ◄── Calls lisa_run_orchestrator
│                     │
│ Spawns fresh Claude │
│ instance for each   │──────┐
│ checkpoint          │      │
└─────────────────────┘      │
                             │
    ┌────────────────────────┘
    │
    ▼
┌─────────────────────┐
│ ITERATION N         │  ◄── Fresh Claude instance
│ (Fresh Context)     │
│                     │
│ - Read LISA_PLAN.md │
│ - Read LISA_SIGNS.md│
│ - Work on checkpoint│
│ - Validate via REPL │
│ - Mark done if pass │
└─────────┬───────────┘
          │
          ▼
    Checkpoint done?
    ├── Yes → Next iteration (fresh instance)
    └── No  → Retry (fresh instance)
          │
          ▼
    All done? → COMPLETE
```

## Quick Start Instructions

When user runs `/lisa-loop`:

### Step 1: Planning Phase (Current Session)

**Check for existing plan:**
```
lisa_get_plan
```

**Check for PRD/documentation:**
```
Glob for: PRD.md, SPEC.md, REQUIREMENTS.md, docs/*.md
```

**Decision tree:**

| Situation | Action |
|-----------|--------|
| LISA_PLAN.md exists with pending checkpoints | Ask: resume or start fresh? |
| PRD exists, no plan | Read PRD, propose checkpoints |
| No plan, no PRD | Create plan from prompt |

### Step 2: Propose Plan to User

**ALWAYS show the proposed checkpoints before creating:**

```markdown
I've analyzed the requirements and propose these checkpoints:

1. **Create password hashing module** (src/auth/password.clj)
   - Acceptance: `(verify-password "test" (hash-password "test")) => true`

2. **Create JWT token module** (src/auth/jwt.clj)
   - Acceptance: `(verify-token (create-token {:user-id 1})) => truthy`

Does this plan look right? I can adjust before starting the loop.
```

**Wait for user approval before proceeding.**

### Step 3: Create Plan and Start Orchestrator

After approval, create the plan:

```
lisa_create_plan with:
  title: "Build user authentication"
  checkpoints: [
    {description: "Create password hashing module",
     file: "src/auth/password.clj",
     acceptance: "(verify-password \"test\" (hash-password \"test\")) => true"},
    ...
  ]
```

Then **start the orchestrator**:

```
lisa_run_orchestrator with:
  max_iterations: 20
```

This spawns fresh Claude instances for each iteration. The orchestrator:
1. Reads LISA_PLAN.md to find current checkpoint
2. Spawns `claude -p <focused-prompt>` for that checkpoint
3. Waits for completion
4. Repeats until all checkpoints done or max iterations

### Step 4: Report Progress

The orchestrator logs to `.forj/logs/lisa/`. When it completes:

```
Lisa Loop complete!
- Iterations: 8
- All checkpoints: DONE
- Logs: .forj/logs/lisa/
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
| `lisa_run_orchestrator` | **Start the loop** (spawns fresh instances) |

### Signs (Learnings) Tools

| Tool | Purpose |
|------|---------|
| `lisa_append_sign` | Record a failure/learning |
| `lisa_get_signs` | Read signs from previous iterations |
| `lisa_clear_signs` | Clear signs file |

### Validation Tools

| Tool | Purpose |
|------|---------|
| `lisa_run_validation` | Run validation checks (REPL, Chrome, Judge) |
| `lisa_check_gates` | Check if gates pass before advancing |

### Loop Management

| Tool | Purpose |
|------|---------|
| `cancel_loop` | Cancel active loop |
| `loop_status` | Check loop iteration count |

## Signs (Guardrails)

Signs record failures that persist across iterations. When something goes wrong in iteration 3, future iterations can read it and avoid the same mistake.

### LISA_SIGNS.md Format

```markdown
# Lisa Loop Signs (Learnings)

## Sign 1 (Iteration 3, 2026-01-16T10:30:00Z)
**Checkpoint:** 2 - Create JWT module
**Issue:** Forgot to require clojure.string
**Fix:** Always check requires when adding string functions
**Severity:** error
```

### When to Append Signs

The spawned Claude instances should append signs when:
- REPL evaluation fails with a fixable error
- Validation check fails due to missing setup
- Common mistake that might recur

## The Fresh Instance Advantage

Each iteration gets a **fresh Claude context**:

| Layer | Persists Across Iterations? |
|-------|----------------------------|
| LISA_PLAN.md | ✅ Yes - progress tracking |
| LISA_SIGNS.md | ✅ Yes - learnings |
| Git/Files | ✅ Yes - code changes |
| REPL state | ✅ Yes - loaded namespaces |
| Claude context | ❌ No - fresh each time |

This prevents context bloat and hallucination from stale memory.

## For /cancel-lisa

Call:
```
cancel_loop
```

Or manually kill the orchestrator process.

## Example Session

```
> /lisa-loop "Build user authentication per PRD.md"

I found PRD.md. Let me analyze it for checkpoints...

I propose these checkpoints:
1. Create password hashing (src/auth/password.clj)
2. Create JWT tokens (src/auth/jwt.clj)
3. Create auth middleware (src/middleware/auth.clj)
4. Integration test

Does this look right?

> yes

Creating LISA_PLAN.md and starting orchestrator...

[Lisa] Iteration 1: Checkpoint 1 - Create password hashing
[Lisa] Checkpoint 1 complete
[Lisa] Iteration 2: Checkpoint 2 - Create JWT tokens
[Lisa] Iteration 2 failed - adding sign
[Lisa] Iteration 3: Checkpoint 2 - Create JWT tokens (retry)
[Lisa] Checkpoint 2 complete
...
[Lisa] All checkpoints complete!

Lisa Loop finished in 6 iterations.
```
