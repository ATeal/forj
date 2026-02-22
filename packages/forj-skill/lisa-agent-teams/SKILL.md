---
name: lisa-agent-teams
description: "Experimental: Run Lisa Loop checkpoints via Claude Code Agent Teams. You become team lead, coordinating parallel teammates with REPL-validated gates."
commands:
  - name: lisa-agent-teams
    description: Start Lisa Loop in Agent Teams mode - you coordinate parallel teammates
    args: "<prompt> [--max-iterations N]"
---

# Lisa Agent Teams - Parallel Checkpoint Execution (Experimental)

Run Lisa Loop checkpoints via Claude Code's native [Agent Teams](https://code.claude.com/docs/en/agent-teams) feature. Instead of spawning headless Claude instances sequentially, **you become the team lead**, creating and coordinating teammates that work on checkpoints in parallel.

This skill shares the same LISA_PLAN.edn format as `/lisa-loop`. The difference is execution: teammates work concurrently with REPL-validated gates instead of sequential orchestrator iterations.

## Prerequisites

Two environment variables must be set:

| Variable | Purpose | How to set |
|----------|---------|------------|
| `FORJ_AGENT_TEAMS` | forj opt-in | `export FORJ_AGENT_TEAMS=1` |
| `CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS` | Claude Code feature flag | Add to `~/.claude/settings.json` under `env` |

Both are set automatically by `bb install --agent-teams`. If either is missing when you invoke this skill, you'll get an error with setup instructions.

## How It Works

```
/lisa-agent-teams "Build user auth"
         │
         ▼
┌─────────────────────┐
│ 1. PLANNING PHASE   │  ◄── You (current session)
│                     │
│ - Read PRD if exists│
│ - Propose checkpoints│
│ - Get user approval │
│ - Create LISA_PLAN.edn│
└─────────┬───────────┘
          │
          ▼
┌─────────────────────┐
│ 2. TEAM SETUP       │  ◄── You become team lead
│                     │
│ - lisa_plan_to_tasks│
│ - TeamCreate        │
│ - TaskCreate (each) │
└─────────┬───────────┘
          │
          ▼
┌─────────────────────┐
│ 3. PARALLEL WORK    │  ◄── Teammates work concurrently
│                     │
│ Teammate A ──► CP 1 │
│ Teammate B ──► CP 2 │
│ Teammate C ──► CP 3 │
│                     │
│ Hooks auto-validate │
│ gates & redirect    │
└─────────┬───────────┘
          │
          ▼
┌─────────────────────┐
│ 4. COMPLETION       │
│                     │
│ All checkpoints done│
│ Report to user      │
└─────────────────────┘
```

## Commands

### /lisa-agent-teams

```
/lisa-agent-teams "Build a REST API for users with CRUD operations"
```

**Arguments:**
- `<prompt>` - The task description (required)
- `--prd <path>` - Path to PRD or specification document

## Step-by-Step Instructions

### Step 1: Verify Agent Teams Availability

Before planning, check that the feature is available:

```
lisa_plan_to_tasks with:
  path: "."
```

If this returns an error about missing env vars, tell the user what to set and stop.

### Step 2: Planning Phase

Same as standard Lisa Loop — propose checkpoints, get user approval.

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

**ALWAYS show proposed checkpoints and wait for user approval before creating.**

### Step 3: Create Plan

```
lisa_create_plan with:
  title: "Build user authentication"
  checkpoints: [
    {id: "password-hashing",
     description: "Create password hashing module",
     file: "src/auth/password.clj",
     acceptance: "(verify-password \"test\" (hash-password \"test\")) => true",
     gates: ["repl:(verify-password \"test\" (hash-password \"test\"))"]},
    ...
  ]
```

**Large plans (>10 checkpoints):** Create with the first few, then use `lisa_add_checkpoint` for the rest.

### Step 4: Convert Plan to Tasks

```
lisa_plan_to_tasks with:
  path: "."
```

This returns structured task data with:
- Subjects matching checkpoint IDs (e.g., "Checkpoint: password-hashing")
- Descriptions including REPL validation workflow and coordination guidance
- Dependency info for task ordering

### Step 5: Create Team and Assign Tasks

```
TeamCreate with:
  name: <team-name from lisa_plan_to_tasks>

For each task from lisa_plan_to_tasks:
  TaskCreate with:
    subject: <task subject>
    description: <task description>
    depends_on: <dependency subjects if any>
```

Then spawn teammates for ready checkpoints using the Task tool.

### Step 6: Coordinate as Team Lead

- Teammates work on checkpoints in parallel
- Each teammate has access to forj MCP tools (reload_namespace, repl_eval, etc.)
- The **TaskCompleted hook** automatically validates REPL gates and syncs completions to LISA_PLAN.edn
- The **TeammateIdle hook** automatically redirects idle teammates to the next ready checkpoint

**File conflict prevention:** When creating the team, tell teammates about shared files upfront. If multiple checkpoints touch the same file, use SendMessage to introduce workers and establish who owns which sections. Instruct workers to always `reload_namespace` before editing.

### Step 7: Monitor and Report

- Use `lisa_get_plan` or `lisa_watch` to check progress
- When all checkpoints are done, report completion to user

## Hook-Based Automation

Two hooks (installed by `bb install --agent-teams`) handle integration automatically:

**TaskCompleted** — fires when a teammate marks a task complete:
- Maps task subject back to LISA_PLAN.edn checkpoint
- Reloads checkpoint's file namespace in REPL (avoids stale state)
- Runs checkpoint gates (REPL validation) if defined
- Pass: marks checkpoint done in plan, allows completion (exit 0)
- Fail: rejects completion, feeds error back to teammate (exit 2)

**TeammateIdle** — fires when a teammate is about to go idle:
- Checks for ready checkpoints (pending with all dependencies met)
- If found: suggests next checkpoint, keeps teammate working (exit 2)
- If none: lets teammate go idle (exit 0)

## Tools Reference

| Tool | Purpose |
|------|---------|
| `lisa_create_plan` | Create LISA_PLAN.edn with checkpoints |
| `lisa_get_plan` | Read current plan status |
| `lisa_plan_to_tasks` | **Convert plan to Agent Teams task descriptions** |
| `lisa_mark_checkpoint_done` | Mark checkpoint complete (usually auto via hook) |
| `lisa_add_checkpoint` | Add checkpoint to running plan |
| `lisa_watch` | Check loop progress |
| `lisa_append_sign` | Record a failure/learning |
| `lisa_get_signs` | Read signs from previous iterations |
| `lisa_run_validation` | Run validation checks manually |
| `lisa_check_gates` | Check if gates pass |

## Comparison with /lisa-loop

| Aspect | /lisa-loop | /lisa-agent-teams |
|--------|-----------|-------------------|
| Execution | Sequential headless instances | Parallel teammates |
| You are | Observer (orchestrator runs) | Team lead (you coordinate) |
| Concurrency | One checkpoint at a time | Multiple checkpoints at once |
| Validation | Per-iteration REPL check | Per-task via TaskCompleted hook |
| Best for | Linear dependency chains | Independent/parallel workloads |
| Stability | Stable | Experimental |
