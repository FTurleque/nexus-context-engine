/// <reference path="../rules.d.ts" />

// Règle de l'ADR BE-0021 (JGit pour les règles .gitignore et .nexusignore). Contrôle propre à Archgate :
// ProjectScannerTest vérifie le comportement des exclusions, pas le moteur qui les évalue.
// - Décision : JGit parse et évalue les règles de type .gitignore ; son usage dans le scanner concerne
//   uniquement la sémantique d'ignore.
// - Contrôle, sur core/src/main/java/com/nexus/index/scan/ (commentaires et littéraux effacés) :
//   ProjectIgnoreMatcher importe org.eclipse.jgit.ignore.IgnoreNode ; aucun import JGit hors de
//   org.eclipse.jgit.ignore ; aucun PathMatcher (conversion maison des motifs) ni lancement de
//   processus (git check-ignore).

const SOURCES = "core/src/main/java/com/nexus/index/scan/**/*.java";
const MATCHER = "core/src/main/java/com/nexus/index/scan/ProjectIgnoreMatcher.java";
const IGNORE_NODE_IMPORT = /\bimport\s+org\s*\.\s*eclipse\s*\.\s*jgit\s*\.\s*ignore\s*\.\s*IgnoreNode\s*;/;

const INTERDITS: { motif: RegExp; quoi: string }[] = [
  {
    motif: /\borg\s*\.\s*eclipse\s*\.\s*jgit\s*\.\s*(?!ignore\b)\w+/g,
    quoi: "usage de JGit hors de la sémantique d'ignore (org.eclipse.jgit.ignore)",
  },
  { motif: /\bPathMatcher\b|\bgetPathMatcher\s*\(/g, quoi: "conversion des motifs d'ignore vers PathMatcher" },
  {
    motif: /\bProcessBuilder\b|\bRuntime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*exec\s*\(/g,
    quoi: "lancement de processus (git check-ignore)",
  },
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
    "scanner-ignore-rules-use-jgit": {
      description:
        "Le scanner évalue .gitignore et .nexusignore avec IgnoreNode de JGit, sans PathMatcher, sans processus git et sans autre usage de JGit",
      severity: "error",
      async check(ctx) {
        const fichiers = (await ctx.glob(SOURCES)).map((f) => f.replace(/\\/g, "/")).sort();
        if (!fichiers.includes(MATCHER)) {
          ctx.report.violation({ message: `${MATCHER} introuvable : le moteur d'ignore du scanner ne peut pas être vérifié.` });
          return;
        }
        for (const chemin of fichiers) {
          const code = masquerJava(await ctx.readFile(chemin));
          if (chemin === MATCHER && !IGNORE_NODE_IMPORT.test(code)) {
            ctx.report.violation({
              message: "ProjectIgnoreMatcher n'importe pas org.eclipse.jgit.ignore.IgnoreNode : les règles d'ignore ne sont plus évaluées par JGit (BE-0021).",
              file: chemin,
              fix: "Évaluer .gitignore et .nexusignore avec IgnoreNode, scopé par répertoire.",
            });
          }
          for (const { motif, quoi } of INTERDITS) {
            for (const trouve of code.matchAll(motif)) {
              ctx.report.violation({
                message: `${quoi} dans le scanner : « ${trouve[0].replace(/\s+/g, " ")} » (BE-0021).`,
                file: chemin,
                line: ligne(code, trouve.index ?? 0),
                fix: "Passer par IgnoreNode de JGit pour la grammaire .gitignore ; ne pas invoquer git ni réimplémenter la syntaxe.",
              });
            }
          }
        }
      },
    },
  },
} satisfies RuleSet;
