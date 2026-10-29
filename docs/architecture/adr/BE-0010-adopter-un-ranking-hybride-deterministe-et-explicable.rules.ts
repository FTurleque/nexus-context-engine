/// <reference path="../rules.d.ts" />

// Règle de l'ADR BE-0010 (ranking hybride, déterministe et explicable). Contrôle propre à Archgate : les
// tests vérifient le déterminisme du classement, aucun ne vérifie l'absence d'appel externe.
// - Décision : le score est calculé à partir d'une configuration explicite et reproductible ;
//   l'explication est produite à partir des mêmes composantes, et n'est pas générée par un LLM.
// - Contrôle : les sources du ranking (core/src/main/java/com/nexus/ranking/) n'ouvrent aucun accès
//   réseau (java.net, client HTTP, socket), ne lancent aucun processus et n'appellent aucun provider
//   d'embeddings ou LLM (EmbeddingProvider, Ollama). Commentaires et littéraux effacés avant la recherche.

const SOURCES = "core/src/main/java/com/nexus/ranking/**/*.java";

const INTERDITS: { motif: RegExp; quoi: string }[] = [
  { motif: /\bjava\s*\.\s*net\b/g, quoi: "accès réseau (java.net)" },
  { motif: /\b(?:HttpClient|HttpRequest|HttpURLConnection|URLConnection|Socket|SocketChannel)\b/g, quoi: "client réseau" },
  { motif: /\bProcessBuilder\b|\bRuntime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*exec\s*\(/g, quoi: "lancement de processus" },
  { motif: /\bEmbeddingProvider\b|\bollama\b|\bOllama\w*/g, quoi: "appel à un provider d'embeddings ou LLM" },
];

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
    "ranking-has-no-network-or-llm-call": {
      description:
        "Le ranking (com.nexus.ranking) calcule et explique ses scores sans réseau, sans processus et sans provider d'embeddings ou LLM",
      severity: "error",
      async check(ctx) {
        const fichiers = (await ctx.glob(SOURCES)).sort();
        if (fichiers.length === 0) {
          ctx.report.violation({ message: `Aucune source sous ${SOURCES} : la règle ne peut rien vérifier.` });
          return;
        }
        for (const chemin of fichiers) {
          const code = masquerJava(await ctx.readFile(chemin));
          for (const { motif, quoi } of INTERDITS) {
            for (const trouve of code.matchAll(motif)) {
              ctx.report.violation({
                message: `${quoi} dans le ranking : « ${trouve[0].replace(/\s+/g, " ")} ». BE-0010 : score et explication sont calculés localement à partir des composantes, sans LLM.`,
                file: chemin,
                line: ligne(code, trouve.index ?? 0),
                fix: "Recevoir le signal déjà calculé dans SearchCandidate (SearchSignals) au lieu d'appeler un service externe depuis le ranking.",
              });
            }
          }
        }
      },
    },
  },
} satisfies RuleSet;
