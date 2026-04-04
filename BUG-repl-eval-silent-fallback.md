# Bug: `repl_eval` silently falls back to wrong project's REPL

## Summary

When no nREPL is running in the current project, `repl_eval` silently connects to a REPL discovered in a different project directory. The `require` call fails (namespace not on that REPL's classpath), but no error is surfaced to the caller. This makes it appear as though the evaluation returned empty/nil results rather than failing.

## Steps to Reproduce

1. Have an nREPL running in **Project A** (e.g., `eert-sprint-report` on port 1667)
2. Open **Project B** (e.g., `jarvis`) with **no** nREPL running
3. Call `repl_eval` with `file: "bb/modules/nutrition/api.clj"` (a file in Project B):
   ```clojure
   (require '[bb.modules.nutrition.api :as nutrition] :reload)
   (nutrition/todays-log)
   ```
4. Tool returns `Tool ran without output or errors` — no indication that:
   - It connected to Project A's REPL (wrong project)
   - The `require` failed with `Could not locate bb/modules/nutrition/api.clj on classpath`
   - Subsequent calls resolved to nothing

## Expected Behavior

At minimum, one of:

1. **Error on require failure** — If `require` throws, surface the exception in the tool output rather than swallowing it.
2. **Warn on cross-project fallback** — If the only available REPL is in a different directory than the file being evaluated, warn the user: "No REPL found in /path/to/project, falling back to REPL at localhost:1667 (/path/to/other-project)."
3. **Don't fall back at all** — If no REPL is found in the current project, return an error suggesting the user start one, rather than silently using a REPL from a different project.

Option 3 is the safest. Option 2 is a reasonable compromise.

## Actual Behavior

- No error, no warning
- `require` failure is completely silent
- Return value shows as empty (`Tool ran without output or errors`)
- User has no way to know the REPL is from the wrong project without manually checking `(System/getProperty "user.dir")`

## Environment

- Babashka nREPL
- forj MCP `repl_eval` tool
- Two projects: `jarvis` (no REPL) and `eert-sprint-report` (REPL on port 1667)

## Impact

Medium — this caused a misleading "no data" result that sent the user on a wild goose chase. The nutrition module had data, but the wrong REPL couldn't load it. Only discovered by reading the state file directly and then checking `user.dir` in the REPL.
