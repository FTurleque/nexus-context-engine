/// <reference path="../rules.d.ts" />

// Règles de l'ADR ARCH-0086 (configuration et NEXUS_HOME résolus dans la composition root). Contrôles
// propres à Archgate : NexusPathsTest couvre la résolution par l'environnement injecté,
// PrivateStoragePolicyTest le stockage privé, CoreArchitectureTest la lecture unique de l'environnement ;
// aucun test ne vérifie la centralisation du home.
// - Décision : « Résoudre le répertoire racine par la propriété système `nexus.home`, puis par la variable
//   d'environnement `NEXUS_HOME`, puis par repli sur `${user.home}/.nexus`. »
//   Contrôle (nexus-home-resolution-order) : NexusPaths déclare HOME_PROPERTY = "nexus.home" et
//   HOME_ENVIRONMENT_VARIABLE = "NEXUS_HOME", et fromEnvironment(Map<String, String> environnement) lit, dans cet
//   ordre, System.getProperty(HOME_PROPERTY), environnement.get(HOME_ENVIRONMENT_VARIABLE), puis user.home avec
//   ".nexus". L'environnement est celui que la composition root a résolu.
// - Décision : « Centraliser la résolution des chemins dans `NexusPaths`. »
//   Contrôle (nexus-home-resolved-by-nexus-paths) : dans les sources de production, les littéraux "nexus.home",
//   "NEXUS_HOME" et "nexus.db" n'apparaissent que dans NexusPaths.java, et aucun autre fichier ne lit
//   NexusPaths.HOME_PROPERTY ou NexusPaths.HOME_ENVIRONMENT_VARIABLE par System.getProperty, System.getenv ou
//   le get d'une table d'environnement.
// Les commentaires sont effacés ; les littéraux sont conservés, puisque ce sont eux qui sont contrôlés.

const SOURCES = "**/src/main/java/**/*.java";
const NEXUS_PATHS = "core/src/main/java/com/nexus/config/NexusPaths.java";
const LITTERAUX_RESERVES = new Set(["nexus.home", "NEXUS_HOME", "nexus.db"]);
const LECTURE_DETOURNEE =
  /(?:\bSystem\s*\.\s*(?:getProperty|getenv)|\.\s*get)\s*\(\s*NexusPaths\s*\.\s*HOME_(?:PROPERTY|ENVIRONMENT_VARIABLE)\b/g;

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
        "NexusPaths.fromEnvironment(Map) résout le home par nexus.home, puis NEXUS_HOME, puis ${user.home}/.nexus",
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
              message: `NexusPaths ne déclare plus ${attendu} (ARCH-0086).`,
              file: NEXUS_PATHS,
              fix: "Rétablir la constante ; changer de nom de propriété ou de variable demande un nouvel ADR.",
            });
          }
        }
        const signature =
          /\bstatic\s+NexusPaths\s+fromEnvironment\s*\(\s*Map\s*<\s*String\s*,\s*String\s*>\s+(\w+)\s*\)/.exec(code);
        if (!signature) {
          ctx.report.violation({
            message:
              "NexusPaths.fromEnvironment(Map<String, String>) introuvable : l'ordre de résolution du home ne peut pas être vérifié.",
            file: NEXUS_PATHS,
          });
          return;
        }
        const debut = signature.index;
        const environnement = signature[1];
        const etapes: [RegExp, string][] = [
          [/\bSystem\s*\.\s*getProperty\s*\(\s*HOME_PROPERTY\s*\)/, "System.getProperty(HOME_PROPERTY)"],
          [
            new RegExp(`\\b${environnement}\\s*\\.\\s*get\\s*\\(\\s*HOME_ENVIRONMENT_VARIABLE\\s*\\)`),
            `${environnement}.get(HOME_ENVIRONMENT_VARIABLE)`,
          ],
          [/\bSystem\s*\.\s*getProperty\s*\(\s*"user\.home"\s*\)\s*,\s*"\.nexus"/, 'System.getProperty("user.home"), ".nexus"'],
        ];
        let position = debut;
        for (const [motif, attendu] of etapes) {
          const reste = code.slice(position);
          const trouve = motif.exec(reste);
          if (!trouve) {
            ctx.report.violation({
              message: `fromEnvironment(Map) ne lit pas ${attendu} à son rang : l'ordre nexus.home, NEXUS_HOME, \${user.home}/.nexus n'est plus respecté (ARCH-0086).`,
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
              message: `Littéral « ${chaine.valeur} » hors de NexusPaths : la résolution du home et de ses sous-chemins est centralisée (ARCH-0086).`,
              file: chemin,
              line: ligne(code, chaine.index),
              fix: "Obtenir le chemin par NexusPaths.fromEnvironment(Map) puis ses accesseurs (databaseFile(), projectLuceneIndex()…).",
            });
          }
          for (const trouve of code.matchAll(LECTURE_DETOURNEE)) {
            ctx.report.violation({
              message: "Lecture du home hors de NexusPaths : seule NexusPaths.fromEnvironment(Map) résout NEXUS_HOME (ARCH-0086).",
              file: chemin,
              line: ligne(code, trouve.index ?? 0),
              fix: "Appeler NexusPaths.fromEnvironment(Map) avec l'environnement de la composition root.",
            });
          }
        }
      },
    },
  },
} satisfies RuleSet;
