# forj

MCP server + hooks that give AI coding agents a Clojure REPL.

## Gotchas

- MCP servers go in `~/.claude.json` (user scope), NOT `~/.claude/mcp.json`
- `bb install` uses `claude mcp add --scope user` to register correctly
- Debug logs: `~/.cache/claude-cli-nodejs/<project-path>/mcp-logs-forj/*.jsonl`
- Structured app logs: `~/.forj/logs/`
- Version pins for scaffolded projects live in `versions.edn` (single source of truth)
