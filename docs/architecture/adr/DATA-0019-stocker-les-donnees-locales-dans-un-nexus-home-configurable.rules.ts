/// <reference path="../rules.d.ts" />

// Règles de l'ADR DATA-0019 (NEXUS_HOME configurable). Contrôles propres à Archgate : NexusPathsTest couvre le
// stockage privé, aucun test ne vérifie l'ordre de résolution du home ni sa centralisation.
// - Décision : priorité propriété système nexus.home, puis variable NEXUS_HOME, puis ${user.home}/.nexus.
//   Contrôle (nexus-home-resolution-order) : NexusPaths déclare HOME_PROPERTY = "nexus.home" et
//   HOME_ENVIRONMENT_VARIABLE = "NEXUS_HOME", et fromEnvironment() lit, dans cet ordre,
//   System.getProperty(HOME_PROPERTY), System.getenv(HOME_ENVIRONMENT_VARIABLE), puis user.home avec ".nexus".
// - Décision (Confirmation) : « NexusPaths centralise la résolution des chemins » ; SQLite et Lucene utilisent des
//   sous-chemins dérivés de NEXUS_HOME.
//   Contrôle (nexus-home-resolved-by-nexus-paths) : dans les sources de production, les littéraux "nexus.home",
//   "NEXUS_HOME" et "nexus.db" n'apparaissent que dans NexusPaths.java, et aucun autre fichier ne lit
//   NexusPaths.HOME_PROPERTY ou NexusPaths.HOME_ENVIRONMENT_VARIABLE par System.getProperty ou System.getenv.
// Les commentaires sont effacés ; les littéraux sont conservés, puisque ce sont eux qui sont contrôlés.

const SOURCES = "**/src/main/java/**/*.java";
const NEXUS_PATHS = "core/src/main/java/com/nexus/config/NexusPaths.java";
const LITTERAUX_RESERVES = new Set(["nexus.home", "NEXUS_HOME", "nexus.db"]);
const LECTURE_DETOURNEE =
  /\bSystem\s*\.\s*(?:getProperty|getenv)\s*\(\s*NexusPaths\s*\.\s*HOME_(?:PROPERTY|ENVIRONMENT_VARIABLE)\b/g;

/**
 * Efface les commentaires et garde les littéraux (longueur et sauts de ligne conservés) ; relève au passage
 * le contenu brut de chaque littéral chaîne ordinaire avec sa position.
 */
function analyser(source: string): { code: string; chaines: { valeur: string; index: number }[] } {
  const out = source.split("");
  const chaines: { valeur: string; index: number }[] = [];
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
      if (c === '"') chaines.push({ valeur: source.slice(i + 1, Math.min(n, j)), index: i });
      i = Math.min(n, j + 1);
    } else {
      i++;
    }
  }
  return { code: out.join(""), chaines };
}

// Fonctions communes : copie conforme de gabarit/fonctions-communes.ts (contrôlée par GEN-0057).

function ligne(texte: string, index: number): number {
  let l = 1;
  for (let k = 0; k < index; k++) if (texte[k] === "\n") l++;
  return l;
}

export default {
  rules: {
    "nexus-home-resolution-order": {
      description:
        "NexusPaths.fromEnvironment() résout le home par nexus.home, puis NEXUS_HOME, puis ${user.home}/.nexus",
      severity: "error",
      async check(ctx) {
        const { code } = analyser(await ctx.readFile(NEXUS_PATHS));
        const constantes: [RegExp, string][] = [
          [/\bHOME_PROPERTY\s*=\s*"nexus\.home"\s*;/, 'HOME_PROPERTY = "nexus.home"'],
          [/\bHOME_ENVIRONMENT_VARIABLE\s*=\s*"NEXUS_HOME"\s*;/, 'HOME_ENVIRONMENT_VARIABLE = "NEXUS_HOME"'],
        ];
        for (const [motif, attendu] of constantes) {
          if (!motif.test(code)) {
            ctx.report.violation({
              message: `NexusPaths ne déclare plus ${attendu} (DATA-0019).`,
              file: NEXUS_PATHS,
              fix: "Rétablir la constante ; changer de nom de propriété ou de variable demande un nouvel ADR.",
            });
          }
        }
        const debut = code.search(/\bstatic\s+NexusPaths\s+fromEnvironment\s*\(\s*\)/);
        if (debut < 0) {
          ctx.report.violation({
            message: "NexusPaths.fromEnvironment() introuvable : l'ordre de résolution du home ne peut pas être vérifié.",
            file: NEXUS_PATHS,
          });
          return;
        }
        const etapes: [RegExp, string][] = [
          [/\bSystem\s*\.\s*getProperty\s*\(\s*HOME_PROPERTY\s*\)/, "System.getProperty(HOME_PROPERTY)"],
          [/\bSystem\s*\.\s*getenv\s*\(\s*HOME_ENVIRONMENT_VARIABLE\s*\)/, "System.getenv(HOME_ENVIRONMENT_VARIABLE)"],
          [/\bSystem\s*\.\s*getProperty\s*\(\s*"user\.home"\s*\)\s*,\s*"\.nexus"/, 'System.getProperty("user.home"), ".nexus"'],
        ];
        let position = debut;
        for (const [motif, attendu] of etapes) {
          const reste = code.slice(position);
          const trouve = motif.exec(reste);
          if (!trouve) {
            ctx.report.violation({
              message: `fromEnvironment() ne lit pas ${attendu} à son rang : l'ordre nexus.home, NEXUS_HOME, \${user.home}/.nexus n'est plus respecté (DATA-0019).`,
              file: NEXUS_PATHS,
              line: ligne(code, debut),
              fix: "Lire la propriété système, puis la variable d'environnement, puis replier sur ${user.home}/.nexus.",
            });
            return;
          }
          position += trouve.index + trouve[0].length;
        }
      },
    },
    "nexus-home-resolved-by-nexus-paths": {
      description:
        'Les littéraux "nexus.home", "NEXUS_HOME" et "nexus.db" et la lecture du home ne figurent que dans NexusPaths',
      severity: "error",
      async check(ctx) {
        for (const chemin of (await ctx.glob(SOURCES)).map((f) => f.replace(/\\/g, "/")).sort()) {
          if (chemin === NEXUS_PATHS) continue;
          const { code, chaines } = analyser(await ctx.readFile(chemin));
          for (const chaine of chaines) {
            if (!LITTERAUX_RESERVES.has(chaine.valeur)) continue;
            ctx.report.violation({
              message: `Littéral « ${chaine.valeur} » hors de NexusPaths : la résolution du home et de ses sous-chemins est centralisée (DATA-0019).`,
              file: chemin,
              line: ligne(code, chaine.index),
              fix: "Obtenir le chemin par NexusPaths.fromEnvironment() puis ses accesseurs (databaseFile(), projectLuceneIndex()…).",
            });
          }
          for (const trouve of code.matchAll(LECTURE_DETOURNEE)) {
            ctx.report.violation({
              message: "Lecture du home hors de NexusPaths : seule NexusPaths.fromEnvironment() résout NEXUS_HOME (DATA-0019).",
              file: chemin,
              line: ligne(code, trouve.index ?? 0),
              fix: "Appeler NexusPaths.fromEnvironment().",
            });
          }
        }
      },
    },
  },
} satisfies RuleSet;
