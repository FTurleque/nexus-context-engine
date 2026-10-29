/// <reference path="../rules.d.ts" />

// Règles de l'ADR DATA-0051 (lots d'écriture SQLite bornés). SqliteBatchInsertTest exerce un lot au-delà de 128
// lignes ; ces règles fixent la borne, le passage obligé et l'absence de transaction dans le helper.
// - Décision : « Les écritures répétitives de symbols et symbol_relations passent par des insertions SQL
//   multi-lignes paramétrées, limitées à 128 lignes par instruction. »
//   Contrôles : SqliteBatchInsert déclare ROW_LIMIT = 128 (sqlite-batch-insert-bounded-to-128) ; dans les
//   sources de production, aucun INSERT INTO symbols ni INSERT INTO symbol_relations hors de SqliteBatchInsert
//   (symbol-inserts-go-through-batch-insert).
// - Décision : « Le helper ne possède pas la transaction : le code appelant garde la maîtrise des commits, des
//   rollbacks ».
//   Contrôle (batch-insert-owns-no-transaction) : SqliteBatchInsert n'appelle ni commit, ni rollback, ni
//   setAutoCommit, ni setSavepoint, ni releaseSavepoint, et n'exécute aucun BEGIN, COMMIT, ROLLBACK ou SAVEPOINT.
// Les commentaires sont effacés ; les littéraux sont conservés, puisque le SQL y figure.

const SOURCES = "**/src/main/java/**/*.java";
const HELPER = "core/src/main/java/com/nexus/persistence/sqlite/SqliteBatchInsert.java";
const BORNE = /\bROW_LIMIT\s*=\s*(\d+)\s*;/;
const INSERTION = /\bINSERT\s+(?:OR\s+\w+\s+)?INTO\s+["`\[]?(symbols|symbol_relations)\b/gi;
const TRANSACTION = /\.\s*(?:commit|rollback|setAutoCommit|setSavepoint|releaseSavepoint)\s*\(|"\s*(?:BEGIN|COMMIT|ROLLBACK|SAVEPOINT|RELEASE|END)\b/gi;

/** Efface les commentaires et garde les littéraux (longueur et sauts de ligne conservés). */
function masquerCommentaires(source: string): string {
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
      i = Math.min(n, j + 3);
    } else if (c === '"' || c === "'") {
      let j = i + 1;
      while (j < n && source[j] !== c && source[j] !== "\n") j += source[j] === "\\" ? 2 : 1;
      i = Math.min(n, j + 1);
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
    "sqlite-batch-insert-bounded-to-128": {
      description: "SqliteBatchInsert limite chaque instruction à ROW_LIMIT = 128 lignes",
      severity: "error",
      async check(ctx) {
        const code = masquerCommentaires(await ctx.readFile(HELPER));
        const trouve = BORNE.exec(code);
        if (!trouve || trouve[1] !== "128") {
          ctx.report.violation({
            message: `SqliteBatchInsert ne déclare pas ROW_LIMIT = 128${trouve ? ` (valeur ${trouve[1]})` : ""} : les lots sont limités à 128 lignes par instruction (DATA-0051).`,
            file: HELPER,
            line: trouve ? ligne(code, trouve.index) : undefined,
            fix: "Rétablir la borne de 128 ; la changer demande un nouvel ADR.",
          });
        }
      },
    },
    "symbol-inserts-go-through-batch-insert": {
      description: "Les INSERT INTO symbols et symbol_relations des sources de production ne figurent que dans SqliteBatchInsert",
      severity: "error",
      async check(ctx) {
        for (const chemin of (await ctx.glob(SOURCES)).map((f) => f.replace(/\\/g, "/")).sort()) {
          if (chemin === HELPER) continue;
          const code = masquerCommentaires(await ctx.readFile(chemin));
          for (const trouve of code.matchAll(INSERTION)) {
            ctx.report.violation({
              message: `Insertion dans ${trouve[1]} hors de SqliteBatchInsert : ces écritures passent par des lots bornés (DATA-0051).`,
              file: chemin,
              line: ligne(code, trouve.index ?? 0),
              fix: `Utiliser new SqliteBatchInsert(connection, SqliteBatchInsert.Table.${trouve[1].toLowerCase() === "symbols" ? "SYMBOLS" : "RELATIONS"}).`,
            });
          }
        }
      },
    },
    "batch-insert-owns-no-transaction": {
      description: "SqliteBatchInsert ne valide, n'annule ni n'ouvre de transaction : l'appelant la possède",
      severity: "error",
      async check(ctx) {
        const code = masquerCommentaires(await ctx.readFile(HELPER));
        for (const trouve of code.matchAll(TRANSACTION)) {
          ctx.report.violation({
            message: `« ${trouve[0].replace(/\s+/g, " ").trim()} » dans SqliteBatchInsert : le helper ne possède pas la transaction (DATA-0051).`,
            file: HELPER,
            line: ligne(code, trouve.index ?? 0),
            fix: "Laisser le commit et le rollback au code appelant.",
          });
        }
      },
    },
  },
} satisfies RuleSet;
