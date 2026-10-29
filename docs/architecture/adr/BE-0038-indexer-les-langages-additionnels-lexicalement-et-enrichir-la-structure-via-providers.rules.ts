/// <reference path="../rules.d.ts" />

// Règle de l'ADR BE-0038 (langages additionnels indexés lexicalement). Complète SourceLanguageTest, qui
// vérifie la détection de cas choisis, en contrôlant la liste complète décidée.
// - Décision : SourceLanguage centralise les extensions reconnues par le scanner ; le scanner reconnaît
//   nativement Kotlin (.kt, .kts), TypeScript (.ts, .tsx), JavaScript (.js, .jsx, .mjs, .cjs), Python
//   (.py) et SQL (.sql) ; Java et Markdown conservent leur comportement (.java, .md).
// - Contrôle : chaque extension décidée figure dans la déclaration de SourceLanguage. Seuls les
//   commentaires sont effacés : les extensions sont des littéraux.

const SOURCE_LANGUAGE = "core/src/main/java/com/nexus/index/SourceLanguage.java";
const DECIDED: Record<string, string[]> = {
  JAVA: [".java"],
  MARKDOWN: [".md"],
  KOTLIN: [".kt", ".kts"],
  TYPESCRIPT: [".ts", ".tsx"],
  JAVASCRIPT: [".js", ".jsx", ".mjs", ".cjs"],
  PYTHON: [".py"],
  SQL: [".sql"],
};

/** Efface les commentaires et, sauf si garderLitteraux, les littéraux (longueur et sauts de ligne conservés). */
function masquerJava(source: string, garderLitteraux = false): string {
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
      if (!garderLitteraux) effacer(i + 3, j - 3);
      i = j;
    } else if (c === '"' || c === "'") {
      let j = i + 1;
      while (j < n && source[j] !== c && source[j] !== "\n") j += source[j] === "\\" ? 2 : 1;
      j = Math.min(n, j + 1);
      if (!garderLitteraux) effacer(i + 1, j - 1);
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
    "source-language-declares-decided-extensions": {
      description: "SourceLanguage déclare chaque langage et chaque extension retenus par BE-0038",
      severity: "error",
      async check(ctx) {
        const code = masquerJava(await ctx.readFile(SOURCE_LANGUAGE), true);
        for (const [constant, extensions] of Object.entries(DECIDED)) {
          const declaration = new RegExp(String.raw`\b${constant}\s*\(\s*"[^"]*"\s*,\s*Set\s*\.\s*of\s*\(([^)]*)\)`).exec(code);
          if (!declaration) {
            ctx.report.violation({
              message: `La constante ${constant} n'est pas déclarée dans SourceLanguage (BE-0038).`,
              file: SOURCE_LANGUAGE,
              fix: `Déclarer ${constant}("…", Set.of(${extensions.map((e) => `"${e}"`).join(", ")})).`,
            });
            continue;
          }
          const declared = new Set([...declaration[1].matchAll(/"([^"]*)"/g)].map((m) => m[1]));
          for (const extension of extensions) {
            if (!declared.has(extension)) {
              ctx.report.violation({
                message: `L'extension ${extension} manque à ${constant} : BE-0038 la fait reconnaître nativement par le scanner.`,
                file: SOURCE_LANGUAGE,
                line: ligne(code, declaration.index),
                fix: `Ajouter "${extension}" à ${constant}, ou remplacer BE-0038 par un nouvel ADR.`,
              });
            }
          }
        }
      },
    },
  },
} satisfies RuleSet;
