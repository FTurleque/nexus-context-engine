/// <reference path="../rules.d.ts" />

// Règles de l'ADR ARCH-0078 (sources de contexte natives derrière des providers). Contrôles propres à
// Archgate : aucun test ni contrat ne vérifie où les conventions natives sont nommées.
// - Décision : « Ne pas faire lire `.github/`, `AGENTS.md`, `CLAUDE.md`, `GEMINI.md` ou `SKILL.md`
//   directement par `ContextBuilder`, ni y placer de condition propre à Copilot, Claude ou Gemini. »
// - Contrôle (context-builder-reads-no-native-format) : les sources du package com.nexus.context
//   lui-même (DefaultContextBuilder et pipeline commun), hors de ses sous-packages de providers
//   (com.nexus.context.source.*), ne nomment ni .github/, ni AGENTS.md, CLAUDE.md, GEMINI.md, SKILL.md,
//   *.instructions.md ou copilot-instructions.
// - Contrôle (context-builder-has-no-vendor-condition) : les mêmes sources ne nomment ni Copilot, ni
//   Claude, ni Gemini, ni AGENTS.md / AGENT.md.
// Les commentaires sont effacés ; les littéraux sont conservés, car un nom de fichier natif s'y écrit et
// une condition propre à un fournisseur compare le plus souvent un nom de fichier.

const PIPELINE = "core/src/main/java/com/nexus/context/*.java";
const FORMAT_NATIF = /\.github\b|\bAGENTS?\.md\b|\bCLAUDE\.md\b|\bGEMINI\.md\b|\bSKILL\.md\b|copilot-instructions|\.instructions\.md\b/g;
const BUILDER_PACKAGE = "core/src/main/java/com/nexus/context/";
const VENDOR = /copilot|claude|gemini|\bagents?\.md\b/gi;

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
    "context-builder-reads-no-native-format": {
      description:
        "Les sources du package com.nexus.context (hors providers com.nexus.context.source.*) ne nomment aucun format natif (.github/, AGENTS.md, CLAUDE.md, GEMINI.md, SKILL.md)",
      severity: "error",
      async check(ctx) {
        const fichiers = (await ctx.glob(PIPELINE)).filter((f) => !f.split("\\").join("/").includes("/context/source/"));
        if (fichiers.length === 0) {
          ctx.report.violation({ message: `Aucune source trouvée sous ${PIPELINE} : la règle ne peut rien vérifier.` });
          return;
        }
        for (const chemin of fichiers) {
          const code = masquerJava(await ctx.readFile(chemin), true);
          for (const trouve of code.matchAll(FORMAT_NATIF)) {
            ctx.report.violation({
              message: `Format natif « ${trouve[0]} » nommé dans le pipeline commun de contexte (ARCH-0078 : les formats natifs sont interprétés par des providers dédiés).`,
              file: chemin,
              line: ligne(code, trouve.index ?? 0),
              fix: "Déplacer la découverte de ce format dans un ContextSourceProvider de com.nexus.context.source.",
            });
          }
        }
      },
    },
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
              message: `« ${match[0]} » dans la construction du contexte : une convention native ne se traite que dans un ContextSourceProvider (ARCH-0078).`,
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
