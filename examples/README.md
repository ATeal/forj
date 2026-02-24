# forj Example Configurations

Copy these files to set up forj for your LLM coding environment.

## Quick Setup (Recommended)

```bash
# Clone forj
git clone https://github.com/ATeal/forj.git
cd forj

# Interactive install - detects your platform
bb install
```

The installer shows a menu to choose Claude Code, OpenCode, or both.

## Manual Setup - Claude Code

```bash
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

## Manual Setup - OpenCode

```bash
export FORJ_PATH=$(pwd)

# Install MCP + plugin config
mkdir -p ~/.config/opencode
cat examples/opencode.json | sed "s|FORJ_PATH|$FORJ_PATH|g" > ~/.config/opencode/opencode.json

# Install plugin (symlink so it can auto-detect forj root)
# OpenCode scans ~/.opencode/plugins/*.{ts,js} for local plugins
mkdir -p ~/.opencode/plugins
ln -sf "$FORJ_PATH/packages/forj-opencode/plugin.js" ~/.opencode/plugins/forj.js

# Install skills (prefer OpenCode-specific variants)
for skill in clj-repl clj-init lisa-loop; do
  mkdir -p ~/.config/opencode/skills/$skill
  if [ -f "packages/forj-skill/$skill/SKILL.opencode.md" ]; then
    cp "packages/forj-skill/$skill/SKILL.opencode.md" ~/.config/opencode/skills/$skill/SKILL.md
  elif [ -f "packages/forj-skill/$skill/SKILL.md" ]; then
    cp "packages/forj-skill/$skill/SKILL.md" ~/.config/opencode/skills/$skill/
  else
    cp packages/forj-skill/SKILL.md ~/.config/opencode/skills/$skill/
  fi
done
```

**Note:** The plugin is symlinked (not copied) so it can auto-detect the forj root via `realpathSync`. OpenCode auto-discovers `*.{ts,js}` files directly in the `plugins/` directory. If you can't use symlinks, set the `FORJ_HOME` environment variable instead.

## Files

### mcp.json (Claude Code)

MCP server configuration. Copy to:
- `~/.claude/mcp.json` (global)
- `<project>/.mcp.json` (project-specific)

Replace `FORJ_PATH` with the absolute path to your forj clone.

### claude-settings.json (Claude Code)

Hooks configuration. Copy to:
- `~/.claude/settings.json` (global)
- `<project>/.claude/settings.json` (project-specific)

Replace `FORJ_PATH` with the absolute path to your forj clone.

### opencode.json (OpenCode)

Combined MCP server and plugin configuration. Copy to:
- `~/.config/opencode/opencode.json`

Replace `FORJ_PATH` with the absolute path to your forj clone.

This configures:
- **MCP server** - REPL connectivity, code evaluation, namespace reloading
- **Plugin** - Session context injection, REPL-first guidance, parenthesis repair

### Skills

Copy skill files to the appropriate location:
- **Claude Code:** `~/.claude/skills/<name>/SKILL.md`
- **OpenCode:** `~/.config/opencode/skills/<name>/SKILL.md`

Available skills: `/clj-repl`, `/clj-init`, `/lisa-loop`

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

1. Start your editor in a Clojure project
2. Check MCP: forj server should appear in your tool list
3. Start REPL: `bb nrepl-server localhost:1667 &`
4. Test: Use `repl_eval` tool with `(+ 1 2)`

## Troubleshooting

**MCP not loading:**
- Check paths are absolute in config
- Verify babashka is installed: `bb --version`
- Check logs: `~/.forj/logs/`

**Hooks not running (Claude Code):**
- Check paths are absolute in settings.json
- Verify project has `deps.edn`, `bb.edn`, or `shadow-cljs.edn`

**Plugin not loading (OpenCode):**
- Plugin must be at `~/.opencode/plugins/forj.js` (NOT `~/.config/opencode/plugins/`)
- OpenCode only scans `~/.opencode/` and `.opencode/` for local plugin files
- File must be `.js` or `.ts` (not `.mjs`) — OpenCode glob matches `*.{ts,js}`
- If symlinks aren't possible, set `FORJ_HOME` env var to your forj clone path

**REPL not connecting:**
- Start REPL: `bb nrepl-server localhost:1667`
- Discover REPLs: `clj-nrepl-eval --discover-ports`
