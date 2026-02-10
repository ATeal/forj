# Agent Teams Integration Design

Exploring how forj's Lisa Loop orchestrator could leverage Claude Code's Agent Teams feature for parallel checkpoint execution.

## Current Lisa Architecture

### Core Components

Lisa Loop is forj's autonomous development orchestrator. It manages multi-checkpoint development tasks by spawning fresh Claude Code instances for each iteration.

**Key files:**
- `packages/forj-mcp/src/forj/lisa/orchestrator.clj` - Main orchestration loop
- `packages/forj-mcp/src/forj/lisa/plan_edn.clj` - Plan state management

### Execution Model

```
LISA_PLAN.edn (state)
       |
       v
+------------------+
|   Orchestrator   |  <-- Babashka process
+------------------+
       |
       | spawns via `claude -p`
       v
+------------------+
| Claude Instance  |  <-- Fresh context per iteration
+------------------+
       |
       | completes checkpoint
       v
+------------------+
|   Orchestrator   |  <-- Reads result, updates plan
+------------------+
       |
       v
   Next checkpoint...
```

### Current Parallel Mode

Lisa already supports parallel checkpoint execution via `run-loop-parallel!`:

1. Reads `LISA_PLAN.edn` to find checkpoints with satisfied dependencies
2. Spawns up to `max-parallel` Claude processes (default: 3)
3. Each process works on an independent checkpoint
4. Orchestrator polls for completion, handles idle timeouts
5. Completed checkpoints unlock dependent work

**Limitations of current approach:**
- No inter-process communication between parallel checkpoints
- Each Claude instance is fully isolated
- Orchestrator manages all coordination externally
- No shared task list between workers
- Cannot dynamically rebalance work

### Plan Format (EDN)

```clojure
{:title "Build user authentication"
 :status :in-progress
 :checkpoints
 [{:id :password-hashing
   :status :done
   :description "Create password hashing module"
   :file "src/auth/password.clj"
   :gates ["repl:(verify-password \"test\" (hash-password \"test\"))"]
   :completed "2026-01-19T10:00:00Z"}
  {:id :jwt-tokens
   :status :in-progress
   :depends-on [:password-hashing]
   :description "Create JWT token module"
   :file "src/auth/jwt.clj"}]
 :signs [...]}
```

## Claude Code Agent Teams

### Overview

Agent Teams (introduced Feb 6, 2026 with Opus 4.6) enable coordinated multi-agent development. One session acts as team lead, spawning teammates that work independently with shared task coordination.

**Enable via:**
```json
{
  "env": {
    "CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS": "1"
  }
}
```

### Architecture

```
+------------------+
|    Team Lead     |  <-- Coordinates work, spawns teammates
+------------------+
    |   |   |
    v   v   v
+------+ +------+ +------+
|  T1  | |  T2  | |  T3  |   <-- Independent Claude instances
+------+ +------+ +------+
    \      |      /
     \     |     /
      v    v    v
+------------------+
|   Shared Tasks   |  ~/.claude/tasks/{team}/
+------------------+
        |
        v
+------------------+
|     Mailbox      |  Inter-agent messaging
+------------------+
```

### Core Primitives

| Tool | Purpose |
|------|---------|
| `TeamCreate` | Initialize team directory and config |
| `TaskCreate` | Define work units as JSON on disk |
| `TaskUpdate` | Claim and complete tasks |
| `TaskList` | Query all tasks with status |
| `Task` (with team_name) | Spawn teammate into existing team |
| `SendMessage` | Direct inter-agent communication |
| `TeamDelete` | Clean up team resources |

### Task Format

Tasks persist as JSON in `~/.claude/tasks/{team_name}/`:

```json
{
  "id": "unique-id",
  "subject": "Create JWT module",
  "description": "Detailed instructions...",
  "status": "pending|in_progress|completed",
  "owner": "teammate-name",
  "depends_on": ["other-task-id"]
}
```

### Messaging Types

- `message` - Direct peer-to-peer
- `broadcast` - One agent to all teammates
- `shutdown_request` / `shutdown_response` - Graceful teardown
- `plan_approval_response` - Quality gates

### Key Characteristics

1. **Independent contexts**: Each teammate has its own context window
2. **Shared state via files**: Task list on disk, not in memory
3. **Self-claiming work**: Teammates can pick up unassigned tasks
4. **Inter-agent discussion**: Teammates can message each other
5. **Lead orchestration**: One session coordinates the team

## Proposed Integration

### Integration Strategy

Rather than replacing Lisa's orchestrator, we integrate Agent Teams as an **optional execution mode** for parallel checkpoints.

**Why hybrid approach:**
- Lisa already handles plan management, signs, analytics
- Agent Teams adds inter-agent communication Lisa lacks
- Gradual adoption without breaking existing workflows
- Can fall back to current mode if teams disabled

### Architecture: Lisa + Agent Teams

```
LISA_PLAN.edn (source of truth)
       |
       v
+----------------------+
|   Lisa Orchestrator  |  <-- Reads plan, manages lifecycle
+----------------------+
       |
       | creates team, maps checkpoints to tasks
       v
+----------------------+
|  Claude Team Lead    |  <-- Coordinates checkpoint execution
+----------------------+
    |       |       |
    v       v       v
+------+ +------+ +------+
| Auth | | API  | | Test |   <-- Teammates per checkpoint
+------+ +------+ +------+
    \       |       /
     \      |      /
      v     v     v
+----------------------+
|   Agent Team Tasks   |  ~/.claude/tasks/lisa-{plan-id}/
+----------------------+
       |
       | completion signals
       v
+----------------------+
|   Lisa Orchestrator  |  <-- Updates LISA_PLAN.edn
+----------------------+
```

### Mapping Concepts

| Lisa Concept | Agent Teams Equivalent |
|--------------|------------------------|
| `LISA_PLAN.edn` | Team configuration source |
| Checkpoint | Task |
| `:depends-on` | Task dependencies |
| `:status :in-progress` | Task `in_progress` + owner |
| Signs | Broadcast messages to team |
| Orchestrator | Team Lead role |

### Implementation Phases

#### Phase 1: Team Lifecycle Management

Add functions to create/destroy Agent Teams from Lisa context:

```clojure
(defn create-team-for-plan!
  "Create an Agent Team from a Lisa plan."
  [project-path plan]
  (let [team-name (str "lisa-" (hash (:title plan)))
        checkpoints (plan-edn/ready-checkpoints plan)]
    ;; Create team via Claude Code
    ;; Map checkpoints to tasks with dependencies
    ;; Return team handle
    ))

(defn sync-plan-to-tasks!
  "Sync LISA_PLAN.edn state to Agent Team tasks."
  [project-path plan team-name]
  ;; Create/update task files for each checkpoint
  ;; Preserve dependency graph
  )

(defn sync-tasks-to-plan!
  "Sync completed Agent Team tasks back to LISA_PLAN.edn."
  [project-path team-name]
  ;; Read task completion status
  ;; Update checkpoint status in plan
  ;; Handle signs/learnings from messages
  )
```

#### Phase 2: Team Lead Prompt Generation

The Team Lead needs context about:
- The Lisa plan structure
- REPL validation requirements
- How to mark checkpoints complete via `lisa_mark_checkpoint_done`
- Sign recording for failures

```clojure
(defn build-team-lead-prompt
  "Build the system prompt for the Agent Team lead."
  [plan signs-content]
  (str "# Lisa Loop Team Lead\n\n"
       "You are coordinating parallel checkpoint execution.\n\n"
       "## Plan: " (:title plan) "\n\n"
       "## Your Role\n"
       "1. Spawn teammates for ready checkpoints\n"
       "2. Monitor progress via task list\n"
       "3. Synthesize results when checkpoints complete\n"
       "4. Handle failures by recording signs\n\n"
       "## Checkpoint Completion Protocol\n"
       "When a teammate reports completion:\n"
       "1. Verify their REPL validation passed\n"
       "2. Use `lisa_mark_checkpoint_done` MCP tool\n"
       "3. Check for newly unblocked checkpoints\n\n"
       (when signs-content
         (str "## Previous Learnings\n" signs-content))))
```

#### Phase 3: Teammate Coordination

Each teammate works on a specific checkpoint:

```clojure
(defn build-teammate-prompt
  "Build spawn prompt for a checkpoint teammate."
  [checkpoint plan-context]
  (str "# Checkpoint: " (:id checkpoint) "\n\n"
       "**Task:** " (:description checkpoint) "\n"
       (when (:file checkpoint)
         (str "**File:** " (:file checkpoint) "\n"))
       "\n## Requirements\n"
       "1. Complete this checkpoint only\n"
       "2. Validate via REPL before marking complete\n"
       "3. Message the lead when done or blocked\n"
       "4. If blocked, explain what's needed\n"))
```

#### Phase 4: Message-to-Signs Bridge

Leverage teammate messages for failure learning:

```clojure
(defn process-team-messages!
  "Process Agent Team messages and convert to Lisa signs."
  [project-path team-name]
  ;; Read messages from team mailbox
  ;; Filter for failure reports, blockers
  ;; Append as signs to LISA_PLAN.edn
  )
```

### New Orchestrator Mode

Add `--agent-teams` flag to orchestrator:

```clojure
(defn run-loop!
  [project-path & [{:keys [parallel agent-teams] :as opts}]]
  (cond
    agent-teams (run-loop-agent-teams! project-path opts)
    parallel (run-loop-parallel! project-path opts)
    :else (run-loop-sequential! project-path opts)))
```

The `run-loop-agent-teams!` function:

```clojure
(defn run-loop-agent-teams!
  "Run Lisa Loop using Claude Code Agent Teams for parallel execution."
  [project-path & [{:keys [max-iterations max-parallel on-complete] :as opts}]]
  (let [plan (plan-edn/read-plan project-path)
        team-name (create-team-for-plan! project-path plan)]
    (try
      ;; Spawn team lead with plan context
      ;; Lead spawns teammates for ready checkpoints
      ;; Poll for task completions
      ;; Sync completed tasks back to LISA_PLAN.edn
      ;; Handle signs from team messages
      ;; Continue until all done or max iterations
      (finally
        (cleanup-team! team-name)))))
```

### Benefits Over Current Parallel Mode

| Feature | Current Parallel | Agent Teams |
|---------|------------------|-------------|
| Inter-worker communication | None | Direct messaging |
| Dynamic work rebalancing | No | Self-claiming tasks |
| Shared context | None | Team lead synthesis |
| Failure coordination | Orchestrator only | Broadcast to team |
| Quality gates | Per-checkpoint | Plan approval system |

### Potential Challenges

1. **Two sources of truth**: LISA_PLAN.edn vs Agent Team tasks
   - Solution: Lisa plan remains authoritative; team tasks are ephemeral
   - Sync on each orchestrator poll cycle

2. **Team lead context bloat**: Lead accumulates context from all teammates
   - Solution: Use delegate mode for lead (coordination only)
   - Teammates report minimal completion status

3. **Session resumption**: Agent Teams don't support `/resume` well
   - Solution: Lisa handles persistence; teams are recreated on resume

4. **Token costs**: Agent Teams use significantly more tokens
   - Solution: Make opt-in, document cost tradeoffs

5. **Experimental status**: Agent Teams are still experimental
   - Solution: Feature flag, graceful fallback to current parallel mode

## Implementation Plan

### Milestone 1: Research Validation (1-2 days)

- [ ] Manually test Agent Teams with Lisa-style checkpoints
- [ ] Verify MCP tools work from team lead context
- [ ] Document any blockers or limitations discovered

### Milestone 2: Team Lifecycle (2-3 days)

- [ ] Add `forj.lisa.agent-teams` namespace
- [ ] Implement `create-team-for-plan!` and `cleanup-team!`
- [ ] Add `--agent-teams` flag to orchestrator
- [ ] Basic integration test with simple 2-checkpoint plan

### Milestone 3: Task Synchronization (2-3 days)

- [ ] Implement bidirectional sync between plan and tasks
- [ ] Handle dependency propagation correctly
- [ ] Add checkpoint completion via team lead
- [ ] Verify signs are captured from team messages

### Milestone 4: Team Lead Orchestration (3-4 days)

- [ ] Build team lead prompt with full Lisa context
- [ ] Implement teammate spawning for ready checkpoints
- [ ] Add delegate mode enforcement for lead
- [ ] Handle teammate failures and retries

### Milestone 5: Production Hardening (2-3 days)

- [ ] Add feature flag and environment variable check
- [ ] Implement graceful fallback to parallel mode
- [ ] Add analytics and cost tracking for team mode
- [ ] Update SKILL.md documentation

### Total Estimate: 10-15 days

## Open Questions

1. **Should team lead use forj MCP tools directly?**
   - Team lead could call `lisa_mark_checkpoint_done` itself
   - Or teammates message completion, lead updates plan
   - Former is simpler; latter matches Agent Teams pattern

2. **How to handle REPL validation in team context?**
   - Each teammate has its own nREPL connection
   - Or shared REPL with namespace isolation
   - Current Lisa model: independent REPL per checkpoint

3. **What's the right team size per plan?**
   - Current parallel default: 3 concurrent
   - Agent Teams work best with 3-5 teammates
   - Consider adaptive sizing based on checkpoint count

4. **Should signs use broadcast or message?**
   - Broadcast: all teammates learn immediately
   - Message to lead: lead decides what to share
   - Hybrid: critical signs broadcast, minor ones to lead

## References

- [Claude Code Agent Teams Documentation](https://code.claude.com/docs/en/agent-teams)
- [Claude Code Agent Teams Architecture](https://alexop.dev/posts/from-tasks-to-swarms-agent-teams-in-claude-code/)
- [Agent Teams Deep Dive](https://addyosmani.com/blog/claude-code-agent-teams/)
- [Lisa Loop SKILL.md](../packages/forj-skill/lisa-loop/SKILL.md)
- [Lisa Loop Review Proposal](./lisa-loop-review.md)
