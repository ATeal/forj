# forj Decision Log

Tracking architectural decisions, outcomes, and learnings.

---

## 2026-01-13: Initial Architecture

### Decision: Build MCP Server from Scratch (Babashka)

**Context**: Three options existed:
1. Extend clojure-mcp (mature, JVM)
2. Build on mcp-clojure-sdk (JVM framework)
3. Build from scratch (Babashka)

**Choice**: Build from scratch using Babashka

**Rationale**:
- Full control over design
- Fast startup (~10ms) critical for MCP servers
- Can shell to existing `clj-nrepl-eval` (already installed, proven)
- Simpler deployment (no JVM startup overhead)
- Aligns with project philosophy (Clojure all the way down)

**Trade-offs Accepted**:
- More work than extending clojure-mcp
- May need to port some nREPL client code later
- Limited access to Java libraries if needed

---

### Decision: Shell to clj-nrepl-eval Initially

**Context**: Need nREPL client functionality. Options:
1. Port nREPL client code to forj
2. Shell to existing clj-nrepl-eval
3. Use nREPL client library

**Choice**: Shell to clj-nrepl-eval

**Rationale**:
- Already works and is installed
- Avoids reimplementation
- Can port code later if performance matters
- Gets us to working state faster

---

### Decision: UserPromptSubmit Hook for Skill Activation

**Context**: Research showed skills only auto-activate ~20% of time.

**Choice**: Use UserPromptSubmit hook to inject skill context

**Rationale**:
- Skills don't reliably activate based on description alone
- Hook guarantees context injection
- Zero token cost (injected before Claude sees prompt)
- Can be smart about when to inject (only Clojure projects)

---

### Decision: Monorepo Structure

**Context**: Could be separate repos or monorepo.

**Choice**: Monorepo with packages/

**Rationale**:
- Easier cross-package development
- Single test/CI setup
- Atomic commits across components
- Simpler versioning

---

## Research Findings

### clojure-mcp (v0.2.2)
- Mature, stable, actively maintained
- Handles: multi-REPL, port discovery, lifecycle, delimiter repair
- License: EPL 2.0 (commercial friendly)
- Could be used alongside forj if needed

### clojure-mcp-light
- Provides `clj-nrepl-eval` (already on PATH)
- Provides `clj-paren-repair` (already configured in hooks)
- Lightweight, focused tools
- Can leverage without depending on full clojure-mcp

### Skills Gotcha
- Skills auto-activate only ~20% of time
- Workarounds: UserPromptSubmit hooks, forced eval
- Must account for this in design

### MCP Protocol
- JSON-RPC 2.0 over stdio
- Simple enough to implement directly
- No need for heavy SDK

---

## Resolved Decisions

- [x] **REPL process management**: Manual startup with `track_process` for cleanup
- [x] **Multi-REPL simultaneous connections**: Supported via path-based routing
- [ ] Remote REPL support (SSH tunnels) - Not yet implemented
- [ ] Session state persistence - Not yet implemented
