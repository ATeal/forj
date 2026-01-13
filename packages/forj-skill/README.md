# forj-skill

Claude Code skill and configuration for Clojure REPL integration.

## Installation

### 1. Install the Skill

Copy the skill to your Claude Code skills directory:

```bash
mkdir -p ~/.claude/skills/clj-repl
cp SKILL.md ~/.claude/skills/clj-repl/
```

The skill will be available as `/clj-repl` in Claude Code.

### 2. Install the MCP Server

Add to your global MCP config (`~/.claude/mcp.json` or `~/.config/claude-code/mcp.json`):

```json
{
  "mcpServers": {
    "forj": {
      "command": "bb",
      "args": ["-cp", "/path/to/forj/packages/forj-mcp/src", "-m", "forj.mcp.server"],
      "env": {}
    }
  }
}
```

Or for project-specific config (`.mcp.json` in project root):

```json
{
  "mcpServers": {
    "forj": {
      "command": "bb",
      "args": ["-cp", "packages/forj-mcp/src", "-m", "forj.mcp.server"]
    }
  }
}
```

### 3. Install Hooks (Optional)

Add to your project's `.claude/settings.json`:

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
    ],
    "UserPromptSubmit": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "bb -cp packages/forj-mcp/src:packages/forj-hooks/src -m forj.hooks.user-prompt"
          }
        ]
      }
    ]
  }
}
```

## Dependencies

- [Babashka](https://babashka.org/) - for running the MCP server and hooks
- [clj-nrepl-eval](https://github.com/your-repo/clj-nrepl-eval) - CLI for nREPL communication

## Usage

1. Start a REPL: `/clj-repl` or `bb nrepl-server localhost:1667`
2. Use MCP tools: `repl_eval`, `eval_at`, `reload_namespace`, `doc_symbol`
3. Hooks auto-inject Clojure context on session start

## Available MCP Tools

| Tool | Description | Like |
|------|-------------|------|
| `repl_eval` | Evaluate Clojure code | Direct eval |
| `reload_namespace` | Reload a namespace | `,ef` in Conjure |
| `eval_at` | Eval form at line (root/inner) | `,er` / `,ee` |
| `doc_symbol` | Look up symbol docs | `K` in Conjure |
| `discover_repls` | Find running nREPLs | - |
| `analyze_project` | Get project info | - |
