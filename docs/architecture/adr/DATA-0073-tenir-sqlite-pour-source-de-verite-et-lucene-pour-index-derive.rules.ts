/// <reference path="../rules.d.ts" />

// Règles de l'ADR DATA-0073 (SQLite source de vérité locale derrière des ports de persistance ; Lucene index de
// recherche dérivé, derrière SearchIndex). Contrôles propres à Archgate, complétés par CoreArchitectureTest sur le
// cœur : aucun contrat ne vérifie où les API JDBC, SQLite et Lucene sont référencées dans les adaptateurs.
// - Décision : « Le cœur métier n'expose pas de types JDBC ou SQLite : les accès passent par des repositories ou
//   ports de persistance. »
//   Contrôle (core-jdbc-confined-to-sqlite-adapter) : dans core/src/main/java, java.sql, javax.sql et org.sqlite ne
//   sont référencés (commentaires et littéraux effacés) que sous com/nexus/persistence/sqlite/.
//   Tolérance unique, décrite dans « Compliance and Enforcement » : la composition (NexusApplication,
//   NexusApplicationFactory) et le point d'entrée NexusCli peuvent référencer java.sql.SQLException, et rien d'autre.
// - Décision : « Le code métier ne dépend pas directement des classes Lucene, et aucune API Lucene n'apparaît dans
//   ContextRequest, ContextBundle ou les modèles métier. »
//   Contrôle (lucene-confined-to-search-adapters) : dans toutes les sources de production (**/src/main/java),
//   org.apache.lucene n'est référencé (commentaires et littéraux effacés) que sous
//   core/src/main/java/com/nexus/search/lucene/ et core/src/main/java/com/nexus/search/semantic/lucene/, les deux
//   adaptateurs Lucene.

const SOURCES_COEUR = "core/src/main/java/**/*.java";
const ADAPTATEUR = "core/src/main/java/com/nexus/persistence/sqlite/";
const COMPOSITION = new Set([
  "core/src/main/java/com/nexus/application/NexusApplication.java",
  "core/src/main/java/com/nexus/application/NexusApplicationFactory.java",
  "core/src/main/java/com/nexus/cli/NexusCli.java",
]);
const TOLERE = "java.sql.SQLException";
const API_PERSISTANCE = /\b(?:java\s*\.\s*sql|javax\s*\.\s*sql|org\s*\.\s*sqlite)\b(?:\s*\.\s*(?:\w+|\*))*/g;

const SOURCES_PRODUCTION = "**/src/main/java/**/*.java";
const ADAPTATEURS = [
  "core/src/main/java/com/nexus/search/lucene/",
  "core/src/main/java/com/nexus/search/semantic/lucene/",
];
const LUCENE = /\borg\s*\.\s*apache\s*\.\s*lucene\b/g;

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
    "core-jdbc-confined-to-sqlite-adapter": {
      description:
        "Dans core/src/main/java, java.sql, javax.sql et org.sqlite ne sont référencés que par persistence/sqlite (SQLException seule tolérée dans la composition et NexusCli)",
      severity: "error",
      async check(ctx) {
        const fichiers = (await ctx.glob(SOURCES_COEUR)).map((f) => f.replace(/\\/g, "/")).sort();
        if (fichiers.length === 0) {
          ctx.report.violation({ message: `Aucune source trouvée sous ${SOURCES_COEUR} : la règle ne peut rien vérifier.` });
          return;
        }
        for (const chemin of fichiers) {
          if (chemin.startsWith(ADAPTATEUR)) continue;
          const code = masquerJava(await ctx.readFile(chemin));
          for (const trouve of code.matchAll(API_PERSISTANCE)) {
            const reference = trouve[0].replace(/\s+/g, "");
            if (COMPOSITION.has(chemin) && reference === TOLERE) continue;
            ctx.report.violation({
              message: `« ${reference} » hors de l'adaptateur SQLite : le cœur n'expose pas de types JDBC ou SQLite (DATA-0073).`,
              file: chemin,
              line: ligne(code, trouve.index ?? 0),
              fix: "Passer par un port de persistance (repository) implémenté sous com.nexus.persistence.sqlite.",
            });
          }
        }
      },
    },
    "lucene-confined-to-search-adapters": {
      description:
        "org.apache.lucene n'est référencé dans les sources de production que par les adaptateurs search/lucene et search/semantic/lucene",
      severity: "error",
      async check(ctx) {
        const fichiers = (await ctx.glob(SOURCES_PRODUCTION)).map((f) => f.replace(/\\/g, "/")).sort();
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
              message: "Référence à Lucene hors des adaptateurs de recherche : le code métier ne dépend pas de Lucene (DATA-0073).",
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
