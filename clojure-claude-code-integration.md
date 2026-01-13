# Clojure + Claude Code: REPL-Driven LLM Development

**Project name**: `forj`

*Forge your code in the REPL fire. Iterate, shape, refine.*

The "j" fits naturally (like "forge" → "forj"), follows Clojurian naming conventions (minimal, drop unnecessary letters), and evokes the blacksmith metaphor — hammering hot metal into shape through rapid iteration.

**Components**:
- `clj-init` — Project scaffolding tool (standalone bb)
- `forj-mcp` — MCP server for REPL connectivity + lifecycle
- `forj-hooks` — bb hook scripts for Claude Code integration
- `forj-skill` — Claude Code skill for Clojure development

---

## Table of Contents

- [Vision](#vision)
- [Core Thesis: Why REPL + LLM is Powerful](#core-thesis-why-repl--llm-is-powerful)
  - [The Training Data Paradox](#the-training-data-paradox)
  - [REPL Validation vs Test Validation (Lisa Loop)](#repl-validation-vs-test-validation)
  - [The Multi-Host Advantage](#the-multi-host-advantage)
- [Problem Analysis](#problem-analysis)
  - [Three Core Challenges](#three-core-challenges)
- [Claude Code Feature Inventory](#claude-code-feature-inventory)
  - [Available Features](#available-features)
  - [Hook Events](#hook-events)
  - [Key Insight: Skills vs Slash Commands vs Hooks](#key-insight-skills-vs-slash-commands-vs-hooks)
- [Proposed Architecture](#proposed-architecture)
  - [Component Overview](#component-overview)
  - [MCP Server](#1-mcp-server-clojure-repl-mcp)
  - [Hooks Configuration](#2-hooks-configuration)
  - [Hook Scripts (Babashka)](#3-hook-scripts-babashka)
  - [Skill Definition](#4-skill-definition)
  - [CLAUDE.md Addition](#5-claudemd-addition)
- [Project Initialization / Scaffolding](#project-initialization--scaffolding)
  - [Interactive Flow](#interactive-flow)
  - [Project Templates](#project-templates)
  - [Generated CLAUDE.md](#generated-claudemd)
  - [Implementation](#implementation)
- [Existing Tools & Implementation Options](#existing-tools--implementation-options)
  - [MCP Server Implementation](#mcp-server-implementation)
- [User Preferences](#user-preferences)
- [Implementation Phases](#implementation-phases)
- [Open Questions](#open-questions)
- [Success Criteria](#success-criteria)
- [References](#references)

---

## Vision

**The Dream:** Open Claude Code in any Clojure repo (monolith or otherwise) and it immediately—without running anything to check—knows:
- Available `bb tasks` for common commands (starting servers, REPLs, builds)
- How to evaluate and validate code via the appropriate REPL based on context (CLJ vs CLJS vs BB)
- The project structure and conventions

**The Goal:** Seamless, deterministic REPL integration that makes Claude Code a first-class Clojure development environment.

---

## Core Thesis: Why REPL + LLM is Powerful

### The Training Data Paradox

Conventional wisdom: "Use Python/TypeScript because LLMs have seen more of it."

The counter-argument: **If the agent has a REPL, training data volume matters less.**

- Wrong syntax? The REPL tells you immediately
- The agent self-corrects in real-time
- The "training data disadvantage" of niche languages gets overcome by tight feedback loops
- The agent doesn't need to have memorized Clojure patterns—it can try things and see immediately if they work

### REPL Validation vs Test Validation

**Ralph Wiggum Loop** (current agentic pattern):
```
while not done:
    try thing
    check if it worked (tests, linter, build)
    if failed: read error, iterate
```

**Lisa Loop** (REPL-enhanced pattern):
```
while not done:
    write function
    eval function in REPL       # immediate feedback
    see actual return value     # not just "tests pass"
    if wrong: iterate on data
```

*Named after Lisa Simpson—the methodical one who validates her work. Contrast to Ralph Wiggum loops (blind iteration until tests pass). Lisa evaluates incrementally, sees actual data, catches mistakes at the expression level.*

The difference: Ralph Wiggum validates at the **test/build level**. REPL validation happens at the **expression level**. You catch mistakes 10x faster because you're not waiting for a full test suite.

### The Multi-Host Advantage

One language → One REPL paradigm → Multiple ecosystems:
- JVM (Clojure)
- npm/Node (ClojureScript)  
- Browser (ClojureScript + shadow-cljs)
- Native scripting (Babashka)
- Python interop (libpython-clj)

---

## Problem Analysis

### Three Core Challenges

**1. Static Discovery (no execution needed)**
```
bb.edn exists        → Claude reads it → knows tasks without running `bb tasks`
deps.edn exists      → Claude parses it → knows aliases, entry points
shadow-cljs.edn exists → Claude knows CLJS build targets
```

Currently solvable with CLAUDE.md that says "read these files first"—but Claude ignores it ~40% of the time.

**2. Context-Aware REPL Routing**
```
src/clj/**/*.clj        → JVM nREPL (port from .nrepl-port)
src/cljs/**/*.cljs      → shadow-cljs nREPL (port 9000)  
scripts/**/*.bb         → Babashka (port 1667 or direct eval)
test/**                  → Same routing based on file extension
```

Requires either:
- Convention (directory structure implies environment)
- Config (explicit mapping in project root)
- Runtime detection (query all REPLs, see which one has the namespace loaded)

**3. Automatic Connection**
The REPLs need to exist before Claude can use them. Someone has to start them.

This might be acceptable as a manual step—similar to how developers run `/init` or accept setup friction for better sessions.

---

## Claude Code Feature Inventory

### Available Features

| Feature | Invocation | Discovery | Use Case |
|---------|------------|-----------|----------|
| **Slash commands** | User types `/cmd` | Manual | Quick workflow shortcuts |
| **MCP prompts** | User types `/mcp__server__prompt` | Manual | External tool workflows |
| **Skills** | Claude auto-detects | Automatic (model-invoked) | Complex capabilities |
| **Subagents** | Claude spawns | Automatic | Parallel/isolated tasks |
| **Hooks** | Events fire | Automatic (deterministic) | Automation, validation |
| **CLAUDE.md** | Session start | Automatic | Project conventions |

### Hook Events

| Event | When It Fires | Potential Use |
|-------|---------------|---------------|
| `SessionStart` | Session begins | Auto-inject Clojure project context |
| `UserPromptSubmit` | Before Claude sees prompt | Add live project state |
| `PreToolUse` | Before tool runs | Route eval to correct REPL |
| `PostToolUse` | After tool completes | Validate REPL output, capture results |
| `Stop` | Claude finishes turn | Run tests, quality gates |
| `PermissionRequest` | Permission dialog | Auto-approve safe REPL operations |
| `SubagentStop` | Subagent finishes | Aggregate results from parallel evals |
| `PreCompact` | Before context compaction | Preserve important REPL history |

### Key Insight: Skills vs Slash Commands vs Hooks

- **Slash commands**: User must remember to invoke them
- **MCP prompts**: User must remember to invoke them  
- **Skills**: Claude auto-applies based on context (but can't execute code on load)
- **Hooks**: Deterministic, always fire on events (can execute code)

**The gap**: No `onProjectLoad` or `onSessionStart` that can both execute code AND inject results into Claude's context automatically.

**The workaround**: `SessionStart` hook runs a script that outputs JSON, which gets added to context.

---

## Proposed Architecture

### Component Overview

```
┌─────────────────────────────────────────────────────────────┐
│                     Claude Code Session                      │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐  │
│  │ SessionStart │───▶│   Skill      │◀───│    MCP       │  │
│  │    Hook      │    │ (auto-apply) │    │   Server     │  │
│  └──────┬───────┘    └──────────────┘    └──────┬───────┘  │
│         │                                        │          │
│         ▼                                        ▼          │
│  ┌──────────────┐                        ┌──────────────┐  │
│  │ clojure-     │                        │  nREPL       │  │
│  │ init.sh      │                        │  Connections │  │
│  └──────┬───────┘                        └──────────────┘  │
│         │                                        ▲          │
│         ▼                                        │          │
│  ┌──────────────────────────────────────────────┴───────┐  │
│  │              Project Context (injected)               │  │
│  │  • Available bb tasks                                 │  │
│  │  • Running REPLs (ports)                             │  │
│  │  • Path → REPL routing rules                         │  │
│  │  • deps.edn aliases                                  │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                              │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐  │
│  │ PreToolUse   │    │ PostToolUse  │    │    Stop      │  │
│  │    Hook      │    │    Hook      │    │    Hook      │  │
│  │ (routing)    │    │ (validation) │    │  (tests)     │  │
│  └──────────────┘    └──────────────┘    └──────────────┘  │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 1. MCP Server: `clojure-repl-mcp`

**Purpose**: Provide REPL connectivity, lifecycle management, and evaluation tools

#### Discovery & Analysis Tools

```
analyze-project     # Parse bb.edn, deps.edn, shadow-cljs.edn
                    # Returns: project type, available tasks/aliases, 
                    # detected REPL commands, source paths

find-repl-commands  # Analyze config files to find REPL-starting commands
                    # Looks for: :repl task in bb.edn, :repl alias in deps.edn,
                    # shadow-cljs watch/server commands
                    # Returns: ranked list of commands with descriptions

discover-repls      # Find running nREPL servers
                    # Checks: .nrepl-port, .shadow-cljs/nrepl.port, .bb-nrepl-port
                    # Returns: [{:type :clj :port 7888 :status :connected}, ...]
```

#### REPL Lifecycle Tools

```
start-repl          # Start a REPL based on project analysis
                    # Params: {:type :clj|:cljs|:bb, :background true|false}
                    # If :background true - starts as managed background process
                    # If :background false - returns command for user to run
                    # Returns: {:status :started, :port 7888, :pid 12345}
                    #      or: {:status :manual, :command "bb repl", :instructions "..."}

stop-repl           # Stop a managed background REPL
                    # Params: {:type :clj|:cljs|:bb} or {:port 7888}

repl-status         # Health check on all known REPLs
                    # Returns: connection status, uptime, last eval time
```

#### Evaluation Tools

```
eval-clj            # Evaluate in JVM Clojure REPL
                    # Params: {:code "(+ 1 2)", :ns "user"}

eval-cljs           # Evaluate in ClojureScript REPL  
                    # Params: {:code "(js/console.log 1)", :build-id :app}

eval-bb             # Evaluate in Babashka (direct or via nREPL)
                    # Params: {:code "(System/getenv \"HOME\")"}

eval-in-context     # Auto-route based on file path
                    # Params: {:code "...", :file-path "src/cljs/app/core.cljs"}
                    # Automatically selects correct REPL

load-namespace      # Load/reload a namespace
                    # Params: {:ns "myapp.core", :reload-all? false}
```

#### Prompts (become `/mcp__clojure__*` commands)

```
clojure-init        # Manual trigger: full project analysis + context injection
repl-status         # Show current REPL connections and health  
start-dev           # Guided flow: analyze project, suggest/start REPLs
```

#### Resources

```
project-context     # Live: parsed configs, detected patterns
repl-history        # Recent evaluations (for context/retry)
```

---

### REPL Startup Strategy

The MCP server should be smart about how it starts REPLs:

**Option A: Background Process (Claude-managed)**
```clojure
;; User preference: "let Claude manage REPLs"
;; MCP server spawns process, tracks PID, handles cleanup

(start-repl {:type :clj :background true})
;; => {:status :started
;;     :port 7888
;;     :pid 54321
;;     :command "clj -M:repl"
;;     :log-file "/tmp/clojure-repl-54321.log"}
```

**Option B: User-Managed (command suggestion)**
```clojure
;; User preference: "I'll manage my own REPLs"
;; MCP server analyzes project and tells user what to run

(start-repl {:type :clj :background false})
;; => {:status :manual
;;     :command "bb repl"
;;     :alternatives ["clj -M:repl" "clj -M:dev"]
;;     :instructions "Run this in a separate terminal. 
;;                    The REPL will be available on port 7888.
;;                    I'll detect it automatically when ready."}
```

**Option C: Hybrid (configurable)**
```clojure
;; In MCP server config or CLAUDE.md
{:repl-management :background}  ; or :manual or :ask
```

---

### REPL Command Detection Logic

The `find-repl-commands` tool should analyze configs intelligently:

```clojure
(defn find-repl-commands [project-root]
  (let [bb-edn (safe-parse-edn "bb.edn")
        deps-edn (safe-parse-edn "deps.edn")
        shadow-edn (safe-parse-edn "shadow-cljs.edn")]
    
    (cond-> []
      ;; Check bb.edn tasks
      (get-in bb-edn [:tasks 'repl])
      (conj {:type :clj
             :command "bb repl"
             :confidence :high
             :source "bb.edn :tasks/repl"})
      
      (get-in bb-edn [:tasks 'dev])
      (conj {:type :clj
             :command "bb dev"
             :confidence :medium
             :source "bb.edn :tasks/dev (may include REPL)"})
      
      (get-in bb-edn [:tasks 'nrepl])
      (conj {:type :clj
             :command "bb nrepl"
             :confidence :high
             :source "bb.edn :tasks/nrepl"})
      
      ;; Check deps.edn aliases
      (get-in deps-edn [:aliases :repl])
      (conj {:type :clj
             :command "clj -M:repl"
             :confidence :high
             :source "deps.edn :aliases/:repl"})
      
      (get-in deps-edn [:aliases :dev])
      (conj {:type :clj
             :command "clj -M:dev"
             :confidence :medium
             :source "deps.edn :aliases/:dev"})
      
      (some #(get-in deps-edn [:aliases %]) [:nrepl :cider :rebel])
      (conj {:type :clj
             :command (str "clj -M:" (first (filter #(get-in deps-edn [:aliases %]) 
                                                    [:nrepl :cider :rebel])))
             :confidence :high
             :source "deps.edn REPL alias"})
      
      ;; Check shadow-cljs
      shadow-edn
      (conj {:type :cljs
             :command "npx shadow-cljs watch app"  ; or first build-id
             :confidence :high
             :source "shadow-cljs.edn detected"}
            {:type :cljs
             :command "npx shadow-cljs cljs-repl app"
             :confidence :high
             :source "shadow-cljs CLJS REPL"})
      
      ;; Fallback: bare clj with nREPL
      (and (empty? (get-in deps-edn [:aliases]))
           (fs/exists? "deps.edn"))
      (conj {:type :clj
             :command "clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version \"1.0.0\"}}}' -M -m nrepl.cmdline"
             :confidence :low
             :source "fallback: inject nREPL dependency"}))))
```

### 2. Hooks Configuration

**File**: `.claude/settings.local.json` or project `.claude/settings.json`

```json
{
  "hooks": {
    "SessionStart": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "bb .claude/hooks/clojure-init.bb"
          }
        ]
      }
    ],
    "PreToolUse": [
      {
        "matcher": "mcp__clojure__eval*",
        "hooks": [
          {
            "type": "command",
            "command": "bb .claude/hooks/route-repl.bb"
          }
        ]
      }
    ],
    "PostToolUse": [
      {
        "matcher": "mcp__clojure__eval*",
        "hooks": [
          {
            "type": "command",
            "command": "bb .claude/hooks/validate-eval.bb"
          }
        ]
      }
    ],
    "Stop": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "bb .claude/hooks/run-tests.bb"
          }
        ]
      }
    ]
  }
}
```

**Note**: If using bbin-installed scripts, just use the command name directly:
```json
"command": "clojure-init"
```

### 3. Hook Scripts (Babashka)

All hooks written in bb—Clojure all the way down. Benefits:
- Native EDN parsing (no shelling out)
- Same language as your project
- Distributable via [bbin](https://github.com/babashka/bbin)
- Fast startup (~10ms)

**`.claude/hooks/clojure-init.bb`**
```clojure
#!/usr/bin/env bb

(ns clojure-init
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [cheshire.core :as json]))

(def project-root (or (System/getenv "CLAUDE_PROJECT_DIR") "."))

(defn safe-parse-edn [path]
  (when (fs/exists? path)
    (try
      (edn/read-string (slurp path))
      (catch Exception _ nil))))

(defn discover-repls []
  (cond-> []
    (fs/exists? (fs/path project-root ".nrepl-port"))
    (conj {:type :clj 
           :port (parse-long (slurp (fs/path project-root ".nrepl-port")))})
    
    (fs/exists? (fs/path project-root ".shadow-cljs/nrepl.port"))
    (conj {:type :cljs 
           :port (parse-long (slurp (fs/path project-root ".shadow-cljs/nrepl.port")))})
    
    ;; Babashka nREPL if running
    (fs/exists? (fs/path project-root ".bb-nrepl-port"))
    (conj {:type :bb
           :port (parse-long (slurp (fs/path project-root ".bb-nrepl-port")))})))

(defn extract-tasks [bb-edn]
  (some-> bb-edn :tasks keys vec))

(defn extract-aliases [deps-edn]
  (some-> deps-edn :aliases keys vec))

(defn extract-shadow-builds [shadow-edn]
  (some-> shadow-edn :builds keys vec))

(defn infer-project-type []
  (cond-> #{}
    (fs/exists? (fs/path project-root "deps.edn")) (conj :clj)
    (fs/exists? (fs/path project-root "bb.edn")) (conj :bb)
    (fs/exists? (fs/path project-root "shadow-cljs.edn")) (conj :cljs)
    (fs/exists? (fs/path project-root "project.clj")) (conj :lein)))

(let [bb-edn (safe-parse-edn (fs/path project-root "bb.edn"))
      deps-edn (safe-parse-edn (fs/path project-root "deps.edn"))
      shadow-edn (safe-parse-edn (fs/path project-root "shadow-cljs.edn"))
      
      context {:project-types (infer-project-type)
               :bb-tasks (extract-tasks bb-edn)
               :deps-aliases (extract-aliases deps-edn)
               :shadow-builds (extract-shadow-builds shadow-edn)
               :repls (discover-repls)}
      
      context-str (str "CLOJURE PROJECT DETECTED.\n"
                       "Project types: " (:project-types context) "\n"
                       "BB tasks: " (or (:bb-tasks context) "none") "\n"
                       "Deps aliases: " (or (:deps-aliases context) "none") "\n"
                       "Shadow builds: " (or (:shadow-builds context) "none") "\n"
                       "Running REPLs: " (if (seq (:repls context)) 
                                           (:repls context) 
                                           "NONE - consider starting with `bb repl` or `bb dev`") "\n"
                       "\n"
                       "Use REPL evaluation for rapid feedback. "
                       "Evaluate expressions incrementally rather than writing large code blocks.")]
  
  (println (json/generate-string
            {:hookSpecificOutput
             {:additionalContext context-str}})))
```

**`.claude/hooks/route-repl.bb`**
```clojure
#!/usr/bin/env bb

(ns route-repl
  (:require [cheshire.core :as json]))

(def input (json/parse-string (slurp *in*) true))

(defn infer-repl-type [file-path]
  (cond
    (nil? file-path) :clj  ; default
    (re-find #"\.cljs$" file-path) :cljs
    (re-find #"\.bb$" file-path) :bb
    (re-find #"/cljs/" file-path) :cljs
    (re-find #"/bb/|/scripts/" file-path) :bb
    (re-find #"\.cljc$" file-path) :cljc  ; cross-platform
    :else :clj))

(let [file-path (get-in input [:tool_input :file_path])
      repl-type (infer-repl-type file-path)
      message (case repl-type
                :cljs "Routing to ClojureScript REPL (shadow-cljs)"
                :bb "Routing to Babashka"
                :cljc "Cross-platform file: defaulting to JVM REPL (specify if CLJS needed)"
                :clj "Routing to JVM Clojure REPL")]
  
  (println (json/generate-string
            {:hookSpecificOutput
             {:additionalContext message
              :repl-type repl-type}})))
```

**`.claude/hooks/validate-eval.bb`**
```clojure
#!/usr/bin/env bb

(ns validate-eval
  (:require [cheshire.core :as json]))

(def input (json/parse-string (slurp *in*) true))

(let [response (get-in input [:tool_response])
      has-error? (or (get response :error)
                     (re-find #"(?i)exception|error" (str response)))
      
      output (if has-error?
               {:decision "block"
                :reason "REPL evaluation returned an error. Review and fix."
                :hookSpecificOutput
                {:additionalContext (str "Error in REPL eval. Response: " response)}}
               {:hookSpecificOutput
                {:additionalContext "REPL eval succeeded."}})]
  
  (println (json/generate-string output)))
```

**`.claude/hooks/run-tests.bb`**
```clojure
#!/usr/bin/env bb

(ns run-tests
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]))

(def project-root (or (System/getenv "CLAUDE_PROJECT_DIR") "."))

(defn has-bb-task? [task-name]
  (when (fs/exists? (fs/path project-root "bb.edn"))
    (let [bb-edn (read-string (slurp (fs/path project-root "bb.edn")))]
      (contains? (:tasks bb-edn) (symbol task-name)))))

(defn run-tests []
  (cond
    ;; Prefer bb test if available
    (has-bb-task? "test")
    (let [result (p/shell {:dir project-root :continue true} "bb test")]
      {:ran "bb test" :exit (:exit result)})
    
    ;; Fall back to clj -X:test
    (fs/exists? (fs/path project-root "deps.edn"))
    (let [result (p/shell {:dir project-root :continue true} "clj -X:test")]
      {:ran "clj -X:test" :exit (:exit result)})
    
    :else
    {:ran nil :exit 0 :message "No test runner found"}))

(let [{:keys [ran exit message]} (run-tests)]
  (if (and ran (not= exit 0))
    ;; Tests failed - exit 2 to block and notify Claude
    (do
      (println (json/generate-string
                {:continue false
                 :stopReason (str "Tests failed: " ran)
                 :hookSpecificOutput
                 {:additionalContext (str "Test suite failed with exit code " exit ". Fix failing tests before continuing.")}}))
      (System/exit 2))
    ;; Tests passed or no tests
    (println (json/generate-string
              {:hookSpecificOutput
               {:additionalContext (or message (str "Tests passed: " ran))}}))))
```

### bbin Distribution (Optional)

For sharing the tooling across projects, publish as bbin scripts:

```bash
# Install the hooks globally
bbin install https://github.com/yourname/clojure-claude-hooks/clojure-init.bb
bbin install https://github.com/yourname/clojure-claude-hooks/route-repl.bb

# Then reference in hooks config
# "command": "clojure-init"  (bbin puts it on PATH)
```

Or create a single installable package:

**`bb.edn`** (for the hooks project itself)
```clojure
{:paths ["src"]
 :deps {io.github.babashka/json {:mvn/version "0.1.6"}}
 :bbin/bin {clojure-init {:main-opts ["-m" "hooks.clojure-init"]}
            route-repl {:main-opts ["-m" "hooks.route-repl"]}
            validate-eval {:main-opts ["-m" "hooks.validate-eval"]}}}
```

### 4. Skill Definition

**Directory**: `.claude/skills/clojure-dev/`

**`.claude/skills/clojure-dev/SKILL.md`**
```markdown
---
name: clojure-dev
description: Clojure/ClojureScript/Babashka development with REPL-driven workflow
---

# Clojure Development Skill

## When to Apply
Activate when working in a project containing:
- `deps.edn` (Clojure CLI)
- `bb.edn` (Babashka)
- `shadow-cljs.edn` (ClojureScript)
- `project.clj` (Leiningen)

## REPL-First Development

**Always prefer REPL evaluation over writing code blindly.**

1. Before writing a function, evaluate small pieces to understand the data
2. Use `(take 5 data)` style exploration
3. Evaluate functions immediately after writing them
4. Use the comment block pattern for scratch work

## REPL Routing

Based on file location:
- `src/clj/**/*.clj` → JVM Clojure REPL
- `src/cljs/**/*.cljs` → ClojureScript REPL (shadow-cljs)
- `src/cljc/**/*.cljc` → Evaluate in both (or ask which)
- `scripts/**/*.bb` → Babashka
- `bb.edn` tasks → Babashka

## Common Workflows

### Starting Development
1. Check if REPLs are running: look for `.nrepl-port`, `.shadow-cljs/nrepl.port`
2. If not running, suggest: `bb dev` or `bb repl` (check bb.edn for actual task names)
3. Load the namespace you're working on

### Testing Incrementally
```clojure
(comment
  ;; Scratch space - eval forms one at a time
  (def sample-data {...})
  (my-function sample-data)
  ;; => see actual output
  )
```

### Common bb Tasks
Check `bb.edn` for available tasks. Common patterns:
- `bb dev` - Start development environment
- `bb repl` - Start REPL
- `bb test` - Run tests
- `bb build` - Build artifacts

## Error Handling

When REPL evaluation fails:
1. Read the error message carefully
2. Check for missing requires
3. Verify data shape with `(type x)` and `(keys x)`
4. Try smaller expressions to isolate the issue
```

**`.claude/skills/clojure-dev/REPL_PATTERNS.md`**
```markdown
# REPL Patterns Reference

## The Comment Block Pattern
```clojure
(ns myapp.core
  (:require [clojure.string :as str]))

(defn process-name [name]
  (str/upper-case name))

(comment
  ;; Development scratch - never runs in production
  
  ;; Test the function
  (process-name "alex")
  ;; => "ALEX"
  
  ;; Explore edge cases
  (process-name nil)
  ;; => NullPointerException
  
  ;; Try a fix
  (defn process-name [name]
    (some-> name str/upper-case))
  
  (process-name nil)
  ;; => nil
  )
```

## Data Exploration
```clojure
;; See what you're working with
(type response)
(keys response)
(first (:items response))

;; Sample large collections
(take 3 items)
(->> items (take 3) (map :name))

;; Pretty print complex data
(require '[clojure.pprint :refer [pprint]])
(pprint (first items))
```

## Namespace Management
```clojure
;; Reload current namespace
(require '[myapp.core :reload])

;; Reload with dependencies
(require '[myapp.core :reload-all])

;; Find where something is defined
(source some-fn)
(doc some-fn)
```
```

### 5. CLAUDE.md Addition

Add to project's `CLAUDE.md`:

```markdown
## Clojure Development

This is a Clojure project. Use REPL-driven development.

### Quick Start
1. Start REPLs if not running: `bb dev` (or check bb.edn for actual command)
2. Available bb tasks are parsed automatically on session start
3. Use `eval-in-context` MCP tool for automatic REPL routing

### Project Structure
- `src/clj/` - JVM Clojure source
- `src/cljs/` - ClojureScript source  
- `src/cljc/` - Cross-platform source
- `scripts/` - Babashka scripts

### Testing
- Prefer REPL evaluation for rapid feedback
- Run full test suite with `bb test` before committing
```

---

## Implementation Phases

### Phase 0: Project Scaffolding Tool
- [ ] Design template structure for each project type
- [ ] Implement `clj-init` as standalone bb script
- [ ] Interactive prompts (project type, features)
- [ ] Generate appropriate CLAUDE.md for each template
- [ ] Pre-configure hooks in generated projects
- [ ] bbin distribution
- [ ] Test: can scaffold all project types

### Phase 1: Foundation
- [ ] Evaluate MCP implementation options (bb, mcp-clojure-sdk, fork clojure-mcp)
- [ ] Create MCP server skeleton
- [ ] Implement `analyze-project` tool
- [ ] Implement `discover-repls` tool  
- [ ] Write `clojure-init.bb` hook script
- [ ] Test SessionStart hook injection
- [ ] Create basic skill documentation

### Phase 2: REPL Lifecycle
- [ ] Implement `find-repl-commands` with config analysis
- [ ] Implement `start-repl` with background process management
- [ ] Implement `stop-repl` and `repl-status`
- [ ] Add user preference for :background vs :manual mode
- [ ] Handle graceful cleanup on session end
- [ ] Test with various project structures (bb-only, deps-only, shadow, monorepo)

### Phase 3: REPL Routing & Eval
- [ ] Implement `eval-clj`, `eval-cljs`, `eval-bb` via nREPL client
- [ ] Implement `eval-in-context` with path-based routing
- [ ] Add PreToolUse hook for routing decisions
- [ ] Handle missing REPL gracefully (offer to start)
- [ ] Support `.nrepl-port` discovery

### Phase 4: Validation Loop (Lisa Loop)
- [ ] PostToolUse hook captures eval results
- [ ] Error detection and context injection
- [ ] Success logging for session history
- [ ] Integration with test running on Stop
- [ ] Experiment: compare agent behavior with/without REPL feedback

### Phase 5: Multi-REPL & Advanced
- [ ] ClojureScript via shadow-cljs nREPL
- [ ] CLJC file handling (ask or eval in both)
- [ ] Babashka pod support
- [ ] Remote REPL connections (SSH tunnel)
- [ ] Multiple simultaneous REPLs (e.g., CLJ + CLJS)

### Phase 6: Polish & Distribution
- [ ] Slash command fallback (`/clojure-init`) for manual trigger
- [ ] bbin distribution for hooks
- [ ] npm/clojars distribution for MCP server
- [ ] Better error messages and suggestions
- [ ] Performance optimization (cache parsed configs)
- [ ] Documentation, examples, demo video

---

## Project Initialization / Scaffolding

### Vision

A standalone bb tool (installable via bbin) that helps bootstrap new Clojure projects with sensible defaults. Works both:
- **Standalone**: Run `clj-init` in terminal, answer prompts, get project
- **Via Claude Code**: `/clojure-init-project` or Claude invokes skill

### Interactive Flow

```
$ clj-init

🔮 Clojure Project Initializer

Project name: my-app
Project type:
  [1] Script/CLI (bb only)
  [2] Library (deps.edn, clj)
  [3] Backend API (deps.edn, ring/http-kit)
  [4] Full-stack web (clj backend + cljs frontend)
  [5] Mobile app (ClojureScript + React Native/Expo)
  [6] Monorepo (multiple sub-projects)

> 4

Frontend framework:
  [1] Reagent
  [2] Re-frame
  [3] UIx
  [4] Helix

> 2

Additional features:
  [x] REPL configuration (nREPL + CIDER middleware)
  [x] bb.edn with common tasks
  [ ] Docker configuration
  [x] GitHub Actions CI
  [ ] Clerk notebooks
  [ ] Malli schemas

Creating project structure...
✓ Created deps.edn
✓ Created bb.edn with tasks: dev, repl, build, test
✓ Created shadow-cljs.edn
✓ Created src/clj/my_app/core.clj
✓ Created src/cljs/my_app/app.cljs
✓ Created .github/workflows/ci.yml
✓ Created CLAUDE.md (Claude Code integration)

Next steps:
  cd my-app
  bb dev        # Start dev environment
  bb repl       # Start REPL only

Happy hacking! 🎉
```

### Project Templates

#### Script/CLI (bb only)
```
my-script/
├── bb.edn
├── src/
│   └── my_script/
│       └── core.clj
├── test/
│   └── my_script/
│       └── core_test.clj
├── CLAUDE.md
└── README.md
```

```clojure
;; bb.edn
{:paths ["src" "test"]
 :deps {io.github.babashka/cli {:mvn/version "0.8.60"}}
 :tasks
 {run {:doc "Run the script"
       :task (exec 'my-script.core/-main)}
  test {:doc "Run tests"
        :task (exec 'my-script.core-test/run-tests)}
  repl {:doc "Start bb nREPL"
        :task (babashka.nrepl.server/start-server! {:port 1667})}}}
```

#### Full-stack Web
```
my-app/
├── deps.edn
├── bb.edn
├── shadow-cljs.edn
├── src/
│   ├── clj/
│   │   └── my_app/
│   │       ├── core.clj        # Entry point
│   │       ├── server.clj      # HTTP server
│   │       └── api/
│   │           └── routes.clj
│   ├── cljs/
│   │   └── my_app/
│   │       ├── app.cljs        # Entry point
│   │       ├── views.cljs      # UI components
│   │       └── state.cljs      # Re-frame db
│   └── cljc/
│       └── my_app/
│           └── shared.cljc     # Shared code
├── resources/
│   └── public/
│       └── index.html
├── test/
│   ├── clj/
│   └── cljs/
├── .claude/
│   ├── settings.json           # Hooks config
│   └── hooks/
│       ├── clojure-init.bb
│       └── route-repl.bb
├── CLAUDE.md
└── README.md
```

```clojure
;; bb.edn for full-stack
{:paths ["src/clj" "src/cljc"]
 :tasks
 {dev {:doc "Start full dev environment (backend + frontend + REPLs)"
       :task (do
               (future (shell "clj -M:dev"))      ; Backend + nREPL
               (shell "npx shadow-cljs watch app"))} ; Frontend + CLJS REPL
  
  repl {:doc "Start backend nREPL only"
        :task (shell "clj -M:repl")}
  
  cljs-repl {:doc "Connect to CLJS REPL (after shadow-cljs running)"
             :task (shell "npx shadow-cljs cljs-repl app")}
  
  build {:doc "Build production artifacts"
         :task (do
                 (shell "npx shadow-cljs release app")
                 (shell "clj -T:build uber"))}
  
  test {:doc "Run all tests"
        :task (do
                (shell "clj -M:test")
                (shell "npx shadow-cljs compile test"))}}}
```

#### Mobile App (CLJS + Expo)
```
my-mobile-app/
├── deps.edn                    # For tooling/scripts
├── bb.edn
├── shadow-cljs.edn
├── app.json                    # Expo config
├── package.json
├── src/
│   └── my_mobile_app/
│       ├── app.cljs           # Root component
│       ├── navigation.cljs    # React Navigation
│       ├── screens/
│       │   ├── home.cljs
│       │   └── settings.cljs
│       └── components/
├── CLAUDE.md
└── README.md
```

### Generated CLAUDE.md

Each template includes a pre-configured `CLAUDE.md`:

```markdown
# my-app

## Project Type
Full-stack Clojure/ClojureScript web application.

## Quick Start
\`\`\`bash
bb dev    # Start everything (backend + frontend + REPLs)
\`\`\`

## REPL Access
- Backend (CLJ): port 7888 (auto-detected from .nrepl-port)
- Frontend (CLJS): port 9000 (shadow-cljs)

## File Routing
- `src/clj/**` → JVM Clojure REPL
- `src/cljs/**` → ClojureScript REPL  
- `src/cljc/**` → Evaluate in CLJ (or specify CLJS if needed)

## Common Tasks
- `bb dev` - Full dev environment
- `bb repl` - Backend REPL only
- `bb test` - Run all tests
- `bb build` - Production build

## Preferences
repl-management: background
auto-test: true
```

### Implementation

**Standalone bb tool**: `clj-init`

```clojure
#!/usr/bin/env bb

(ns clj-init.core
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [clojure.string :as str]))

(def templates
  {:script    {:name "Script/CLI (bb only)"
               :files ["bb.edn" "src/{{ns}}/core.clj" "CLAUDE.md"]}
   :library   {:name "Library"
               :files ["deps.edn" "bb.edn" "src/{{ns}}/core.clj" "test/{{ns}}/core_test.clj"]}
   :backend   {:name "Backend API"
               :files ["deps.edn" "bb.edn" "src/{{ns}}/core.clj" "src/{{ns}}/server.clj"]}
   :fullstack {:name "Full-stack web"
               :files ["deps.edn" "bb.edn" "shadow-cljs.edn" "package.json" "..."]}
   :mobile    {:name "Mobile app (Expo)"
               :files ["shadow-cljs.edn" "package.json" "app.json" "..."]}
   :monorepo  {:name "Monorepo"
               :files ["bb.edn" "modules/..."]}})

(defn prompt [question options]
  (println question)
  (doseq [[i opt] (map-indexed vector options)]
    (println (str "  [" (inc i) "] " opt)))
  (print "> ")
  (flush)
  (let [choice (parse-long (read-line))]
    (nth options (dec choice))))

(defn create-project [{:keys [name template features]}]
  ;; Template expansion logic here
  ,,,)

(defn -main [& args]
  (let [project-name (or (first args) 
                         (do (print "Project name: ") (flush) (read-line)))
        template (prompt "Project type:" (map :name (vals templates)))
        ;; ... more prompts based on template
        ]
    (create-project {:name project-name
                     :template template
                     :features #{:repl :bb-tasks}})))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
```

**bbin installation**:
```bash
bbin install https://github.com/yourname/clj-init/clj-init.bb
```

**Claude Code integration**:

As a slash command (`.claude/commands/init-project.md`):
```markdown
---
description: Initialize a new Clojure project with interactive prompts
---

Help me create a new Clojure project. Ask me:
1. Project name
2. Project type (script, library, backend, full-stack, mobile, monorepo)
3. Additional features needed

Then use the `clj-init` tool or create the file structure directly.
Follow the templates defined in the clojure-dev skill.
```

Or Claude can just invoke `clj-init` directly if it's on PATH.

### MCP Server Implementation

Several paths forward—TBD which is best:

**Option A: Pure Babashka**
- Clojure all the way down
- Fast startup (~10ms)
- Limited nREPL client libraries (would need to implement or port)
- Could use [mcp-clojure-sdk](https://github.com/unravel-team/mcp-clojure-sdk)

**Option B: Fork Existing MCP**
- [clojure-mcp](https://github.com/bhauman/clojure-mcp) — full-featured, JVM Clojure
- [clojure-mcp-light](https://github.com/bhauman/clojure-mcp-light) — CLI tools approach
- Leverage existing nREPL integration, add lifecycle management

**Option C: mcp-clojure-sdk**
- Official-ish Clojure MCP SDK
- Would need to evaluate maturity and features
- Could build our server on top of it

**Option D: Node/TypeScript**
- Rich MCP tooling ecosystem
- Good nREPL client libraries exist
- But... not Clojure 😢

### Considerations
- clojure-mcp may already solve 80% of the eval problem
- Focus effort on the novel parts: lifecycle management, project analysis, hooks integration
- Could start by extending clojure-mcp rather than from scratch

---

## User Preferences

Add to project `CLAUDE.md` or global `~/.claude/CLAUDE.md`:

```markdown
## Clojure REPL Preferences

<!-- How should Claude manage REPLs? -->
repl-management: background  
# Options:
#   background - Claude starts/manages REPLs as background processes
#   manual     - Claude suggests commands, user starts in own terminal
#   ask        - Claude asks each time

<!-- Default REPL type when ambiguous -->
default-repl: clj
# Options: clj, cljs, bb

<!-- Run tests automatically on Stop? -->
auto-test: true

<!-- Preferred test command (overrides auto-detection) -->
test-command: bb test
```

Or via MCP server config (`.mcp.json`):

```json
{
  "mcpServers": {
    "clojure-repl": {
      "command": "clojure-repl-mcp",
      "env": {
        "REPL_MANAGEMENT": "background",
        "DEFAULT_REPL": "clj",
        "AUTO_TEST": "true"
      }
    }
  }
}
```

---

## Open Questions

1. **REPL Management Default**: Should background or manual be the default? Background is more seamless but some devs want control.

2. **Process Cleanup**: When Claude Code session ends, should managed REPLs be killed? Or kept running for the next session?

3. **Multi-Project/Monorepo**: How to handle repos with multiple `bb.edn`/`deps.edn`? Detect based on working directory?

4. **Remote REPLs**: Support for SSH-tunneled REPLs? Common in production debugging scenarios.

5. **State Persistence**: Should REPL state (defined vars, loaded namespaces) persist across Claude Code sessions? Could serialize and restore.

6. **MCP Server Implementation Language**: 
   - Babashka: Fast startup, native EDN, but limited nREPL client libraries
   - Node/TypeScript: Rich MCP tooling, good nREPL clients exist
   - JVM Clojure: Best nREPL support, but slow startup for MCP

7. **Conflict with Existing Tools**: How to coexist with Calva, Cursive, Conjure? They may also connect to REPLs.

8. **Error Handling Philosophy**: On eval error, should we block Claude (force immediate fix) or just inject context and continue?

---

## Success Criteria

The integration is successful when:

1. **Quick Start**: Run `clj-init`, answer 3 questions, have a working project with REPL + Claude Code integration in <2 minutes

2. **Zero Config Session**: Open Claude Code in any Clojure repo, it knows the project structure immediately

3. **REPL-First**: Claude naturally reaches for REPL eval before writing large code blocks

4. **Context-Aware**: Evaluating a `.cljs` file automatically uses the CLJS REPL

5. **Self-Correcting**: Failed evals feed back into Claude's context for immediate retry

6. **Lifecycle Management**: Claude can start/stop REPLs based on user preference (background or manual)

7. **Discoverable**: `bb tasks` equivalent info available without running commands

---

## References

- [Claude Code Hooks Documentation](https://docs.claude.com/en/docs/claude-code/hooks)
- [Claude Code Skills Documentation](https://code.claude.com/docs/en/skills)
- [MCP Specification](https://modelcontextprotocol.io/)
- [clojure-mcp](https://github.com/bhauman/clojure-mcp)
- [nREPL Protocol](https://nrepl.org/)
- [shadow-cljs](https://shadow-cljs.github.io/docs/UsersGuide.html)
