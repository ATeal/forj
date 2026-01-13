# forj

REPL-driven LLM development for Clojure.

## Project Overview

forj provides seamless Claude Code integration for Clojure projects:
- **forj-mcp**: MCP server for REPL connectivity ✅
- **forj-hooks**: Claude Code hooks for context injection ✅
- **forj-skill**: `/clj-repl` skill for REPL management ✅
- **clj-init**: Project scaffolding tool (pending)

## Quick Start

```bash
git clone https://github.com/your-org/forj.git
cd forj
bb install    # Installs MCP, hooks, and skill to ~/.claude/
```

## Development

```bash
bb nrepl                  # Start REPL for forj development
bb mcp:dev                # Run MCP server in dev mode
bb test                   # Run all tests (21 tests, 105 assertions)
bb logs                   # View forj logs
```

## MCP Tools Available

| Tool | Description | Like |
|------|-------------|------|
| `repl_eval` | Evaluate Clojure code in nREPL | Direct eval |
| `reload_namespace` | Reload a namespace from file | `,ef` |
| `eval_at` | Evaluate form at specific line (root/inner) | `,er` / `,ee` |
| `eval_comment_block` | Evaluate all forms in a comment block | `,eb` |
| `doc_symbol` | Look up documentation for a symbol | `K` |
| `discover_repls` | Find running nREPL servers | - |
| `analyze_project` | Get project configuration info | - |
| `run_tests` | Run project tests (auto-detects runner) | - |

## Architecture

Babashka-based, shells to `clj-nrepl-eval` for REPL operations.
Uses edamame for Clojure parsing with location metadata.
Path-based REPL routing auto-selects clj/cljs/bb REPLs.

## Files

- `packages/forj-mcp/` - MCP server implementation
- `packages/forj-hooks/` - Claude Code hooks
- `packages/forj-skill/` - `/clj-repl` skill definition
- `examples/` - Example configs for installation
- `.claude/settings.json` - Hook configuration
- `.mcp.json` - MCP server configuration

## Testing After Session Restart

These tools were added/modified and need MCP testing:
- [ ] `run_tests` - Run via MCP, verify output
- [ ] `eval_comment_block` - Test on a file with rich comments

To test:
```
# In Claude Code after restart:
1. Use run_tests tool on forj project
2. Use eval_comment_block on a file with (comment ...) blocks
```

## Current Status

### Completed
- Phase 1: MCP server with core tools
- Phase 2: SessionStart + UserPromptSubmit hooks
- Phase 3: Path-based REPL routing (clj/cljs/bb auto-selection)
- Surgical eval (`eval_at` with root/inner scope)
- Rich comment block eval (`eval_comment_block`)
- Doc lookup (`doc_symbol`)
- Test runner (`run_tests`)
- Auto port discovery
- Structured logging to `~/.forj/logs/`
- LSP detection and context injection
- Installation tasks (`bb install`)
- Full test suite (21 tests, 105 assertions)

### Pending
- `clj-init` scaffolding tool
