/// <reference path="../rules.d.ts" />

// Règles de l'ADR BE-0016 (SDK Java MCP officiel pour l'adaptateur MCP). Contrôles propres à Archgate :
// les tests de adapters/mcp-java exercent le protocole, aucun ne vérifie où le SDK est référencé.
// - Décision : le SDK MCP est une dépendance d'adaptateur ; il n'apparaît ni dans ContextRequest,
//   ContextBundle, SearchStrategy, ni dans les modèles métier.
//   Contrôle : io.modelcontextprotocol n'est référencé, dans les sources de production, que sous
//   adapters/mcp-java/src/main/java/ (commentaires et littéraux effacés).
// - Décision : l'adaptateur MCP utilise le SDK Java MCP officiel.
//   Contrôle : adapters/mcp-java/pom.xml dépend du groupId io.modelcontextprotocol.sdk et le serveur
//   importe io.modelcontextprotocol.server.

const SOURCES = ["core/src/main/java/**/*.java", "adapters/*/src/main/java/**/*.java"];
const ADAPTER_SOURCES = "adapters/mcp-java/src/main/java/";
const ADAPTER_POM = "adapters/mcp-java/pom.xml";
const SDK = /\bio\s*\.\s*modelcontextprotocol\b/g;
const SDK_SERVER_IMPORT = /\bimport\s+io\s*\.\s*modelcontextprotocol\s*\.\s*server\s*\./;
const SDK_DEPENDENCY = /<groupId>\s*io\.modelcontextprotocol\.sdk\s*<\/groupId>/;

/** Efface commentaires et littéraux (longueur et sauts de ligne conservés). */
function masquerJava(source: string): string {
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
      effacer(i + 3, j - 3);
      i = j;
    } else if (c === '"' || c === "'") {
      let j = i + 1;
      while (j < n && source[j] !== c && source[j] !== "\n") j += source[j] === "\\" ? 2 : 1;
      j = Math.min(n, j + 1);
      effacer(i + 1, j - 1);
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
    "mcp-sdk-confined-to-mcp-adapter": {
      description:
        "Le SDK MCP (io.modelcontextprotocol) n'est référencé dans les sources de production que par adapters/mcp-java",
      severity: "error",
      async check(ctx) {
        for (const chemin of (await Promise.all(SOURCES.map((motif) => ctx.glob(motif)))).flat().map((f) => f.replace(/\\/g, "/")).sort()) {
          if (chemin.startsWith(ADAPTER_SOURCES)) continue;
          const code = masquerJava(await ctx.readFile(chemin));
          for (const trouve of code.matchAll(SDK)) {
            ctx.report.violation({
              message: "Référence au SDK MCP hors de l'adaptateur MCP : le SDK est une dépendance d'adaptateur (BE-0016).",
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
            message: `${ADAPTER_POM} ne déclare aucune dépendance io.modelcontextprotocol.sdk : l'adaptateur MCP doit s'appuyer sur le SDK officiel (BE-0016).`,
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
            message: `Aucune source de ${ADAPTER_SOURCES} n'importe io.modelcontextprotocol.server : le serveur MCP n'est pas construit sur le SDK officiel (BE-0016).`,
            file: ADAPTER_SOURCES,
            fix: "Construire le serveur avec McpServer et StdioServerTransportProvider du SDK au lieu d'un protocole maison.",
          });
        }
      },
    },
  },
} satisfies RuleSet;
