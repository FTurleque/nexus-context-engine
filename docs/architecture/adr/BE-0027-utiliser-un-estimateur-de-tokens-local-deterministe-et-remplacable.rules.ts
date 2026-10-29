/// <reference path="../rules.d.ts" />

// Règle de l'ADR BE-0027 (estimateur de tokens local, déterministe et remplaçable). Contrôle propre à
// Archgate : HeuristicTokenEstimatorTest vérifie l'estimation, aucun test ne vérifie le couplage.
// - Décision : l'heuristique locale vit derrière le port TokenEstimator ; le contrat reste indépendant
//   de l'heuristique, qui fonctionne sans dépendance réseau.
// - Contrôle (commentaires et littéraux effacés) : HeuristicTokenEstimator implémente TokenEstimator et
//   n'ouvre aucun accès réseau ; les sources de core/src/main/java/com/nexus/context/ ne nomment pas
//   HeuristicTokenEstimator et ne portent pas le ratio de 3,5 caractères par token.

const TOKEN_SOURCES = "core/src/main/java/com/nexus/token/**/*.java";
const HEURISTIC = "core/src/main/java/com/nexus/token/HeuristicTokenEstimator.java";
const CONTEXT_SOURCES = "core/src/main/java/com/nexus/context/**/*.java";

const RESEAU = /\bjava\s*\.\s*net\b|\b(?:HttpClient|HttpURLConnection|URLConnection|Socket)\b/g;
const COUPLAGE: { motif: RegExp; quoi: string }[] = [
  { motif: /\bHeuristicTokenEstimator\b/g, quoi: "référence directe à l'heuristique HeuristicTokenEstimator" },
  { motif: /(?<![\w.])3\.5[dDfF]?\b/g, quoi: "ratio de 3,5 caractères par token codé en dur" },
];

/** Efface commentaires et littéraux (longueur et sauts de ligne conservés). */
function masquerJava(source: string): string {
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
      effacer(i + 3, j - 3);
      i = j;
    } else if (c === '"' || c === "'") {
      let j = i + 1;
      while (j < n && source[j] !== c && source[j] !== "\n") j += source[j] === "\\" ? 2 : 1;
      j = Math.min(n, j + 1);
      effacer(i + 1, j - 1);
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
    "token-estimation-behind-port": {
      description:
        "HeuristicTokenEstimator implémente TokenEstimator sans réseau, et le pipeline de contexte ne dépend que du port TokenEstimator",
      severity: "error",
      async check(ctx) {
        const tokens = (await ctx.glob(TOKEN_SOURCES)).map((f) => f.replace(/\\/g, "/")).sort();
        if (!tokens.includes(HEURISTIC)) {
          ctx.report.violation({ message: `${HEURISTIC} introuvable : l'estimateur par défaut ne peut pas être vérifié.` });
          return;
        }
        for (const chemin of tokens) {
          const code = masquerJava(await ctx.readFile(chemin));
          if (chemin === HEURISTIC && !/\bclass\s+HeuristicTokenEstimator\b[^{]*\bimplements\b[^{]*\bTokenEstimator\b/.test(code)) {
            ctx.report.violation({
              message: "HeuristicTokenEstimator n'implémente pas TokenEstimator (BE-0027 : l'heuristique vit derrière le port).",
              file: chemin,
              fix: "Déclarer HeuristicTokenEstimator implements TokenEstimator.",
            });
          }
          for (const trouve of code.matchAll(RESEAU)) {
            ctx.report.violation({
              message: `Accès réseau « ${trouve[0]} » dans l'estimation de tokens : l'implémentation par défaut fonctionne sans réseau (BE-0027).`,
              file: chemin,
              line: ligne(code, trouve.index ?? 0),
              fix: "Garder l'estimateur local ; un tokenizer de fournisseur relève d'un adaptateur consommateur.",
            });
          }
        }
        for (const chemin of (await ctx.glob(CONTEXT_SOURCES)).sort()) {
          const code = masquerJava(await ctx.readFile(chemin));
          for (const { motif, quoi } of COUPLAGE) {
            for (const trouve of code.matchAll(motif)) {
              ctx.report.violation({
                message: `${quoi} dans le pipeline de contexte (BE-0027 : toute estimation passe par TokenEstimator).`,
                file: chemin,
                line: ligne(code, trouve.index ?? 0),
                fix: "Recevoir un TokenEstimator injecté (composition dans NexusApplicationFactory) et appeler estimate().",
              });
            }
          }
        }
      },
    },
  },
} satisfies RuleSet;
