# forj Example Configurations

Copy these files to set up forj for your Claude Code environment.

## Quick Setup

```bash
# Clone forj
git clone https://github.com/your-org/forj.git
cd forj

# Set FORJ_PATH (use absolute path)
export FORJ_PATH=$(pwd)

# Install MCP server config
mkdir -p ~/.claude
cat examples/mcp.json | sed "s|FORJ_PATH|$FORJ_PATH|g" > ~/.claude/mcp.json

# Install hooks config
cat examples/claude-settings.json | sed "s|FORJ_PATH|$FORJ_PATH|g" > ~/.claude/settings.json

# Install skill
mkdir -p ~/.claude/skills/clj-repl
cp packages/forj-skill/SKILL.md ~/.claude/skills/clj-repl/
```

## Files

### mcp.json

MCP server configuration. Copy to:
- `~/.claude/mcp.json` (global)
- `<project>/.mcp.json` (project-specific)

Replace `FORJ_PATH` with the absolute path to your forj clone.

### claude-settings.json

Hooks configuration. Copy to:
- `~/.claude/settings.json` (global)
- `<project>/.claude/settings.json` (project-specific)

Replace `FORJ_PATH` with the absolute path to your forj clone.

### Skill (SKILL.md)

Copy `packages/forj-skill/SKILL.md` to `~/.claude/skills/clj-repl/SKILL.md`

## Dependencies

Install these before using forj:

1. **Babashka** - https://babashka.org/
   ```bash
   # Linux
   curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install
   chmod +x install && ./install

   # macOS
   brew install borkdude/brew/babashka
   ```

2. **clj-nrepl-eval** - REPL communication CLI
   ```bash
   # Install from source or your package manager
   # Must be on PATH
   ```

## Verify Installation

1. Start Claude Code in a Clojure project
2. Check MCP: `/mcp` should show forj server
3. Check skill: `/clj-repl status` should work
4. Start REPL: `bb nrepl-server localhost:1667 &`
5. Test: Use `repl_eval` tool with `(+ 1 2)`

## Troubleshooting

**MCP not loading:**
- Check paths are absolute in mcp.json
- Verify babashka is installed: `bb --version`
- Check logs: `~/.forj/logs/`

**Hooks not running:**
- Check paths are absolute in settings.json
- Verify project has `deps.edn`, `bb.edn`, or `shadow-cljs.edn`

**REPL not connecting:**
- Start REPL: `bb nrepl-server localhost:1667`
- Discover REPLs: `clj-nrepl-eval --discover-ports`
