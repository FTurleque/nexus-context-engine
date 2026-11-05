/// <reference path="../rules.d.ts" />

// Règle de l'ADR BE-0008 (JavaParser, analyseur Java embarqué). Contrôle propre à Archgate : aucun test
// ni contrat ne vérifie où les types JavaParser sont référencés.
// - Décision : JavaParser est l'implémentation embarquée de LanguageAnalyzer ; il ne devient pas une
//   dépendance des contrats métier et son résultat est normalisé dans les modèles NEXUS.
// - Contrôle : toute référence à com.github.javaparser dans les sources de production (cœur et
//   adaptateurs) reste dans core/src/main/java/com/nexus/index/java/, et JavaParserLanguageAnalyzer
//   implémente LanguageAnalyzer. Les commentaires et les littéraux sont effacés avant la recherche.

const SOURCES = ["core/src/main/java/**/*.java", "adapters/*/src/main/java/**/*.java"];
const ANALYZER_PACKAGE = "core/src/main/java/com/nexus/index/java/";
const ANALYZER = `${ANALYZER_PACKAGE}JavaParserLanguageAnalyzer.java`;
const JAVAPARSER = /\bcom\s*\.\s*github\s*\.\s*javaparser\b/g;

/** Efface commentaires et littéraux (longueur et sauts de ligne conservés). */
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
    "javaparser-confined-to-java-analyzer": {
      description:
        "Les types JavaParser (com.github.javaparser) ne sont référencés que dans core/src/main/java/com/nexus/index/java/, dont JavaParserLanguageAnalyzer implémente LanguageAnalyzer",
      severity: "error",
      async check(ctx) {
        const fichiers = (await Promise.all(SOURCES.map((motif) => ctx.glob(motif)))).flat().map((f) => f.replace(/\\/g, "/")).sort();
        if (!fichiers.includes(ANALYZER)) {
          ctx.report.violation({
            message: `${ANALYZER} introuvable : l'implémentation JavaParser de LanguageAnalyzer ne peut pas être vérifiée.`,
            file: ANALYZER_PACKAGE,
          });
          return;
        }
        for (const chemin of fichiers) {
          const code = masquerJava(await ctx.readFile(chemin));
          if (chemin === ANALYZER) {
            if (!/\bclass\s+JavaParserLanguageAnalyzer\b[^{]*\bimplements\b[^{]*\bLanguageAnalyzer\b/.test(code)) {
              ctx.report.violation({
                message: "JavaParserLanguageAnalyzer n'implémente pas LanguageAnalyzer (BE-0008 : JavaParser est une implémentation de ce contrat NEXUS).",
                file: chemin,
                fix: "Déclarer JavaParserLanguageAnalyzer implements LanguageAnalyzer.",
              });
            }
          }
          if (chemin.startsWith(ANALYZER_PACKAGE)) continue;
          for (const trouve of code.matchAll(JAVAPARSER)) {
            ctx.report.violation({
              message: "Référence à JavaParser hors du package de l'analyseur : JavaParser ne doit pas devenir une dépendance des contrats métier (BE-0008).",
              file: chemin,
              line: ligne(code, trouve.index ?? 0),
              fix: "Mapper le résultat vers CodeSymbol / SymbolRelation dans com.nexus.index.java et ne consommer que les modèles NEXUS.",
            });
          }
        }
      },
    },
  },
} satisfies RuleSet;
