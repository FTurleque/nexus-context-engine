/// <reference path="../rules.d.ts" />

// Règle de l'ADR SEC-0047 (borner le support filesystem aux filesystems locaux qualifiés).
// Décision : « Cette décision n'ajoute pas un blocage runtime fondé sur FileStore.type() » ; la frontière de support
// est documentaire et de qualification.
// Contrôlé ici, commentaires et littéraux effacés : aucun source de production (src/main/java du cœur et des
// adaptateurs) qui référence FileStore ou getFileStore n'appelle .type(). Le reste de la décision (périmètre
// qualifié) est vérifié par les workflows filesystem-semantics-qualification.yml et smb-filesystem-qualification.yml ;
// ce contrôle est propre à Archgate.

const SOURCES = ["core/src/main/java/**/*.java", "adapters/*/src/main/java/**/*.java"];

/** Efface commentaires et littéraux Java (longueur et sauts de ligne conservés). */
function maskJava(source: string): string {
  const out = source.split("");
  const blank = (from: number, to: number) => {
    for (let k = from; k < to; k++) if (out[k] !== "\n" && out[k] !== "\r") out[k] = " ";
  };
  const n = source.length;
  let i = 0;
  while (i < n) {
    const c = source[i];
    if (c === "/" && source[i + 1] === "/") {
      let j = i;
      while (j < n && source[j] !== "\n") j++;
      blank(i, j);
      i = j;
    } else if (c === "/" && source[i + 1] === "*") {
      const end = source.indexOf("*/", i + 2);
      const j = end < 0 ? n : end + 2;
      blank(i, j);
      i = j;
    } else if (source.startsWith('"""', i)) {
      let j = i + 3;
      while (j < n && !source.startsWith('"""', j)) j += source[j] === "\\" ? 2 : 1;
      j = Math.min(n, j + 3);
      blank(i + 3, j - 3);
      i = j;
    } else if (c === '"' || c === "'") {
      let j = i + 1;
      while (j < n && source[j] !== c && source[j] !== "\n") j += source[j] === "\\" ? 2 : 1;
      j = Math.min(n, j + 1);
      blank(i + 1, j - 1);
      i = j;
    } else {
      i++;
    }
  }
  return out.join("");
}

function lineOf(text: string, index: number): number {
  return text.slice(0, index).split("\n").length;
}

export default {
  rules: {
    "no-runtime-filesystem-type-gate": {
      description:
        "Aucun source de production qui manipule FileStore n'appelle .type() : pas de blocage runtime fondé sur FileStore.type()",
      severity: "error",
      async check(ctx) {
        const files = (await Promise.all(SOURCES.map((pattern) => ctx.glob(pattern)))).flat().sort();
        if (files.length === 0) {
          ctx.report.violation({ message: "Aucun source de production trouvé : la règle ne peut rien vérifier." });
          return;
        }
        for (const file of files) {
          const code = maskJava(await ctx.readFile(file));
          if (!/\b(?:FileStore|getFileStore)\b/.test(code)) continue;
          for (const match of code.matchAll(/\.\s*type\s*\(\s*\)/g)) {
            ctx.report.violation({
              message: "Appel .type() dans un source qui manipule FileStore : SEC-0047 n'ajoute pas de blocage runtime fondé sur le type de filesystem.",
              file,
              line: lineOf(code, match.index ?? 0),
              fix: "Tenir la frontière de support par la documentation et la qualification ; étendre le support par une PR de qualification dédiée.",
            });
          }
        }
      },
    },
  },
} satisfies RuleSet;
