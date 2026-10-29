/// <reference path="../rules.d.ts" />

// ARCH-0017 : le cœur n'a aucune dépendance de compilation vers un SDK Copilot, Claude/Anthropic,
// OpenAI, ni vers JARVIS, Alfred ou Brainiac. La règle contrôle les groupId/artifactId de
// core/pom.xml (commentaires XML effacés) et les imports de core/src/main/java (commentaires et
// littéraux effacés). Les providers d'instructions natives (ClaudeInstructionProvider…) lisent des
// fichiers du projet et n'importent aucun SDK : ils ne sont pas visés.

const SOURCES = "core/src/main/java/**/*.java";
const POM_COEUR = "core/pom.xml";

const COORDONNEE_POM = /<(groupId|artifactId)>\s*([^<]*?(?:anthropic|openai|copilot|claude|jarvis|alfred|brainiac)[^<]*?)\s*<\/\1>/gi;
const IMPORT_OUTIL =
  /^\s*import\s+(?:static\s+)?((?:com\s*\.\s*anthropic|com\s*\.\s*openai|com\s*\.\s*theokanning|dev\s*\.\s*langchain4j|com\s*\.\s*github\s*\.\s*copilot|[\w.\s]*\b(?:jarvis|alfred|brainiac)\b)[\w.\s*]*);/gim;

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
    "core-depends-on-no-assistant-or-orchestrator": {
      description:
        "core/pom.xml et core/src/main/java ne dépendent d'aucun SDK Copilot, Claude/Anthropic, OpenAI ni d'aucun projet JARVIS, Alfred ou Brainiac",
      severity: "error",
      async check(ctx) {
        const pom = masquerXml(await ctx.readFile(POM_COEUR));
        for (const trouve of pom.matchAll(COORDONNEE_POM)) {
          ctx.report.violation({
            message: `Dépendance « ${trouve[2]} » du cœur vers un outil ou orchestrateur externe (ARCH-0017).`,
            file: POM_COEUR,
            line: ligne(pom, trouve.index ?? 0),
            fix: "Placer l'intégration dans un adaptateur optionnel ; le cœur n'en connaît que le ContextBundle.",
          });
        }
        const fichiers = await ctx.glob(SOURCES);
        if (fichiers.length === 0) {
          ctx.report.violation({ message: `Aucune source trouvée sous ${SOURCES} : la règle ne peut rien vérifier.` });
          return;
        }
        for (const chemin of fichiers) {
          const code = masquerJava(await ctx.readFile(chemin));
          for (const trouve of code.matchAll(IMPORT_OUTIL)) {
            ctx.report.violation({
              message: `Import « ${trouve[1].replace(/\s+/g, "")} » d'un outil ou orchestrateur externe dans le cœur (ARCH-0017).`,
              file: chemin,
              line: ligne(code, trouve.index ?? 0),
              fix: "Intégrer cet outil par un adaptateur (API, MCP ou contrat dédié), sans dépendance de compilation du cœur.",
            });
          }
        }
      },
    },
  },
} satisfies RuleSet;
