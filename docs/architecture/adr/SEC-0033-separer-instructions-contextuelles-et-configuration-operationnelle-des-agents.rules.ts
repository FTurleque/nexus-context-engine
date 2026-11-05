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
// Fonctions communes : copie conforme de gabarit/fonctions-communes.ts (contrôlée par GEN-0057).

type Jeton =
  | { type: "commentaire"; fin: number }
  | { type: "litteral"; fin: number; delimiteur: number }
  | { type: "code"; fin: number };

function finDeJeton(source: string, i: number): Jeton {
  const n = source.length;
  const c = source[i];
  if (c === "/" && source[i + 1] === "/") {
    const fin = source.indexOf("\n", i);
    return { type: "commentaire", fin: fin < 0 ? n : fin };
  }
  if (c === "/" && source[i + 1] === "*") {
    const fin = source.indexOf("*/", i + 2);
    return { type: "commentaire", fin: fin < 0 ? n : fin + 2 };
  }
  if (source.startsWith('"""', i)) {
    let j = i + 3;
    while (j < n && !source.startsWith('"""', j)) j += source[j] === "\\" ? 2 : 1;
    return { type: "litteral", fin: Math.min(n, j + 3), delimiteur: 3 };
  }
  if (c === '"' || c === "'") {
    let j = i + 1;
    while (j < n && source[j] !== c && source[j] !== "\n") j += source[j] === "\\" ? 2 : 1;
    return { type: "litteral", fin: Math.min(n, j + 1), delimiteur: 1 };
  }
  return { type: "code", fin: i + 1 };
}

function masquerJava(source: string, garderLitteraux = false): string {
  const out = source.split("");
  const effacer = (debut: number, fin: number) => {
    for (let k = debut; k < fin; k++) if (out[k] !== "\n" && out[k] !== "\r") out[k] = " ";
  };
  let i = 0;
  while (i < source.length) {
    const jeton = finDeJeton(source, i);
    if (jeton.type === "commentaire") effacer(i, jeton.fin);
    else if (jeton.type === "litteral" && !garderLitteraux) effacer(i + jeton.delimiteur, jeton.fin - jeton.delimiteur);
    i = jeton.fin;
  }
  return out.join("");
}

function ligne(texte: string, index: number): number {
  let l = 1;
  for (let k = 0; k < index; k++) if (texte[k] === "\n") l++;
  return l;
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
          const code = masquerJava(await ctx.readFile(file));
          for (const { pattern, what } of FORBIDDEN) {
            for (const match of code.matchAll(pattern)) {
              ctx.report.violation({
                message: `${what} dans la construction du contexte : SEC-0033 interdit d'exécuter un hook ou de démarrer un serveur MCP.`,
                file,
                line: ligne(code, match.index ?? 0),
                fix: "Détecter la configuration en lecture seule et la signaler dans les métadonnées du build, sans l'exécuter.",
              });
            }
          }
        }
      },
    },
  },
} satisfies RuleSet;
