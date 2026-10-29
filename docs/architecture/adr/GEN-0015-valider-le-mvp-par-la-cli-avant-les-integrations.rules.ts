/// <reference path="../rules.d.ts" />

// Règles de l'ADR GEN-0015 (valider le MVP par la CLI avant l'API, MCP et les intégrations IDE).
// - Décision : « La CLI reste un adaptateur mince. Elle ne contient ni logique de recherche, ni ranking, ni politique
//   de budget. » Contrôlé ici, commentaires et littéraux effacés : le paquet com.nexus.cli ne référence ni les
//   moteurs (Lucene, SQLite/JDBC, JavaParser, JGit, paquets persistence, search.lucene, search.semantic,
//   ranking.graph), ni les classes qui portent la recherche, le ranking ou la sélection sous budget ; il passe par
//   NexusApplication. Lire les bornes publiées par ContextBudgetPolicy et ResultLimitPolicy reste permis.
// - Décision : commandes cibles project add/list, index, search, context, inspect, options --budget, --explain et
//   sortie JSON. Contrôlé ici sur le texte de NexusCli.java (littéraux conservés).
// Contrôles propres à Archgate ; NexusCliTest vérifie le comportement des commandes.

const CLI_SOURCES = "core/src/main/java/com/nexus/cli/**/*.java";
const CLI_MAIN = "core/src/main/java/com/nexus/cli/NexusCli.java";

const ENGINE_PACKAGES =
  /\b(?:org\s*\.\s*apache\s*\.\s*lucene|org\s*\.\s*sqlite|com\s*\.\s*github\s*\.\s*javaparser|org\s*\.\s*eclipse\s*\.\s*jgit|com\s*\.\s*nexus\s*\.\s*persistence|com\s*\.\s*nexus\s*\.\s*search\s*\.\s*(?:lucene|semantic)|com\s*\.\s*nexus\s*\.\s*ranking\s*\.\s*graph)\b/g;
const ENGINE_CLASSES =
  /\b(?:ContextRanker|DeterministicContextRanker|SemanticHybridContextRanker|SearchService|FederatedSearchService|SearchIndex|SearchStrategy|SymbolSearchStrategy|CandidateMerger|ContextBuilder|DefaultContextBuilder|BudgetedContextSelector|ContextBudgetAllocator|FederatedContextService|DriverManager|Connection|PreparedStatement|ResultSet)\b/g;

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
    "cli-delegates-to-application-services": {
      description:
        "Le paquet com.nexus.cli ne référence ni moteur de recherche, ni ranking, ni sélection sous budget : il délègue à NexusApplication",
      severity: "error",
      async check(ctx) {
        const files = await ctx.glob(CLI_SOURCES);
        if (files.length === 0) {
          ctx.report.violation({ message: `Aucune source sous ${CLI_SOURCES} : la minceur de la CLI ne peut pas être vérifiée.` });
          return;
        }
        let delegates = false;
        for (const file of files.sort()) {
          const code = maskJava(await ctx.readFile(file));
          if (/\bNexusApplication\b/.test(code)) delegates = true;
          for (const pattern of [ENGINE_PACKAGES, ENGINE_CLASSES]) {
            for (const match of code.matchAll(pattern)) {
              ctx.report.violation({
                message: `« ${match[0].replace(/\s+/g, "")} » dans la CLI : GEN-0015 la veut mince, sans logique de recherche, de ranking ni de politique de budget.`,
                file,
                line: lineOf(code, match.index ?? 0),
                fix: "Exposer l'opération par un service applicatif de NexusApplication et l'appeler depuis la commande.",
              });
            }
          }
        }
        if (!delegates) {
          ctx.report.violation({
            message: "Aucune source de com.nexus.cli ne référence NexusApplication : la CLI ne délègue plus aux services applicatifs.",
            file: CLI_MAIN,
          });
        }
      },
    },

    "cli-exposes-mvp-commands": {
      description:
        "NexusCli expose project add/list, index, search, context, inspect, --budget, --explain et --json (décision GEN-0015)",
      severity: "error",
      async check(ctx) {
        const text = await ctx.readFile(CLI_MAIN);
        const required: { needle: RegExp; what: string }[] = [
          { needle: /case\s+"project"\s*->/, what: "commande project" },
          { needle: /case\s+"add"\s*->/, what: "sous-commande project add" },
          { needle: /case\s+"list"\s*->/, what: "sous-commande project list" },
          { needle: /case\s+"index"\s*->/, what: "commande index" },
          { needle: /case\s+"search"\s*->/, what: "commande search" },
          { needle: /CONTEXT_COMMAND\s*=\s*"context"|case\s+"context"\s*->/, what: "commande context" },
          { needle: /case\s+"inspect"\s*->/, what: "commande inspect" },
          { needle: /"--budget"/, what: "option --budget" },
          { needle: /"--explain"/, what: "option --explain" },
          { needle: /"--json"/, what: "sortie --json" },
        ];
        for (const { needle, what } of required) {
          if (!needle.test(text)) {
            ctx.report.violation({
              message: `${what} introuvable dans ${CLI_MAIN} : la CLI ne couvre plus le flux minimal du MVP (GEN-0015).`,
              file: CLI_MAIN,
              fix: "Rétablir la commande ou l'option, ou remplacer GEN-0015 par un nouvel ADR.",
            });
          }
        }
      },
    },
  },
} satisfies RuleSet;
