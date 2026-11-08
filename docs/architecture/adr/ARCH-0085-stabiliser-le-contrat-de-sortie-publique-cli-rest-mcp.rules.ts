/// <reference path="../rules.d.ts" />

// Règles de l'ADR ARCH-0085 (stabiliser le contrat de sortie publique dans la projection du cœur : CLI humaine ou
// JSON, codes de sortie, parité REST et MCP). Contrôles propres à Archgate ; NexusCliTest vérifie le comportement
// des commandes et les tests *OutputParityTest la parité des canaux.
// - cli-only-main-calls-system-exit : « La logique principale retourne un code de sortie testable ; seul `main`
//   appelle `System.exit`. » Contrôle : dans core/src/main/java (commentaires et littéraux effacés), aucun
//   System.exit, Runtime.exit ou Runtime.halt ailleurs que dans le corps de la méthode
//   `public static void main(String[] …)` de com.nexus.cli.NexusCli.
// - cli-delegates-to-application-services : « La CLI est un adaptateur mince qui passe par `NexusApplication` :
//   elle ne contient ni logique de recherche, ni ranking, ni politique de budget. » Contrôle, commentaires et
//   littéraux effacés : le paquet com.nexus.cli ne référence ni les moteurs (Lucene, SQLite/JDBC, JavaParser, JGit,
//   paquets persistence, search.lucene, search.semantic, ranking.graph), ni les classes qui portent la recherche,
//   le ranking ou la sélection sous budget ; il passe par NexusApplication. Lire les bornes publiées par
//   ContextBudgetPolicy et ResultLimitPolicy reste permis.
// - cli-exposes-mvp-commands : « La CLI expose au minimum `nexus project add`, `nexus project list`, `nexus index`,
//   `nexus search`, `nexus context` et `nexus inspect`, avec `--budget`, `--explain`, une sortie lisible par un
//   humain et une sortie JSON (`--json`). » Contrôle sur le texte de NexusCli.java (littéraux conservés).

// Codes de sortie.
const SOURCES = "core/src/main/java/**/*.java";
const POINT_D_ENTREE = "core/src/main/java/com/nexus/cli/NexusCli.java";
const SORTIE = /\bSystem\s*\.\s*exit\s*\(|\bRuntime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*(?:exit|halt)\s*\(/g;
const MAIN = /\bpublic\s+static\s+void\s+main\s*\(\s*(?:final\s+)?String\s*(?:\[\s*\]|\.\.\.)\s*\w+\s*(?:\[\s*\])?\s*\)[^{]*\{/g;

// CLI adaptateur mince.
const CLI_SOURCES = "core/src/main/java/com/nexus/cli/**/*.java";
const CLI_MAIN = "core/src/main/java/com/nexus/cli/NexusCli.java";

const ENGINE_PACKAGES =
  /\b(?:org\s*\.\s*apache\s*\.\s*lucene|org\s*\.\s*sqlite|com\s*\.\s*github\s*\.\s*javaparser|org\s*\.\s*eclipse\s*\.\s*jgit|com\s*\.\s*nexus\s*\.\s*persistence|com\s*\.\s*nexus\s*\.\s*search\s*\.\s*(?:lucene|semantic)|com\s*\.\s*nexus\s*\.\s*ranking\s*\.\s*graph)\b/g;
const ENGINE_CLASSES =
  /\b(?:ContextRanker|DeterministicContextRanker|SemanticHybridContextRanker|SearchService|FederatedSearchService|SearchIndex|SearchStrategy|SymbolSearchStrategy|CandidateMerger|ContextBuilder|DefaultContextBuilder|BudgetedContextSelector|ContextBudgetAllocator|FederatedContextService|DriverManager|Connection|PreparedStatement|ResultSet)\b/g;

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

/** Plages [début, fin) des corps de `main` du fichier. */
function corpsDeMain(code: string): [number, number][] {
  const plages: [number, number][] = [];
  for (const trouve of code.matchAll(MAIN)) {
    const ouverture = (trouve.index ?? 0) + trouve[0].length - 1;
    let profondeur = 0;
    for (let k = ouverture; k < code.length; k++) {
      if (code[k] === "{") profondeur++;
      else if (code[k] === "}" && --profondeur === 0) {
        plages.push([ouverture, k]);
        break;
      }
    }
  }
  return plages;
}

export default {
  rules: {
    "cli-only-main-calls-system-exit": {
      description:
        "Dans core/src/main/java, System.exit (ou Runtime.exit/halt) n'apparaît que dans NexusCli.main ; la logique CLI retourne un code testable",
      severity: "error",
      async check(ctx) {
        const fichiers = await ctx.glob(SOURCES);
        if (fichiers.length === 0) {
          ctx.report.violation({ message: `Aucune source trouvée sous ${SOURCES} : la règle ne peut rien vérifier.` });
          return;
        }
        for (const chemin of fichiers) {
          const code = masquerJava(await ctx.readFile(chemin));
          const autorisees = chemin.split("\\").join("/") === POINT_D_ENTREE ? corpsDeMain(code) : [];
          for (const trouve of code.matchAll(SORTIE)) {
            const index = trouve.index ?? 0;
            if (autorisees.some(([debut, fin]) => index > debut && index < fin)) continue;
            ctx.report.violation({
              message: `« ${trouve[0].replace(/\s+/g, "")} » hors de NexusCli.main : la logique CLI doit retourner un code de sortie testable (ARCH-0085).`,
              file: chemin,
              line: ligne(code, index),
              fix: "Retourner le code de sortie (0, 1 ou 2) à l'appelant ; seul NexusCli.main le transmet à System.exit.",
            });
          }
        }
      },
    },

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
          const code = masquerJava(await ctx.readFile(file));
          if (/\bNexusApplication\b/.test(code)) delegates = true;
          for (const pattern of [ENGINE_PACKAGES, ENGINE_CLASSES]) {
            for (const match of code.matchAll(pattern)) {
              ctx.report.violation({
                message: `« ${match[0].replace(/\s+/g, "")} » dans la CLI : ARCH-0085 la veut mince, sans logique de recherche, de ranking ni de politique de budget.`,
                file,
                line: ligne(code, match.index ?? 0),
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
        "NexusCli expose project add/list, index, search, context, inspect, --budget, --explain et --json (décision ARCH-0085)",
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
              message: `${what} introuvable dans ${CLI_MAIN} : la CLI ne couvre plus le flux minimal du MVP (ARCH-0085).`,
              file: CLI_MAIN,
              fix: "Rétablir la commande ou l'option, ou remplacer ARCH-0085 par un nouvel ADR.",
            });
          }
        }
      },
    },
  },
} satisfies RuleSet;
