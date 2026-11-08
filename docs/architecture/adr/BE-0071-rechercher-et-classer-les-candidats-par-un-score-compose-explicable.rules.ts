/// <reference path="../rules.d.ts" />

// Règles de l'ADR BE-0071 (recherche des candidats dans Lucene, SQLite et le graphe d'imports, score
// composé explicable). Contrôles propres à Archgate : les tests vérifient le déterminisme du classement
// et la fusion des candidats, aucun ne vérifie l'absence d'appel externe ni les dépendances du code Lucene.
//
// ranking-has-no-network-or-llm-call
// - Décision : « L'explication est produite par le moteur de ranking lui-même, à partir des composantes
//   qui ont servi au calcul ; elle n'est ni générée a posteriori, ni reformulée par un LLM. Aucun appel
//   LLM n'est nécessaire pour calculer ou expliquer le score. »
// - Contrôle : les sources du ranking (core/src/main/java/com/nexus/ranking/) n'ouvrent aucun accès
//   réseau (java.net, client HTTP, socket), ne lancent aucun processus et n'appellent aucun provider
//   d'embeddings ou LLM (EmbeddingProvider, Ollama). Commentaires et littéraux effacés avant la recherche.
//
// lucene-search-does-not-touch-sqlite
// - Décision : « Lucene indexe les noms de symboles comme champs du document fichier, pour qu'ils
//   influencent le score lexical du fichier, mais les objets `CodeSymbol` complets sont lus depuis SQLite.
//   Lucene ne devient pas source de vérité des symboles »
// - Contrôle : les sources de core/src/main/java/com/nexus/search/lucene/ (index lexical, hors
//   search/semantic) ne référencent ni la persistance SQLite (com.nexus.persistence, java.sql,
//   org.sqlite) ni IndexRepository. Commentaires et littéraux effacés avant la recherche.

const SOURCES_RANKING = "core/src/main/java/com/nexus/ranking/**/*.java";

const INTERDITS_RANKING: { motif: RegExp; quoi: string }[] = [
  { motif: /\bjava\s*\.\s*net\b/g, quoi: "accès réseau (java.net)" },
  { motif: /\b(?:HttpClient|HttpRequest|HttpURLConnection|URLConnection|Socket|SocketChannel)\b/g, quoi: "client réseau" },
  { motif: /\bProcessBuilder\b|\bRuntime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*exec\s*\(/g, quoi: "lancement de processus" },
  { motif: /\bEmbeddingProvider\b|\bollama\b|\bOllama\w*/g, quoi: "appel à un provider d'embeddings ou LLM" },
];

const SOURCES_LUCENE = "core/src/main/java/com/nexus/search/lucene/**/*.java";

const INTERDITS_LUCENE: { motif: RegExp; quoi: string }[] = [
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
    "ranking-has-no-network-or-llm-call": {
      description:
        "Le ranking (com.nexus.ranking) calcule et explique ses scores sans réseau, sans processus et sans provider d'embeddings ou LLM",
      severity: "error",
      async check(ctx) {
        const fichiers = (await ctx.glob(SOURCES_RANKING)).sort();
        if (fichiers.length === 0) {
          ctx.report.violation({ message: `Aucune source sous ${SOURCES_RANKING} : la règle ne peut rien vérifier.` });
          return;
        }
        for (const chemin of fichiers) {
          const code = masquerJava(await ctx.readFile(chemin));
          for (const { motif, quoi } of INTERDITS_RANKING) {
            for (const trouve of code.matchAll(motif)) {
              ctx.report.violation({
                message: `${quoi} dans le ranking : « ${trouve[0].replace(/\s+/g, " ")} ». BE-0071 : score et explication sont calculés localement à partir des composantes, sans LLM.`,
                file: chemin,
                line: ligne(code, trouve.index ?? 0),
                fix: "Recevoir le signal déjà calculé dans SearchCandidate (SearchSignals) au lieu d'appeler un service externe depuis le ranking.",
              });
            }
          }
        }
      },
    },
    "lucene-search-does-not-touch-sqlite": {
      description:
        "L'index lexical Lucene (com.nexus.search.lucene) ne lit ni n'écrit la persistance SQLite ni IndexRepository",
      severity: "error",
      async check(ctx) {
        const fichiers = (await ctx.glob(SOURCES_LUCENE)).sort();
        if (fichiers.length === 0) {
          ctx.report.violation({ message: `Aucune source sous ${SOURCES_LUCENE} : la règle ne peut rien vérifier.` });
          return;
        }
        for (const chemin of fichiers) {
          const code = masquerJava(await ctx.readFile(chemin));
          for (const { motif, quoi } of INTERDITS_LUCENE) {
            for (const trouve of code.matchAll(motif)) {
              ctx.report.violation({
                message: `${quoi} dans l'index Lucene : « ${trouve[0].replace(/\s+/g, " ")} ». BE-0071 : Lucene reste un index lexical, SQLite la source des symboles.`,
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
