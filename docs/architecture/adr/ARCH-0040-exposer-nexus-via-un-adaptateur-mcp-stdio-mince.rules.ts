/// <reference path="../rules.d.ts" />

// ARCH-0040 : l'adaptateur MCP est isolé dans adapters/mcp-java, sur le SDK Java MCP officiel, en
// STDIO, et ses handlers délèguent à NexusApplication. Trois contrôles :
// - mcp-sdk-only-in-mcp-adapter : aucune référence à io.modelcontextprotocol dans les sources Java
//   hors de adapters/mcp-java (commentaires et littéraux effacés), ni dans core/pom.xml,
//   adapters/rest-quarkus/pom.xml et adapters/assistant-clients/pom.xml ;
// - mcp-stdout-reserved-for-transport : dans adapters/mcp-java/src/main/java, System.out n'apparaît
//   que comme argument de new StdioServerTransportProvider(...) ;
// - mcp-handlers-delegate-to-nexus-application : les sources de adapters/mcp-java/src/main/java ne
//   nomment aucun service ni package technique du moteur.

const SOURCES_JAVA = "**/src/*/java/**/*.java";
const ADAPTATEUR_MCP = "adapters/mcp-java/";
const SOURCES_MCP = "adapters/mcp-java/src/main/java/**/*.java";
const POMS_SANS_MCP = ["core/pom.xml", "adapters/rest-quarkus/pom.xml", "adapters/assistant-clients/pom.xml"];

const SDK_MCP = /\bio\s*\.\s*modelcontextprotocol\b/g;
const SDK_MCP_POM = /<groupId>\s*(io\.modelcontextprotocol[\w.-]*)\s*<\/groupId>/g;
const STDOUT = /\bSystem\s*\.\s*out\b|\bSystem\s*\.\s*setOut\s*\(|\bFileDescriptor\s*\.\s*out\b/g;
const TRANSPORT = /\bnew\s+StdioServerTransportProvider\s*\(/g;

// Classes et packages du moteur que seule NexusApplication compose : un adaptateur qui les nomme
// recompose ou réimplémente l'indexation, la recherche, le ranking ou la construction de contexte.
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

function masquerXml(source: string): string {
  return source.replace(/<!--[\s\S]*?-->/g, (bloc) => bloc.replace(/[^\r\n]/g, " "));
}

function ligne(texte: string, index: number): number {
  let l = 1;
  for (let k = 0; k < index; k++) if (texte[k] === "\n") l++;
  return l;
}

/** Efface les commentaires XML (longueur et sauts de ligne conservés). */
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

export default {
  rules: {
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
              message: `Référence au SDK MCP hors de adapters/mcp-java (ARCH-0040 : aucune dépendance MCP dans le cœur ni dans les autres adaptateurs).`,
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
              message: `Dépendance MCP « ${trouve[1]} » hors de adapters/mcp-java (ARCH-0040).`,
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
              message: `« ${trouve[0].replace(/\s+/g, "")} » hors du transport STDIO : stdout est réservé au flux JSON-RPC du SDK (ARCH-0040).`,
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
                message: `${quoi} « ${trouve[0].replace(/\s+/g, " ")} » dans l'adaptateur MCP (ARCH-0040 : les handlers ne calculent aucun score et ne reconstruisent aucun ContextBundle).`,
                file: chemin,
                line: ligne(code, trouve.index ?? 0),
                fix: "Valider les arguments, appeler NexusApplication puis traduire son résultat en réponse MCP.",
              });
            }
          }
        }
      },
    },
  },
} satisfies RuleSet;
