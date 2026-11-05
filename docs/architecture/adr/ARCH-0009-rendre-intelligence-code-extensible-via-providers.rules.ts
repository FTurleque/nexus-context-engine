/// <reference path="../rules.d.ts" />

// ARCH-0009 : les ports CodeIntelligenceProvider et CodeIndexImporter, et le modèle NEXUS qu'ils
// échangent (CodeIntelligenceSnapshot, CodeSymbol, SymbolRelation), ne dépendent d'aucun format
// externe. La règle n'admet dans ces fichiers que des imports java.* et com.nexus.*, et refuse toute
// référence (commentaires et littéraux effacés) aux implémentations com.nexus.index.{jdt,scip,minos,java}
// ou aux bibliothèques des formats externes (JavaParser, Eclipse/JDT, SCIP, Protobuf, Jackson).

const RACINE = "core/src/main/java/com/nexus/index/";
const CONTRATS = [
  "CodeIntelligenceProvider.java",
  "CodeIndexImporter.java",
  "CodeIntelligenceSnapshot.java",
  "CodeSymbol.java",
  "SymbolRelation.java",
];

const IMPORT = /^\s*import\s+(?:static\s+)?([\w.]+(?:\.\*)?)\s*;/gm;
const EXTERNE =
  /\b(?:com\s*\.\s*nexus\s*\.\s*index\s*\.\s*(?:jdt|scip|minos|java)|com\s*\.\s*github\s*\.\s*javaparser|org\s*\.\s*eclipse|com\s*\.\s*sourcegraph|com\s*\.\s*google\s*\.\s*protobuf|com\s*\.\s*fasterxml)\s*\./g;

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

export default {
  rules: {
    "code-intelligence-contracts-free-of-external-formats": {
      description:
        "Les ports CodeIntelligenceProvider/CodeIndexImporter et le modèle CodeIntelligenceSnapshot/CodeSymbol/SymbolRelation n'importent que java.* et com.nexus.*, sans référence aux providers ni aux formats externes",
      severity: "error",
      async check(ctx) {
        for (const nom of CONTRATS) {
          const chemin = RACINE + nom;
          let source: string;
          try {
            source = await ctx.readFile(chemin);
          } catch {
            ctx.report.violation({
              message: `${chemin} introuvable : le contrat d'intelligence de code d'ARCH-0009 ne peut pas être vérifié.`,
              file: chemin,
            });
            continue;
          }
          const code = masquerJava(source);
          for (const trouve of code.matchAll(IMPORT)) {
            const cible = trouve[1];
            if (cible.startsWith("java.") || cible.startsWith("com.nexus.")) continue;
            ctx.report.violation({
              message: `Import « ${cible} » hors de java.* et com.nexus.* dans un contrat d'intelligence de code (ARCH-0009).`,
              file: chemin,
              line: ligne(code, trouve.index ?? 0),
              fix: "Garder le contrat neutre : mapper le format externe vers CodeSymbol/SymbolRelation dans le provider ou l'importer.",
            });
          }
          for (const trouve of code.matchAll(EXTERNE)) {
            ctx.report.violation({
              message: `Référence « ${trouve[0].replace(/\s+/g, "")} » à un provider ou à un format externe dans un contrat d'intelligence de code (ARCH-0009).`,
              file: chemin,
              line: ligne(code, trouve.index ?? 0),
              fix: "Le contrat ne connaît que le modèle NEXUS ; la conversion appartient à l'implémentation du provider.",
            });
          }
        }
      },
    },
  },
} satisfies RuleSet;
