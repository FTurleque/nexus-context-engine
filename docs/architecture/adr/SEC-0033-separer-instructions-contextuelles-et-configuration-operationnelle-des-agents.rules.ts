/// <reference path="../rules.d.ts" />

// Règle de l'ADR SEC-0033 (séparer instructions contextuelles et configuration opérationnelle des agents).
// Décision : « NEXUS ne lance aucun hook, ne démarre aucun serveur MCP et ne transforme pas automatiquement les
// permissions d'un outil en instructions de contexte. »
// Contrôlé ici, commentaires et littéraux effacés : le code de construction du contexte
// (core/src/main/java/com/nexus/context/**) ne lance aucun processus (ProcessBuilder, Runtime.exec, ProcessHandle)
// et ne référence pas le SDK MCP (io.modelcontextprotocol). La non-injection du contenu brut de
// .claude/settings.json est vérifiée par NativeContextSourcesIntegrationTest ; ce contrôle est propre à Archgate.

const CONTEXT_SOURCES = "core/src/main/java/com/nexus/context/**/*.java";

const FORBIDDEN: { pattern: RegExp; what: string }[] = [
  { pattern: /\bProcessBuilder\b/g, what: "lancement de processus (ProcessBuilder)" },
  { pattern: /\bRuntime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*exec\s*\(/g, what: "lancement de processus (Runtime.exec)" },
  { pattern: /\bProcessHandle\b/g, what: "pilotage de processus (ProcessHandle)" },
  { pattern: /\bio\s*\.\s*modelcontextprotocol\b/g, what: "SDK MCP (démarrage ou client d'un serveur MCP)" },
];

/** Efface commentaires et littéraux Java (longueur et sauts de ligne conservés). */
function maskJava(source: string): string {
  const out = source.split("");
  const blank = (from: number, to: number) => {
    for (let k = from; k < to; k++) if (out[k] !== "\n" && out[k] !== "\r") out[k] = " ";
  };
  const n = source.length;
  let i = 0;
  while (i < n) {
    const c = source[i];
    if (c === "/" && source[i + 1] === "/") {
      let j = i;
      while (j < n && source[j] !== "\n") j++;
      blank(i, j);
      i = j;
    } else if (c === "/" && source[i + 1] === "*") {
      const end = source.indexOf("*/", i + 2);
      const j = end < 0 ? n : end + 2;
      blank(i, j);
      i = j;
    } else if (source.startsWith('"""', i)) {
      let j = i + 3;
      while (j < n && !source.startsWith('"""', j)) j += source[j] === "\\" ? 2 : 1;
      j = Math.min(n, j + 3);
      blank(i + 3, j - 3);
      i = j;
    } else if (c === '"' || c === "'") {
      let j = i + 1;
      while (j < n && source[j] !== c && source[j] !== "\n") j += source[j] === "\\" ? 2 : 1;
      j = Math.min(n, j + 1);
      blank(i + 1, j - 1);
      i = j;
    } else {
      i++;
    }
  }
  return out.join("");
}

function lineOf(text: string, index: number): number {
  return text.slice(0, index).split("\n").length;
}

export default {
  rules: {
    "context-build-runs-no-hook-or-mcp-server": {
      description:
        "La construction du contexte (com.nexus.context) ne lance aucun processus et ne référence pas le SDK MCP : aucun hook ni serveur MCP n'est exécuté",
      severity: "error",
      async check(ctx) {
        const files = await ctx.glob(CONTEXT_SOURCES);
        if (files.length === 0) {
          ctx.report.violation({ message: `Aucune source sous ${CONTEXT_SOURCES} : la règle ne peut rien vérifier.` });
          return;
        }
        for (const file of files.sort()) {
          const code = maskJava(await ctx.readFile(file));
          for (const { pattern, what } of FORBIDDEN) {
            for (const match of code.matchAll(pattern)) {
              ctx.report.violation({
                message: `${what} dans la construction du contexte : SEC-0033 interdit d'exécuter un hook ou de démarrer un serveur MCP.`,
                file,
                line: lineOf(code, match.index ?? 0),
                fix: "Détecter la configuration en lecture seule et la signaler dans les métadonnées du build, sans l'exécuter.",
              });
            }
          }
        }
      },
    },
  },
} satisfies RuleSet;
