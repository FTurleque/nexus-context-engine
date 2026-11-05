/// <reference path="../rules.d.ts" />

// ARCH-0030 : la logique de la CLI retourne un code de sortie testable ; seul `main` appelle
// System.exit. La règle refuse dans core/src/main/java (commentaires et littéraux effacés) tout
// System.exit, Runtime.exit ou Runtime.halt situé ailleurs que dans le corps de la méthode
// `public static void main(String[] …)` de com.nexus.cli.NexusCli.

const SOURCES = "core/src/main/java/**/*.java";
const POINT_D_ENTREE = "core/src/main/java/com/nexus/cli/NexusCli.java";
const SORTIE = /\bSystem\s*\.\s*exit\s*\(|\bRuntime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*(?:exit|halt)\s*\(/g;
const MAIN = /\bpublic\s+static\s+void\s+main\s*\(\s*(?:final\s+)?String\s*(?:\[\s*\]|\.\.\.)\s*\w+\s*(?:\[\s*\])?\s*\)[^{]*\{/g;

/** Efface commentaires et, si demandé, littéraux Java (longueur et sauts de ligne conservés). */
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

/** Plages [début, fin) des corps de `main` du fichier. */
function corpsDeMain(code: string): [number, number][] {
  const plages: [number, number][] = [];
  for (const trouve of code.matchAll(MAIN)) {
    const ouverture = (trouve.index ?? 0) + trouve[0].length - 1;
    let profondeur = 0;
    for (let k = ouverture; k < code.length; k++) {
      if (code[k] === "{") profondeur++;
      else if (code[k] === "}" && --profondeur === 0) {
        plages.push([ouverture, k]);
        break;
      }
    }
  }
  return plages;
}

export default {
  rules: {
    "cli-only-main-calls-system-exit": {
      description:
        "Dans core/src/main/java, System.exit (ou Runtime.exit/halt) n'apparaît que dans NexusCli.main ; la logique CLI retourne un code testable",
      severity: "error",
      async check(ctx) {
        const fichiers = await ctx.glob(SOURCES);
        if (fichiers.length === 0) {
          ctx.report.violation({ message: `Aucune source trouvée sous ${SOURCES} : la règle ne peut rien vérifier.` });
          return;
        }
        for (const chemin of fichiers) {
          const code = masquerJava(await ctx.readFile(chemin));
          const autorisees = chemin.split("\\").join("/") === POINT_D_ENTREE ? corpsDeMain(code) : [];
          for (const trouve of code.matchAll(SORTIE)) {
            const index = trouve.index ?? 0;
            if (autorisees.some(([debut, fin]) => index > debut && index < fin)) continue;
            ctx.report.violation({
              message: `« ${trouve[0].replace(/\s+/g, "")} » hors de NexusCli.main : la logique CLI doit retourner un code de sortie testable (ARCH-0030).`,
              file: chemin,
              line: ligne(code, index),
              fix: "Retourner le code de sortie (0, 1 ou 2) à l'appelant ; seul NexusCli.main le transmet à System.exit.",
            });
          }
        }
      },
    },
  },
} satisfies RuleSet;
