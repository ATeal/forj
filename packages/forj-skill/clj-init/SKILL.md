---
name: clj-init
description: Scaffold a new Clojure project with composable modules. Creates hello-world examples only - no assumptions, no custom code.
---

# Clojure Project Scaffolding

Create minimal project structure to get started. This is scaffolding, NOT code generation.

## Philosophy

**CRITICAL:** This tool creates the bare minimum to start coding:
- Directory structure
- Build configuration (bb.edn, deps.edn, shadow-cljs.edn as needed)
- Hello world examples with `(comment ...)` blocks
- Basic .gitignore and README

**DO NOT:**
- Generate custom endpoints, routes, or pages
- Make assumptions about business logic
- Add features not explicitly requested
- Take creative liberties with code

The user will build their app with forj and REPLs after scaffolding.

## Usage Modes

### Direct Mode (Power Users)

Skip questions with flags:

```
/clj-init my-app --api --db postgres --web
/clj-init my-app --mobile
/clj-init my-app --api --mobile
/clj-init my-app --script
/clj-init my-app --flutter
/clj-init my-app --htmx simpleui --db postgres
/clj-init my-app --htmx biff
```

**Flags:**
| Flag | Module | Description |
|------|--------|-------------|
| `--api` | api | Clojure server with Ring/Reitit |
| `--db postgres` | db-postgres | PostgreSQL with next.jdbc |
| `--db sqlite` | db-sqlite | SQLite with next.jdbc |
| `--htmx simpleui` | htmx-simpleui | HTMX with SimpleUI (lightweight) |
| `--htmx biff` | (external) | HTMX with Biff (uses Biff generator) |
| `--web` | web | ClojureScript web (Reagent/Re-frame) |
| `--mobile` | mobile | Expo + ClojureScript |
| `--script` | script | Babashka script only |
| `--flutter` | flutter | ClojureDart Flutter (mobile/desktop/web) |

**Examples:**
- `/clj-init api-server --api --db postgres` → REST API + PostgreSQL
- `/clj-init my-site --web` → Web frontend only
- `/clj-init my-tool --script` → Babashka script
- `/clj-init my-app --flutter` → ClojureDart Flutter app
- `/clj-init my-app --htmx simpleui` → HTMX web app with SimpleUI
- `/clj-init my-app --htmx biff` → Triggers Biff flow (external generator)

### Guided Mode (Interactive)

```
/clj-init
/clj-init my-app
```

If no flags provided, ask questions to determine modules.

## Question Flow (Guided Mode)

### Step 1: Project Name

If not provided, ask in plain text:

> "What would you like to name the project?"

**CRITICAL: Do NOT use AskUserQuestion tool for the project name.** Just output the question as text. The name is free-form text, not multiple choice.

### Step 2: Backend

**Question:** "Do you need a backend/API?"
**Options:**
1. **Yes** - Clojure server
2. **No** - Frontend only or script

**If Yes, ask:**

**Question:** "What kind of interface?"
**Options:**
1. **API only** - REST endpoints with Ring/Reitit
2. **HTMX web app** - Server-rendered HTML
3. **Separate frontend** - API that serves a SPA or mobile app

**If HTMX selected, ask:**

**Question:** "Which HTMX framework?"
**Options:**
1. **Biff** - Full-featured web framework (opinionated, batteries included)
2. **SimpleUI** - Lightweight library on Ring/Reitit

**If Biff selected:** See [Biff Flow](#biff-flow) below - uses external generator.

**If API only, SimpleUI, or Separate frontend, ask:**

**Question:** "Database?"
**Options:**
1. **None** - No database
2. **PostgreSQL** - With next.jdbc and HoneySQL
3. **SQLite** - Lightweight, file-based

### Step 3: Frontend

**Only ask if "Separate frontend" was selected OR if user said No to backend.**

**Question:** "Do you need a frontend?"
**Options:**
1. **None** - Backend/script only
2. **ClojureScript** - Web and/or mobile with Expo
3. **Flutter** - ClojureDart (mobile/desktop/web)

**If ClojureScript selected, ask:**

**Question:** "Which platforms?"
**Options:**
1. **Web only** - Browser app with Reagent/Re-frame
2. **Mobile only** - Expo/React Native
3. **Both** - Web and mobile

**Note:** Flutter uses ClojureDart (a different Clojure dialect). It cannot be combined with ClojureScript frontends, but works fine with a Clojure backend.

### Step 4: Script-Only

If user selected No backend AND No frontend → Script project (Babashka only)

## Biff Flow

Biff is an opinionated full-stack framework with its own project generator. We don't scaffold it directly.

**When user selects Biff:**

1. Output this message:
   > "Biff has its own project generator. Please run this command in another terminal:
   >
   > ```
   > clj -M -e '(load-string (slurp "https://biffweb.com/new.clj"))'
   > ```
   >
   > Follow the prompts to create your project. Let me know when you're done and what you named the project."

2. Wait for user to confirm completion and provide project name.

3. Once they confirm (e.g., "done, called it myapp"):
   - Run `validate_project` with path `./myapp` and `fix=true`
   - Ask about permissions (see [After Scaffolding](#after-scaffolding))
   - Tell them: `cd myapp && claude /clj-repl`

**IMPORTANT:** Do NOT try to run Biff's generator programmatically. Let the user interact with it directly.

## Module Selection

Based on answers, determine modules for `scaffold_project`:

| Configuration | Modules |
|---------------|---------|
| Script only | `["script"]` |
| API only | `["api"]` |
| API + PostgreSQL | `["api", "db-postgres"]` |
| API + SQLite | `["api", "db-sqlite"]` |
| HTMX (Biff) | **Use Biff flow** (not scaffold_project) |
| HTMX (SimpleUI) | `["api", "htmx-simpleui"]` |
| HTMX (SimpleUI) + PostgreSQL | `["api", "htmx-simpleui", "db-postgres"]` |
| HTMX (SimpleUI) + SQLite | `["api", "htmx-simpleui", "db-sqlite"]` |
| Web only | `["web"]` |
| Mobile (Expo) only | `["mobile"]` |
| Web + Mobile (Expo) | `["web", "mobile"]` |
| Flutter only | `["flutter"]` |
| API + Web | `["api", "web"]` |
| API + Mobile (Expo) | `["api", "mobile"]` |
| API + Web + Mobile (Expo) | `["api", "web", "mobile"]` |
| API + Flutter | `["api", "flutter"]` |
| API + PostgreSQL + Web | `["api", "db-postgres", "web"]` |
| API + PostgreSQL + Flutter | `["api", "db-postgres", "flutter"]` |

## Creating the Project

**CRITICAL: Use the `scaffold_project` MCP tool. Do NOT manually write files.**

Once you have the project name and modules list:

```
scaffold_project with:
  project_name: "my-app"
  modules: ["api", "web"]
  output_path: "." (optional, defaults to current directory)
```

The tool handles everything:
- Merges configs from all modules (deps.edn, bb.edn, shadow-cljs.edn, package.json)
- Substitutes version placeholders from versions.edn
- Copies source files with namespace substitution
- Handles module dependencies (e.g., db-postgres requires api)

## Validation (REQUIRED)

After scaffold_project succeeds, run validation:

```
validate_project with path="./my-app" fix=true
```

This tool handles:
- **bb.edn repl task** - Adds `:override-builtin true` if missing
- **deps.edn resolution** - Verifies dependencies resolve
- **npm install** - Runs if package.json exists but node_modules doesn't
- **Java version** - Reports if Java version is below 21 for shadow-cljs

**DO NOT tell the user the project is ready until `validate_project` returns `success: true`.**

## After Scaffolding

### Step 1: Ask About Permissions

**Question:** "Would you like to enable forj tool permissions for this project?"
**Options:**
1. **Yes (Recommended)** - Auto-approve forj MCP tools and bb tasks
2. **No** - I'll approve tools manually as needed

**If Yes**, create `.claude/settings.local.json` in the project:

```bash
mkdir -p ./my-app/.claude
```

Then write this file:

```json
{
  "permissions": {
    "allow": [
      "Bash(bb:*)",
      "Bash(mkdir:*)",
      "Bash(pgrep:*)",
      "Bash(pkill:*)",
      "Bash(lsof:*)",
      "mcp__forj__repl_eval",
      "mcp__forj__discover_repls",
      "mcp__forj__reload_namespace",
      "mcp__forj__doc_symbol",
      "mcp__forj__eval_at",
      "mcp__forj__eval_comment_block",
      "mcp__forj__run_tests",
      "mcp__forj__analyze_project",
      "mcp__forj__validate_changed_files",
      "mcp__forj__track_process",
      "mcp__forj__stop_project",
      "mcp__forj__list_tracked_processes",
      "mcp__forj__view_repl_logs"
    ]
  }
}
```

### Step 2: Report Success

Tell the user:
1. Project created at `./my-app`
2. Dependencies verified ✓
3. Permissions configured (if they chose yes)
4. **Next step:**

**For Clojure/ClojureScript projects:**
```
cd my-app && claude /clj-repl
```

**For Flutter projects:**
```
cd my-app
bb flutter   # Run app with hot reload + REPL
```
ClojureDart is initialized automatically during scaffolding. REPL starts automatically - watch for `🤫 ClojureDart REPL listening on port XXXXX` in output, then connect with `nc localhost XXXXX`.

**If project has BOTH backend AND Flutter:**
```
cd my-app && claude /clj-repl   # For the Clojure backend
# In another terminal:
bb flutter                       # For Flutter frontend
```

**IMPORTANT:** Do NOT offer to start the REPL from the current session. The user should exit and restart Claude Code in the new project directory so that:
- Hooks detect the correct project type
- REPL starts in the correct working directory
- File paths are relative to the project root

**STOP HERE.** Do not start building or generating code. The scaffolding is complete.

## Available Modules

| Module | Description | Provides |
|--------|-------------|----------|
| base | Common files | .gitignore, README.md |
| script | Babashka script | bb.edn, hello-world src |
| api | Ring/Reitit server | deps.edn, bb.edn, core.clj, routes.clj |
| htmx-simpleui | HTMX with SimpleUI | simpleui dep, htmx, hiccup views |
| db-postgres | PostgreSQL support | next.jdbc, honeysql, pg driver |
| db-sqlite | SQLite support | next.jdbc, honeysql, sqlite driver |
| web | ClojureScript web | shadow-cljs.edn, package.json, reagent, re-frame |
| mobile | Expo mobile | shadow-cljs.edn, package.json, app.json, expo config |
| flutter | ClojureDart Flutter | deps.edn (git dep), bb.edn, main.cljd |

Modules automatically include their dependencies (e.g., `api` includes `base`, `htmx-simpleui` includes `api`).

**Notes:**
- The `flutter` module uses ClojureDart (a different Clojure dialect). It can be combined with backend modules (`api`, `db-*`) but NOT with ClojureScript frontend modules (`web`, `mobile`).
- The `htmx-simpleui` module is for server-rendered HTML apps. It cannot be combined with `web` or `mobile` modules (no ClojureScript needed).
- For Biff (full-featured HTMX framework), use the Biff flow instead of scaffold_project.

## DevOps (Optional)

Only if user asks, add basic deployment configs:

- **Dockerfile** - Simple multi-stage build
- **Procfile** - For Railway/Heroku
- **.github/workflows/test.yml** - Basic CI

Do NOT add complex infrastructure. Keep it simple.

## Future Enhancements

- **Electric** - Full-stack reactive apps
- **GraphQL** - Lacinia integration module
