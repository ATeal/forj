# forj-hooks

Claude Code hooks for Clojure project context injection.

## What the Hooks Do

### SessionStart Hook

Runs when Claude Code starts a session in a Clojure project. Injects:
- Project type detection (Babashka, Clojure, ClojureScript)
- Available bb tasks, deps.edn aliases, shadow-cljs builds
- Running nREPL servers
- Available forj MCP tools
- LSP status (clojure-lsp detection)

### UserPromptSubmit Hook

Runs on each prompt submission. Injects a reminder to use REPL-first workflow and forj MCP tools.

## Installation

### 1. Copy Hook Source Files

The hooks live in `src/forj/hooks/`:
- `session_start.clj` - SessionStart hook
- `user_prompt.clj` - UserPromptSubmit hook

Also requires shared logging from forj-mcp:
- `../forj-mcp/src/forj/logging.clj`

### 2. Configure Hooks

Add to your project's `.claude/settings.json`:

```json
{
  "hooks": {
    "SessionStart": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "bb -cp /path/to/forj/packages/forj-mcp/src:/path/to/forj/packages/forj-hooks/src -m forj.hooks.session-start"
          }
        ]
      }
    ],
    "UserPromptSubmit": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "bb -cp /path/to/forj/packages/forj-mcp/src:/path/to/forj/packages/forj-hooks/src -m forj.hooks.user-prompt"
          }
        ]
      }
    ]
  }
}
```

Replace `/path/to/forj` with the actual path to your forj installation.

### For Global Installation

Add to `~/.claude/settings.json` with absolute paths.

### For Project-Specific Installation

Add to `<project>/.claude/settings.json` with relative paths:

```json
{
  "hooks": {
    "SessionStart": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "bb -cp packages/forj-mcp/src:packages/forj-hooks/src -m forj.hooks.session-start"
          }
        ]
      }
    ]
  }
}
```

## Dependencies

- [Babashka](https://babashka.org/)
- [clj-nrepl-eval](https://github.com/your-repo/clj-nrepl-eval) (for REPL discovery)

## Logs

Hooks log to `~/.forj/logs/`:
- `session-start.log`
- `user-prompt.log`
- `errors.log` (warnings and errors)

Set `FORJ_LOG_LEVEL=debug` for verbose logging.
