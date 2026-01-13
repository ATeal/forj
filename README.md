# forj

*Forge your code in the REPL fire. Iterate, shape, refine.*

REPL-driven LLM development for Clojure. Provides seamless Claude Code integration with automatic project detection, REPL connectivity, and context injection.

## Status

| Component | Status | Description |
|-----------|--------|-------------|
| forj-mcp | ✅ Complete | MCP server with 8 tools |
| forj-hooks | ✅ Complete | SessionStart + UserPromptSubmit |
| forj-skill | ✅ Complete | `/clj-repl` + `/clj-init` skills |

## Prerequisites

- [Babashka](https://babashka.org/) (bb)
- [clojure-mcp-light](https://github.com/bhauman/clojure-mcp-light) tools on PATH:
  - `clj-nrepl-eval` - nREPL client for REPL evaluation
  - `clj-paren-repair-claude-hook` - Auto-fix delimiter errors on file write

```bash
# Install Babashka
curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install
chmod +x install && ./install

# Install clojure-mcp-light (provides clj-nrepl-eval and clj-paren-repair-claude-hook)
bbin install io.github.bhauman/clojure-mcp-light
```

## Quick Start

```bash
git clone https://github.com/yourusername/forj.git
cd forj
bb install    # Installs MCP, hooks, and skill to ~/.claude/
```

Then restart Claude Code. The tools will be available automatically in any Clojure project.

## MCP Tools

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

## Features

### Path-Based REPL Routing
Automatically routes code to the right REPL based on file type:
- `.cljs` → ClojureScript REPL
- `.bb` → Babashka REPL
- `.clj` → Clojure/Babashka REPL (based on project)

### Hooks
- **SessionStart**: Detects Clojure projects, injects context about tasks/aliases/REPLs
- **UserPromptSubmit**: Reminds Claude to use REPL-first workflow
- **PreToolUse**: Auto-fixes Clojure delimiter errors before file writes (via `clj-paren-repair-claude-hook`)

### Skills

| Skill | Description |
|-------|-------------|
| `/clj-repl` | Start or connect to nREPL servers with auto-detection |
| `/clj-init` | Create new Clojure projects with interactive wizard |

#### /clj-init Project Types
- **Script/CLI** - Babashka with tasks
- **Library** - deps.edn with tests
- **Web API** - Ring/Reitit backend
- **Full-stack** - Clojure + ClojureScript + shadow-cljs
- **Mobile** - Expo + ClojureScript (Reagent/Re-frame)

## Project Structure

```
forj/
├── bb.edn                    # Root tasks (install, test, etc.)
├── packages/
│   ├── forj-mcp/            # MCP server
│   │   ├── src/forj/mcp/
│   │   └── test/forj/mcp/
│   ├── forj-hooks/          # Claude Code hooks
│   │   ├── src/forj/hooks/
│   │   └── test/forj/hooks/
│   └── forj-skill/          # Skills
│       ├── SKILL.md         # /clj-repl skill
│       ├── clj-init/        # /clj-init skill
│       │   ├── SKILL.md
│       │   └── templates/   # Project templates
│       └── test/forj/
├── examples/                 # Config templates
└── .claude/
    └── settings.json        # Hook registration
```

## Development

```bash
bb tasks              # List available tasks
bb nrepl              # Start nREPL server on port 1669
bb test               # Run all tests (21 tests, 105 assertions)
bb test:mcp           # Test MCP tools
bb test:hooks         # Test hooks
bb test:skill         # Validate skill definition
bb mcp:dev            # Run MCP server for testing
bb logs               # View forj logs
bb install            # Install to ~/.claude/
bb uninstall          # Remove from ~/.claude/
```

## Manual Configuration

### MCP Server
Add to `~/.claude/mcp.json`:
```json
{
  "mcpServers": {
    "forj": {
      "command": "bb",
      "args": ["-cp", "/path/to/forj/packages/forj-mcp/src", "-m", "forj.mcp.server"]
    }
  }
}
```

### Hooks
Add to `~/.claude/settings.json`:
```json
{
  "hooks": {
    "SessionStart": [{
      "hooks": [{
        "type": "command",
        "command": "bb -cp /path/to/forj/packages/forj-mcp/src:/path/to/forj/packages/forj-hooks/src -m forj.hooks.session-start"
      }]
    }]
  }
}
```

## Architecture

Built entirely in Babashka for fast startup (~10ms). Uses:
- `clj-nrepl-eval` for nREPL communication
- `edamame` for Clojure parsing with location metadata
- Path-based routing for multi-REPL projects

## License

EPL-2.0
