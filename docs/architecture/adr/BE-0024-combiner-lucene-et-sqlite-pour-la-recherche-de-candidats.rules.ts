/// <reference path="../rules.d.ts" />

// Règle de l'ADR BE-0024 (Lucene et SQLite pour la recherche de candidats). Contrôle propre à Archgate :
// les tests couvrent la fusion des candidats, aucun ne vérifie les dépendances du code Lucene.
// - Décision : Lucene sert la recherche lexicale de fichiers ; les objets CodeSymbol complets restent
//   lus depuis SQLite, et aucun second référentiel canonique des symboles n'est introduit.
// - Contrôle : les sources de core/src/main/java/com/nexus/search/lucene/ (index lexical, hors
//   search/semantic) ne référencent ni la persistance SQLite (com.nexus.persistence, java.sql,
//   org.sqlite) ni IndexRepository. Commentaires et littéraux effacés avant la recherche.

const SOURCES = "core/src/main/java/com/nexus/search/lucene/**/*.java";

const INTERDITS: { motif: RegExp; quoi: string }[] = [
  { motif: /\bcom\s*\.\s*nexus\s*\.\s*persistence\b/g, quoi: "dépendance à la persistance SQLite (com.nexus.persistence)" },
  { motif: /\bjava\s*\.\s*sql\b|\borg\s*\.\s*sqlite\b/g, quoi: "accès SQL direct" },
  { motif: /\bIndexRepository\b/g, quoi: "accès au référentiel structurel (IndexRepository)" },
];

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
    "lucene-search-does-not-touch-sqlite": {
      description:
        "L'index lexical Lucene (com.nexus.search.lucene) ne lit ni n'écrit la persistance SQLite ni IndexRepository",
      severity: "error",
      async check(ctx) {
        const fichiers = (await ctx.glob(SOURCES)).sort();
        if (fichiers.length === 0) {
          ctx.report.violation({ message: `Aucune source sous ${SOURCES} : la règle ne peut rien vérifier.` });
          return;
        }
        for (const chemin of fichiers) {
          const code = masquerJava(await ctx.readFile(chemin));
          for (const { motif, quoi } of INTERDITS) {
            for (const trouve of code.matchAll(motif)) {
              ctx.report.violation({
                message: `${quoi} dans l'index Lucene : « ${trouve[0].replace(/\s+/g, " ")} ». BE-0024 : Lucene reste un index lexical, SQLite la source des symboles.`,
                file: chemin,
                line: ligne(code, trouve.index ?? 0),
                fix: "Lire symboles et relations par IndexRepository dans les stratégies structurelles, puis fusionner par CandidateMerger.",
              });
            }
          }
        }
      },
    },
  },
} satisfies RuleSet;
