/// <reference path="../rules.d.ts" />

// ARCH-0005 : le chemin local de NEXUS ne fait aucun appel réseau ; toute intégration externe est
// opt-in. La règle refuse dans core/src/main/java (commentaires et littéraux effacés) tout client
// réseau Java (java.net.http, URL/URLConnection, sockets, canaux réseau NIO) et tout transport
// distant JGit (clone, ls-remote, org.eclipse.jgit.transport), hors du seul provider externe opt-in
// du cœur : le provider d'embeddings Ollama (com.nexus.search.semantic.ollama).

const SOURCES = "core/src/main/java/**/*.java";
const PROVIDERS_OPT_IN = ["core/src/main/java/com/nexus/search/semantic/ollama/"];

const RESEAU: { motif: RegExp; quoi: string }[] = [
  {
    motif:
      /\bjava\s*\.\s*net\s*\.\s*(?:http\b|\*|URL\b|URLConnection\b|HttpURLConnection\b|JarURLConnection\b|Socket\b|ServerSocket\b|DatagramSocket\b|MulticastSocket\b)/g,
    quoi: "client réseau java.net",
  },
  {
    motif: /\bjava\s*\.\s*nio\s*\.\s*channels\s*\.\s*(?:(?:Asynchronous)?(?:Server)?SocketChannel|DatagramChannel)\b/g,
    quoi: "canal réseau NIO",
  },
  { motif: /\borg\s*\.\s*eclipse\s*\.\s*jgit\s*\.\s*transport\b/g, quoi: "transport distant JGit" },
  { motif: /\bGit\s*\.\s*(?:cloneRepository|lsRemoteRepository)\s*\(/g, quoi: "opération distante JGit" },
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

function ligne(texte: string, index: number): number {
  let l = 1;
  for (let k = 0; k < index; k++) if (texte[k] === "\n") l++;
  return l;
}

function normaliser(chemin: string): string {
  return chemin.split("\\").join("/");
}

export default {
  rules: {
    "core-network-only-in-opt-in-providers": {
      description:
        "Aucun client réseau ni transport Git distant dans core/src/main/java hors du provider d'embeddings opt-in com.nexus.search.semantic.ollama",
      severity: "error",
      async check(ctx) {
        const fichiers = await ctx.glob(SOURCES);
        if (fichiers.length === 0) {
          ctx.report.violation({ message: `Aucune source trouvée sous ${SOURCES} : la règle ne peut rien vérifier.` });
          return;
        }
        for (const chemin of fichiers) {
          if (PROVIDERS_OPT_IN.some((prefixe) => normaliser(chemin).startsWith(prefixe))) continue;
          const code = masquerJava(await ctx.readFile(chemin));
          for (const { motif, quoi } of RESEAU) {
            for (const trouve of code.matchAll(motif)) {
              ctx.report.violation({
                message: `${quoi} « ${trouve[0].replace(/\s+/g, "")} » dans le chemin local du cœur (ARCH-0005 : local-first, intégrations externes opt-in).`,
                file: chemin,
                line: ligne(code, trouve.index ?? 0),
                fix: "Isoler l'appel réseau dans un provider externe explicitement activé par configuration, puis l'ajouter à la liste des providers opt-in de cette règle par décision documentée.",
              });
            }
          }
        }
      },
    },
  },
} satisfies RuleSet;
