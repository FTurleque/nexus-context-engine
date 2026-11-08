/// <reference path="../rules.d.ts" />

// Règles de l'ADR ARCH-0075 (exposer le cœur par des adaptateurs minces isolés : REST Quarkus, MCP STDIO sur
// le SDK Java officiel, clients assistants). Commentaires et littéraux Java effacés, commentaires XML effacés.
// - quarkus-dependencies-only-in-rest-adapter : « Quarkus n'est déclaré que dans `adapters/rest-quarkus/pom.xml` ;
//   le `pom.xml` racine ne reçoit aucune dépendance Quarkus. » Contrôle : aucun groupId Quarkus dans
//   core/pom.xml, adapters/mcp-java/pom.xml et adapters/assistant-clients/pom.xml. Le pom.xml racine n'est pas
//   contrôlé : il importe la BOM Quarkus (écart consigné dans l'ADR).
// - rest-adapter-delegates-business-logic et mcp-handlers-delegate-to-nexus-application : « Il ne contient aucune
//   logique métier d'indexation, de recherche, de ranking, de budget ni de construction de contexte : il ne
//   calcule aucun score et ne reconstruit aucun `ContextBundle`. » Contrôle : les sources de
//   adapters/rest-quarkus/src/main/java et de adapters/mcp-java/src/main/java ne nomment aucun service ni
//   package technique du moteur : elles passent par NexusApplication.
// - mcp-sdk-only-in-mcp-adapter et mcp-sdk-confined-to-mcp-adapter : « Le SDK MCP est une dépendance
//   d'adaptateur. Il n'apparaît ni dans `ContextRequest`, `ContextBundle`, `SearchStrategy`, ni dans les modèles
//   métier. » Contrôle : aucune référence à io.modelcontextprotocol dans les sources Java hors de
//   adapters/mcp-java, ni dans core/pom.xml, adapters/rest-quarkus/pom.xml et adapters/assistant-clients/pom.xml.
// - mcp-adapter-uses-official-sdk : « L'adaptateur MCP vit dans `adapters/mcp-java` et s'appuie sur le SDK Java
//   MCP officiel ; aucune implémentation maison du framing ou du transport MCP n'est ajoutée. » Contrôle :
//   adapters/mcp-java/pom.xml dépend du groupId io.modelcontextprotocol.sdk et le serveur importe
//   io.modelcontextprotocol.server.
// - mcp-stdout-reserved-for-transport : « `stdout` est réservé au flux JSON-RPC du SDK ; les diagnostics
//   applicatifs ne sont jamais écrits sur `stdout`. » Contrôle : dans adapters/mcp-java/src/main/java,
//   System.out n'apparaît que comme argument de new StdioServerTransportProvider(...).
// - assistant-clients-independent-of-engine : « Il ne modifie pas les préférences de l'utilisateur et ne crée
//   aucun nouveau chemin métier vers le cœur. Le cœur et le serveur MCP restent indépendants de ce module. »
//   Contrôle : adapters/assistant-clients/pom.xml ne dépend ni de nexus-context-engine ni de nexus-mcp-java ; ses
//   sources n'importent aucun package com.nexus hors de com.nexus.integrations ; ni core/pom.xml ni
//   adapters/mcp-java/pom.xml ne dépendent de nexus-assistant-clients.
// - assistant-clients-write-no-file : « Le module `adapters/assistant-clients` reste autonome et génère uniquement
//   des commandes ou fragments de configuration. » Contrôle : ses sources principales n'écrivent, ne créent, ne
//   copient, ne déplacent ni ne suppriment aucun fichier ; seule l'ouverture de stdout par
//   new FileOutputStream(FileDescriptor.out) est admise.

// Adaptateur REST.
const POMS_SANS_QUARKUS = ["core/pom.xml", "adapters/mcp-java/pom.xml", "adapters/assistant-clients/pom.xml"];
const QUARKUS_POM = /<groupId>\s*(io\.quarkus[\w.-]*|\$\{quarkus\.platform\.group-id\})\s*<\/groupId>/g;
const SOURCES_REST = "adapters/rest-quarkus/src/main/java/**/*.java";

// Adaptateur MCP.
const SOURCES_JAVA = "**/src/*/java/**/*.java";
const ADAPTATEUR_MCP = "adapters/mcp-java/";
const SOURCES_MCP = "adapters/mcp-java/src/main/java/**/*.java";
const POMS_SANS_MCP = ["core/pom.xml", "adapters/rest-quarkus/pom.xml", "adapters/assistant-clients/pom.xml"];

const SDK_MCP = /\bio\s*\.\s*modelcontextprotocol\b/g;
const SDK_MCP_POM = /<groupId>\s*(io\.modelcontextprotocol[\w.-]*)\s*<\/groupId>/g;
const STDOUT = /\bSystem\s*\.\s*out\b|\bSystem\s*\.\s*setOut\s*\(|\bFileDescriptor\s*\.\s*out\b/g;
const TRANSPORT = /\bnew\s+StdioServerTransportProvider\s*\(/g;

// SDK Java MCP officiel.
const SOURCES_PRODUCTION = ["core/src/main/java/**/*.java", "adapters/*/src/main/java/**/*.java"];
const ADAPTER_SOURCES = "adapters/mcp-java/src/main/java/";
const ADAPTER_POM = "adapters/mcp-java/pom.xml";
const SDK = /\bio\s*\.\s*modelcontextprotocol\b/g;
const SDK_SERVER_IMPORT = /\bimport\s+io\s*\.\s*modelcontextprotocol\s*\.\s*server\s*\./;
const SDK_DEPENDENCY = /<groupId>\s*io\.modelcontextprotocol\.sdk\s*<\/groupId>/;

// Intégrations clientes.
const POM_INTEGRATIONS = "adapters/assistant-clients/pom.xml";
const POMS_INDEPENDANTS = ["core/pom.xml", "adapters/mcp-java/pom.xml"];
const SOURCES_INTEGRATIONS = "adapters/assistant-clients/src/main/java/**/*.java";

const MOTEUR_POM = /<artifactId>\s*(nexus-context-engine|nexus-mcp-java)\s*<\/artifactId>/g;
const INTEGRATIONS_POM = /<artifactId>\s*(nexus-assistant-clients)\s*<\/artifactId>/g;
const IMPORT_NEXUS = /^\s*import\s+(?:static\s+)?(com\s*\.\s*nexus\s*\.\s*[\w.\s*]+?)\s*;/gm;

const ECRITURES: { motif: RegExp; quoi: string }[] = [
  {
    motif:
      /\bFiles\s*(?:\.|::)\s*(?:write|writeString|newOutputStream|newBufferedWriter|createFile|createDirector(?:y|ies)|createTemp(?:File|Directory)|create(?:Symbolic)?Link|delete|deleteIfExists|copy|move|setAttribute|setPosixFilePermissions|setLastModifiedTime|setOwner)\b/g,
    quoi: "écriture ou modification de fichier (java.nio.file.Files)",
  },
  {
    motif: /\bimport\s+static\s+java\s*\.\s*nio\s*\.\s*file\s*\.\s*Files\s*\.\s*[\w*]+\s*;/g,
    quoi: "import statique de java.nio.file.Files (masque les appels d'écriture)",
  },
  { motif: /\bnew\s+(?:FileWriter|RandomAccessFile)\b/g, quoi: "écriture de fichier (FileWriter ou RandomAccessFile)" },
  {
    motif: /\bnew\s+FileOutputStream\s*\((?!\s*FileDescriptor\s*\.\s*(?:out|err)\s*\))/g,
    quoi: "écriture de fichier (FileOutputStream hors stdout/stderr)",
  },
  { motif: /\b(?:Asynchronous)?FileChannel\s*(?:\.|::)\s*open\b/g, quoi: "ouverture d'un canal de fichier" },
  { motif: /\.\s*(?:mkdirs?|createNewFile|renameTo|deleteOnExit|delete)\s*\(\s*\)/g, quoi: "modification de fichier (java.io.File)" },
];

// Classes et packages du moteur que seule NexusApplication compose : un adaptateur qui les nomme
// recompose ou réimplémente l'indexation, la recherche, le ranking ou la construction de contexte.
// Liste commune aux adaptateurs REST et MCP.
const MOTEUR: { motif: RegExp; quoi: string }[] = [
  {
    motif:
      /\b(?:DefaultContextBuilder|ContextBuilder|BudgetedContextSelector|ContextBudgetAllocator|ContextFragmentFactory|FragmentMerger|NativeContextPipeline|TaskContextMaterializer|FederatedContextService|ContextRanker|DeterministicContextRanker|SemanticHybridContextRanker|SearchService|FederatedSearchService|SymbolSearchStrategy|CandidateMerger|CandidateEnricher|ProjectIndexingService|SemanticIndexingService)\b/g,
    quoi: "service du moteur",
  },
  {
    motif:
      /\bcom\s*\.\s*nexus\s*\.\s*(?:persistence|search\s*\.\s*lucene|search\s*\.\s*semantic|index\s*\.\s*(?:scan|jdt|scip|minos|java|markdown))\b/g,
    quoi: "package technique du moteur",
  },
  { motif: /\bcom\s*\.\s*nexus\s*\.\s*ranking\s*\.\s*(?!RankedCandidate\b)\w+/g, quoi: "ranking du moteur" },
  { motif: /\bnew\s+(?:ContextBundle|FederatedContextBundle|RankedCandidate)\s*\(/g, quoi: "construction d'un résultat du moteur" },
];

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

function normaliser(chemin: string): string {
  return chemin.split("\\").join("/");
}

/** Vrai si l'index tombe dans la liste d'arguments d'un `new StdioServerTransportProvider(`. */
function dansLeTransport(code: string, index: number): boolean {
  for (const appel of code.matchAll(TRANSPORT)) {
    const ouverture = (appel.index ?? 0) + appel[0].length - 1;
    if (ouverture > index) break;
    let profondeur = 0;
    for (let k = ouverture; k < code.length; k++) {
      if (code[k] === "(") profondeur++;
      else if (code[k] === ")" && --profondeur === 0) {
        if (index < k) return true;
        break;
      }
    }
  }
  return false;
}

async function sourcesMcp(ctx: RuleContext): Promise<{ chemin: string; code: string }[]> {
  const fichiers = await ctx.glob(SOURCES_MCP);
  if (fichiers.length === 0) {
    ctx.report.violation({ message: `Aucune source trouvée sous ${SOURCES_MCP} : la règle ne peut rien vérifier.` });
  }
  const sources: { chemin: string; code: string }[] = [];
  for (const chemin of fichiers) sources.push({ chemin, code: masquerJava(await ctx.readFile(chemin)) });
  return sources;
}

async function sourcesIntegrations(ctx: RuleContext): Promise<{ chemin: string; code: string }[]> {
  const fichiers = await ctx.glob(SOURCES_INTEGRATIONS);
  if (fichiers.length === 0) {
    ctx.report.violation({ message: `Aucune source trouvée sous ${SOURCES_INTEGRATIONS} : la règle ne peut rien vérifier.` });
  }
  const resultat: { chemin: string; code: string }[] = [];
  for (const chemin of fichiers) resultat.push({ chemin, code: masquerJava(await ctx.readFile(chemin)) });
  return resultat;
}

export default {
  rules: {
    "quarkus-dependencies-only-in-rest-adapter": {
      description:
        "Aucune dépendance Quarkus dans les pom.xml du cœur, de l'adaptateur MCP et des intégrations clientes : Quarkus reste confiné à adapters/rest-quarkus",
      severity: "error",
      async check(ctx) {
        for (const chemin of POMS_SANS_QUARKUS) {
          const pom = masquerXml(await ctx.readFile(chemin));
          for (const trouve of pom.matchAll(QUARKUS_POM)) {
            ctx.report.violation({
              message: `Dépendance Quarkus « ${trouve[1]} » hors de adapters/rest-quarkus (ARCH-0075).`,
              file: chemin,
              line: ligne(pom, trouve.index ?? 0),
              fix: "Déclarer Quarkus uniquement dans adapters/rest-quarkus/pom.xml.",
            });
          }
        }
      },
    },
    "rest-adapter-delegates-business-logic": {
      description:
        "Les sources de adapters/rest-quarkus ne nomment aucun service ni package technique du moteur (ContextBuilder, ranking, SearchService, indexation, SQLite, Lucene) : elles délèguent à NexusApplication",
      severity: "error",
      async check(ctx) {
        const fichiers = await ctx.glob(SOURCES_REST);
        if (fichiers.length === 0) {
          ctx.report.violation({ message: `Aucune source trouvée sous ${SOURCES_REST} : la règle ne peut rien vérifier.` });
          return;
        }
        for (const chemin of fichiers) {
          const code = masquerJava(await ctx.readFile(chemin));
          for (const { motif, quoi } of MOTEUR) {
            for (const trouve of code.matchAll(motif)) {
              ctx.report.violation({
                message: `${quoi} « ${trouve[0].replace(/\s+/g, " ")} » dans l'adaptateur REST (ARCH-0075 : aucune logique métier d'indexation, de recherche ou de contexte dans les ressources REST).`,
                file: chemin,
                line: ligne(code, trouve.index ?? 0),
                fix: "Appeler la façade NexusApplication et mapper son résultat vers un DTO de l'adaptateur.",
              });
            }
          }
        }
      },
    },
    "mcp-sdk-only-in-mcp-adapter": {
      description:
        "Le SDK MCP (io.modelcontextprotocol) n'est référencé que sous adapters/mcp-java : ni le cœur, ni REST, ni les intégrations clientes",
      severity: "error",
      async check(ctx) {
        for (const chemin of await ctx.glob(SOURCES_JAVA)) {
          if (normaliser(chemin).startsWith(ADAPTATEUR_MCP)) continue;
          const code = masquerJava(await ctx.readFile(chemin));
          for (const trouve of code.matchAll(SDK_MCP)) {
            ctx.report.violation({
              message: `Référence au SDK MCP hors de adapters/mcp-java (ARCH-0075 : aucune dépendance MCP dans le cœur ni dans les autres adaptateurs).`,
              file: chemin,
              line: ligne(code, trouve.index ?? 0),
              fix: "Exposer la capacité par NexusApplication et ne la traduire en MCP que dans adapters/mcp-java.",
            });
          }
        }
        for (const chemin of POMS_SANS_MCP) {
          const pom = masquerXml(await ctx.readFile(chemin));
          for (const trouve of pom.matchAll(SDK_MCP_POM)) {
            ctx.report.violation({
              message: `Dépendance MCP « ${trouve[1]} » hors de adapters/mcp-java (ARCH-0075).`,
              file: chemin,
              line: ligne(pom, trouve.index ?? 0),
              fix: "Déclarer le SDK MCP uniquement dans adapters/mcp-java/pom.xml.",
            });
          }
        }
      },
    },
    "mcp-stdout-reserved-for-transport": {
      description:
        "STDIO : dans adapters/mcp-java/src/main/java, System.out n'apparaît que comme argument de new StdioServerTransportProvider(...)",
      severity: "error",
      async check(ctx) {
        for (const { chemin, code } of await sourcesMcp(ctx)) {
          for (const trouve of code.matchAll(STDOUT)) {
            const index = trouve.index ?? 0;
            if (/\bSystem\s*\.\s*out\b/.test(trouve[0]) && dansLeTransport(code, index)) continue;
            ctx.report.violation({
              message: `« ${trouve[0].replace(/\s+/g, "")} » hors du transport STDIO : stdout est réservé au flux JSON-RPC du SDK (ARCH-0075).`,
              file: chemin,
              line: ligne(code, index),
              fix: "Écrire les diagnostics sur stderr ; seul StdioServerTransportProvider reçoit System.out.",
            });
          }
        }
      },
    },
    "mcp-handlers-delegate-to-nexus-application": {
      description:
        "Les sources de adapters/mcp-java ne nomment aucun service ni package technique du moteur (ContextBuilder, ranking, SearchService, indexation, SQLite, Lucene) : elles délèguent à NexusApplication",
      severity: "error",
      async check(ctx) {
        for (const { chemin, code } of await sourcesMcp(ctx)) {
          for (const { motif, quoi } of MOTEUR) {
            for (const trouve of code.matchAll(motif)) {
              ctx.report.violation({
                message: `${quoi} « ${trouve[0].replace(/\s+/g, " ")} » dans l'adaptateur MCP (ARCH-0075 : les handlers ne calculent aucun score et ne reconstruisent aucun ContextBundle).`,
                file: chemin,
                line: ligne(code, trouve.index ?? 0),
                fix: "Valider les arguments, appeler NexusApplication puis traduire son résultat en réponse MCP.",
              });
            }
          }
        }
      },
    },
    "mcp-sdk-confined-to-mcp-adapter": {
      description:
        "Le SDK MCP (io.modelcontextprotocol) n'est référencé dans les sources de production que par adapters/mcp-java",
      severity: "error",
      async check(ctx) {
        for (const chemin of (await Promise.all(SOURCES_PRODUCTION.map((motif) => ctx.glob(motif)))).flat().map((f) => f.replace(/\\/g, "/")).sort()) {
          if (chemin.startsWith(ADAPTER_SOURCES)) continue;
          const code = masquerJava(await ctx.readFile(chemin));
          for (const trouve of code.matchAll(SDK)) {
            ctx.report.violation({
              message: "Référence au SDK MCP hors de l'adaptateur MCP : le SDK est une dépendance d'adaptateur (ARCH-0075).",
              file: chemin,
              line: ligne(code, trouve.index ?? 0),
              fix: "Traduire la requête et la réponse MCP dans adapters/mcp-java et n'exposer au cœur que des types NEXUS.",
            });
          }
        }
      },
    },
    "mcp-adapter-uses-official-sdk": {
      description:
        "adapters/mcp-java dépend du SDK Java MCP officiel (groupId io.modelcontextprotocol.sdk) et son serveur importe io.modelcontextprotocol.server",
      severity: "error",
      async check(ctx) {
        const pom = await ctx.readFile(ADAPTER_POM);
        if (!SDK_DEPENDENCY.test(pom)) {
          ctx.report.violation({
            message: `${ADAPTER_POM} ne déclare aucune dépendance io.modelcontextprotocol.sdk : l'adaptateur MCP doit s'appuyer sur le SDK officiel (ARCH-0075).`,
            file: ADAPTER_POM,
            fix: "Déclarer io.modelcontextprotocol.sdk:mcp-core (version gérée par le mcp-bom du pom parent).",
          });
        }
        const sources = (await ctx.glob(`${ADAPTER_SOURCES}**/*.java`)).sort();
        let importeLeServeur = false;
        for (const chemin of sources) {
          if (SDK_SERVER_IMPORT.test(masquerJava(await ctx.readFile(chemin)))) importeLeServeur = true;
        }
        if (!importeLeServeur) {
          ctx.report.violation({
            message: `Aucune source de ${ADAPTER_SOURCES} n'importe io.modelcontextprotocol.server : le serveur MCP n'est pas construit sur le SDK officiel (ARCH-0075).`,
            file: ADAPTER_SOURCES,
            fix: "Construire le serveur avec McpServer et StdioServerTransportProvider du SDK au lieu d'un protocole maison.",
          });
        }
      },
    },
    "assistant-clients-independent-of-engine": {
      description:
        "adapters/assistant-clients ne dépend ni du cœur ni du serveur MCP, n'importe que com.nexus.integrations, et aucun d'eux ne dépend de lui",
      severity: "error",
      async check(ctx) {
        const pom = masquerXml(await ctx.readFile(POM_INTEGRATIONS));
        for (const trouve of pom.matchAll(MOTEUR_POM)) {
          ctx.report.violation({
            message: `adapters/assistant-clients dépend de « ${trouve[1]} » : nouveau chemin vers le moteur (ARCH-0075).`,
            file: POM_INTEGRATIONS,
            line: ligne(pom, trouve.index ?? 0),
            fix: "Le module ne génère que des commandes et fragments de configuration du serveur MCP existant ; retirer la dépendance.",
          });
        }
        for (const chemin of POMS_INDEPENDANTS) {
          const autre = masquerXml(await ctx.readFile(chemin));
          for (const trouve of autre.matchAll(INTEGRATIONS_POM)) {
            ctx.report.violation({
              message: `${chemin} dépend de « ${trouve[1]} » : le cœur et le serveur MCP restent indépendants du module d'intégration (ARCH-0075).`,
              file: chemin,
              line: ligne(autre, trouve.index ?? 0),
              fix: "Retirer la dépendance vers nexus-assistant-clients.",
            });
          }
        }
        for (const { chemin, code } of await sourcesIntegrations(ctx)) {
          for (const trouve of code.matchAll(IMPORT_NEXUS)) {
            const cible = trouve[1].replace(/\s+/g, "");
            if (cible.startsWith("com.nexus.integrations.")) continue;
            ctx.report.violation({
              message: `Import « ${cible} » hors de com.nexus.integrations dans adapters/assistant-clients (ARCH-0075).`,
              file: chemin,
              line: ligne(code, trouve.index ?? 0),
              fix: "Ne pas appeler le moteur depuis ce module : les clients passent par le serveur MCP STDIO.",
            });
          }
        }
      },
    },
    "assistant-clients-write-no-file": {
      description:
        "Les sources principales de adapters/assistant-clients n'écrivent, ne créent, ne déplacent ni ne suppriment aucun fichier (sortie sur stdout uniquement)",
      severity: "error",
      async check(ctx) {
        for (const { chemin, code } of await sourcesIntegrations(ctx)) {
          for (const { motif, quoi } of ECRITURES) {
            for (const trouve of code.matchAll(motif)) {
              ctx.report.violation({
                message: `${quoi} : « ${trouve[0].replace(/\s+/g, " ")} ». ARCH-0075 : la génération ne modifie aucun fichier de l'utilisateur.`,
                file: chemin,
                line: ligne(code, trouve.index ?? 0),
                fix: "Émettre la commande ou le fragment de configuration sur stdout ; l'utilisateur l'applique lui-même.",
              });
            }
          }
        }
      },
    },
  },
} satisfies RuleSet;
