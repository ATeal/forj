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

The user will build their app with forj and REPLs after scaffolding.

## Commands

| Command | Action |
|---------|--------|
| `/clj-init` | Start interactive project creation |
| `/clj-init my-app` | Create project with name "my-app" |

## Question Flow

### Step 1: Project Name

If not provided, ask for project name (lowercase, hyphens ok).

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

Same as above but with Expo structure instead of web.

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

All projects use Babashka for task running. Example:

```clojure
{:paths ["src"]
 :tasks
 {dev {:doc "Start development"
       :task (do
               (when (fs/exists? "shadow-cljs.edn")
                 (shell "npx shadow-cljs watch app"))
               (when (fs/exists? "deps.edn")
                 (shell "clj -M:dev")))}

  test {:doc "Run tests"
        :task (shell "clj -M:test")}

  repl {:doc "Start nREPL"
        :task (shell "clj -M:dev")}

  ;; For Expo projects
  mobile {:doc "Start Expo"
          :task (shell "npx expo start")}

  build {:doc "Production build"
         :task (do
                 (when (fs/exists? "shadow-cljs.edn")
                   (shell "npx shadow-cljs release app"))
                 (when (fs/exists? "deps.edn")
                   (shell "clj -M:uberjar")))}}}
```

## DevOps (Minimal)

Only if user asks, add basic deployment configs:

- **Dockerfile** - Simple multi-stage build
- **Procfile** - For Railway/Heroku
- **.github/workflows/test.yml** - Basic CI

Do NOT add complex infrastructure. Keep it simple.

## Validation (REQUIRED)

After creating files, ALWAYS verify the project works:

### 1. Validate Dependencies

```bash
# For deps.edn projects
cd my-app && clj -Spath > /dev/null && echo "✓ deps resolve"

# For shadow-cljs projects
cd my-app && npm install && echo "✓ npm deps installed"
```

If deps fail, fix version numbers immediately. Use known-good versions:
- `com.github.seancorfield/honeysql {:mvn/version "2.6.1220"}`
- `com.github.seancorfield/next.jdbc {:mvn/version "1.3.939"}`
- `metosin/reitit {:mvn/version "0.7.2"}`
- `ring/ring-core {:mvn/version "1.12.2"}`

### 2. Fix bb.edn Warnings

Always add `:override-builtin true` to repl task:
```clojure
repl {:doc "Start nREPL"
      :override-builtin true
      :task (shell "clj -M:dev")}
```

### 3. Smoke Test

Start a REPL to verify compilation:
```bash
# Babashka (fast)
bb nrepl-server 1667 &
sleep 1
echo "(+ 1 2)" | clj-nrepl-eval -p 1667

# Or JVM Clojure
clj -M:dev &
```

### 4. Report Issues

If anything fails during validation, fix it before telling the user the project is ready.

## After Scaffolding

Tell the user:
1. Project created at `./my-app`
2. Dependencies verified ✓
3. Start with: `cd my-app && bb dev`
4. REPLs will auto-connect based on file type

**STOP HERE.** Do not start building or generating code. The scaffolding is complete - the user will take over from here.

Offer to start the REPL with `/clj-repl`.

## Templates

Templates are in `packages/forj-skill/clj-init/templates/`:
- `bb/` - Babashka script
- `lib/` - Library
- `api/` - Backend API
- `web/` - Web frontend
- `mobile/` - Expo mobile
- `fullstack/` - Combined
- `common/` - Shared files

## Future Enhancements

- **ClojureDart** - Flutter target for cross-platform mobile
- **HTMX** - Server-side rendering option
- **Electric** - Full-stack reactive apps
