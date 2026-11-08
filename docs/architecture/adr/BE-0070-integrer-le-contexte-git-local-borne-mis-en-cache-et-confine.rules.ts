/// <reference path="../rules.d.ts" />

// Règles de l'ADR BE-0070 (contexte Git local borné, mis en cache en mémoire et confiné au dépôt du
// projet). Contrôles propres à Archgate : les tests Git couvrent des cas de lecture, de bornes et de
// cache (hits, invalidation, éviction), pas l'absence d'appel distant, la façon de parcourir les arbres,
// le câblage ni le stockage du cache.
// - Décision : « Le provider est local et basé sur JGit ; les données sont lues depuis le repository
//   local uniquement, sans `fetch`, `pull`, `push`, `checkout` ni commit. »
//   Contrôle git-context-reads-local-repository-only : les sources de com.nexus.context.source.git
//   n'appellent aucune commande JGit distante (fetch, pull, push, lsRemote, clone, Transport) ni aucune
//   commande ou API qui modifie le repository (commit, checkout, add, rm, reset, merge, rebase, stash,
//   tag, branche, cherry-pick, revert, clean, gc, RefUpdate, ObjectInserter, verrou du DirCache).
// - Décision : « `LocalGitContextSourceProvider` parcourt directement les deux arbres avec un `TreeWalk`
//   récursif et `TreeFilter.ANY_DIFF`. » et « La détection coûteuse des renommages n'est pas nécessaire
//   pour cette découverte bornée. »
//   Contrôle git-trees-walked-before-collect : le fichier construit un TreeWalk, le rend récursif, filtre
//   par TreeFilter.ANY_DIFF et n'utilise ni DiffFormatter, ni RenameDetector, ni setDetectRenames.
// - Décision : « Le cache est activé uniquement dans `NexusApplication.createLongLived(...)` (REST, MCP) ;
//   `NexusApplication.create(...)` conserve `LocalGitContextSourceProvider` pour la CLI et les usages
//   one-shot. »
//   Contrôle git-cache-only-in-long-lived-runtime : dans les sources de production, seul
//   NexusApplicationFactory instancie PersistentGitContextSourceProvider, et dans NexusApplication les
//   appels NexusApplicationFactory.create(..., true) ne figurent que dans les méthodes createLongLived.
// - Décision : « La capacité par défaut est `FederatedScopePolicy.MAX_PROJECTS` (100) » et « ni fichier,
//   ni schéma SQLite, ni protocole inter-processus ».
//   Contrôle git-cache-in-memory-sixteen-entries (identifiant conservé) : DEFAULT_CAPACITY vaut
//   FederatedScopePolicy.MAX_PROJECTS et PersistentGitContextSourceProvider n'écrit aucun fichier ni
//   n'ouvre de connexion SQL ou réseau.
// Commentaires et littéraux effacés avant la recherche.

const SOURCES = "core/src/main/java/com/nexus/context/source/git/**/*.java";

const FORBIDDEN: { pattern: RegExp; what: string }[] = [
  {
    // Commandes de porcelaine JGit : Git#fetch(), Git#commit()… s'appellent sans argument.
    pattern:
      /\.\s*(?:fetch|pull|push|lsRemote|commit|checkout|add|rm|reset|merge|rebase|stashCreate|stashApply|stashDrop|tag|tagDelete|branchCreate|branchDelete|branchRename|cherryPick|revert|clean|gc|apply|notesAdd|notesRemove|submoduleAdd|submoduleUpdate|submoduleInit)\s*\(\s*\)/g,
    what: "commande JGit distante ou mutante",
  },
  { pattern: /\bGit\s*\.\s*(?:cloneRepository|lsRemoteRepository|init)\s*\(/g, what: "commande JGit distante ou de création" },
  { pattern: /\bTransport\s*\.\s*open\b|\bimport\s+org\s*\.\s*eclipse\s*\.\s*jgit\s*\.\s*transport\s*\.\s*Transport\s*;/g, what: "transport Git distant" },
  { pattern: /\b(?:updateRef|newObjectInserter|lockDirCache)\s*\(/g, what: "écriture dans le repository Git" },
  { pattern: /\bRefUpdate\b/g, what: "mise à jour de référence Git (RefUpdate)" },
];

const PRODUCTION_SOURCES = "**/src/main/java/**/*.java";
const FACTORY = "core/src/main/java/com/nexus/application/NexusApplicationFactory.java";
const APPLICATION = "core/src/main/java/com/nexus/application/NexusApplication.java";
const CACHE = "core/src/main/java/com/nexus/context/source/git/PersistentGitContextSourceProvider.java";
const STORAGE =
  /\bFiles\s*\.\s*(?:write\w*|newOutputStream|newBufferedWriter|create\w*|copy|move|delete\w*)\b|\bnew\s+(?:FileOutputStream|FileWriter|RandomAccessFile)\b|\bjava\s*\.\s*sql\b|\bDriverManager\b|\bjava\s*\.\s*net\b/g;
const NEW_CACHE = /\bnew\s+PersistentGitContextSourceProvider\s*\(/g;

const PROVIDER = "core/src/main/java/com/nexus/context/source/git/LocalGitContextSourceProvider.java";
const REQUIRED: { pattern: RegExp; what: string }[] = [
  { pattern: /\bnew\s+TreeWalk\s*\(/, what: "new TreeWalk(...)" },
  { pattern: /\.\s*setRecursive\s*\(\s*true\s*\)/, what: "setRecursive(true)" },
  { pattern: /\bTreeFilter\s*\.\s*ANY_DIFF\b/, what: "TreeFilter.ANY_DIFF" },
];
const TREE_WALK_FORBIDDEN = /\bDiffFormatter\b|\bRenameDetector\b|\bsetDetectRenames\s*\(/g;

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
    "git-context-reads-local-repository-only": {
      description:
        "Les sources de com.nexus.context.source.git n'appellent ni commande Git distante ni commande qui modifie le repository",
      severity: "error",
      async check(ctx) {
        const files = (await ctx.glob(SOURCES)).sort();
        if (!files.some((file) => file.endsWith("/LocalGitContextSourceProvider.java"))) {
          ctx.report.violation({ message: `LocalGitContextSourceProvider.java introuvable sous ${SOURCES} : la règle ne peut rien vérifier.` });
          return;
        }
        for (const file of files) {
          const code = masquerJava(await ctx.readFile(file));
          for (const { pattern, what } of FORBIDDEN) {
            for (const match of code.matchAll(pattern)) {
              ctx.report.violation({
                message: `${what} dans le contexte Git : « ${match[0].replace(/\s+/g, " ")} ». BE-0070 : les données sont lues depuis le repository local uniquement.`,
                file,
                line: ligne(code, match.index ?? 0),
                fix: "Lire l'état local (RevWalk, TreeWalk, status, diff) sans réseau ni écriture.",
              });
            }
          }
        }
      },
    },
    "git-trees-walked-before-collect": {
      description:
        "LocalGitContextSourceProvider parcourt les arbres par TreeWalk récursif filtré ANY_DIFF, sans DiffFormatter ni détection de renommage",
      severity: "error",
      async check(ctx) {
        const code = masquerJava(await ctx.readFile(PROVIDER));
        for (const { pattern, what } of REQUIRED) {
          if (!pattern.test(code)) {
            ctx.report.violation({
              message: `${what} absent de LocalGitContextSourceProvider : BE-0070 impose un parcours direct des deux arbres.`,
              file: PROVIDER,
              fix: "Parcourir parent et commit par un TreeWalk récursif filtré par TreeFilter.ANY_DIFF, en débitant le budget à chaque entrée.",
            });
          }
        }
        for (const match of code.matchAll(TREE_WALK_FORBIDDEN)) {
          ctx.report.violation({
            message: `« ${match[0].replace(/\s+/g, "")} » dans LocalGitContextSourceProvider : le diff n'est pas matérialisé avant le débit du budget (BE-0070).`,
            file: PROVIDER,
            line: ligne(code, match.index ?? 0),
            fix: "Revenir au parcours TreeWalk borné ; la détection des renommages n'est pas nécessaire à cette découverte.",
          });
        }
      },
    },
    "git-cache-only-in-long-lived-runtime": {
      description:
        "Seul NexusApplicationFactory instancie PersistentGitContextSourceProvider, et seul createLongLived demande le runtime longue durée",
      severity: "error",
      async check(ctx) {
        for (const file of (await ctx.glob(PRODUCTION_SOURCES)).sort()) {
          if (file === FACTORY || file === CACHE) continue;
          const code = masquerJava(await ctx.readFile(file));
          for (const match of code.matchAll(NEW_CACHE)) {
            ctx.report.violation({
              message:
                "PersistentGitContextSourceProvider instancié hors de la composition : le cache est réservé au runtime longue durée (BE-0070).",
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
              "NexusApplicationFactory doit composer PersistentGitContextSourceProvider (longue durée) et LocalGitContextSourceProvider (one-shot) (BE-0070).",
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
              message: `NexusApplication.${method ?? "?"} appelle NexusApplicationFactory.create(..., ${longLived}) : seul createLongLived active le runtime longue durée et son cache Git (BE-0070).`,
              file: APPLICATION,
              line: ligne(application, match.index ?? 0),
              fix: "Passer true uniquement depuis createLongLived(...), false depuis create(...).",
            });
          }
        }
      },
    },
    "git-cache-in-memory-sixteen-entries": {
      description: "PersistentGitContextSourceProvider garde FederatedScopePolicy.MAX_PROJECTS résultats par défaut, en mémoire seulement",
      severity: "error",
      async check(ctx) {
        const code = masquerJava(await ctx.readFile(CACHE));
        const capacity = /\bDEFAULT_CAPACITY\s*=\s*([^;]+);/.exec(code);
        if (!capacity || capacity[1].replace(/\s+/g, "") !== "FederatedScopePolicy.MAX_PROJECTS") {
          ctx.report.violation({
            message: `DEFAULT_CAPACITY vaut ${capacity ? capacity[1].trim() : "(absent)"} ; `
              + "BE-0070 fixe FederatedScopePolicy.MAX_PROJECTS.",
            file: CACHE,
            line: capacity ? ligne(code, capacity.index) : undefined,
            fix: "Rétablir FederatedScopePolicy.MAX_PROJECTS, ou remplacer BE-0070 par un nouvel ADR fondé sur une mesure.",
          });
        }
        for (const match of code.matchAll(STORAGE)) {
          ctx.report.violation({
            message: `« ${match[0].replace(/\s+/g, " ")} » dans le cache Git : la persistance est en mémoire seulement, sans fichier, schéma SQLite ni protocole (BE-0070).`,
            file: CACHE,
            line: ligne(code, match.index ?? 0),
            fix: "Garder les résultats dans la LinkedHashMap LRU bornée du processus.",
          });
        }
      },
    },
  },
} satisfies RuleSet;
