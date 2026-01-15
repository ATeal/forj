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

## CRITICAL: Use MCP Tools for REPL Operations

**After REPLs are running, ALWAYS use forj MCP tools instead of bash commands:**

| Task | MCP Tool | NOT bash |
|------|----------|----------|
| Evaluate code | `repl_eval` | ~~`clj-nrepl-eval -p ...`~~ |
| Find REPLs | `discover_repls` | ~~`clj-nrepl-eval --discover-ports`~~ |
| Reload namespace | `reload_namespace` | ~~manual require~~ |
| Look up docs | `doc_symbol` | ~~`(doc ...)`~~ |
| Eval at line | `eval_at` | ~~copy/paste~~ |
| Run tests | `run_tests` | ~~`clj -M:test`~~ |

**Bash is ONLY for starting/stopping REPL servers.** All evaluation should use MCP tools.

## You Are NOT Done Until...

**Before reporting success, ALL of these must be true:**

- [ ] All required processes started for the project type
- [ ] Application server is running (not just REPL process)
- [ ] You've reported ports AND URLs to user

## Instructions

### Step 1: Check Current Status

Use the MCP tool to discover running REPLs:

```
discover_repls
```

If REPLs are already running for this project, report them and ask if user wants to use existing or start fresh.

### Step 2: Detect Project Type & What to Start

Check which config files exist:

| Files Present | Project Type | Start These |
|---------------|--------------|-------------|
| `deps.edn` + `shadow-cljs.edn` + `app.json` | Full-stack Mobile | Backend + shadow-cljs + Expo |
| `deps.edn` + `shadow-cljs.edn` | Full-stack Web | Backend REPL + shadow-cljs |
| `shadow-cljs.edn` + `app.json` | Mobile Only | shadow-cljs + Expo |
| `deps.edn` only | Backend | Backend REPL |
| `shadow-cljs.edn` only | Frontend Web | shadow-cljs |
| `bb.edn` only | Script | Babashka REPL |

---

**⚠️ FULL-STACK MOBILE PROJECT? You MUST start ALL THREE:**
1. Backend REPL (`bb dev`)
2. shadow-cljs (`bb shadow`)
3. Expo (`bb mobile`)

**Do NOT stop after starting REPLs. The app server must also be running.**

---

### Step 3: Use bb Tasks (Required When Available)

If `bb.edn` has relevant tasks, **you MUST use them** instead of raw commands.

| Task | Use For |
|------|---------|
| `bb dev` or `bb repl` | Backend REPL |
| `bb shadow` | ClojureScript/shadow-cljs |
| `bb mobile` | Expo |

**Only fall back to raw commands if NO bb task exists.**

### Step 4: Start ALL Required Processes

Use `run_in_background: true` for each. **Start them all before moving on.**

**First, create log directory:**
```bash
mkdir -p .forj/logs
```

**Use `tee` to capture logs while keeping output visible:**
```bash
# Pattern: command 2>&1 | tee .forj/logs/<name>.log &
bb dev 2>&1 | tee .forj/logs/backend.log &
bb shadow 2>&1 | tee .forj/logs/shadow.log &
```

**To view logs later, use the `view_repl_logs` MCP tool:**
- `view_repl_logs` with `log: "all"` - see all logs at once
- `view_repl_logs` with `log: "backend"` / `"shadow"` / `"expo"` - specific log
- `lines: 50` (default) - number of lines to return

**Full-stack mobile:**
```bash
mkdir -p .forj/logs
bb dev 2>&1 | tee .forj/logs/backend.log &
bb shadow 2>&1 | tee .forj/logs/shadow.log &
# Then prompt for device (see Step 4a)
```

### Step 4a: Mobile Device Selection (For Mobile Projects)

**First, check if Expo Web is available:**
```bash
# Check package.json for react-native-web
grep -q "react-native-web" package.json && echo "WEB_ENABLED"
```

**Ask the user (include Web option only if detected):**

> "How do you want to run the app?"
> 1. Android emulator (launches automatically)
> 2. iOS simulator (launches automatically)
> 3. Physical device (enter URL in Expo Go)
> 4. Web browser (if react-native-web installed) - great for Playwright/automation testing

**Use bb tasks (NOT npx):**

**Android:**
```bash
bb android 2>&1 | tee .forj/logs/expo.log &
```

**iOS:**
```bash
bb ios 2>&1 | tee .forj/logs/expo.log &
```

**Physical Device (Manual URL):**
```bash
# Get local IP
LOCAL_IP=$(ip route get 1 | awk '{print $7; exit}')

# Start Expo in background with logging
bb mobile 2>&1 | tee .forj/logs/expo.log &

# Wait for startup
sleep 3

# Display connection info
echo "Open Expo Go on your device and enter URL:"
echo "  exp://${LOCAL_IP}:8081"
```

**Web Browser (if react-native-web installed):**
```bash
npx expo start --web 2>&1 | tee .forj/logs/expo.log &

# Wait for startup
sleep 3

echo "Open http://localhost:8081 in your browser"
```

**Full-stack web:**
```bash
mkdir -p .forj/logs
bb dev 2>&1 | tee .forj/logs/backend.log &
bb shadow 2>&1 | tee .forj/logs/shadow.log &
```

**Backend only:**
```bash
mkdir -p .forj/logs
bb dev 2>&1 | tee .forj/logs/backend.log &
```

### Step 5: Start Application Server (REQUIRED - Don't Skip!)

**REPL running ≠ Done.** The application server must be started from within the REPL.

**Detection priority:**

1. **Common functions** - Search `src/**/core.clj` for:
   - `(defn start-server` → eval `(<namespace>/start-server)`
   - `(defn start` → eval `(<namespace>/start)`
   - `(defn go` → eval `(<namespace>/go)`

2. **State management** - Check requires:
   - `mount.core` → eval `(mount.core/start)`
   - `integrant.core` → look for `(ig/init ...)`
   - `component` → look for `(component/start ...)`

3. **Comment blocks** - Read them for startup examples

4. **Fallback** - Read core.clj and figure out how to start it

**Example:**
```
repl_eval with code="(require '[myapp.core :as core] :reload)"
repl_eval with code="(core/start-server {:port 3000})"
```

### Step 6: Verify Everything Works

Wait 2-5 seconds after starting, then:

```
discover_repls
```

Test each REPL:
```
repl_eval with code="(+ 1 2)" port=<backend-port>
repl_eval with code="(+ 1 2)" port=<shadow-port>
```

### Step 7: Report Success (Only After Everything Running)

**Final checklist before reporting:**
- [ ] All REPLs started for project type?
- [ ] Application server running and accepting requests?
- [ ] Expo running (if mobile project)?

Tell the user:
- All processes started
- Port numbers for each
- URLs (http://localhost:3000, http://localhost:9630, etc.)
- They can now use forj MCP tools

## Evaluating Code

When user asks to evaluate code, **ALWAYS use `repl_eval` MCP tool:**

```
repl_eval with code="(+ 1 2 3)"
```

The tool auto-discovers ports. Do NOT shell out to `clj-nrepl-eval`.

## Stopping a REPL

Find and kill the process (bash OK here):
```bash
lsof -i :<port>
kill $(lsof -t -i :<port>)
```

## Monorepo Handling

For projects with multiple build files:
1. Use `discover_repls` to see what's running
2. Ask user which to start if needed
3. Multiple REPLs can run on different ports
4. MCP tools auto-discover all ports

## Troubleshooting

**No REPLs found:**
- Use `discover_repls` to check what's running
- Try starting one with `/clj-repl bb` or `/clj-repl clj`

**Wrong project context:**
The nREPL's working directory determines the project. Make sure to start the REPL from the correct directory.

**Port conflicts:**
Each REPL needs a unique port. Use different ports for multiple REPLs.

## Notes

- Babashka nREPL is fast to start - good for quick scripts
- JVM Clojure takes 5-10 seconds to start but has full Clojure
- Shadow-cljs provides ClojureScript REPL with hot reload
- **All REPL interaction should use MCP tools, not bash**
