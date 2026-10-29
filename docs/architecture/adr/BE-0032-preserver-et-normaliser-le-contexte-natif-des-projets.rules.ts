/// <reference path="../rules.d.ts" />

// Règle de l'ADR BE-0032 (contexte natif des projets). Contrôle propre à Archgate : aucun test ni contrat
// ne vérifie où les conventions natives sont nommées.
// - Décision : NEXUS découvre les conventions natives via des ContextSourceProvider et les normalise dans
//   un modèle commun ; confirmation : ContextBuilder ne contient aucune condition spécifique à Copilot
//   ou Claude.
// - Contrôle : les sources du paquetage com.nexus.context (hors sous-paquetages, donc hors providers de
//   com.nexus.context.source) ne nomment ni Copilot, ni Claude, ni Gemini, ni AGENTS.md / AGENT.md.
//   Les commentaires sont effacés ; les littéraux sont conservés, car une condition propre à un
//   fournisseur compare le plus souvent un nom de fichier.

const BUILDER_PACKAGE = "core/src/main/java/com/nexus/context/";
const VENDOR = /copilot|claude|gemini|\bagents?\.md\b/gi;

/** Efface les commentaires et, sauf si garderLitteraux, les littéraux (longueur et sauts de ligne conservés). */
function masquerJava(source: string, garderLitteraux = false): string {
  const out = source.split("");
  const effacer = (debut: number, fin: number) => {
    for (let k = debut; k < fin; k++) if (out[k] !== "\n" && out[k] !== "\r") out[k] = " ";
  };
  const n = source.length;
  let i = 0;
  while (i < n) {
    const c = source[i];
    if (c === "/" && source[i + 1] === "/") {
      let j = i;
      while (j < n && source[j] !== "\n") j++;
      effacer(i, j);
      i = j;
    } else if (c === "/" && source[i + 1] === "*") {
      const fin = source.indexOf("*/", i + 2);
      const j = fin < 0 ? n : fin + 2;
      effacer(i, j);
      i = j;
    } else if (source.startsWith('"""', i)) {
      let j = i + 3;
      while (j < n && !source.startsWith('"""', j)) j += source[j] === "\\" ? 2 : 1;
      j = Math.min(n, j + 3);
      if (!garderLitteraux) effacer(i + 3, j - 3);
      i = j;
    } else if (c === '"' || c === "'") {
      let j = i + 1;
      while (j < n && source[j] !== c && source[j] !== "\n") j += source[j] === "\\" ? 2 : 1;
      j = Math.min(n, j + 1);
      if (!garderLitteraux) effacer(i + 1, j - 1);
      i = j;
    } else {
      i++;
    }
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
    "context-builder-has-no-vendor-condition": {
      description:
        "Le paquetage com.nexus.context (hors providers de source) ne contient aucune condition propre à Copilot, Claude, Gemini ou AGENTS.md",
      severity: "error",
      async check(ctx) {
        const files = (await ctx.glob(`${BUILDER_PACKAGE}*.java`)).sort();
        if (!files.some((file) => file.endsWith("/DefaultContextBuilder.java"))) {
          ctx.report.violation({
            message: `DefaultContextBuilder.java introuvable sous ${BUILDER_PACKAGE} : la règle ne peut rien vérifier.`,
            file: BUILDER_PACKAGE,
          });
          return;
        }
        for (const file of files) {
          const code = masquerJava(await ctx.readFile(file), true);
          for (const match of code.matchAll(VENDOR)) {
            ctx.report.violation({
              message: `« ${match[0]} » dans la construction du contexte : une convention native ne se traite que dans un ContextSourceProvider (BE-0032).`,
              file,
              line: ligne(code, match.index ?? 0),
              fix: "Déplacer cette logique dans un provider de com.nexus.context.source.instruction et consommer le modèle normalisé.",
            });
          }
        }
      },
    },
  },
} satisfies RuleSet;
