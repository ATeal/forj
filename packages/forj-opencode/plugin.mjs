import { execSync } from "node:child_process";
import { resolve } from "node:path";

/**
 * forj OpenCode plugin - REPL-driven LLM development for Clojure.
 *
 * Provides hooks that inject Clojure project context and REPL guidance
 * into OpenCode sessions. Shells out to babashka for the actual logic,
 * sharing implementation with the Claude Code hooks.
 *
 * Requires FORJ_HOME env var pointing to the forj installation directory.
 */

const CLOJURE_EXTENSIONS = [".clj", ".cljs", ".cljc", ".edn", ".bb"];

function getForjHome() {
  if (process.env.FORJ_HOME) {
    return process.env.FORJ_HOME;
  }
  return null;
}

function getClasspath(forjHome) {
  return [
    resolve(forjHome, "packages", "forj-mcp", "src"),
    resolve(forjHome, "packages", "forj-hooks", "src"),
  ].join(":");
}

function isClojureFile(filePath) {
  if (!filePath) return false;
  const lower = filePath.toLowerCase();
  return CLOJURE_EXTENSIONS.some((ext) => lower.endsWith(ext));
}

export const forjPlugin = async ({ project, directory, $ }) => {
  const forjHome = getForjHome();
  const cwd = directory || process.cwd();

  return {
    "session.created": async (input, output) => {
      if (!forjHome) {
        console.error("[forj] FORJ_HOME not set, skipping session hook");
        return;
      }

      try {
        const cp = getClasspath(forjHome);
        const result = execSync(`bb -cp "${cp}" -m forj.hooks.session-start`, {
          cwd,
          encoding: "utf-8",
          timeout: 15000,
          env: {
            ...process.env,
            CLAUDE_PROJECT_DIR: cwd,
          },
        });

        if (result && result.trim()) {
          console.log("[forj] Session context injected");
        }
      } catch (err) {
        console.error("[forj] session.created hook error:", err.message);
      }
    },

    "tool.execute.before": async (input, output) => {
      if (!forjHome) return;

      try {
        const cp = getClasspath(forjHome);
        const promptJson = JSON.stringify({ prompt: "" });
        const result = execSync(
          `bb -cp "${cp}" -m forj.hooks.user-prompt`,
          {
            cwd,
            encoding: "utf-8",
            timeout: 10000,
            input: promptJson,
            env: {
              ...process.env,
              CLAUDE_PROJECT_DIR: cwd,
            },
          },
        );

        if (result && result.trim()) {
          console.log("[forj] REPL-first guidance injected");
        }
      } catch (err) {
        console.error("[forj] tool.execute.before hook error:", err.message);
      }
    },

    "tool.execute.after": async (input, output) => {
      if (!forjHome) return;

      try {
        const toolName = input?.tool || input?.name || "";
        if (!/edit|write/i.test(toolName)) return;

        const filePath =
          input?.input?.file_path || input?.input?.path || "";
        if (!isClojureFile(filePath)) return;

        execSync(`clj-paren-repair "${filePath}"`, {
          cwd,
          encoding: "utf-8",
          timeout: 10000,
          stdio: ["pipe", "pipe", "pipe"],
        });

        console.log(`[forj] Paren repair ran on ${filePath}`);
      } catch (err) {
        console.error("[forj] tool.execute.after hook error:", err.message);
      }
    },
  };
};
