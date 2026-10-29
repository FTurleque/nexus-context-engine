/// <reference path="../rules.d.ts" />

// ARCH-0039 : l'adaptateur REST vit dans adapters/rest-quarkus, seul module à dépendre de Quarkus, et
// ses ressources ne contiennent aucune logique métier d'indexation, de recherche ou de construction de
// contexte. Deux contrôles :
// - aucun groupId Quarkus dans core/pom.xml, adapters/mcp-java/pom.xml et
//   adapters/assistant-clients/pom.xml (commentaires XML effacés). Le pom.xml racine n'est pas
//   contrôlé : il importe la BOM Quarkus (écart consigné dans l'ADR) ;
// - les sources de adapters/rest-quarkus/src/main/java (commentaires et littéraux effacés) ne nomment
//   aucun service ni package technique du moteur : elles passent par NexusApplication.

const POMS_SANS_QUARKUS = ["core/pom.xml", "adapters/mcp-java/pom.xml", "adapters/assistant-clients/pom.xml"];
const QUARKUS_POM = /<groupId>\s*(io\.quarkus[\w.-]*|\$\{quarkus\.platform\.group-id\})\s*<\/groupId>/g;
const SOURCES_REST = "adapters/rest-quarkus/src/main/java/**/*.java";

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
              message: `Dépendance Quarkus « ${trouve[1]} » hors de adapters/rest-quarkus (ARCH-0039).`,
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
                message: `${quoi} « ${trouve[0].replace(/\s+/g, " ")} » dans l'adaptateur REST (ARCH-0039 : aucune logique métier d'indexation, de recherche ou de contexte dans les ressources REST).`,
                file: chemin,
                line: ligne(code, trouve.index ?? 0),
                fix: "Appeler la façade NexusApplication et mapper son résultat vers un DTO de l'adaptateur.",
              });
            }
          }
        }
      },
    },
  },
} satisfies RuleSet;
