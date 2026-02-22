---
name: lisa-loop
description: REPL-driven autonomous development loops for Clojure. Spawns fresh instances per iteration with REPL validation for 10x faster feedback.
commands:
  - name: lisa-loop
    description: Start an autonomous development loop - spawns fresh instances per checkpoint
    args: "<prompt> [--max-iterations N]"
  - name: lisa-loop watch
    description: Watch the active Lisa loop - spawns background monitor that notifies on completion
    args: "[--interval N]"
  - name: cancel-lisa
    description: Cancel the active Lisa loop
---

# Lisa Loop - REPL-Driven Autonomous Development

Lisa Loop is forj's native autonomous development loop. It spawns **fresh instances for each iteration**, using LISA_PLAN.edn for state and REPL for validation.

## Key Concepts

1. **Fresh Context Per Iteration**: Each checkpoint gets a fresh instance (no context bloat)
2. **LISA_PLAN.edn as State**: Checkpoints persist progress across instances
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

### /lisa-loop watch

Monitor the loop and get notified on completion:

```
/lisa-loop watch
/lisa-loop watch --interval 60
```

**Arguments:**
- `--interval N` - Check every N seconds (default: 30)

**This will:**
1. Show current status immediately via `lisa_watch`
2. Spawn a background monitor agent that polls at intervals
3. Notify you when the loop completes with a full summary
4. Tell you the log file path for live tailing

**Example output:**
```
[Lisa Watch] Current Status:

┌────────────┬─────────────────────────────────┬────────┐
│ Checkpoint │           Description           │ Status │
├────────────┼─────────────────────────────────┼────────┤
│ 1          │ Create password hashing         │ Done   │
│ 2          │ Create JWT tokens               │ Done   │
│ 3          │ Create auth middleware          │ In Progress │
│ 4          │ Integration test                │ Pending │
└────────────┴─────────────────────────────────┴────────┘
Progress: 2/4 (50%)

Background monitor started (checking every 30s).
You'll be notified when the loop completes.

For live updates, tail the log in another terminal:
  tail -f .forj/logs/lisa/orchestrator-20260128-102332.log
```

**On completion, the monitor reports:**
```
Lisa Loop complete!

Final Status: COMPLETE
Checkpoints: 4/4 (100%)
Signs/Learnings: None

All checkpoints:
  1. Create password hashing - Done
  2. Create JWT tokens - Done
  3. Create auth middleware - Done
  4. Integration test - Done
```

## How to implement /lisa-loop watch

When user runs `/lisa-loop watch`:

**Step 1: Show immediate status**
```
lisa_watch  → Display current checkpoint table
```

**Step 2: Find orchestrator log**
```
Glob for: .forj/logs/lisa/orchestrator-*.log
Get the most recent one for the tail command
```

**Step 3: Tell user about live tailing option**
```
Tell user:
- For live updates: tail -f <log-path>
- You'll also get periodic status updates here
```

**Step 4: Spawn a background check**

Use your platform's background task mechanism to periodically check the plan status.
Read LISA_PLAN.edn, check how many checkpoints are `:done` vs total, and report.

**Step 5: React to status**

1. Read the status report
2. Tell the user the current status (X/Y checkpoints, current checkpoint name)
3. If status is `:complete` → announce "Lisa Loop complete!" and stop
4. If status is `:in-progress` → schedule another check

## How It Works

```
/lisa-loop "Build user auth"
         │
         ▼
┌─────────────────────┐
│ 1. PLANNING PHASE   │  ◄── Current session
│                     │
│ - Read PRD if exists│
│ - Propose checkpoints│
│ - Get user approval │
│ - Create LISA_PLAN.edn│
└─────────┬───────────┘
          │
          ▼
┌─────────────────────┐
│ 2. RUN ORCHESTRATOR │  ◄── Calls lisa_run_orchestrator
│                     │
│ Spawns fresh        │
│ instance for each   │──────┐
│ checkpoint          │      │
└─────────────────────┘      │
                             │
    ┌────────────────────────┘
    │
    ▼
┌─────────────────────┐
│ ITERATION N         │  ◄── Fresh instance
│ (Fresh Context)     │
│                     │
│ - Read LISA_PLAN.edn│
│ - Read embedded signs│
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
| LISA_PLAN.edn exists with pending checkpoints | Ask: resume or start fresh? |
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

### Step 3: Create Plan and Start Execution

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

**Large plans (>10 checkpoints):** Create the plan with the first few checkpoints, then use `lisa_add_checkpoint` to add the rest individually. This avoids payload size issues with `lisa_create_plan`.

Then **start the orchestrator**:

```
lisa_run_orchestrator with:
  max_iterations: 20
```

This spawns fresh instances for each iteration. The orchestrator:
1. Reads LISA_PLAN.edn to find current checkpoint
2. Spawns a focused instance for that checkpoint
3. Waits for completion
4. Repeats until all checkpoints done or max iterations

### Step 4: Report Progress

The orchestrator logs to `.forj/logs/lisa/`. When it completes:

```
Lisa Loop finished in 6 iterations.
- All checkpoints: DONE
- Logs: .forj/logs/lisa/
```

## LISA_PLAN.edn Format

```clojure
{:title "Build user authentication"
 :status :in-progress
 :checkpoints
 [{:id :password-hashing
   :description "Create password hashing module"
   :file "src/auth/password.clj"
   :acceptance "(verify-password \"test\" (hash-password \"test\")) => true"
   :status :done
   :completed "2026-01-16T10:30:00Z"}
  {:id :jwt-tokens
   :description "Create JWT token module"
   :file "src/auth/jwt.clj"
   :acceptance "(verify-token (create-token {:user-id 1})) => {:user-id 1}"
   :status :in-progress
   :started "2026-01-16T10:35:00Z"}
  {:id :auth-middleware
   :description "Create auth middleware"
   :file "src/middleware/auth.clj"
   :acceptance "Middleware extracts user from valid token"
   :status :pending}]
 :signs []
 :created "2026-01-16T10:00:00Z"}
```

## Tools Reference

### Planning Tools

| Tool | Purpose |
|------|---------|
| `lisa_create_plan` | Create LISA_PLAN.edn with checkpoints |
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

Signs record failures that persist across iterations. When something goes wrong in iteration 3, future iterations can read it and avoid the same mistake. Signs are embedded directly in the LISA_PLAN.edn file.

### Signs Format (embedded in LISA_PLAN.edn)

```clojure
{:title "Build user authentication"
 :status :in-progress
 :checkpoints [...]
 :signs
 [{:checkpoint :jwt-tokens
   :iteration 3
   :timestamp "2026-01-16T10:30:00Z"
   :issue "Forgot to require clojure.string"
   :fix "Always check requires when adding string functions"
   :severity :error}]
 :created "2026-01-16T10:00:00Z"}
```

### When to Append Signs

The spawned instances should append signs when:
- REPL evaluation fails with a fixable error
- Validation check fails due to missing setup
- Common mistake that might recur

## The Fresh Instance Advantage

Each iteration gets a **fresh context**:

| Layer | Persists Across Iterations? |
|-------|----------------------------|
| LISA_PLAN.edn | Yes - progress tracking & learnings |
| Git/Files | Yes - code changes |
| REPL state | Yes - loaded namespaces |
| LLM context | No - fresh each time |

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

Creating LISA_PLAN.edn and starting orchestrator...

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
