/// <reference path="../rules.d.ts" />

// Règle de l'ADR BE-0053 (parcours borné des arbres Git). Contrôle propre à Archgate : les tests Git
// vérifient les résultats bornés, pas la façon de parcourir les arbres.
// - Décision : LocalGitContextSourceProvider parcourt directement les deux arbres avec un TreeWalk
//   récursif et TreeFilter.ANY_DIFF, chaque entrée débitée avant sa collecte ; la détection coûteuse
//   des renommages n'est pas nécessaire.
// - Contrôle : le fichier construit un TreeWalk, le rend récursif, filtre par TreeFilter.ANY_DIFF et
//   n'utilise ni DiffFormatter, ni RenameDetector, ni setDetectRenames. Commentaires et littéraux effacés.

const PROVIDER = "core/src/main/java/com/nexus/context/source/git/LocalGitContextSourceProvider.java";
const REQUIRED: { pattern: RegExp; what: string }[] = [
  { pattern: /\bnew\s+TreeWalk\s*\(/, what: "new TreeWalk(...)" },
  { pattern: /\.\s*setRecursive\s*\(\s*true\s*\)/, what: "setRecursive(true)" },
  { pattern: /\bTreeFilter\s*\.\s*ANY_DIFF\b/, what: "TreeFilter.ANY_DIFF" },
];
const FORBIDDEN = /\bDiffFormatter\b|\bRenameDetector\b|\bsetDetectRenames\s*\(/g;

/** Efface les commentaires et, sauf si garderLitteraux, les littéraux (longueur et sauts de ligne conservés). */
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
    "git-trees-walked-before-collect": {
      description:
        "LocalGitContextSourceProvider parcourt les arbres par TreeWalk récursif filtré ANY_DIFF, sans DiffFormatter ni détection de renommage",
      severity: "error",
      async check(ctx) {
        const code = masquerJava(await ctx.readFile(PROVIDER));
        for (const { pattern, what } of REQUIRED) {
          if (!pattern.test(code)) {
            ctx.report.violation({
              message: `${what} absent de LocalGitContextSourceProvider : BE-0053 impose un parcours direct des deux arbres.`,
              file: PROVIDER,
              fix: "Parcourir parent et commit par un TreeWalk récursif filtré par TreeFilter.ANY_DIFF, en débitant le budget à chaque entrée.",
            });
          }
        }
        for (const match of code.matchAll(FORBIDDEN)) {
          ctx.report.violation({
            message: `« ${match[0].replace(/\s+/g, "")} » dans LocalGitContextSourceProvider : le diff n'est pas matérialisé avant le débit du budget (BE-0053).`,
            file: PROVIDER,
            line: ligne(code, match.index ?? 0),
            fix: "Revenir au parcours TreeWalk borné ; la détection des renommages n'est pas nécessaire à cette découverte.",
          });
        }
      },
    },
  },
} satisfies RuleSet;
