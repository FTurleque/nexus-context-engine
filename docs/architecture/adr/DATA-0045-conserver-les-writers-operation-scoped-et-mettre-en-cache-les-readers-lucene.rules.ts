/// <reference path="../rules.d.ts" />

// Règles de l'ADR DATA-0045 (readers Lucene persistants bornés, writers operation-scoped).
// Les tests NexusApplicationLongLivedLifecycleTest et LuceneLifecycleQualificationBenchmarkTest exercent le
// comportement ; ces règles fixent la structure que la décision décrit.
// - Décision : « Les writers restent ouverts uniquement pendant applyChanges ou rebuild, puis sont fermés » ;
//   Confirmation : « inspection de l'absence de writer persistant dans les classes de cache ».
//   Contrôle (reader-cache-holds-no-index-writer) : BoundedLuceneSearcherCache, PersistentLuceneReaderSupport,
//   PersistentLuceneSearchIndex et PersistentLuceneSemanticSearchIndex ne référencent pas IndexWriter
//   (commentaires et littéraux effacés).
// - Décision : « Le cache est borné à 100 projets par index ».
//   Contrôle (reader-cache-bounded-to-100-projects) : PersistentLuceneSearchIndex et
//   PersistentLuceneSemanticSearchIndex déclarent MAX_CACHED_PROJECTS = 100.
// - Décision : « La persistance est activée uniquement par la composition NexusApplication.createLongLived(...),
//   destinée aux processus REST et MCP » ; create(...) reste operation-scoped pour la CLI.
//   Contrôle (long-lived-composition-only-in-rest-and-mcp) : dans les sources de production, createLongLived(
//   n'est appelé que sous adapters/rest-quarkus/src/main/java et adapters/mcp-java/src/main/java (hors de sa
//   propre définition dans NexusApplication) ; NexusApplication ne passe true (readers persistants) à
//   NexusApplicationFactory.create que depuis createLongLived ; new PersistentLuceneSearchIndex(…) et
//   new PersistentLuceneSemanticSearchIndex(…) n'apparaissent que dans NexusApplicationFactory.

const SOURCES = "**/src/main/java/**/*.java";
const LUCENE = "core/src/main/java/com/nexus/search/lucene";
const SEMANTIQUE = "core/src/main/java/com/nexus/search/semantic/lucene";
const CLASSES_DE_CACHE = [
  `${LUCENE}/BoundedLuceneSearcherCache.java`,
  `${LUCENE}/PersistentLuceneReaderSupport.java`,
  `${LUCENE}/PersistentLuceneSearchIndex.java`,
  `${SEMANTIQUE}/PersistentLuceneSemanticSearchIndex.java`,
];
const INDEX_PERSISTANTS = [`${LUCENE}/PersistentLuceneSearchIndex.java`, `${SEMANTIQUE}/PersistentLuceneSemanticSearchIndex.java`];
const FACADE = "core/src/main/java/com/nexus/application/NexusApplication.java";
const FABRIQUE = "core/src/main/java/com/nexus/application/NexusApplicationFactory.java";
const SERVEURS = ["adapters/rest-quarkus/src/main/java/", "adapters/mcp-java/src/main/java/"];
const WRITER = /\bIndexWriter\b/g;
const BORNE = /\bMAX_CACHED_PROJECTS\s*=\s*(\d+)\s*;/;
const APPEL_LONGUE_DUREE = /\bcreateLongLived\s*\(/g;
const DEFINITION_LONGUE_DUREE = /\bstatic\s+NexusApplication\s+createLongLived\s*\(/g;
const INSTANCIATION_PERSISTANTE = /\bnew\s+PersistentLucene(?:Semantic)?SearchIndex\s*\(/g;
const FABRIQUE_PERSISTANTE = /\bNexusApplicationFactory\s*\.\s*create\s*\([^;]*,\s*true\s*\)\s*;/g;
const METHODE_DE_FABRIQUE = /\bstatic\s+NexusApplication\s+(\w+)\s*\(/g;

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
    "reader-cache-holds-no-index-writer": {
      description: "Les classes du cache de readers Lucene ne référencent pas IndexWriter : les writers restent operation-scoped",
      severity: "error",
      async check(ctx) {
        for (const chemin of CLASSES_DE_CACHE) {
          const code = masquerJava(await ctx.readFile(chemin));
          for (const trouve of code.matchAll(WRITER)) {
            ctx.report.violation({
              message: "IndexWriter dans une classe du cache de readers : aucun writer persistant n'est gardé (DATA-0045).",
              file: chemin,
              line: ligne(code, trouve.index ?? 0),
              fix: "Ouvrir le writer dans LuceneSearchIndex ou LuceneSemanticSearchIndex, le temps de applyChanges ou rebuild.",
            });
          }
        }
      },
    },
    "reader-cache-bounded-to-100-projects": {
      description: "Les index Lucene persistants bornent leur cache à MAX_CACHED_PROJECTS = 100 projets",
      severity: "error",
      async check(ctx) {
        for (const chemin of INDEX_PERSISTANTS) {
          const code = masquerJava(await ctx.readFile(chemin));
          const trouve = BORNE.exec(code);
          if (!trouve || trouve[1] !== "100") {
            ctx.report.violation({
              message: `${chemin.split("/").pop()} ne déclare pas MAX_CACHED_PROJECTS = 100${trouve ? ` (valeur ${trouve[1]})` : ""} : le cache est borné à 100 projets par index (DATA-0045).`,
              file: chemin,
              line: trouve ? ligne(code, trouve.index) : undefined,
              fix: "Rétablir la borne de 100 ; la changer demande un nouvel ADR.",
            });
          }
        }
      },
    },
    "long-lived-composition-only-in-rest-and-mcp": {
      description:
        "createLongLived(...) n'est appelé que par les adaptateurs REST et MCP ; les index persistants ne sont créés que par NexusApplicationFactory",
      severity: "error",
      async check(ctx) {
        for (const chemin of (await ctx.glob(SOURCES)).map((f) => f.replace(/\\/g, "/")).sort()) {
          const code = masquerJava(await ctx.readFile(chemin));
          if (!SERVEURS.some((s) => chemin.startsWith(s))) {
            const definitions = new Set(
              chemin === FACADE
                ? [...code.matchAll(DEFINITION_LONGUE_DUREE)].map((d) => (d.index ?? 0) + d[0].length - 1)
                : [],
            );
            for (const trouve of code.matchAll(APPEL_LONGUE_DUREE)) {
              const parenthese = (trouve.index ?? 0) + trouve[0].length - 1;
              if (definitions.has(parenthese)) continue;
              ctx.report.violation({
                message: "createLongLived(...) appelé hors des processus REST et MCP : la CLI et les commandes one-shot restent operation-scoped (DATA-0045).",
                file: chemin,
                line: ligne(code, trouve.index ?? 0),
                fix: "Utiliser NexusApplication.create(...) hors de adapters/rest-quarkus et adapters/mcp-java.",
              });
            }
          }
          if (chemin === FACADE) {
            // La façade ne demande des readers persistants (dernier argument true) que depuis createLongLived.
            for (const appel of code.matchAll(FABRIQUE_PERSISTANTE)) {
              const avant = code.slice(0, appel.index ?? 0);
              const methodes = [...avant.matchAll(METHODE_DE_FABRIQUE)];
              const englobante = methodes.length > 0 ? methodes[methodes.length - 1][1] : "?";
              if (englobante === "createLongLived") continue;
              ctx.report.violation({
                message: `${englobante}(...) compose des readers persistants : seule createLongLived(...) les active (DATA-0045).`,
                file: chemin,
                line: ligne(code, appel.index ?? 0),
                fix: "Passer false à NexusApplicationFactory.create hors de createLongLived(...).",
              });
            }
          }
          if (chemin === FABRIQUE) continue;
          for (const trouve of code.matchAll(INSTANCIATION_PERSISTANTE)) {
            ctx.report.violation({
              message: "Index Lucene persistant instancié hors de NexusApplicationFactory : seule la composition longue durée l'active (DATA-0045).",
              file: chemin,
              line: ligne(code, trouve.index ?? 0),
              fix: "Laisser NexusApplicationFactory choisir l'implémentation selon la composition (create ou createLongLived).",
            });
          }
        }
      },
    },
  },
} satisfies RuleSet;
