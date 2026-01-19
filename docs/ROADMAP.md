# forj Roadmap

Future improvements discussed during development.

---

## Lisa Loop Improvements

### P1 - High Value

- [x] **Watch mode** - `/lisa-loop watch` auto-refreshes status table every 10s until complete or Ctrl+C (2026-01-16)
- [x] **Terminal bell on completion** - `(println "\u0007")` when loop finishes (2026-01-16)
- [ ] **Structured acceptance criteria** - Plan generation creates:
  ```markdown
  - code_check: (require '[mobile.views] :reload) compiles
  - visual_check: screenshot localhost:8081 shows locker cards
  - interaction_check: tap locker → notes grid appears
  ```

### P2 - Nice to Have

- [ ] **Stream JSON for live visibility** - Use `--output-format stream-json` instead of `json`
  - Parse NDJSON stream line-by-line
  - Pretty-print tool calls as they happen: `[Iter 4] Editing views.cljs...`
  - Show real-time progress without waiting for iteration to complete

- [ ] **Checkpoint-named screenshots** - Rename `page-2026-01-17T00-53-29.png` to `checkpoint-4-history-view.png`

- [x] **Token tracking** - Capture input/output token breakdown from Claude's JSON output, not just cost (2026-01-16)

- [x] **Implement judge: validation** - LLM-as-judge for subjective criteria ("does this look good?") (2026-01-16)
  - Uses Claude Haiku for speed/cost
  - Auto-discovers recent screenshots for visual evaluation

---

## /clj-repl Improvements

### P1 - High Value

- [x] **Flags for common scenarios** (2026-01-16)
  - `/clj-repl` - Smart default (ask if unclear)
  - `/clj-repl fresh` - Always restart all (no prompt)
  - `/clj-repl keep` - Use what's running (no prompt)

- [x] **Quieter output by default** (2026-01-16)
  - Default: concise status updates, summary table only
  - `--verbose` flag for detailed MCP output

- [ ] **Offer to create `bb dev-all` task**
  - After first successful startup, ask: "Want me to create a `bb dev-all` task?"
  - One command to start all services for the project

### P2 - Nice to Have

- [ ] **Add `dev-all` task to clj-init scaffolding**
  - Based on selected modules, generate task that starts everything
  - Removes need for skill to figure it out each time

---

## Completed

- [x] Chrome MCP support for visual validation (2026-01-16)
- [x] MCP-agnostic visual validation instructions (2026-01-16)
- [x] Auto-commit rollback points after checkpoints (2026-01-16)
- [x] `--dangerously-skip-permissions` fix for orchestrator (2026-01-16)
- [x] Stdin prompt passing to avoid shell escaping (2026-01-16)
- [x] UI checkpoint detection for visual validation (2026-01-16)

---

## Ideas from Lisa Loop Self-Review

From the parent-locker build session:

> "Lisa Loop works. It built a real app in 7 iterations for $10. The fresh-context-per-iteration approach prevents the context bloat that kills long agentic sessions."

> "Main gap: UI validation is vibes-based ('I wrote code that should work') rather than evidence-based ('I verified it renders correctly')."

Key metrics from that run:
- 8 checkpoints / 7 iterations = 1.14 iterations per checkpoint
- ~$10 total = ~$1.25 per checkpoint
- Full-stack app with backend, mobile UI, persistence

**Cost breakdown (with visual validation):**
- Backend checkpoints: ~$0.75-0.95 each
- UI checkpoints with Playwright screenshots: ~$5-7 each
- ~6-7x multiplier for visual validation (useful for planning budgets)
