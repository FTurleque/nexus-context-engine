/// <reference path="../rules.d.ts" />

// Règle de l'ADR DATA-0023 (SHA-256 comme empreinte canonique du contenu des fichiers indexés).
// Contrôle propre à Archgate : les tests d'indexation comparent des empreintes, aucun ne fixe l'algorithme.
// - Décision : « le scanner calcule SHA-256 des fichiers éligibles » ; « le hash persisté est SHA-256 en
//   représentation hexadécimale ».
//   Contrôle : ProjectScanner calcule l'empreinte par FileHasher.sha256(…) ; FileHasher n'instancie que
//   MessageDigest.getInstance("SHA-256") et encode le condensat par HexFormat (formatHex).
// Les commentaires sont effacés ; les littéraux sont conservés, puisque le nom de l'algorithme en est un.

const SCANNER = "core/src/main/java/com/nexus/index/scan/ProjectScanner.java";
const HASHER = "core/src/main/java/com/nexus/index/FileHasher.java";
const APPEL_HASHER = /\bFileHasher\s*\.\s*sha256\s*\(/;
const ALGORITHME = /\bMessageDigest\s*\.\s*getInstance\s*\(\s*("(?:[^"\\\n]|\\.)*"|[^)]*)\s*\)/g;
const HEXADECIMAL = /\bHexFormat\s*\.\s*of\s*\(\s*\)\s*\.\s*formatHex\s*\(/;

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
    "content-hash-is-sha256-hex": {
      description:
        "ProjectScanner hache par FileHasher.sha256, qui n'utilise que SHA-256 et encode le condensat en hexadécimal",
      severity: "error",
      async check(ctx) {
        const scanner = masquerCommentaires(await ctx.readFile(SCANNER));
        if (!APPEL_HASHER.test(scanner)) {
          ctx.report.violation({
            message: "ProjectScanner ne calcule plus l'empreinte par FileHasher.sha256(…) (DATA-0023).",
            file: SCANNER,
            fix: "Calculer contentHash par FileHasher.sha256(fichier, borne).",
          });
        }
        const hasher = masquerCommentaires(await ctx.readFile(HASHER));
        const algorithmes = [...hasher.matchAll(ALGORITHME)];
        if (algorithmes.length === 0) {
          ctx.report.violation({
            message: "FileHasher n'instancie aucun MessageDigest : l'algorithme de l'empreinte ne peut pas être vérifié (DATA-0023).",
            file: HASHER,
          });
        }
        for (const trouve of algorithmes) {
          if (trouve[1].trim() === '"SHA-256"') continue;
          ctx.report.violation({
            message: `FileHasher utilise MessageDigest.getInstance(${trouve[1].trim()}) : l'empreinte canonique est SHA-256 (DATA-0023).`,
            file: HASHER,
            line: ligne(hasher, trouve.index ?? 0),
            fix: 'Utiliser MessageDigest.getInstance("SHA-256") ; changer d\'algorithme demande un nouvel ADR.',
          });
        }
        if (!HEXADECIMAL.test(hasher)) {
          ctx.report.violation({
            message: "FileHasher n'encode plus le condensat par HexFormat.of().formatHex(…) : le hash persisté est hexadécimal (DATA-0023).",
            file: HASHER,
          });
        }
      },
    },
  },
} satisfies RuleSet;
