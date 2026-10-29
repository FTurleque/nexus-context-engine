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
          const code = masquerJava(await ctx.readFile(file));
          if (!/\b(?:FileStore|getFileStore)\b/.test(code)) continue;
          for (const match of code.matchAll(/\.\s*type\s*\(\s*\)/g)) {
            ctx.report.violation({
              message: "Appel .type() dans un source qui manipule FileStore : SEC-0047 n'ajoute pas de blocage runtime fondé sur le type de filesystem.",
              file,
              line: ligne(code, match.index ?? 0),
              fix: "Tenir la frontière de support par la documentation et la qualification ; étendre le support par une PR de qualification dédiée.",
            });
          }
        }
      },
    },
  },
} satisfies RuleSet;
