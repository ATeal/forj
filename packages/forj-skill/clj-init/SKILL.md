---
name: clj-init
description: Scaffold a new Clojure project with minimal structure. Creates hello-world examples only - no assumptions, no custom code.
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
- **Make up dependency versions from memory**

**USE TEMPLATES:** Read files from `packages/forj-skill/clj-init/templates/` and copy them with placeholder substitution. See Templates section below.

The user will build their app with forj and REPLs after scaffolding.

## Commands

| Command | Action |
|---------|--------|
| `/clj-init` | Start interactive project creation |
| `/clj-init my-app` | Create project with name "my-app" |

## Question Flow

### Step 1: Project Name

If not provided via `/clj-init <name>`, simply ask in plain text:

> "What would you like to name the project?"

Accept any valid name (lowercase, hyphens ok).

**CRITICAL: Do NOT use AskUserQuestion tool for the project name.** Just output the question as text and wait for the user to type the name. The name is free-form text, not a multiple choice.

❌ WRONG: Using AskUserQuestion with options like "my-app", "api-service"
✅ RIGHT: Just ask "What would you like to name the project?" as plain text output

### Step 2: Backend

**Question:** "Do you need a backend?"
**Options:**
1. **Yes** - Clojure server with Ring/Reitit
2. **No** - Frontend only or script

**If Yes, ask:**

**Question:** "Database?"
**Options:**
1. **None** - No database
2. **PostgreSQL** - With next.jdbc and HoneySQL
3. **SQLite** - Lightweight, file-based

**When adding database deps, use these exact versions:**
```clojure
com.github.seancorfield/next.jdbc {:mvn/version "1.3.1070"}
com.github.seancorfield/honeysql {:mvn/version "2.7.1350"}
org.postgresql/postgresql {:mvn/version "42.7.4"}        ; for PostgreSQL
org.xerial/sqlite-jdbc {:mvn/version "3.47.2.0"}         ; for SQLite
```

**Question:** "API style?"
**Options:**
1. **REST** - Standard REST endpoints
2. **GraphQL** - With Lacinia
<!-- Future: gRPC -->

### Step 3: Frontend

**Question:** "Do you need a frontend?"
**Options:**
1. **None** - Backend/script only
2. **Web** - ClojureScript with Reagent/Re-frame
3. **Mobile** - Expo + ClojureScript (React Native)
4. **Both** - Web and Mobile sharing code

<!-- FUTURE: ClojureDart option for Flutter -->

**If Web or Both, ask:**

**Question:** "Styling framework?"
**Options:**
1. **None** - Plain CSS
2. **Tailwind** - Utility-first CSS
3. **Material UI** - React component library

**If Mobile or Both:**

**Question:** "Include React Native Web support?"
**Options:**
1. **Yes** - Run in browser for testing (Playwright, Chrome automation)
2. **No** - Native only (Android/iOS)

*Note: RN Web lets you run the mobile app in a browser, useful for UI testing with tools like Playwright or Claude Chrome MCP.*

If yes, add to package.json dependencies:
```json
"react-dom": "19.2.3",
"react-native-web": "~0.21.2"
```

Mobile uses React Native's built-in styling. No additional framework needed.

### Step 4: Script-Only Option

If user selected No backend AND No frontend, this is a Babashka script project:
- Just bb.edn with tasks
- src/ with hello world
- No deps.edn needed

## Project Structures

### Backend Only (REST)

```
my-app/
├── bb.edn                  # Tasks: dev, test, run
├── deps.edn
├── src/my_app/
│   ├── core.clj            # Entry point, start server
│   └── routes.clj          # Hello world route
├── test/my_app/
│   └── core_test.clj
├── resources/
├── .gitignore
└── README.md
```

**routes.clj (hello world only):**
```clojure
(ns my-app.routes
  (:require [reitit.ring :as ring]))

(defn hello-handler [_request]
  {:status 200
   :body {:message "Hello, World!"}})

(def routes
  [["/hello" {:get hello-handler}]])

(comment
  ;; REPL exploration
  (hello-handler {})
  )
```

### Web Frontend Only

```
my-app/
├── bb.edn
├── shadow-cljs.edn
├── package.json
├── src/my_app/
│   ├── core.cljs           # Init, mount root
│   └── views.cljs          # Hello world component
├── resources/public/
│   └── index.html
├── .gitignore
└── README.md
```

**views.cljs (hello world only):**
```clojure
(ns my-app.views)

(defn app []
  [:div
   [:h1 "Hello, World!"]
   [:p "Edit src/my_app/views.cljs and save to reload."]])

(comment
  ;; REPL exploration
  (app)
  )
```

### Mobile Only (Expo)

```
my-app/
├── bb.edn
├── shadow-cljs.edn
├── package.json
├── app.json
├── index.js
├── babel.config.js
├── src/my_app/
│   ├── core.cljs
│   └── views.cljs          # Hello world screen
├── src/expo/
│   └── root.cljs
├── assets/
├── .gitignore
└── README.md
```


### Full-Stack (Backend + Web)

```
my-app/
├── bb.edn
├── deps.edn
├── shadow-cljs.edn
├── package.json
├── src/
│   ├── clj/my_app/         # Backend
│   │   ├── core.clj
│   │   └── routes.clj
│   └── cljs/my_app/        # Frontend
│       ├── core.cljs
│       └── views.cljs
├── resources/public/
│   └── index.html
├── .gitignore
└── README.md
```

### Full-Stack (Backend + Mobile)

```
my-app/
├── bb.edn
├── deps.edn                # Backend deps
├── shadow-cljs.edn         # source-paths: ["src/cljs" "src"]
├── package.json
├── app.json                # Expo config
├── index.js                # Expo entry
├── babel.config.js
├── src/
│   ├── clj/my_app/         # Backend Clojure
│   │   ├── core.clj
│   │   ├── routes.clj
│   │   └── handlers.clj
│   ├── cljs/my_app/        # ClojureScript (mobile)
│   │   ├── core.cljs
│   │   ├── views.cljs
│   │   ├── events.cljs
│   │   └── subs.cljs
│   └── expo/               # Expo root helper
│       └── root.cljs
├── resources/
├── .gitignore
└── README.md
```

**Use template:** `fullstack-mobile/`

### Full-Stack (Backend + Web + Mobile)

```
my-app/
├── bb.edn
├── deps.edn
├── shadow-cljs.edn
├── package.json
├── app.json                 # Expo config
├── index.js                 # Expo entry
├── src/
│   ├── clj/my_app/         # Backend
│   │   ├── core.clj
│   │   └── routes.clj
│   ├── cljs/my_app/        # Shared ClojureScript
│   │   ├── core.cljs
│   │   └── views.cljs      # Can be shared between web/mobile
│   ├── web/my_app/         # Web-specific
│   │   └── app.cljs
│   └── mobile/my_app/      # Mobile-specific
│       └── app.cljs
├── resources/public/
├── .gitignore
└── README.md
```

### Script (Babashka only)

```
my-app/
├── bb.edn
├── src/my_app/
│   └── core.clj
├── .gitignore
└── README.md
```

## bb.edn Tasks

All projects use Babashka for task running.

**CRITICAL:** Always use `clojure` command, NOT `clj`. The `clj` command requires `rlwrap` and fails in headless/background operation. The `clojure` command works without rlwrap.

Example:

```clojure
{:paths ["src"]
 :tasks
 {dev {:doc "Start development"
       :task (do
               (when (fs/exists? "shadow-cljs.edn")
                 (shell "npx shadow-cljs watch app"))
               (when (fs/exists? "deps.edn")
                 (shell "clojure -M:dev")))}

  test {:doc "Run tests"
        :task (shell "clojure -M:test")}

  repl {:doc "Start nREPL"
        :override-builtin true
        :task (shell "clojure -M:dev")}

  ;; For Expo projects
  mobile {:doc "Start Expo"
          :task (shell "npx expo start")}

  build {:doc "Production build"
         :task (do
                 (when (fs/exists? "shadow-cljs.edn")
                   (shell "npx shadow-cljs release app"))
                 (when (fs/exists? "deps.edn")
                   (shell "clojure -M:uberjar")))}}}
```

## DevOps (Minimal)

Only if user asks, add basic deployment configs:

- **Dockerfile** - Simple multi-stage build
- **Procfile** - For Railway/Heroku
- **.github/workflows/test.yml** - Basic CI

Do NOT add complex infrastructure. Keep it simple.

## Validation (REQUIRED)

**CRITICAL: Use the `validate_project` MCP tool. Do NOT run bash commands manually for validation.**

After creating files, call the forj MCP tool:

```
validate_project with path="./my-app" fix=true
```

This tool handles everything:
- **bb.edn repl task** - Adds `:override-builtin true` if missing
- **deps.edn resolution** - Verifies dependencies resolve
- **npm install** - Runs if package.json exists but node_modules doesn't
- **Java version** - Reports if Java version is below 21 for shadow-cljs (informational)

If `validate_project` reports `deps-resolve-failed`, fix deps.edn with known-good versions:
- `com.github.seancorfield/honeysql {:mvn/version "2.7.1350"}`
- `com.github.seancorfield/next.jdbc {:mvn/version "1.3.1070"}`
- `metosin/reitit {:mvn/version "0.9.2"}`
- `ring/ring-core {:mvn/version "1.15.3"}`

Then run `validate_project` again to confirm the fix.

**DO NOT tell the user the project is ready until `validate_project` returns `success: true`.**

## After Scaffolding

Tell the user:
1. Project created at `./my-app`
2. Dependencies verified ✓
3. **Next step: Start a new Claude Code session with REPL:**
   ```
   cd my-app && claude /clj-repl
   ```

**IMPORTANT:** Do NOT offer to start the REPL from the current session. The user should exit and restart Claude Code in the new project directory so that:
- Hooks detect the correct project type
- REPL starts in the correct working directory
- File paths are relative to the project root

**STOP HERE.** Do not start building or generating code. The scaffolding is complete.

## Templates

**CRITICAL: You MUST read and use the template files. Do NOT generate files from memory.**

Templates are in `packages/forj-skill/clj-init/templates/`:
- `bb/` - Babashka script
- `lib/` - Library
- `api/` - Backend API
- `web/` - Web frontend
- `mobile/` - Expo mobile (no backend)
- `fullstack/` - Backend + Web frontend
- `fullstack-mobile/` - Backend + Expo mobile
- `common/` - Shared files

**When creating a project:**
1. Read the relevant template files (e.g., `templates/mobile/package.json`)
2. Copy contents, replacing `{{project-name}}` and `{{namespace}}` placeholders
3. Do NOT make up versions or dependencies - use exactly what's in the templates

The templates contain tested, compatible dependency versions. Using outdated versions from memory will cause runtime errors.

## Future Enhancements

- **ClojureDart** - Flutter target for cross-platform mobile
- **HTMX** - Server-side rendering option
- **Electric** - Full-stack reactive apps
