# {{project-name}}

A ClojureDart Flutter app created with [forj](https://github.com/ateal/forj).

## Prerequisites

- [Flutter SDK](https://flutter.dev/docs/get-started/install) (on PATH)
- [Clojure CLI](https://clojure.org/guides/install_clojure)
- Java 8+

## Getting Started

```bash
# Run the app (includes hot reload + REPL)
bb flutter
```

ClojureDart is initialized automatically when the project is scaffolded.

## Development Commands

| Command | Description |
|---------|-------------|
| `bb init` | Re-initialize ClojureDart (auto-run during scaffold) |
| `bb flutter` | Run app with hot reload and REPL |
| `bb compile` | Compile without running |
| `bb build:apk` | Build Android APK |
| `bb build:ios` | Build iOS app (macOS only) |
| `bb clean` | Clean build artifacts |
| `bb upgrade` | Upgrade to latest ClojureDart |

## REPL (Beta)

The REPL starts automatically when you run `bb flutter`. Look for output like:

```
🤫 ClojureDart REPL listening on port 59268
```

Connect via socket (the port number varies each run):

```bash
nc localhost 59268
```

Or in Emacs: `C-U M-x inferior-lisp` → enter `nc localhost 59268`

### REPL Features

- **`*1`, `*2`, `*3`, `*e`** - Standard result/error history
- **`*env`** - Widget context (after calling `cljd.flutter.repl/pick!`)
- **`cljd.flutter.repl/mount!`** - Hot-swap widgets

**Note:** REPL is in beta. Performance depends on namespace position in dependency graph and relies on Dart's hot reload.

**Important:** The REPL requires a persistent connection (like `nc` provides). Programmatic connect/disconnect cycles will crash the REPL. For Claude Code / forj workflows, edit `.cljd` files directly and let hot reload pick up changes rather than using the socket REPL.

## Project Structure

```
{{project-name}}/
├── bb.edn              # Babashka tasks
├── deps.edn            # ClojureDart dependencies
├── src/
│   └── {{namespace}}/
│       └── main.cljd   # App entry point
└── build/              # Flutter build output (after init)
```

## Learn More

- [ClojureDart Documentation](https://github.com/Tensegritics/ClojureDart)
- [ClojureDart REPL Guide](https://github.com/Tensegritics/ClojureDart#repl-beta)
- [Flutter Documentation](https://flutter.dev/docs)
