# Lisa Loop Improvement Proposal

## Research Summary

### Context Rotation Philosophy

Based on established agentic loop methodologies, the pattern has **three distinct phases**:

1. **Phase 1 - Requirements Definition**: Human + LLM collaborate to define Jobs-to-Be-Done (JTBDs), break into topics, generate `specs/*.md` files as single source of truth.

2. **Phase 2 - Planning**: Dedicated planning prompt performs gap analysis between specs and existing code. Outputs `IMPLEMENTATION_PLAN.md` with prioritized task list. **No implementation occurs**.

3. **Phase 3 - Building**: One task per loop iteration. Agent reads plan, implements one item, updates plan, commits, exits. Next iteration starts fresh.

### Core Principles We're Missing

| Principle | Context Rotation Pattern | Our Lisa Loop |
|-----------|--------------------------|---------------|
| **Context Isolation** | Fresh context each iteration, state in files/git | Accumulates context until compaction |
| **1 Task Per Loop** | "Tight tasks = 100% smart zone" | Re-runs entire prompt each time |
| **Deterministic Planning** | IMPLEMENTATION_PLAN.md drives work | No planning phase |
| **Spec Files** | `specs/*.md` as source of truth | Requirements in prompt only |
| **Guardrails/Signs** | Record failures for future iterations | No failure memory |
| **Backpressure Gates** | Tests/lints must pass before commit | REPL validation only |

### What Makes Context Rotation Effective

> "State lives in files and git, not in the LLM's memory."

The technique solves the "malloc/free problem" - traditional conversations accumulate context pollution. Context rotation resets by spawning fresh instances while preserving progress in version control.

Key success factors:
1. **Machine-verifiable success criteria** (not subjective goals)
2. **Learning persistence** through guardrails/signs files
3. **Context rotation** before degradation occurs
4. **Backpressure mechanisms** that prevent incomplete work

### Criticisms of Simpler Implementations

Common issues with naive loop implementations:

1. **Context bloat**: Without fresh starts, accumulated context degrades performance
2. **Semantic filtering unreliability**: Asking AI to "filter to feature X" at runtime achieves only 70-80% accuracy. Context rotation scopes work *at plan creation*
3. **Hallucination accumulation**: Continuous context prevents self-correction
4. **Missing backpressure**: Simple loops accept incomplete work

---

## Current Lisa Loop Analysis

### What We Do Well

1. **REPL-driven validation** - Our key differentiator. Faster feedback than test-based loops
2. **Rich information** - Actual data, not just pass/fail
3. **Between-iteration injection** - `validate_changed_files` runs automatically
4. **Clojure-native** - Uses the REPL as backpressure mechanism

### What We're Missing

1. **No planning phase** - We jump straight to building
2. **No task decomposition** - Entire prompt runs each iteration
3. **No spec files** - Requirements exist only in the prompt
4. **No context rotation** - We accumulate until Claude compacts
5. **No failure memory** - Each iteration doesn't learn from previous failures
6. **No structured checkpoints** - Progress isn't tracked in files

---

## Proposed Improvements

### Change 1: Add Planning Phase

**Why**: Planning creates deterministic continuation. Without a plan, each iteration must re-parse the entire task.

**Proposed**:
- Add `/lisa-plan` command (or make it automatic in `/lisa-loop`)
- Generate `LISA_PLAN.md` with:
  - Task breakdown into checkpoints
  - Priority ordering
  - Acceptance criteria for each checkpoint
- Building phase reads plan, picks next incomplete checkpoint

**Example LISA_PLAN.md**:
```markdown
# Lisa Loop Plan: Build user authentication

## Status: IN_PROGRESS
## Current Checkpoint: 2

## Checkpoints

### 1. [DONE] Create password hashing module
- File: src/myapp/auth/password.clj
- Acceptance: `(verify-password "test" (hash-password "test"))` => true
- Completed: 2026-01-15T10:30:00Z

### 2. [IN_PROGRESS] Create JWT token module
- File: src/myapp/auth/jwt.clj
- Acceptance: `(verify-token (create-token {:user-id 1}))` => {:user-id 1}
- Started: 2026-01-15T10:31:00Z

### 3. [PENDING] Create auth middleware
- File: src/myapp/middleware/auth.clj
- Acceptance: Middleware extracts user from valid token
```

### Change 2: One Checkpoint Per Iteration

**Why**: "Tight tasks + 1 task per loop = 100% smart zone context utilization." Working on the entire task each iteration wastes context on already-complete work.

**Proposed**:
- Each iteration focuses on ONE checkpoint from LISA_PLAN.md
- Stop hook reads plan, identifies current checkpoint
- Continuation message includes only:
  - Current checkpoint details
  - REPL validation results for that checkpoint
  - "Mark as DONE when checkpoint passes"

**Benefit**: Claude's context is focused entirely on the current checkpoint, not re-processing completed work.

### Change 3: Add Guardrails/Signs File

**Why**: "The guardrails system records failures as 'Signs' that future iterations read, preventing repeated mistakes across context rotations."

**Proposed**:
- Create `LISA_SIGNS.md` in project root
- Stop hook appends failures/learnings after each iteration
- Each iteration reads signs before starting work

**Example LISA_SIGNS.md**:
```markdown
# Lisa Loop Signs (Learnings)

## Sign 1 (Iteration 3)
**Issue**: Forgot to require clojure.string in namespace
**Fix**: Always check requires when adding string functions

## Sign 2 (Iteration 5)
**Issue**: REPL connection failed - port not found
**Fix**: Check nREPL is running with discover_repls before validation
```

**Hook change**: When validation fails, extract the error and append to LISA_SIGNS.md.

### Change 4: Spec Files for Complex Tasks

**Why**: "Specs become the single source of truth per topic." This prevents requirements drift across iterations.

**Proposed**:
- For `/lisa-loop` with complex multi-file tasks, create `lisa-specs/` directory
- Each spec file covers one topic of concern
- Specs are human-curated during planning phase
- Building phase references specs, not original prompt

**When to use**: Optional. For simple tasks, the LISA_PLAN.md is sufficient. For complex features spanning multiple files, create specs.

### Change 5: Explicit Backpressure Gates

**Why**: "Required tests prevent placeholder implementations." Our REPL validation is good but not mandatory.

**Proposed**:
- Add `gates` to each checkpoint in LISA_PLAN.md:
  ```markdown
  ### 2. Create JWT token module
  - Gates:
    - [ ] Namespace reloads without error
    - [ ] Comment block evaluates successfully
    - [ ] `(verify-token (create-token {:user-id 1}))` returns map with :user-id
  ```
- Stop hook verifies gates before marking checkpoint complete
- Cannot advance to next checkpoint until gates pass

### Change 6: True Outer Loop with Fresh Context

**Why**: Context rotation deliberately spawns fresh LLM instances before degradation occurs. Simple "restart at 70%" suggestions punt the problem to the user.

**Proposed**: Run Claude Code instances as background processes, managed by a skill/orchestrator.

**Architecture**:
```
/lisa-loop "Build user auth" --max-iterations 20
                │
                ▼
        ┌───────────────────┐
        │  Lisa Orchestrator │  (Babashka daemon or skill)
        │                    │
        │  - Spawns Claude   │
        │  - Monitors output │
        │  - Checks plan     │
        │  - Respawns fresh  │
        └───────────────────┘
                │
        ┌───────┴───────┐
        ▼               ▼
   ┌─────────┐    ┌─────────┐
   │ Claude 1│    │ Claude 2│    ...
   │ (fresh) │    │ (fresh) │
   └─────────┘    └─────────┘
        │               │
        └───────┬───────┘
                ▼
         LISA_PLAN.md + REPL
         (shared persistent state)
```

**How it works**:
```bash
# Orchestrator spawns Claude in background
claude --print "Work on next checkpoint in LISA_PLAN.md" \
       --allowedTools "Bash,Edit,Read,mcp__forj__*" \
       --max-turns 50 \
       2>&1 | tee .forj/logs/lisa-1.log &

# Orchestrator monitors, waits for exit
# On exit: check LISA_PLAN.md for completion
# If incomplete: spawn fresh instance
```

**Advantages over stop hooks**:
- True context isolation per iteration
- State in files + REPL, not LLM memory
- Context rotation philosophy
- Can add spending caps, token tracking
- Each instance gets 100% "smart zone" context

---

### Change 7: REPL as Persistent State Layer

**Why**: Clojure's REPL is a unique advantage. It's a persistent state machine that survives across Claude instances.

**State layers**:

| Layer | Persists Across | What It Holds |
|-------|-----------------|---------------|
| Git | Everything | Committed code |
| LISA_PLAN.md | Claude instances | Task progress, checkpoints |
| **REPL** | Claude instances | Loaded namespaces, running servers, defined vars |
| Claude context | Nothing (fresh each time) | Current reasoning |

**The REPL as ground truth**:

Each fresh Claude instance can **interrogate the REPL** to understand current state rather than trusting its own memory:

```clojure
;; What's already defined?
(ns-publics 'myapp.auth)
;; => {hash-password #'myapp.auth/hash-password, verify-password #'...}

;; Is the server running?
@myapp.core/server
;; => #object[org.eclipse.jetty.server.Server ...]

;; What's the current DB state?
(jdbc/execute! db ["SELECT count(*) FROM users"])
;; => [{:count 5}]
```

**Flow with REPL state**:

```
Iteration 1:
  Claude writes hash-password, evaluates in REPL
  REPL now has hash-password loaded
  Claude exits, updates LISA_PLAN.md

Iteration 2 (fresh context):
  Claude reads LISA_PLAN.md: "Checkpoint 1 DONE"
  Claude queries REPL: (ns-publics 'myapp.auth) → sees hash-password exists
  Claude knows it's real, not hallucinated
  Claude continues with checkpoint 2
```

**REPL state snapshot tool**:

Add an MCP tool that captures current REPL state for injection into prompts:

```clojure
(defn snapshot-repl-state []
  {:loaded-namespaces (map ns-name (all-ns))
   :project-vars (ns-publics 'myapp.core)
   :server-running? (some? @myapp.core/server)
   :repl-ports (discover-repls)
   :last-eval-results (:results (read-eval-history))})
```

This snapshot gets injected into each fresh Claude instance - it knows exactly what's live without needing memory.

---

### Change 8: Pluggable Validation Methods

**Why**: Good agentic loops support configurable validation - both programmatic (tests, lints) AND non-deterministic (LLM-as-judge for aesthetics). Different tasks need different validation.

**Validation methods**:

| Task Type | Validation Method |
|-----------|-------------------|
| Backend logic | REPL eval, comment blocks |
| API endpoints | curl/HTTP checks, REPL |
| Frontend UI | Chrome MCP / Playwright screenshots |
| Styling/UX | LLM-as-judge ("Does this look professional?") |
| Documentation | LLM-as-judge ("Is this clear?") |
| Data pipeline | REPL eval, query results |

**Specify validation per checkpoint in LISA_PLAN.md**:

```markdown
# Lisa Loop Plan: Build login page

## Validation Strategy
Primary: Chrome MCP screenshots + REPL state
Subjective: LLM-as-judge for visual quality

## Checkpoints

### 1. [PENDING] Create login form component
- File: src/web/views/login.cljs
- Validation:
  - repl: `(render-to-string [login-form])` returns hiccup with :form element
  - chrome: Navigate to /login, screenshot form visible
  - judge: "Form has email/password fields, clean layout, visible submit button"

### 2. [PENDING] Add form validation
- Validation:
  - repl: `(validate-login {:email "" :password ""})` returns error map
  - chrome: Submit empty form, verify error messages display
  - judge: "Error messages are clear and positioned near fields"

### 3. [PENDING] Style the form
- Validation:
  - chrome: Screenshot at mobile (375px) and desktop (1200px) widths
  - judge: "Looks professional, consistent spacing, responsive layout"
```

**Pluggable validation runner**:

```clojure
(defn run-checkpoint-validation [checkpoint]
  (let [{:keys [repl chrome judge]} (:validation checkpoint)]
    {:repl-results   (when repl (run-repl-validations repl))
     :chrome-results (when chrome (run-chrome-validations chrome))
     :judge-results  (when judge (run-llm-judge judge))}))
```

**LLM-as-judge pattern** (for subjective criteria):

Use binary pass/fail with explanation:
```
Judge prompt: "Does this login form look professional and polished?
              Answer only PASS or FAIL with one sentence explanation."

Response: "PASS - Clean layout with proper spacing and clear visual hierarchy."
```

This converges through iteration - if it fails, Claude fixes and retries.

**Validation strategy in plan header**:

```markdown
## Validation Strategy
- Primary: repl (always run REPL checks first - fastest)
- Secondary: chrome (for UI checkpoints only)
- Subjective: judge (for styling/UX checkpoints)
- Final: tests (run bb test before marking loop complete)
```

---

## Implementation Priority

| Change | Impact | Effort | Priority |
|--------|--------|--------|----------|
| 1. Planning Phase | High | Medium | **P0** |
| 2. One Checkpoint Per Iteration | High | Low | **P0** |
| 6. True Outer Loop (Fresh Context) | High | High | **P0** |
| 7. REPL as Persistent State | High | Medium | **P0** |
| 3. Guardrails/Signs File | Medium | Low | **P1** |
| 5. Explicit Backpressure Gates | Medium | Medium | **P1** |
| 8. Pluggable Validation | Medium | Medium | **P1** |
| 4. Spec Files | Low | Low | **P2** |

**Recommended approach**:
1. **Phase 1 (P0)**: Planning + One Checkpoint + Outer Loop + REPL State. These form the new architecture.
2. **Phase 2 (P1)**: Guardrails + Gates + Pluggable Validation. These add robustness.
3. **Phase 3 (P2)**: Spec files for complex tasks.

---

## Proposed New Flow

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
            │ - Gates       │
            └───────┬───────┘
                    │
                    ▼
            ┌───────────────┐
            │ BUILDING PHASE │◄────────────────┐
            │               │                  │
            │ Pick next     │                  │
            │ checkpoint    │                  │
            │ from plan     │                  │
            └───────┬───────┘                  │
                    │                          │
                    ▼                          │
            ┌───────────────┐                  │
            │ WORK ON       │                  │
            │ CHECKPOINT    │                  │
            │               │                  │
            │ - Write code  │                  │
            │ - REPL eval   │                  │
            │ - Claude exits│                  │
            └───────┬───────┘                  │
                    │                          │
                    ▼                          │
            ┌───────────────┐                  │
            │ STOP HOOK     │                  │
            │               │                  │
            │ - Read signs  │                  │
            │ - Validate    │                  │
            │ - Check gates │                  │
            │ - Update plan │                  │
            │ - Append signs│                  │
            └───────┬───────┘                  │
                    │                          │
           ┌────────┴────────┐                 │
           │                 │                 │
     Gates Pass?        Gates Fail?            │
           │                 │                 │
           ▼                 ▼                 │
    Mark checkpoint    Continue with          │
    DONE in plan       same checkpoint ───────┘
           │
           ▼
    More checkpoints?
           │
     ┌─────┴─────┐
     │           │
    Yes          No
     │           │
     ▼           ▼
   Loop       Complete!
  continues   Clear state
```

---

## Questions for Discussion

1. **Planning automation**: Should planning be automatic or a separate `/lisa-plan` command?

2. **Gate strictness**: Should we block advancement on failed gates, or just warn?

3. **Signs file format**: Markdown vs EDN for machine readability?

4. **Spec file threshold**: How many files/functions before specs are recommended?

5. **Context rotation**: ✅ Validated feasible - see spike results below.

---

## Spike: Outer Loop Validation (2026-01-16)

Validated that Claude Code can be orchestrated as background processes.

### Test Environment

```
/tmp/lisa-spike/
├── test-plan.md      # Simple checkpoint file
└── claude-output*.json  # Captured outputs
```

### Capabilities Tested

| Capability | Status | Command/Flag |
|------------|--------|--------------|
| Spawn Claude in background | ✅ | `claude -p "prompt" &` |
| Structured JSON output | ✅ | `--output-format json` |
| Detect process completion | ✅ | `ps -p $PID` returns exit when done |
| Read state files | ✅ | Works out of the box |
| Modify files | ✅ | Requires permission flags |
| Success/error status | ✅ | JSON: `is_error`, `subtype` |
| Session ID for debugging | ✅ | JSON: `session_id` |
| Cost tracking | ✅ | JSON: `total_cost_usd` |
| Turn counting | ✅ | JSON: `num_turns` |

### Key Findings

**1. Permission Handling**

Without permission flags, Edit tool is denied:
```json
"permission_denials": [
  {"tool_name": "Edit", "tool_use_id": "toolu_017...", ...}
]
```

Options for allowing edits:
- `--dangerously-skip-permissions` - Bypasses all checks (use in sandboxed environments)
- `--allowedTools "Bash,Edit,Read,Write,Glob,Grep,mcp__forj__*"` - Explicit allowlist

**2. JSON Output Structure**

```json
{
  "type": "result",
  "subtype": "success",
  "is_error": false,
  "result": "Checkpoint 1 complete",
  "session_id": "99f328f3-...",
  "num_turns": 5,
  "total_cost_usd": 0.218,
  "duration_ms": 15720,
  "permission_denials": []
}
```

**3. Process Monitoring**

```bash
# Start in background
claude -p "..." --output-format json > output.json 2>&1 &
PID=$!

# Check if running
ps -p $PID --no-headers && echo "running" || echo "done"

# Read result when done
jq '.result' output.json
```

**4. File Modification Test**

Claude successfully:
1. Read `test-plan.md`
2. Identified checkpoint 1 as `[IN_PROGRESS]`
3. Edited file to mark `[DONE]`
4. Returned confirmation message

### Production Orchestrator Pattern

```bash
#!/usr/bin/env bash
# lisa-orchestrator.sh

PLAN_FILE="LISA_PLAN.md"
MAX_ITERATIONS=20
ITERATION=0

while [ $ITERATION -lt $MAX_ITERATIONS ]; do
  ITERATION=$((ITERATION + 1))
  LOG_FILE=".forj/logs/lisa-iter-${ITERATION}.json"

  # Spawn fresh Claude instance
  # NOTE: --dangerously-skip-permissions is REQUIRED for non-interactive -p mode
  claude -p "Read $PLAN_FILE. Work on the current [IN_PROGRESS] checkpoint. \
             When complete, mark it [DONE] and output 'CHECKPOINT_COMPLETE'. \
             If all checkpoints done, output 'LOOP_COMPLETE'." \
    --output-format json \
    --dangerously-skip-permissions \
    --allowedTools "Bash,Edit,Read,Write,Glob,Grep,mcp__forj__*" \
    > "$LOG_FILE" 2>&1 &

  PID=$!

  # Wait for completion
  while ps -p $PID > /dev/null 2>&1; do
    sleep 5
  done

  # Check result
  RESULT=$(jq -r '.result' "$LOG_FILE")
  IS_ERROR=$(jq -r '.is_error' "$LOG_FILE")

  if [ "$IS_ERROR" = "true" ]; then
    echo "Iteration $ITERATION failed"
    # Could append to LISA_SIGNS.md here
    continue
  fi

  if [[ "$RESULT" == *"LOOP_COMPLETE"* ]]; then
    echo "Lisa loop completed after $ITERATION iterations"
    exit 0
  fi
done

echo "Max iterations reached"
```

### Babashka Implementation Notes

The actual implementation in `forj.lisa.orchestrator` uses `babashka.process`:

```clojure
;; Key insight: Pass prompt via stdin to avoid shell quoting issues
(p/process {:dir project-path
            :in prompt                    ; prompt via stdin
            :out (java.io.File. log-file) ; capture JSON output
            :err :stdout}
           "claude" "-p" "--output-format" "json"
           "--dangerously-skip-permissions"
           "--allowedTools" allowed-tools)
```

**Critical flags:**
- `--dangerously-skip-permissions` - REQUIRED for non-interactive mode
- `--output-format json` - Structured output for parsing
- `:in prompt` - Avoids shell escaping issues with complex prompts

### Considerations for Implementation

1. **Babashka orchestrator** - Rewrite above in bb for consistency with forj
2. **MCP tools** - Need `--mcp-config` to include forj MCP server
3. **REPL state** - Each iteration queries REPL for ground truth
4. **Cost tracking** - Sum `total_cost_usd` across iterations
5. **Guardrails** - Append failures to `LISA_SIGNS.md` between iterations

### Verdict

**Outer loop is feasible.** Claude Code's `-p` mode with JSON output provides everything needed for orchestration.

---

## Sources

- [UltraThink Documentation](https://claudelog.com/faqs/what-is-ultrathink/)
- [Extended Thinking Tips](https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/extended-thinking-tips)
