---
name: clj-init
description: Create a new Clojure project with interactive configuration. Generates project structure, build config, and forj integration.
---

# Clojure Project Generator

Create new Clojure/ClojureScript/Babashka projects interactively.

## Quick Reference

| Command | Action |
|---------|--------|
| `/clj-init` | Start interactive project creation |
| `/clj-init my-app` | Create project with name "my-app" |

## Instructions

### Step 1: Get Project Name

If not provided as argument, ask for project name:
- Must be valid directory name (lowercase, hyphens ok)
- Will be used as namespace prefix (hyphens → underscores)

### Step 2: Choose Project Type

Use AskUserQuestion to ask:

**Question:** "What kind of project?"
**Options:**
1. **Script/CLI** - Babashka only, fast startup, great for scripts and tools
2. **Library** - deps.edn, portable Clojure library
3. **Web API** - Backend with Ring/Reitit, ready for deployment
4. **Full-stack** - Backend + ClojureScript frontend (Reagent/Re-frame)
5. **Mobile** - Expo + ClojureScript (Reagent/Re-frame) for iOS/Android

### Step 3: Ask About Features

Based on project type, ask relevant follow-up questions using AskUserQuestion:

**For Web API / Full-stack:**
- Database? (None / PostgreSQL / SQLite)
- Auth? (None / Basic session / JWT)

**For all projects:**
- Include Docker setup? (Yes / No)
- Include CI/GitHub Actions? (Yes / No)

### Step 4: Generate Project

Create the project directory and files based on choices.

#### Script/CLI (Babashka)

```
my-app/
├── bb.edn
├── src/
│   └── my_app/
│       └── core.clj
├── test/
│   └── my_app/
│       └── core_test.clj
├── .gitignore
└── README.md
```

**bb.edn:**
```clojure
{:paths ["src" "test"]
 :deps {}
 :tasks
 {dev {:doc "Start development REPL"
       :task (shell "bb" "--nrepl-server")}
  test {:doc "Run tests"
        :task (shell "bb" "-cp" "src:test" "-m" "my-app.core-test")}
  run {:doc "Run the application"
       :task (exec 'my-app.core/-main)}}}
```

**src/my_app/core.clj:**
```clojure
(ns my-app.core)

(defn greet [name]
  (str "Hello, " name "!"))

(defn -main [& args]
  (println (greet (or (first args) "World"))))

(comment
  ;; REPL-driven development
  (greet "Claude")

  (-main)
  (-main "Developer")
  )
```

#### Library (deps.edn)

```
my-app/
├── deps.edn
├── src/
│   └── my_app/
│       └── core.clj
├── test/
│   └── my_app/
│       └── core_test.clj
├── .gitignore
└── README.md
```

**deps.edn:**
```clojure
{:paths ["src"]
 :deps {}
 :aliases
 {:dev {:extra-paths ["test"]
        :extra-deps {nrepl/nrepl {:mvn/version "1.1.0"}
                     cider/cider-nrepl {:mvn/version "0.45.0"}}
        :main-opts ["-m" "nrepl.cmdline" "--interactive"]}
  :test {:extra-paths ["test"]
         :extra-deps {io.github.cognitect-labs/test-runner
                      {:git/tag "v0.5.1" :git/sha "dfb30dd"}}
         :main-opts ["-m" "cognitect.test-runner"]
         :exec-fn cognitect.test-runner.api/test}}}
```

#### Web API

```
my-app/
├── deps.edn
├── src/
│   └── my_app/
│       ├── core.clj
│       ├── routes.clj
│       └── handlers.clj
├── test/
│   └── my_app/
│       └── routes_test.clj
├── resources/
├── .gitignore
└── README.md
```

**deps.edn (add to base):**
```clojure
{:deps {org.clojure/clojure {:mvn/version "1.11.1"}
        ring/ring-core {:mvn/version "1.10.0"}
        ring/ring-jetty-adapter {:mvn/version "1.10.0"}
        metosin/reitit {:mvn/version "0.7.0"}
        metosin/muuntaja {:mvn/version "0.6.8"}}}
```

#### Full-stack

Includes Web API structure plus:

```
my-app/
├── deps.edn
├── shadow-cljs.edn
├── src/
│   ├── clj/my_app/        # Backend
│   │   ├── core.clj
│   │   └── routes.clj
│   └── cljs/my_app/       # Frontend
│       ├── core.cljs
│       └── views.cljs
├── resources/
│   └── public/
│       └── index.html
└── ...
```

#### Mobile (Expo + ClojureScript)

```
my-app/
├── shadow-cljs.edn
├── package.json
├── app.json              # Expo config
├── index.js              # JS entry (disables Metro HMR)
├── babel.config.js
├── src/
│   ├── my_app/
│   │   ├── core.cljs     # App entry, init/start
│   │   ├── views.cljs    # React Native components
│   │   ├── events.cljs   # Re-frame events
│   │   └── subs.cljs     # Re-frame subscriptions
│   └── expo/
│       └── root.cljs     # Expo root component helper
├── assets/               # Icons, splash screens
├── app/                  # shadow-cljs output (gitignored)
├── .gitignore
└── README.md
```

**shadow-cljs.edn:**
```clojure
{:source-paths ["src"]
 :dependencies [[reagent "1.2.0"]
                [re-frame "1.4.3"]]
 :builds {:app {:target :react-native
                :init-fn my-app.core/init
                :output-dir "app"
                :devtools {:autoload true}}}}
```

**Development:**
```bash
npm install
npm run dev              # Starts shadow-cljs watch + Expo
```

**Building:**
```bash
npm run shadow:release   # Production ClojureScript build
npx expo prebuild        # Generate native projects
npx expo run:ios         # Run on iOS
npx expo run:android     # Run on Android
```

### Step 5: Optional Additions

**If PostgreSQL selected:**
- Add next.jdbc and HoneySQL deps
- Create src/my_app/db.clj with connection pool setup
- Add .env.example with DATABASE_URL

**If Docker selected:**
- Create Dockerfile (multi-stage for production)
- Create docker-compose.yml (with db if needed)

**If CI selected:**
- Create .github/workflows/test.yml

### Step 6: Initialize Git & Report

```bash
cd my-app
git init
git add .
git commit -m "Initial commit from clj-init"
```

Tell user:
- Project created at ./my-app
- How to start: `cd my-app && bb dev` or `clj -M:dev`
- Next steps based on project type

### Step 7: Offer to Start REPL

Ask if user wants to start a REPL in the new project. If yes, use `/clj-repl` skill.

## Templates Reference

The skill uses templates from `packages/forj-skill/clj-init/templates/`:
- `bb/` - Babashka project files
- `lib/` - Library project files
- `api/` - Web API project files
- `fullstack/` - Full-stack project files
- `mobile/` - Expo + ClojureScript project files
- `common/` - Shared files (.gitignore, etc.)

## Notes

- Always include a `(comment ...)` block in main files for REPL exploration
- Use conventional directory structure (src/clj, src/cljs for full-stack)
- Default to latest stable dependency versions
- Include forj-friendly setup (nREPL config, test tasks)
