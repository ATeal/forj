# forj

*Forge your code in the REPL fire. Iterate, shape, refine.*

REPL-driven LLM development for Clojure. Provides seamless Claude Code integration with automatic project detection, REPL connectivity, and context injection.

## Status

**Phase 1 & 2 Complete** - MCP server and hooks working.

| Component | Status |
|-----------|--------|
| forj-mcp | ✅ Working |
| forj-hooks | ✅ Working |
| forj-skill | 🔨 Pending |
| clj-init | 🔨 Pending |

## Prerequisites

- [Babashka](https://babashka.org/) (bb)
- [clojure-mcp-light](https://github.com/bhauman/clojure-mcp-light) - provides `clj-nrepl-eval`

Install clojure-mcp-light:
```bash
bbin install io.github.bhauman/clojure-mcp-light
```

## Quick Start

### 1. Clone and enter the repo
```bash
git clone https://github.com/yourusername/forj.git
cd forj
```

### 2. Start a REPL (for development)
```bash
bb --nrepl-server 1668
```

### 3. Test the MCP server
```bash
# Initialize
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' | bb mcp:dev

# List tools
echo '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' | bb mcp:dev

# Discover REPLs
echo '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"discover_repls","arguments":{}}}' | bb mcp:dev

# Evaluate code
echo '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"repl_eval","arguments":{"code":"(+ 1 2 3)","port":1668}}}' | bb mcp:dev
```

## Using with Claude Code

### Option A: Project-level config (recommended for forj development)

The `.mcp.json` file is already configured. Claude Code will detect it when you open the forj directory.

### Option B: Add to your Claude Code settings

Add to your Claude Code MCP configuration:
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

The hooks in `.claude/settings.json` provide:
- **SessionStart**: Auto-detects Clojure projects, injects context about available tasks/aliases/REPLs
- **UserPromptSubmit**: Reminds Claude to use REPL-first workflow

## MCP Tools

| Tool | Description |
|------|-------------|
| `repl_eval` | Evaluate Clojure code in an nREPL server |
| `discover_repls` | Find running nREPL servers (checks .nrepl-port, etc.) |
| `analyze_project` | Parse bb.edn, deps.edn, shadow-cljs.edn for tasks/aliases/builds |

## Project Structure

```
forj/
├── bb.edn                    # Root tasks
├── .mcp.json                 # MCP server config for Claude Code
├── packages/
│   ├── forj-mcp/            # MCP server
│   │   └── src/forj/mcp/
│   │       ├── server.clj   # Main entry point
│   │       ├── protocol.clj # JSON-RPC handling
│   │       └── tools.clj    # Tool implementations
│   └── forj-hooks/          # Claude Code hooks
│       └── src/forj/hooks/
│           ├── session_start.clj
│           └── user_prompt.clj
└── .claude/
    ├── settings.json        # Hook registration
    └── decisions.md         # Architecture decision log
```

## Development

```bash
bb tasks              # List available tasks
bb mcp:dev            # Run MCP server (for testing)
bb test               # Run all tests
```

## Architecture

Built entirely in Babashka for fast startup (~10ms). Shells to `clj-nrepl-eval` from clojure-mcp-light for proven nREPL client functionality.

See `clojure-claude-code-integration.md` for the full design spec and `.claude/decisions.md` for architecture decisions.

## License

EPL-2.0
