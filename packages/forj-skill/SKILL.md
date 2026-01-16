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
| `/clj-repl status` | Check tracked + discovered REPLs |
| `/clj-repl stop` | Stop all tracked REPLs (uses `stop_project` tool) |

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

### Step 1: Check for Existing Processes

**First, check for tracked processes from a previous session:**

```
list_tracked_processes
```

If there are tracked processes that are still alive:
- Tell the user what's already running (names, ports, PIDs)
- Ask: "Found existing processes from a previous session. Would you like to:"
  1. **Keep them** - Use the existing REPLs (recommended if they're working)
  2. **Restart** - Stop all and start fresh
  3. **Stop only** - Just stop them, don't start new ones

If processes are tracked but all dead, clean them up silently:
```
stop_project
```

**Then discover running REPLs (may include untracked ones):**

```
discover_repls
```

If REPLs are already running for this project, report them and ask if user wants to use existing or start fresh.

### Step 2: Detect Project Type & What to Start

Check which config files exist:

| Files Present | What to Start |
|---------------|---------------|
| `deps.edn` + `shadow-cljs.edn` + `app.json` | Backend + shadow-cljs (check for multiple builds!) + Expo |
| `deps.edn` + `shadow-cljs.edn` | Backend + shadow-cljs (check for multiple builds!) |
| `shadow-cljs.edn` + `app.json` | shadow-cljs + Expo |
| `deps.edn` only | Backend REPL |
| `shadow-cljs.edn` only | shadow-cljs |
| `bb.edn` only | Babashka REPL |

---

**⚠️ CHECK FOR MULTIPLE SHADOW BUILDS:**

Many projects have BOTH `:web` and `:mobile` builds. **Check shadow-cljs.edn:**

```bash
grep -E "^\s+:(web|mobile|app|main)" shadow-cljs.edn
```

If you see multiple builds (e.g., `:web` AND `:mobile`):
- Start **both** shadow builds: `bb shadow:web` AND `bb shadow:mobile`
- Don't assume one is enough!

**Example project with both:**
```
bb dev           # Backend REPL
bb shadow:web    # Web frontend (port 8080)
bb shadow:mobile # Mobile frontend
bb expo:web      # Expo dev server
```

**Do NOT stop after starting REPLs. The app server must also be running.**

---

### Step 3: Use bb Tasks (Required When Available)

If `bb.edn` has relevant tasks, **you MUST use them** instead of raw commands.

| Task | Use For |
|------|---------|
| `bb dev` or `bb repl` | Backend REPL |
| `bb shadow:mobile` | ClojureScript for Expo (outputs to `app/`) |
| `bb shadow:web` | ClojureScript for browser |
| `bb expo` / `bb expo:android` / `bb expo:ios` / `bb expo:web` | Expo dev server |

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
bb shadow:web 2>&1 | tee .forj/logs/shadow.log &   # or shadow:mobile for Expo
```

**IMPORTANT: Track each process after starting!**

After each background process starts, call `track_process` with the PID (from Bash tool output):
```
track_process with pid=<PID> name="backend-repl" port=<PORT> command="bb dev"
track_process with pid=<PID> name="shadow-cljs" port=9630 command="bb shadow:mobile"  # or shadow:web
track_process with pid=<PID> name="expo" port=8081 command="bb expo"
```

This enables `/clj-repl stop` to cleanly shut down all processes later.

**To view logs later, use the `view_repl_logs` MCP tool:**
- `view_repl_logs` with `log: "all"` - see all logs at once
- `view_repl_logs` with `log: "backend"` / `"shadow"` / `"expo"` - specific log
- `lines: 50` (default) - number of lines to return

**Full-stack with web + mobile (check shadow-cljs.edn for builds!):**
```bash
mkdir -p .forj/logs
bb dev 2>&1 | tee .forj/logs/backend.log &
# Check shadow-cljs.edn for builds - start ALL that exist:
bb shadow:web 2>&1 | tee .forj/logs/shadow-web.log &     # If :web build exists
bb shadow:mobile 2>&1 | tee .forj/logs/shadow-mobile.log &  # If :mobile build exists
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
bb expo:android 2>&1 | tee .forj/logs/expo.log &
```

**iOS:**
```bash
bb expo:ios 2>&1 | tee .forj/logs/expo.log &
```

**Physical Device (Manual URL):**
```bash
# Get local IP
LOCAL_IP=$(ip route get 1 | awk '{print $7; exit}')

# Start Expo in background with logging
bb expo 2>&1 | tee .forj/logs/expo.log &

# Wait for startup
sleep 3

# Display connection info
echo "Open Expo Go on your device and enter URL:"
echo "  exp://${LOCAL_IP}:8081"
```

**Web Browser (if react-native-web installed):**
```bash
bb expo:web 2>&1 | tee .forj/logs/expo.log &

# Wait for startup
sleep 3

echo "Open http://localhost:8081 in your browser"
```

**Full-stack web:**
```bash
mkdir -p .forj/logs
bb dev 2>&1 | tee .forj/logs/backend.log &
bb shadow:web 2>&1 | tee .forj/logs/shadow.log &
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

**Check shadow-cljs.edn for dev-http ports:**
```bash
grep -o ':dev-http {[0-9]*' shadow-cljs.edn
```

If `:dev-http` is present (e.g., `{8080 "resources/public"}`), the web app is served on that port.

**Report all services in a table:**

| Service | Port | URL |
|---------|------|-----|
| Backend API | 3000 | http://localhost:3000 |
| Web App (dev-http) | 8080 | http://localhost:8080 |
| Expo Web | 8081 | http://localhost:8081 |
| shadow-cljs UI | 9630 | http://localhost:9630 |

**Include only the services actually running for this project type.**

Tell the user:
- All processes started
- Port numbers for each service
- URLs for accessing each service
- They can now use forj MCP tools

## Evaluating Code

When user asks to evaluate code, **ALWAYS use `repl_eval` MCP tool:**

```
repl_eval with code="(+ 1 2 3)"
```

The tool auto-discovers ports. Do NOT shell out to `clj-nrepl-eval`.

## Checking Status (`/clj-repl status`)

Run both tools and summarize:
```
list_tracked_processes
discover_repls
```

Report to user:
- **Tracked processes**: What this session started (with alive/dead status)
- **Discovered REPLs**: All nREPL servers found (may include untracked ones)

## Stopping REPLs

**Use the `stop_project` MCP tool** to stop all tracked processes:

```
stop_project
```

This kills all REPLs, shadow-cljs, and Expo processes that were tracked with `track_process`.

**To check what's tracked before stopping:**
```
list_tracked_processes
```

**Manual fallback** (if processes weren't tracked):
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
