# forj

REPL-driven LLM development for Clojure.

## Project Overview

forj provides seamless Claude Code integration for Clojure projects:
- **forj-mcp**: MCP server for REPL connectivity ✅
- **forj-hooks**: Claude Code hooks for context injection ✅
- **forj-skill**: `/clj-repl` skill for REPL management ✅
- **clj-init**: `/clj-init` skill for project scaffolding ✅

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
| `validate_changed_files` | Reload + eval comment blocks in changed files | Lisa Loop |
| `start_loop` | Start a Lisa Loop session | - |
| `cancel_loop` | Cancel active Lisa Loop | - |
| `loop_status` | Check Lisa Loop status | - |
| `validate_project` | Validate project setup (deps, bb.edn, npm, Java) | - |
| `scaffold_project` | Create new project from composable modules | - |

## Architecture

Babashka-based, shells to `clj-nrepl-eval` for REPL operations.
Uses edamame for Clojure parsing with location metadata.
Path-based REPL routing auto-selects clj/cljs/bb REPLs.

## Skills

| Skill | Description |
|-------|-------------|
| `/clj-repl` | Start or connect to Clojure/ClojureScript/Babashka nREPL |
| `/clj-init` | Create a new Clojure project with interactive configuration |
| `/lisa-loop` | REPL-driven autonomous development loops |

### /clj-init Project Types

- **Script/CLI** (bb) - Babashka script with tasks
- **Library** (clj) - deps.edn library with tests
- **Web API** (api) - Ring/Reitit backend
- **Full-stack** (fullstack) - Clojure + ClojureScript + shadow-cljs
- **Mobile** (mobile) - Expo + ClojureScript (Reagent/Re-frame)

### /lisa-loop - Autonomous Development

Start an autonomous development loop:
```
/lisa-loop "Build a REST API for users" --max-iterations 20
```

The Stop hook runs `validate_changed_files` between iterations, injecting REPL feedback.
Cancel with `/cancel-lisa`.

## Files

- `packages/forj-mcp/` - MCP server implementation
- `packages/forj-hooks/` - Claude Code hooks
- `packages/forj-skill/` - Skill definitions (`/clj-repl`, `/clj-init`, `/lisa-loop`)
- `bin/forj-mcp` - Launcher script for MCP server
- `examples/` - Example configs for installation

## MCP Configuration

**IMPORTANT**: Claude Code MCP servers must be configured in `~/.claude.json` (user scope), NOT `~/.claude/mcp.json`.

The `bb install` task uses `claude mcp add --scope user` to register forj correctly.

To manually add/remove:
```bash
claude mcp add forj /path/to/forj/bin/forj-mcp --scope user
claude mcp remove forj --scope user
```

Debug logs: `~/.cache/claude-cli-nodejs/<project-path>/mcp-logs-forj/*.jsonl`

## Testing After Session Restart

All tools tested and working:
- [x] `run_tests` - Runs bb test, returns structured output ✅
- [x] `eval_comment_block` - Evaluates all forms in comment block ✅

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

### Completed Recently
- `/lisa-loop` native autonomous development loop with Stop hook
- `/clj-init` skill with interactive project scaffolding
- Composable module system for scaffolding (replaces templates)
- `scaffold_project` MCP tool with config merging
- Version management via versions.edn (single source of truth)
