/// <reference path="../rules.d.ts" />

// Règle de l'ADR DATA-0007 (Apache Lucene comme index de recherche local, derrière une abstraction SearchIndex).
// Contrôle propre à Archgate : les tests Lucene exercent l'index, aucun ne vérifie où l'API Lucene est référencée.
// - Décision : « Le code métier ne doit pas dépendre directement des classes Lucene » ; aucune API Lucene
//   n'apparaît dans ContextRequest, ContextBundle ou les modèles métier.
//   Contrôle : dans toutes les sources de production (**/src/main/java), org.apache.lucene n'est référencé
//   (commentaires et littéraux effacés) que sous core/src/main/java/com/nexus/search/lucene/ et
//   core/src/main/java/com/nexus/search/semantic/lucene/, les deux adaptateurs Lucene.

const SOURCES = "**/src/main/java/**/*.java";
const ADAPTATEURS = [
  "core/src/main/java/com/nexus/search/lucene/",
  "core/src/main/java/com/nexus/search/semantic/lucene/",
];
const LUCENE = /\borg\s*\.\s*apache\s*\.\s*lucene\b/g;

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
    "lucene-confined-to-search-adapters": {
      description:
        "org.apache.lucene n'est référencé dans les sources de production que par les adaptateurs search/lucene et search/semantic/lucene",
      severity: "error",
      async check(ctx) {
        const fichiers = (await ctx.glob(SOURCES)).map((f) => f.replace(/\\/g, "/")).sort();
        if (!fichiers.some((f) => ADAPTATEURS.some((a) => f.startsWith(a)))) {
          ctx.report.violation({
            message: `Aucune source sous ${ADAPTATEURS.join(" ni ")} : le confinement de Lucene ne peut pas être vérifié.`,
          });
          return;
        }
        for (const chemin of fichiers) {
          if (ADAPTATEURS.some((a) => chemin.startsWith(a))) continue;
          const code = masquerJava(await ctx.readFile(chemin));
          for (const trouve of code.matchAll(LUCENE)) {
            ctx.report.violation({
              message: "Référence à Lucene hors des adaptateurs de recherche : le code métier ne dépend pas de Lucene (DATA-0007).",
              file: chemin,
              line: ligne(code, trouve.index ?? 0),
              fix: "Passer par le port SearchIndex (ou SemanticSearchIndex) et garder l'API Lucene dans search/lucene ou search/semantic/lucene.",
            });
          }
        }
      },
    },
  },
} satisfies RuleSet;
