/// <reference path="../rules.d.ts" />

// Règles de l'ADR BE-0036 (import opportuniste de SCIP). Contrôles propres à Archgate : les tests de
// com.nexus.index.scip vérifient l'import, pas les dépendances des autres paquetages ni l'absence de
// lancement de processus.
// - Décision : le format SCIP reste confiné dans com.nexus.index.scip ; les consommateurs de recherche
//   et de contexte ne dépendent d'aucune classe SCIP.
//   Contrôle scip-format-confined-to-scip-package : hors de core/src/main/java/com/nexus/index/scip/,
//   seule la composition (application/NexusApplicationFactory.java) référence com.nexus.index.scip, dans
//   toutes les sources de production (cœur et adaptateurs).
// - Décision : NEXUS ne lance jamais scip-java automatiquement.
//   Contrôle scip-import-launches-no-process : aucune source de com.nexus.index.scip ne lance de
//   processus (ProcessBuilder, Runtime.exec, ProcessHandle).
// Commentaires et littéraux effacés avant la recherche.

const SOURCES = "**/src/main/java/**/*.java";
const SCIP_PACKAGE = "core/src/main/java/com/nexus/index/scip/";
const COMPOSITION = "core/src/main/java/com/nexus/application/NexusApplicationFactory.java";
const SCIP_REFERENCE = /\bcom\s*\.\s*nexus\s*\.\s*index\s*\.\s*scip\b/g;
const PROCESS = /\bProcessBuilder\b|\bRuntime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*exec\s*\(|\bProcessHandle\b/g;

/** Efface les commentaires et, sauf si garderLitteraux, les littéraux (longueur et sauts de ligne conservés). */
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
    "scip-format-confined-to-scip-package": {
      description:
        "Hors de com.nexus.index.scip, seule la composition NexusApplicationFactory référence une classe SCIP",
      severity: "error",
      async check(ctx) {
        const files = (await ctx.glob(SOURCES)).sort();
        if (!files.some((file) => file.startsWith(SCIP_PACKAGE))) {
          ctx.report.violation({ message: `Aucune source sous ${SCIP_PACKAGE} : la règle ne peut rien vérifier.`, file: SCIP_PACKAGE });
          return;
        }
        for (const file of files) {
          if (file.startsWith(SCIP_PACKAGE) || file === COMPOSITION) continue;
          const code = masquerJava(await ctx.readFile(file));
          for (const match of code.matchAll(SCIP_REFERENCE)) {
            ctx.report.violation({
              message: "Référence à com.nexus.index.scip hors de son paquetage : le format SCIP reste confiné (BE-0036).",
              file,
              line: ligne(code, match.index ?? 0),
              fix: "Consommer CodeSymbol, SymbolRelation ou CodeIntelligenceSnapshot ; seule la composition instancie ScipCodeIndexImporter.",
            });
          }
        }
      },
    },
    "scip-import-launches-no-process": {
      description: "Aucune source de com.nexus.index.scip ne lance de processus : NEXUS ne lance jamais scip-java",
      severity: "error",
      async check(ctx) {
        for (const file of (await ctx.glob(`${SCIP_PACKAGE}**/*.java`)).sort()) {
          const code = masquerJava(await ctx.readFile(file));
          for (const match of code.matchAll(PROCESS)) {
            ctx.report.violation({
              message: `Lancement de processus dans l'import SCIP : « ${match[0].replace(/\s+/g, " ")} ». BE-0036 : NEXUS importe un index.scip existant et ne lance jamais scip-java.`,
              file,
              line: ligne(code, match.index ?? 0),
              fix: "Lire seulement index.scip à la racine du projet ; sa génération reste hors de NEXUS.",
            });
          }
        }
      },
    },
  },
} satisfies RuleSet;
