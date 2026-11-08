/// <reference path="../rules.d.ts" />

// Règles de l'ADR ARCH-0080 (intelligence de code enrichie par des providers et importers optionnels : SCIP,
// JDT Language Server, MINOS). Contrôles propres à Archgate : les tests des paquetages scip, jdt et minos vérifient
// les imports, pas les dépendances des autres paquetages, l'absence de lancement de processus ni la valeur des
// constantes. Commentaires et littéraux effacés avant la recherche.
// - Décision : « Les ports `CodeIntelligenceProvider` et `CodeIndexImporter`, et le modèle
//   `CodeIntelligenceSnapshot`, `CodeSymbol`, `SymbolRelation` qu'ils échangent, ne dépendent d'aucun format externe. »
//   Contrôle code-intelligence-contracts-free-of-external-formats : ces fichiers n'importent que java.* et com.nexus.*,
//   et ne référencent ni les implémentations com.nexus.index.{jdt,scip,minos,java} ni les bibliothèques des formats
//   externes (JavaParser, Eclipse/JDT, SCIP, Protobuf, Jackson).
// - Décision : « Le format SCIP reste confiné dans `com.nexus.index.scip` ; hors de ce paquetage, seule la composition
//   référence l'importeur. » Contrôle scip-format-confined-to-scip-package : hors de
//   core/src/main/java/com/nexus/index/scip/, seule application/NexusApplicationFactory.java référence
//   com.nexus.index.scip, dans toutes les sources de production (cœur et adaptateurs).
// - Décision : « NEXUS ne lance jamais `scip-java` automatiquement ». Contrôle scip-import-launches-no-process : aucune
//   source de com.nexus.index.scip ne lance de processus (ProcessBuilder, Runtime.exec, ProcessHandle).
// - Décision : « NEXUS ne dépend pas des classes Eclipse JDT ni des modèles LSP dans son cœur ». Contrôle
//   core-has-no-eclipse-jdt-or-lsp-dependency : ni import ni nom qualifié org.eclipse.jdt ou org.eclipse.lsp4j dans
//   core/src/main/java (le nom de l'application Equinox passé en argument au processus JDT LS est un littéral), ni
//   dépendance de ces groupes dans core/pom.xml.
// - Décision : « 250 symboles par défaut (`NEXUS_JDTLS_MAX_SYMBOLS`, plafond 10 000) ; 120 secondes par échange
//   (`NEXUS_JDTLS_TIMEOUT_SECONDS`, plafond 3 600) ». Contrôle jdt-cost-bounds-match-decision : valeur des quatre
//   constantes de JdtLanguageServerCodeIntelligenceProvider.
// - Décision : « le provider n'applique pas les `workspace/applyEdit` demandés par le serveur ». Contrôle
//   jdt-workspace-edits-refused : JdtStdioSession répond à workspace/applyEdit par applied=false et ne répond jamais
//   applied=true.
// - Décision : « `MinosCodeIndexImporter` est un adaptateur pur de contrat JSON : il ne lance aucun processus et ne
//   découvre aucun JAR MINOS ; aucun type `com.minos` ni aucune dépendance Maven vers MINOS n'existe dans NEXUS. »
//   Contrôle minos-import-launches-no-process : aucun lancement de processus (ProcessBuilder, Runtime.exec,
//   ProcessHandle) dans core/src/main/java/com/nexus/index/minos. Contrôle no-minos-types-in-nexus : aucune référence
//   au package com.minos dans les sources Java du dépôt, aucune dépendance Maven vers un groupId com.minos ou un
//   artifactId minos-* dans les pom.xml.

// Contrats neutres d'intelligence de code.
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

// SCIP.
const SOURCES = "**/src/main/java/**/*.java";
const SCIP_PACKAGE = "core/src/main/java/com/nexus/index/scip/";
const COMPOSITION = "core/src/main/java/com/nexus/application/NexusApplicationFactory.java";
const SCIP_REFERENCE = /\bcom\s*\.\s*nexus\s*\.\s*index\s*\.\s*scip\b/g;
const PROCESS = /\bProcessBuilder\b|\bRuntime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*exec\s*\(|\bProcessHandle\b/g;

// JDT Language Server.
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

// MINOS.
const SURFACE_MINOS = "core/src/main/java/com/nexus/index/minos/**/*.java";
const SOURCES_JAVA = "**/src/*/java/**/*.java";
const POMS = ["pom.xml", "core/pom.xml", "adapters/rest-quarkus/pom.xml", "adapters/mcp-java/pom.xml", "adapters/assistant-clients/pom.xml"];
const PROCESSUS = /\bProcessBuilder\b|\bRuntime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*exec\s*\(|\bProcessHandle\b/g;
const TYPE_MINOS = /\bcom\s*\.\s*minos\b/g;
const MINOS_POM = /<(groupId|artifactId)>\s*(com\.minos[\w.-]*|minos-[\w.-]+)\s*<\/\1>/g;

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

function masquerXml(source: string): string {
  return source.replace(/<!--[\s\S]*?-->/g, (bloc) => bloc.replace(/[^\r\n]/g, " "));
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
              message: `${chemin} introuvable : le contrat d'intelligence de code d'ARCH-0080 ne peut pas être vérifié.`,
              file: chemin,
            });
            continue;
          }
          const code = masquerJava(source);
          for (const trouve of code.matchAll(IMPORT)) {
            const cible = trouve[1];
            if (cible.startsWith("java.") || cible.startsWith("com.nexus.")) continue;
            ctx.report.violation({
              message: `Import « ${cible} » hors de java.* et com.nexus.* dans un contrat d'intelligence de code (ARCH-0080).`,
              file: chemin,
              line: ligne(code, trouve.index ?? 0),
              fix: "Garder le contrat neutre : mapper le format externe vers CodeSymbol/SymbolRelation dans le provider ou l'importer.",
            });
          }
          for (const trouve of code.matchAll(EXTERNE)) {
            ctx.report.violation({
              message: `Référence « ${trouve[0].replace(/\s+/g, "")} » à un provider ou à un format externe dans un contrat d'intelligence de code (ARCH-0080).`,
              file: chemin,
              line: ligne(code, trouve.index ?? 0),
              fix: "Le contrat ne connaît que le modèle NEXUS ; la conversion appartient à l'implémentation du provider.",
            });
          }
        }
      },
    },
    "scip-format-confined-to-scip-package": {
      description:
        "Hors de com.nexus.index.scip, seule la composition NexusApplicationFactory référence une classe SCIP",
      severity: "error",
      async check(ctx) {
        const files = (await ctx.glob(SOURCES)).sort();
        if (!files.some((file) => file.startsWith(SCIP_PACKAGE))) {
          ctx.report.violation({ message: `Aucune source sous ${SCIP_PACKAGE} : la règle ne peut rien vérifier.`, file: SCIP_PACKAGE });
          return;
        }
        for (const file of files) {
          if (file.startsWith(SCIP_PACKAGE) || file === COMPOSITION) continue;
          const code = masquerJava(await ctx.readFile(file));
          for (const match of code.matchAll(SCIP_REFERENCE)) {
            ctx.report.violation({
              message: "Référence à com.nexus.index.scip hors de son paquetage : le format SCIP reste confiné (ARCH-0080).",
              file,
              line: ligne(code, match.index ?? 0),
              fix: "Consommer CodeSymbol, SymbolRelation ou CodeIntelligenceSnapshot ; seule la composition instancie ScipCodeIndexImporter.",
            });
          }
        }
      },
    },
    "scip-import-launches-no-process": {
      description: "Aucune source de com.nexus.index.scip ne lance de processus : NEXUS ne lance jamais scip-java",
      severity: "error",
      async check(ctx) {
        for (const file of (await ctx.glob(`${SCIP_PACKAGE}**/*.java`)).sort()) {
          const code = masquerJava(await ctx.readFile(file));
          for (const match of code.matchAll(PROCESS)) {
            ctx.report.violation({
              message: `Lancement de processus dans l'import SCIP : « ${match[0].replace(/\s+/g, " ")} ». ARCH-0080 : NEXUS importe un index.scip existant et ne lance jamais scip-java.`,
              file,
              line: ligne(code, match.index ?? 0),
              fix: "Lire seulement index.scip à la racine du projet ; sa génération reste hors de NEXUS.",
            });
          }
        }
      },
    },
    "core-has-no-eclipse-jdt-or-lsp-dependency": {
      description: "Le cœur ne référence ni org.eclipse.jdt ni org.eclipse.lsp4j, dans ses sources comme dans core/pom.xml",
      severity: "error",
      async check(ctx) {
        for (const file of (await ctx.glob(CORE_SOURCES)).sort()) {
          const code = masquerJava(await ctx.readFile(file));
          for (const match of code.matchAll(ECLIPSE_MODEL)) {
            ctx.report.violation({
              message: `Référence à ${match[0].replace(/\s+/g, "")} dans le cœur : NEXUS ne dépend pas des classes Eclipse JDT ni des modèles LSP (ARCH-0080).`,
              file,
              line: ligne(code, match.index ?? 0),
              fix: "Traduire les réponses JSON-RPC de JDT LS directement vers CodeSymbol et SymbolRelation.",
            });
          }
        }
        const pom = await ctx.readFile(CORE_POM);
        for (const match of pom.matchAll(ECLIPSE_ARTIFACT)) {
          ctx.report.violation({
            message: `Dépendance ${match[0]} dans ${CORE_POM} : le cœur ne dépend pas d'Eclipse JDT ni de LSP4J (ARCH-0080).`,
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
              message: `${name} vaut ${actual ?? "(absent)"} ; ARCH-0080 fixe ${expected}.`,
              file: PROVIDER,
              line: match ? ligne(code, match.index) : undefined,
              fix: "Rétablir la valeur de la décision, ou remplacer ARCH-0080 par un nouvel ADR.",
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
            message: "Aucune branche workspace/applyEdit répondant applied=false : le provider JDT doit refuser les modifications (ARCH-0080).",
            file: SESSION,
            fix: 'Répondre à workspace/applyEdit par un objet { "applied": false, "failureReason": … }.',
          });
        }
        for (const match of code.matchAll(/\.\s*put\s*\(\s*"applied"\s*,\s*true\s*\)/g)) {
          ctx.report.violation({
            message: "Réponse applied=true : le provider JDT NEXUS n'applique pas de modification de workspace (ARCH-0080).",
            file: SESSION,
            line: ligne(code, match.index ?? 0),
            fix: "Répondre applied=false ; NEXUS n'est ni un IDE ni un client de refactoring.",
          });
        }
      },
    },
    "minos-import-launches-no-process": {
      description:
        "La surface d'import MINOS (com.nexus.index.minos) ne lance aucun processus : ni ProcessBuilder, ni Runtime.exec, ni ProcessHandle",
      severity: "error",
      async check(ctx) {
        const fichiers = await ctx.glob(SURFACE_MINOS);
        if (fichiers.length === 0) {
          ctx.report.violation({ message: `Aucune source trouvée sous ${SURFACE_MINOS} : la règle ne peut rien vérifier.` });
          return;
        }
        for (const chemin of fichiers) {
          const code = masquerJava(await ctx.readFile(chemin));
          for (const trouve of code.matchAll(PROCESSUS)) {
            ctx.report.violation({
              message: `Lancement de processus « ${trouve[0].replace(/\s+/g, "")} » dans l'import MINOS (ARCH-0080 : MinosCodeIndexImporter ne lance aucun processus et ne découvre aucun JAR MINOS).`,
              file: chemin,
              line: ligne(code, trouve.index ?? 0),
              fix: "Lire l'export MINOS sur stdin ; l'appelant enchaîne lui-même nexus-export et minos-import.",
            });
          }
        }
      },
    },
    "no-minos-types-in-nexus": {
      description: "Aucun type com.minos dans les sources Java et aucune dépendance Maven vers MINOS dans les pom.xml",
      severity: "error",
      async check(ctx) {
        for (const chemin of await ctx.glob(SOURCES_JAVA)) {
          const code = masquerJava(await ctx.readFile(chemin));
          for (const trouve of code.matchAll(TYPE_MINOS)) {
            ctx.report.violation({
              message: "Référence au package com.minos : NEXUS ne connaît MINOS que par son contrat JSON (ARCH-0080).",
              file: chemin,
              line: ligne(code, trouve.index ?? 0),
              fix: "Mapper le document JSON MINOS vers les modèles NEXUS dans com.nexus.index.minos.",
            });
          }
        }
        for (const chemin of POMS) {
          const pom = masquerXml(await ctx.readFile(chemin));
          for (const trouve of pom.matchAll(MINOS_POM)) {
            ctx.report.violation({
              message: `Dépendance Maven « ${trouve[2]} » vers MINOS (ARCH-0080 : aucune dépendance Java/Maven croisée).`,
              file: chemin,
              line: ligne(pom, trouve.index ?? 0),
              fix: "Consommer MINOS par son export JSON lu sur stdin.",
            });
          }
        }
      },
    },
  },
} satisfies RuleSet;
