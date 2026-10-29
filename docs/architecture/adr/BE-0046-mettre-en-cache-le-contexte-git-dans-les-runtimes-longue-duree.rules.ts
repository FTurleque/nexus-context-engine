/// <reference path="../rules.d.ts" />

// Règles de l'ADR BE-0046 (cache mémoire du contexte Git en runtime longue durée). Complètent
// PersistentGitContextSourceProviderTest (hits, invalidation, éviction) par des contrôles de câblage et
// de stockage qu'aucun test ne fait.
// - Décision : le cache est activé uniquement dans NexusApplication.createLongLived(...) ;
//   NexusApplication.create(...) conserve LocalGitContextSourceProvider.
//   Contrôle git-cache-only-in-long-lived-runtime : dans les sources de production, seul
//   NexusApplicationFactory instancie PersistentGitContextSourceProvider, et dans NexusApplication les
//   appels NexusApplicationFactory.create(..., true) ne figurent que dans les méthodes createLongLived.
// - Décision : capacité par défaut de 16 résultats ; aucun fichier, schéma SQLite ou protocole
//   inter-processus supplémentaire.
//   Contrôle git-cache-in-memory-sixteen-entries : DEFAULT_CAPACITY vaut 16 et
//   PersistentGitContextSourceProvider n'écrit aucun fichier ni n'ouvre de connexion SQL ou réseau.
// Commentaires et littéraux effacés avant la recherche.

const SOURCES = "**/src/main/java/**/*.java";
const FACTORY = "core/src/main/java/com/nexus/application/NexusApplicationFactory.java";
const APPLICATION = "core/src/main/java/com/nexus/application/NexusApplication.java";
const CACHE = "core/src/main/java/com/nexus/context/source/git/PersistentGitContextSourceProvider.java";
const STORAGE =
  /\bFiles\s*\.\s*(?:write\w*|newOutputStream|newBufferedWriter|create\w*|copy|move|delete\w*)\b|\bnew\s+(?:FileOutputStream|FileWriter|RandomAccessFile)\b|\bjava\s*\.\s*sql\b|\bDriverManager\b|\bjava\s*\.\s*net\b/g;
const NEW_CACHE = /\bnew\s+PersistentGitContextSourceProvider\s*\(/g;

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

function ligne(texte: string, index: number): number {
  let l = 1;
  for (let k = 0; k < index; k++) if (texte[k] === "\n") l++;
  return l;
}

/** Nom de la dernière méthode `static NexusApplication nom(` déclarée avant l'index, ou null. */
function enclosingMethod(code: string, index: number): string | null {
  let name: string | null = null;
  for (const match of code.matchAll(/\bstatic\s+NexusApplication\s+(\w+)\s*\(/g)) {
    if ((match.index ?? 0) > index) break;
    name = match[1];
  }
  return name;
}

export default {
  rules: {
    "git-cache-only-in-long-lived-runtime": {
      description:
        "Seul NexusApplicationFactory instancie PersistentGitContextSourceProvider, et seul createLongLived demande le runtime longue durée",
      severity: "error",
      async check(ctx) {
        for (const file of (await ctx.glob(SOURCES)).sort()) {
          if (file === FACTORY || file === CACHE) continue;
          const code = masquerJava(await ctx.readFile(file));
          for (const match of code.matchAll(NEW_CACHE)) {
            ctx.report.violation({
              message:
                "PersistentGitContextSourceProvider instancié hors de la composition : le cache est réservé au runtime longue durée (BE-0046).",
              file,
              line: ligne(code, match.index ?? 0),
              fix: "Obtenir le runtime par NexusApplication.createLongLived(...) au lieu d'instancier le cache.",
            });
          }
        }
        const factory = masquerJava(await ctx.readFile(FACTORY));
        if (!/\bnew\s+PersistentGitContextSourceProvider\s*\(/.test(factory) || !/\bnew\s+LocalGitContextSourceProvider\s*\(/.test(factory)) {
          ctx.report.violation({
            message:
              "NexusApplicationFactory doit composer PersistentGitContextSourceProvider (longue durée) et LocalGitContextSourceProvider (one-shot) (BE-0046).",
            file: FACTORY,
            fix: "Choisir le provider Git selon le mode longue durée transmis par NexusApplication.",
          });
        }
        const application = masquerJava(await ctx.readFile(APPLICATION));
        for (const match of application.matchAll(/\bNexusApplicationFactory\s*\.\s*create\s*\(([^;]*?)\)\s*;/g)) {
          const longLived = /,\s*true$/.test(match[1].trim());
          const method = enclosingMethod(application, match.index ?? 0);
          if (longLived !== (method === "createLongLived")) {
            ctx.report.violation({
              message: `NexusApplication.${method ?? "?"} appelle NexusApplicationFactory.create(..., ${longLived}) : seul createLongLived active le runtime longue durée et son cache Git (BE-0046).`,
              file: APPLICATION,
              line: ligne(application, match.index ?? 0),
              fix: "Passer true uniquement depuis createLongLived(...), false depuis create(...).",
            });
          }
        }
      },
    },
    "git-cache-in-memory-sixteen-entries": {
      description: "PersistentGitContextSourceProvider garde 16 résultats par défaut, en mémoire seulement",
      severity: "error",
      async check(ctx) {
        const code = masquerJava(await ctx.readFile(CACHE));
        const capacity = /\bDEFAULT_CAPACITY\s*=\s*([0-9_]+)\s*;/.exec(code);
        if (!capacity || Number(capacity[1].replace(/_/g, "")) !== 16) {
          ctx.report.violation({
            message: `DEFAULT_CAPACITY vaut ${capacity ? capacity[1] : "(absent)"} ; BE-0046 fixe 16 résultats.`,
            file: CACHE,
            line: capacity ? ligne(code, capacity.index) : undefined,
            fix: "Rétablir 16, ou remplacer BE-0046 par un nouvel ADR fondé sur une mesure.",
          });
        }
        for (const match of code.matchAll(STORAGE)) {
          ctx.report.violation({
            message: `« ${match[0].replace(/\s+/g, " ")} » dans le cache Git : la persistance est en mémoire seulement, sans fichier, schéma SQLite ni protocole (BE-0046).`,
            file: CACHE,
            line: ligne(code, match.index ?? 0),
            fix: "Garder les résultats dans la LinkedHashMap LRU bornée du processus.",
          });
        }
      },
    },
  },
} satisfies RuleSet;
