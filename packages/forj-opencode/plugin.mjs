import { execSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

/**
 * forj OpenCode plugin - REPL-driven LLM development for Clojure.
 *
 * Provides hooks that inject Clojure project context and REPL guidance
 * into OpenCode sessions. Shells out to babashka for the actual logic,
 * sharing implementation with the Claude Code hooks.
 *
 * FORJ_HOME is resolved from (in order):
 *   1. forj-home.txt next to this plugin (written by bb install)
 *   2. FORJ_HOME environment variable
 */

const CLOJURE_EXTENSIONS = [".clj", ".cljs", ".cljc", ".edn", ".bb"];

const __dirname = dirname(fileURLToPath(import.meta.url));

function getForjHome() {
  // Try config file first (written by installer)
  try {
    const configPath = resolve(__dirname, "forj-home.txt");
    const home = readFileSync(configPath, "utf-8").trim();
    if (home) return home;
  } catch {
    // fall through
  }
  // Fall back to env var
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

// Debounce: only run REPL-first guidance once per 30s
// (tool.execute.before fires per tool call, not per user message)
const GUIDANCE_DEBOUNCE_MS = 30_000;
let lastGuidanceTime = 0;

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

      const now = Date.now();
      if (now - lastGuidanceTime < GUIDANCE_DEBOUNCE_MS) return;
      lastGuidanceTime = now;

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
