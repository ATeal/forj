# forj

REPL-driven LLM development for Clojure.

## Project Overview

forj provides seamless Claude Code integration for Clojure projects:
- **forj-mcp**: MCP server for REPL connectivity (working)
- **forj-hooks**: Claude Code hooks for context injection (working)
- **forj-skill**: Skill definition for REPL-first workflow (pending)
- **clj-init**: Project scaffolding tool (pending)

## Development

```bash
bb --nrepl-server 1668    # Start REPL for forj development
bb mcp:dev                # Run MCP server in dev mode
bb test                   # Run all tests
```

## MCP Tools Available

- **repl_eval**: Evaluate Clojure code in nREPL
- **discover_repls**: Find running nREPL servers
- **analyze_project**: Get project configuration info

## Architecture

Babashka-based, shells to `clj-nrepl-eval` for REPL operations.

## Files

- `packages/forj-mcp/` - MCP server implementation
- `packages/forj-hooks/` - Claude Code hooks
- `.claude/settings.json` - Hook configuration
- `.mcp.json` - MCP server configuration
- `.claude/decisions.md` - Decision log
- `clojure-claude-code-integration.md` - Full design spec

## Current Phase

Phase 1 & 2 complete: MCP server and hooks working.
Next: Path-based REPL routing, skill definition, clj-init scaffolding.
