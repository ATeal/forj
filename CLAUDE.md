# forj

REPL-driven LLM development for Clojure.

## Project Overview

forj provides seamless Claude Code integration for Clojure projects:
- **forj-mcp**: MCP server for REPL connectivity ✅
- **forj-hooks**: Claude Code hooks for context injection ✅
- **forj-skill**: `/clj-repl` skill for REPL management (pending)
- **clj-init**: Project scaffolding tool (pending)

## Development

```bash
bb nrepl                  # Start REPL for forj development
bb mcp:dev                # Run MCP server in dev mode
bb test                   # Run all tests
bb logs                   # View forj logs
```

## MCP Tools Available

| Tool | Description | Like |
|------|-------------|------|
| `repl_eval` | Evaluate Clojure code in nREPL | Direct eval |
| `reload_namespace` | Reload a namespace from file | `,ef` |
| `eval_at` | Evaluate form at specific line (root/inner) | `,er` / `,ee` |
| `doc_symbol` | Look up documentation for a symbol | `K` |
| `discover_repls` | Find running nREPL servers | - |
| `analyze_project` | Get project configuration info | - |

## Architecture

Babashka-based, shells to `clj-nrepl-eval` for REPL operations.
Uses edamame for Clojure parsing with location metadata.

## Files

- `packages/forj-mcp/` - MCP server implementation
- `packages/forj-hooks/` - Claude Code hooks
- `.claude/settings.json` - Hook configuration
- `.mcp.json` - MCP server configuration
- `.claude/decisions.md` - Decision log
- `clojure-claude-code-integration.md` - Full design spec

## Current Status

### Completed
- Phase 1: MCP server with core tools
- Phase 2: SessionStart + UserPromptSubmit hooks
- Surgical eval (`eval_at` with root/inner scope)
- Doc lookup (`doc_symbol`)
- Auto port discovery
- Structured logging to `~/.forj/logs/`
- LSP detection and context injection

### In Progress
- Path-based REPL routing (clj/cljs/bb auto-selection)

### Pending
- `run_tests` tool for in-session test running
- `eval_comment_block` tool for rich comment evaluation
- `/clj-repl` skill for starting REPLs when none running (low priority - hooks handle context)
- `clj-init` scaffolding tool
