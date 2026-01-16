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
```

**Flags:**
| Flag | Module | Description |
|------|--------|-------------|
| `--api` | api | Clojure server with Ring/Reitit |
| `--db postgres` | db-postgres | PostgreSQL with next.jdbc |
| `--db sqlite` | db-sqlite | SQLite with next.jdbc |
| `--web` | web | ClojureScript web (Reagent/Re-frame) |
| `--mobile` | mobile | Expo + ClojureScript |
| `--script` | script | Babashka script only |

**Examples:**
- `/clj-init api-server --api --db postgres` → Backend + PostgreSQL
- `/clj-init my-site --web` → Web frontend only
- `/clj-init my-tool --script` → Babashka script

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

**Question:** "Do you need a api?"
**Options:**
1. **Yes** - Clojure server with Ring/Reitit
2. **No** - Frontend only or script

**If Yes, ask:**

**Question:** "Database?"
**Options:**
1. **None** - No database
2. **PostgreSQL** - With next.jdbc and HoneySQL
3. **SQLite** - Lightweight, file-based

### Step 3: Frontend

**Question:** "Do you need a frontend?"
**Options:**
1. **None** - Backend/script only
2. **Web** - ClojureScript with Reagent/Re-frame
3. **Mobile** - Expo + ClojureScript (React Native)
4. **Both** - Web and Mobile

### Step 4: Script-Only

If user selected No api AND No frontend → Script project (Babashka only)

## Module Selection

Based on answers, determine modules for `scaffold_project`:

| Configuration | Modules |
|---------------|---------|
| Script only | `["script"]` |
| Backend only | `["api"]` |
| Backend + PostgreSQL | `["api", "db-postgres"]` |
| Backend + SQLite | `["api", "db-sqlite"]` |
| Web only | `["web"]` |
| Mobile only | `["mobile"]` |
| Backend + Web | `["api", "web"]` |
| Backend + Mobile | `["api", "mobile"]` |
| Backend + Web + Mobile | `["api", "web", "mobile"]` |
| Backend + PostgreSQL + Web | `["api", "db-postgres", "web"]` |

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
4. **Next step: Start a new Claude Code session with REPL:**
   ```
   cd my-app && claude /clj-repl
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
| db-postgres | PostgreSQL support | next.jdbc, honeysql, pg driver |
| db-sqlite | SQLite support | next.jdbc, honeysql, sqlite driver |
| web | ClojureScript web | shadow-cljs.edn, package.json, reagent, re-frame |
| mobile | Expo mobile | shadow-cljs.edn, package.json, app.json, expo config |

Modules automatically include their dependencies (e.g., `api` includes `base`).

## DevOps (Optional)

Only if user asks, add basic deployment configs:

- **Dockerfile** - Simple multi-stage build
- **Procfile** - For Railway/Heroku
- **.github/workflows/test.yml** - Basic CI

Do NOT add complex infrastructure. Keep it simple.

## Future Enhancements

- **ClojureDart** - Flutter target for cross-platform mobile
- **HTMX** - Server-side rendering option
- **Electric** - Full-stack reactive apps
- **GraphQL** - Lacinia integration module
