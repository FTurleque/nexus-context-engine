/// <reference path="../rules.d.ts" />

// Règle de l'ADR DATA-0006 (SQLite source de vérité structurelle, derrière une abstraction de persistance).
// Contrôle propre à Archgate : aucun test ni contrat ne vérifie où les API JDBC et SQLite sont référencées.
// - Décision : « Le cœur métier ne doit pas exposer directement des types JDBC ou SQLite. Les accès passent par
//   des repositories ou ports de persistance. »
//   Contrôle : dans core/src/main/java, java.sql, javax.sql et org.sqlite ne sont référencés (commentaires et
//   littéraux effacés) que sous com/nexus/persistence/sqlite/.
//   Tolérance unique, consignée dans la section « Écarts constatés » de l'ADR : la composition (NexusApplication,
//   NexusApplicationFactory) et le point d'entrée NexusCli peuvent référencer java.sql.SQLException, et rien d'autre.

const SOURCES = "core/src/main/java/**/*.java";
const ADAPTATEUR = "core/src/main/java/com/nexus/persistence/sqlite/";
const COMPOSITION = new Set([
  "core/src/main/java/com/nexus/application/NexusApplication.java",
  "core/src/main/java/com/nexus/application/NexusApplicationFactory.java",
  "core/src/main/java/com/nexus/cli/NexusCli.java",
]);
const TOLERE = "java.sql.SQLException";
const API_PERSISTANCE = /\b(?:java\s*\.\s*sql|javax\s*\.\s*sql|org\s*\.\s*sqlite)\b(?:\s*\.\s*(?:\w+|\*))*/g;

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
    "core-jdbc-confined-to-sqlite-adapter": {
      description:
        "Dans core/src/main/java, java.sql, javax.sql et org.sqlite ne sont référencés que par persistence/sqlite (SQLException seule tolérée dans la composition et NexusCli)",
      severity: "error",
      async check(ctx) {
        const fichiers = (await ctx.glob(SOURCES)).map((f) => f.replace(/\\/g, "/")).sort();
        if (fichiers.length === 0) {
          ctx.report.violation({ message: `Aucune source trouvée sous ${SOURCES} : la règle ne peut rien vérifier.` });
          return;
        }
        for (const chemin of fichiers) {
          if (chemin.startsWith(ADAPTATEUR)) continue;
          const code = masquerJava(await ctx.readFile(chemin));
          for (const trouve of code.matchAll(API_PERSISTANCE)) {
            const reference = trouve[0].replace(/\s+/g, "");
            if (COMPOSITION.has(chemin) && reference === TOLERE) continue;
            ctx.report.violation({
              message: `« ${reference} » hors de l'adaptateur SQLite : le cœur n'expose pas de types JDBC ou SQLite (DATA-0006).`,
              file: chemin,
              line: ligne(code, trouve.index ?? 0),
              fix: "Passer par un port de persistance (repository) implémenté sous com.nexus.persistence.sqlite.",
            });
          }
        }
      },
    },
  },
} satisfies RuleSet;
