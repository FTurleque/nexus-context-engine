/// <reference path="../rules.d.ts" />

// Règles de l'ADR BE-0037 (JDT LS, provider Java profond optionnel). Contrôles propres à Archgate :
// JdtConfigurationBoundsTest vérifie le rejet des valeurs hors bornes, pas la valeur des constantes ;
// aucun test ne vérifie les dépendances Eclipse du cœur.
// - Décision : NEXUS ne dépend pas des classes Eclipse JDT ni des modèles LSP dans son cœur.
//   Contrôle core-has-no-eclipse-jdt-or-lsp-dependency : ni import ni nom qualifié org.eclipse.jdt ou
//   org.eclipse.lsp4j dans core/src/main/java (commentaires et littéraux effacés : le nom de
//   l'application Equinox passé en argument au processus JDT LS est un littéral), ni dépendance de ces
//   groupes dans core/pom.xml.
// - Décision (bornage du coût) : 250 symboles par défaut, plafond 10 000 ; timeout 120 s par défaut,
//   plafond 3 600 s. Contrôle jdt-cost-bounds-match-decision : valeur des quatre constantes de
//   JdtLanguageServerCodeIntelligenceProvider.
// - Décision (lecture seule) : le provider n'applique pas les workspace/applyEdit demandés par le serveur.
//   Contrôle jdt-workspace-edits-refused : JdtStdioSession répond à workspace/applyEdit par
//   applied=false et ne répond jamais applied=true.

const CORE_SOURCES = "core/src/main/java/**/*.java";
const CORE_POM = "core/pom.xml";
const PROVIDER = "core/src/main/java/com/nexus/index/jdt/JdtLanguageServerCodeIntelligenceProvider.java";
const SESSION = "core/src/main/java/com/nexus/index/jdt/JdtStdioSession.java";
const ECLIPSE_MODEL = /\borg\s*\.\s*eclipse\s*\.\s*(?:jdt|lsp4j)\b/g;
const ECLIPSE_ARTIFACT = /<groupId>\s*org\.eclipse\.(?:jdt|lsp4j)[^<]*<\/groupId>/g;
const BOUNDS: { name: string; expected: number }[] = [
  { name: "DEFAULT_MAX_SYMBOLS", expected: 250 },
  { name: "MAX_SYMBOLS", expected: 10_000 },
  { name: "DEFAULT_TIMEOUT_SECONDS", expected: 120 },
  { name: "MAX_TIMEOUT_SECONDS", expected: 3_600 },
];

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
    "core-has-no-eclipse-jdt-or-lsp-dependency": {
      description: "Le cœur ne référence ni org.eclipse.jdt ni org.eclipse.lsp4j, dans ses sources comme dans core/pom.xml",
      severity: "error",
      async check(ctx) {
        for (const file of (await ctx.glob(CORE_SOURCES)).sort()) {
          const code = masquerJava(await ctx.readFile(file));
          for (const match of code.matchAll(ECLIPSE_MODEL)) {
            ctx.report.violation({
              message: `Référence à ${match[0].replace(/\s+/g, "")} dans le cœur : NEXUS ne dépend pas des classes Eclipse JDT ni des modèles LSP (BE-0037).`,
              file,
              line: ligne(code, match.index ?? 0),
              fix: "Traduire les réponses JSON-RPC de JDT LS directement vers CodeSymbol et SymbolRelation.",
            });
          }
        }
        const pom = await ctx.readFile(CORE_POM);
        for (const match of pom.matchAll(ECLIPSE_ARTIFACT)) {
          ctx.report.violation({
            message: `Dépendance ${match[0]} dans ${CORE_POM} : le cœur ne dépend pas d'Eclipse JDT ni de LSP4J (BE-0037).`,
            file: CORE_POM,
            line: ligne(pom, match.index ?? 0),
            fix: "Retirer la dépendance ; JDT LS reste un processus externe piloté par stdio.",
          });
        }
      },
    },
    "jdt-cost-bounds-match-decision": {
      description: "Les bornes du provider JDT LS valent 250 / 10 000 symboles et 120 / 3 600 secondes",
      severity: "error",
      async check(ctx) {
        const code = masquerJava(await ctx.readFile(PROVIDER), true);
        for (const { name, expected } of BOUNDS) {
          const match = new RegExp(String.raw`\b${name}\s*=\s*([0-9_]+)[lL]?\s*;`).exec(code);
          const actual = match ? Number(match[1].replace(/_/g, "")) : undefined;
          if (actual !== expected) {
            ctx.report.violation({
              message: `${name} vaut ${actual ?? "(absent)"} ; BE-0037 fixe ${expected}.`,
              file: PROVIDER,
              line: match ? ligne(code, match.index) : undefined,
              fix: "Rétablir la valeur de la décision, ou remplacer BE-0037 par un nouvel ADR.",
            });
          }
        }
      },
    },
    "jdt-workspace-edits-refused": {
      description: "JdtStdioSession répond à workspace/applyEdit par applied=false et jamais applied=true",
      severity: "error",
      async check(ctx) {
        const source = await ctx.readFile(SESSION);
        const code = masquerJava(source, true);
        const branch = /"workspace\/applyEdit"\s*\.\s*equals\s*\(\s*method\s*\)\s*\)\s*\{([^}]*)\}/.exec(code);
        if (!branch || !/\.\s*put\s*\(\s*"applied"\s*,\s*false\s*\)/.test(branch[1])) {
          ctx.report.violation({
            message: "Aucune branche workspace/applyEdit répondant applied=false : le provider JDT doit refuser les modifications (BE-0037).",
            file: SESSION,
            fix: 'Répondre à workspace/applyEdit par un objet { "applied": false, "failureReason": … }.',
          });
        }
        for (const match of code.matchAll(/\.\s*put\s*\(\s*"applied"\s*,\s*true\s*\)/g)) {
          ctx.report.violation({
            message: "Réponse applied=true : le provider JDT NEXUS n'applique pas de modification de workspace (BE-0037).",
            file: SESSION,
            line: ligne(code, match.index ?? 0),
            fix: "Répondre applied=false ; NEXUS n'est ni un IDE ni un client de refactoring.",
          });
        }
      },
    },
  },
} satisfies RuleSet;
