/// <reference path="../rules.d.ts" />

// Règle de l'ADR DATA-0050 (temporaires SQLite en mémoire). Contrôle propre à Archgate : aucun test ne lit la
// valeur de temp_store (SqliteDatabaseHardeningTest vérifie busy_timeout et foreign_keys).
// - Décision : « Chaque connexion créée par SqliteDatabase configure PRAGMA temp_store=MEMORY avant toute
//   transaction. »
//   Contrôle : dans les sources de production, seul SqliteDatabase.java ouvre une connexion JDBC
//   (DriverManager.getConnection ou URL "jdbc:sqlite:") ; chaque ouverture y est suivie, avant le return qui
//   rend la connexion, de PRAGMA temp_store = MEMORY ; aucune source Java ni migration SQL ne fixe temp_store à
//   une autre valeur.
// Les commentaires sont effacés ; les littéraux sont conservés, puisque le PRAGMA en est un.

const SOURCES = "**/src/main/java/**/*.java";
const MIGRATIONS = "core/src/main/resources/db/migration/*.sql";
const BASE = "core/src/main/java/com/nexus/persistence/sqlite/SqliteDatabase.java";
const OUVERTURE = /\bDriverManager\s*\.\s*getConnection\s*\(|"jdbc:sqlite:/g;
const OUVERTURE_DRIVER = /\bDriverManager\s*\.\s*getConnection\s*\(/g;
const MEMOIRE = /PRAGMA\s+temp_store\s*=\s*(?:MEMORY|2)\b/i;
const AUTRE_VALEUR = /PRAGMA\s+temp_store\s*=\s*(?!(?:MEMORY|2)\b)['"]?\w+/gi;
const RETOUR = /\breturn\s+connection\s*;/;

/** Efface les commentaires et garde les littéraux (longueur et sauts de ligne conservés). */
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

function masquerCommentaires(source: string): string {
  return masquerJava(source, true);
}

function ligne(texte: string, index: number): number {
  let l = 1;
  for (let k = 0; k < index; k++) if (texte[k] === "\n") l++;
  return l;
}

export default {
  rules: {
    "sqlite-connections-use-memory-temp-store": {
      description:
        "Seul SqliteDatabase ouvre les connexions SQLite, chacune avec PRAGMA temp_store = MEMORY ; aucune autre valeur n'est fixée",
      severity: "error",
      async check(ctx) {
        for (const chemin of (await ctx.glob(SOURCES)).map((f) => f.replace(/\\/g, "/")).sort()) {
          const code = masquerCommentaires(await ctx.readFile(chemin));
          if (chemin !== BASE) {
            for (const trouve of code.matchAll(OUVERTURE)) {
              ctx.report.violation({
                message: "Connexion SQLite ouverte hors de SqliteDatabase : elle échapperait à temp_store=MEMORY (DATA-0050).",
                file: chemin,
                line: ligne(code, trouve.index ?? 0),
                fix: "Obtenir la connexion par SqliteDatabase.",
              });
            }
          }
          for (const trouve of code.matchAll(AUTRE_VALEUR)) {
            ctx.report.violation({
              message: `« ${trouve[0]} » : les temporaires SQLite restent en mémoire (DATA-0050).`,
              file: chemin,
              line: ligne(code, trouve.index ?? 0),
            });
          }
        }
        for (const chemin of (await ctx.glob(MIGRATIONS)).map((f) => f.replace(/\\/g, "/")).sort()) {
          const sql = await ctx.readFile(chemin);
          for (const trouve of sql.matchAll(AUTRE_VALEUR)) {
            ctx.report.violation({
              message: `« ${trouve[0]} » dans une migration : les temporaires SQLite restent en mémoire (DATA-0050).`,
              file: chemin,
              line: ligne(sql, trouve.index ?? 0),
            });
          }
        }
        const base = masquerCommentaires(await ctx.readFile(BASE));
        const ouvertures = [...base.matchAll(OUVERTURE_DRIVER)];
        if (ouvertures.length === 0) {
          ctx.report.violation({
            message: "SqliteDatabase n'ouvre plus de connexion par DriverManager.getConnection : temp_store ne peut pas être vérifié (DATA-0050).",
            file: BASE,
          });
        }
        for (const ouverture of ouvertures) {
          const suite = base.slice(ouverture.index ?? 0);
          const retour = RETOUR.exec(suite);
          const configuration = retour ? suite.slice(0, retour.index) : suite;
          if (!MEMOIRE.test(configuration)) {
            ctx.report.violation({
              message: "Connexion ouverte sans PRAGMA temp_store = MEMORY avant d'être rendue (DATA-0050).",
              file: BASE,
              line: ligne(base, ouverture.index ?? 0),
              fix: 'Exécuter statement.execute("PRAGMA temp_store = MEMORY") à la création de la connexion.',
            });
          }
        }
      },
    },
  },
} satisfies RuleSet;
