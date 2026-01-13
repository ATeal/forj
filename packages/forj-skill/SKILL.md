---
name: clj-repl
description: Start or connect to a Clojure/ClojureScript/Babashka nREPL. Detects project type and starts appropriate REPL in background. Use when user wants to evaluate code, start REPL, or needs nREPL connection.
---

# Clojure REPL Skill

Start or connect to an nREPL server for REPL-driven development.

## Quick Reference

| Command | Action |
|---------|--------|
| `/clj-repl` | Auto-detect project, start REPL |
| `/clj-repl bb` | Start Babashka nREPL |
| `/clj-repl clj` | Start JVM Clojure nREPL |
| `/clj-repl shadow` | Start shadow-cljs nREPL |
| `/clj-repl status` | Check running REPLs |
| `/clj-repl stop` | Stop REPL |

## Instructions

### Step 1: Check Current Status

First discover any running nREPLs:

```bash
clj-nrepl-eval --discover-ports
```

Or use the forj MCP tool:
- Call `discover_repls` tool

If REPLs are already running for this project, report them and ask if user wants to use existing or start fresh.

### Step 2: Detect Project Type

Walk up from current directory looking for:

| File | Project Type | REPL Command |
|------|--------------|--------------|
| `bb.edn` | Babashka | `bb nrepl-server localhost:<port>` |
| `deps.edn` | JVM Clojure | `clojure -M:dev` or `clj -M:nrepl` |
| `shadow-cljs.edn` | ClojureScript | `npx shadow-cljs server` |
| `project.clj` | Leiningen | `lein repl :headless` |

**Priority for starting:** bb.edn (fast) > deps.edn > shadow-cljs.edn > project.clj

For **monorepos**, scan for subdirectories with these files and ask user which to start.

### Step 3: Start nREPL in Background

**Babashka (fastest - ~10ms startup):**
```bash
bb nrepl-server localhost:1667 &
```

**JVM Clojure:**
```bash
clojure -M:dev &
# or if project has :nrepl alias
clojure -M:nrepl &
```

**Shadow-cljs:**
```bash
npx shadow-cljs server &
# or for a specific build
npx shadow-cljs watch <build-id> &
```

**Leiningen:**
```bash
lein repl :headless &
```

Use `run_in_background: true` parameter when starting via Bash tool.

### Step 4: Verify Startup

Wait 2-5 seconds (longer for JVM), then verify:

```bash
clj-nrepl-eval --discover-ports
```

Or test with a simple eval:
```bash
clj-nrepl-eval -p <port> "(+ 1 2)"
```

### Step 5: Report Success

Tell the user:
- REPL type started (clj/cljs/bb)
- Project directory
- Port number
- They can now use forj MCP tools: `repl_eval`, `eval_at`, `reload_namespace`, `doc_symbol`

### Stopping a REPL

Find and kill the process:
```bash
# Find the process on a specific port
lsof -i :<port>

# Kill it
kill $(lsof -t -i :<port>)
```

## Monorepo Handling

For projects like:
```
myproject/
├── backend/deps.edn       # JVM API server
├── web/shadow-cljs.edn    # Web frontend
├── mobile/shadow-cljs.edn # Mobile app
└── bb.edn                 # Build tasks
```

1. List all detected project types
2. Ask user which to start
3. Multiple REPLs can run on different ports
4. forj discovers all running REPLs automatically

## Troubleshooting

**No REPLs found:**
- Check if nREPL server is running
- Look for `.nrepl-port` file in project directory
- Try starting one with `/clj-repl bb` or `/clj-repl clj`

**Wrong project context:**
The nREPL's working directory determines the project. Make sure to `cd` to the right project before starting.

**Port conflicts:**
Each REPL needs a unique port. Use different ports for multiple REPLs:
```bash
bb nrepl-server localhost:1667 &  # First project
bb nrepl-server localhost:1668 &  # Second project
```

## Notes

- Babashka nREPL is fast to start - good for quick scripts
- JVM Clojure takes 5-10 seconds to start but has full Clojure
- Shadow-cljs provides ClojureScript REPL with hot reload
- forj MCP tools auto-discover ports, no hardcoding needed
