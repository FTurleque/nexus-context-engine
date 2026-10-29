/// <reference path="../rules.d.ts" />

// Règles de l'ADR DATA-0020 (schéma SQLite versionné par des migrations SQL embarquées).
// - Décision : migrations dans core/src/main/resources/db/migration/, nommées V001__initial_schema.sql,
//   V002__description.sql…
//   Contrôle (migration-files-named-vnnn) : chaque fichier du dossier est nommé Vnnn__description.sql
//   (trois chiffres, description en minuscules séparée par _), numéros uniques et contigus depuis 001.
//   Les tests SchemaMigrator*Test appliquent les migrations ; aucun ne vérifie le nommage du dossier.
// - Décision : le migrateur applique « les migrations connues dans l'ordre » (liste explicite ordonnée).
//   Contrôle (migrations-listed-in-schema-migrator) : SchemaMigrator.java déclare chaque migration du dossier
//   par new Migration(n, "db/migration/Vnnn__….sql"), avec n égal à nnn, une seule fois, dans l'ordre croissant,
//   et ne déclare aucun script absent du dossier.
// - Décision (Confirmation) : « aucune table métier n'est créée directement dans les repositories » ; le
//   migrateur ne crée que schema_migrations.
//   Contrôle (no-table-creation-outside-migrations) : dans les sources de production, aucun littéral
//   « CREATE TABLE » hors de SchemaMigrator.java, et SchemaMigrator.java ne crée que schema_migrations.
// Les commentaires sont effacés ; les littéraux sont conservés, puisque le SQL y figure.

const MIGRATIONS = "core/src/main/resources/db/migration";
const MIGRATEUR = "core/src/main/java/com/nexus/persistence/sqlite/SchemaMigrator.java";
const SOURCES = "**/src/main/java/**/*.java";
const NOM = /^V(\d{3})__[a-z0-9]+(?:_[a-z0-9]+)*\.sql$/;
const DECLARATION = /\bnew\s+Migration\s*\(\s*(\d+)\s*,\s*"db\/migration\/([^"]+)"\s*\)/g;
const CREATION_TABLE = /\bCREATE\s+(?:TEMP(?:ORARY)?\s+)?(?:VIRTUAL\s+)?TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([\w"`\[\].]+)/gi;
const TABLE_DU_MIGRATEUR = "schema_migrations";

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

function nomDeFichier(chemin: string): string {
  return chemin.replace(/\\/g, "/").split("/").pop() ?? chemin;
}

async function fichiersDeMigration(ctx: RuleContext): Promise<string[]> {
  return (await ctx.glob(`${MIGRATIONS}/*`)).map((f) => f.replace(/\\/g, "/")).sort();
}

export default {
  rules: {
    "migration-files-named-vnnn": {
      description:
        "Les migrations de db/migration sont nommées Vnnn__description.sql, numéros uniques et contigus depuis 001",
      severity: "error",
      async check(ctx) {
        const fichiers = await fichiersDeMigration(ctx);
        if (fichiers.length === 0) {
          ctx.report.violation({ message: `Aucune migration sous ${MIGRATIONS}/ : la base vierge n'est plus initialisée par les migrations.` });
          return;
        }
        const versions: number[] = [];
        for (const chemin of fichiers) {
          const trouve = NOM.exec(nomDeFichier(chemin));
          if (!trouve) {
            ctx.report.violation({
              message: `${nomDeFichier(chemin)} : une migration est nommée Vnnn__description.sql (DATA-0020).`,
              file: chemin,
              fix: "Renommer en V{numéro sur trois chiffres}__{description_en_minuscules}.sql, ou sortir ce fichier du dossier.",
            });
            continue;
          }
          const version = Number(trouve[1]);
          if (versions.includes(version)) {
            ctx.report.violation({
              message: `Numéro de migration ${trouve[1]} dupliqué (DATA-0020).`,
              file: chemin,
              fix: "Prendre le numéro suivant le dernier publié.",
            });
          }
          versions.push(version);
        }
        const tries = [...new Set(versions)].sort((a, b) => a - b);
        tries.forEach((version, rang) => {
          if (version !== rang + 1) {
            ctx.report.violation({
              message: `Numérotation des migrations non contiguë : V${String(rang + 1).padStart(3, "0")} attendue, V${String(version).padStart(3, "0")} trouvée (DATA-0020).`,
              fix: "Numéroter la nouvelle migration à la suite de la dernière publiée.",
            });
          }
        });
      },
    },
    "migrations-listed-in-schema-migrator": {
      description:
        "SchemaMigrator déclare chaque migration du dossier, une fois, avec son numéro et dans l'ordre croissant",
      severity: "error",
      async check(ctx) {
        const code = masquerCommentaires(await ctx.readFile(MIGRATEUR));
        const declarees: { version: number; nom: string; index: number }[] = [];
        for (const trouve of code.matchAll(DECLARATION)) {
          declarees.push({ version: Number(trouve[1]), nom: trouve[2], index: trouve.index ?? 0 });
        }
        const presents = new Set((await fichiersDeMigration(ctx)).map(nomDeFichier));
        let precedente = 0;
        const vus = new Set<string>();
        for (const { version, nom, index } of declarees) {
          const ou = { file: MIGRATEUR, line: ligne(code, index) };
          const numero = /^V(\d{3})__/.exec(nom);
          if (!numero || Number(numero[1]) !== version) {
            ctx.report.violation({ ...ou, message: `new Migration(${version}, "…/${nom}") : le numéro ne correspond pas au nom du script (DATA-0020).` });
          }
          if (!presents.has(nom)) {
            ctx.report.violation({ ...ou, message: `${nom} est déclaré par SchemaMigrator mais absent de ${MIGRATIONS}/ (DATA-0020).` });
          }
          if (vus.has(nom)) {
            ctx.report.violation({ ...ou, message: `${nom} est déclaré deux fois par SchemaMigrator (DATA-0020).` });
          }
          if (version <= precedente) {
            ctx.report.violation({ ...ou, message: `Migration ${version} déclarée après ${precedente} : la liste n'est plus ordonnée (DATA-0020).` });
          }
          vus.add(nom);
          precedente = Math.max(precedente, version);
        }
        for (const nom of [...presents].sort()) {
          if (vus.has(nom)) continue;
          ctx.report.violation({
            message: `${nom} n'est pas déclaré dans SchemaMigrator : il ne serait jamais appliqué (DATA-0020).`,
            file: `${MIGRATIONS}/${nom}`,
            fix: `Ajouter new Migration(n, "db/migration/${nom}") à la fin de la liste MIGRATIONS de SchemaMigrator.`,
          });
        }
      },
    },
    "no-table-creation-outside-migrations": {
      description:
        "Aucun CREATE TABLE dans les sources Java de production, hors de la table schema_migrations du migrateur",
      severity: "error",
      async check(ctx) {
        for (const chemin of (await ctx.glob(SOURCES)).map((f) => f.replace(/\\/g, "/")).sort()) {
          const code = masquerCommentaires(await ctx.readFile(chemin));
          for (const trouve of code.matchAll(CREATION_TABLE)) {
            const table = trouve[1].replace(/["`\[\]]/g, "").toLowerCase();
            if (chemin === MIGRATEUR && table === TABLE_DU_MIGRATEUR) continue;
            ctx.report.violation({
              message: `Création de la table « ${trouve[1]} » dans le code Java : le schéma est porté par les migrations SQL (DATA-0020).`,
              file: chemin,
              line: ligne(code, trouve.index ?? 0),
              fix: `Écrire une nouvelle migration Vnnn__….sql dans ${MIGRATIONS}/ et la déclarer dans SchemaMigrator.`,
            });
          }
        }
      },
    },
  },
} satisfies RuleSet;
