import { execSync } from "node:child_process";
import { realpathSync } from "node:fs";
import { resolve, dirname } from "node:path";

/**
 * forj OpenCode plugin - REPL-driven LLM development for Clojure.
 *
 * Auto-discovered by OpenCode from ~/.config/opencode/plugins/forj.js
 * (symlinked back to packages/forj-opencode/plugin.js in the forj repo).
 *
 * FORJ_HOME resolved via: symlink resolution (preferred) or FORJ_HOME env var.
 */

const CLOJURE_EXTENSIONS = [".clj", ".cljs", ".cljc", ".edn", ".bb"];

function getForjHome() {
  try {
    // Bun provides import.meta.dir natively (no fileURLToPath needed)
    const realDir = dirname(realpathSync(resolve(import.meta.dir, "plugin.js")));
    const candidate = resolve(realDir, "..", "..");
    realpathSync(resolve(candidate, "packages", "forj-mcp", "src"));
    return candidate;
  } catch {
    // symlink broken or structure doesn't match
  }
  return process.env.FORJ_HOME || null;
}

function getClasspath(forjHome) {
  return [
    resolve(forjHome, "packages", "forj-mcp", "src"),
    resolve(forjHome, "packages", "forj-hooks", "src"),
  ].join(":");
}

function isClojureFile(filePath) {
  if (!filePath) return false;
  return CLOJURE_EXTENSIONS.some((ext) => filePath.toLowerCase().endsWith(ext));
}

// Caches to avoid shelling to bb on every message
let _sessionCtx = null;
let _sessionCtxAt = 0;
let _guidance = null;
let _guidanceAt = 0;

function bbExec(forjHome, cwd, ns, opts) {
  const cp = getClasspath(forjHome);
  return execSync(`bb -cp "${cp}" -m ${ns}`, {
    cwd,
    encoding: "utf-8",
    timeout: 15000,
    env: { ...process.env, CLAUDE_PROJECT_DIR: cwd },
    ...opts,
  });
}

function getSessionContext(forjHome, cwd) {
  const now = Date.now();
  if (_sessionCtx && now - _sessionCtxAt < 60_000) return _sessionCtx;
  try {
    const r = bbExec(forjHome, cwd, "forj.hooks.session-start", {});
    if (r?.trim()) { _sessionCtx = r.trim(); _sessionCtxAt = now; }
  } catch (e) { console.error("[forj] session-start:", e.message); }
  return _sessionCtx;
}

function getGuidance(forjHome, cwd) {
  const now = Date.now();
  if (_guidance && now - _guidanceAt < 30_000) return _guidance;
  try {
    const r = bbExec(forjHome, cwd, "forj.hooks.user-prompt", {
      input: JSON.stringify({ prompt: "" }),
      timeout: 10000,
    });
    if (r?.trim()) { _guidance = r.trim(); _guidanceAt = now; }
  } catch (e) { console.error("[forj] user-prompt:", e.message); }
  return _guidance;
}

// NOTE: Only export the plugin function. OpenCode calls ALL exports as plugins.
export const forjPlugin = async ({ project, directory, $ }) => {
  const forjHome = getForjHome();
  const cwd = directory || process.cwd();

  if (!forjHome) {
    console.error("[forj] Could not detect forj root, plugin disabled");
    return {};
  }

  console.error(`[forj] plugin loaded, forjHome=${forjHome}`);

  return {
    "experimental.chat.system.transform": async (input, output) => {
      const ctx = getSessionContext(forjHome, cwd);
      if (ctx) output.system.push(ctx);
      const guide = getGuidance(forjHome, cwd);
      if (guide) output.system.push(guide);
    },

    "tool.execute.after": async (input, output) => {
      const tool = input.tool || "";
      if (!/edit|write/i.test(tool)) return;
      const file = input.args?.file_path || input.args?.path || "";
      if (!isClojureFile(file)) return;
      try {
        execSync(`clj-paren-repair "${file}"`, {
          cwd, encoding: "utf-8", timeout: 10000,
          stdio: ["pipe", "pipe", "pipe"],
        });
        console.error(`[forj] paren repair: ${file}`);
      } catch (e) {
        console.error("[forj] paren repair error:", e.message);
      }
    },
  };
};
