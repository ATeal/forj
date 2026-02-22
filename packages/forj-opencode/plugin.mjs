import { execSync } from "node:child_process";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

/**
 * forj OpenCode plugin - REPL-driven LLM development for Clojure.
 *
 * Provides hooks that inject Clojure project context and REPL guidance
 * into OpenCode sessions. Shells out to babashka for the actual logic,
 * sharing implementation with the Claude Code hooks.
 *
 * Requires FORJ_HOME env var pointing to the forj installation directory.
 */

function getForjHome() {
  if (process.env.FORJ_HOME) {
    return process.env.FORJ_HOME;
  }
  // Fallback: assume plugin is at ~/.config/opencode/plugins/forj/plugin.mjs
  // and FORJ_HOME wasn't set - this won't work, but gives a clear error.
  return null;
}

function getClasspath(forjHome) {
  return [
    resolve(forjHome, "packages", "forj-mcp", "src"),
    resolve(forjHome, "packages", "forj-hooks", "src"),
  ].join(":");
}

export const forjPlugin = async ({ project, directory, $ }) => {
  const forjHome = getForjHome();

  return {
    "session.created": async (input, output) => {
      if (!forjHome) {
        console.error("[forj] FORJ_HOME not set, skipping session hook");
        return;
      }

      try {
        const cp = getClasspath(forjHome);
        const result = execSync(`bb -cp "${cp}" -m forj.hooks.session-start`, {
          cwd: directory || process.cwd(),
          encoding: "utf-8",
          timeout: 15000,
          env: {
            ...process.env,
            CLAUDE_PROJECT_DIR: directory || process.cwd(),
          },
        });

        if (result && result.trim()) {
          console.log("[forj] Session context injected");
        }
      } catch (err) {
        // Log but don't fail - hooks should be resilient
        console.error("[forj] session.created hook error:", err.message);
      }
    },
  };
};
