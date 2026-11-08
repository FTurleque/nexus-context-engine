/// <reference path="../rules.d.ts" />

// Règles de l'ADR SEC-0082 (support filesystem borné aux filesystems locaux qualifiés ; traversée stricte et
// compatibilité). Commentaires et littéraux effacés avant la recherche ; contrôles propres à Archgate.
// - Décision : « NEXUS n'ajoute pas de blocage runtime fondé sur `FileStore.type()` : la frontière de support est
//   documentaire et de qualification. » Contrôle no-runtime-filesystem-type-gate : aucun source de production
//   (src/main/java du cœur et des adaptateurs) qui référence FileStore ou getFileStore n'appelle .type(). Le périmètre
//   qualifié est vérifié par les workflows filesystem-semantics-qualification.yml et smb-filesystem-qualification.yml.
// - Décision : « Il n'existe aucune option de production pour forcer le fallback ; la seam de test est package-private »
//   et « `SafeFileIO` utilise toujours `SecureDirectoryStream` lorsqu'il est disponible ». Contrôle
//   safe-file-io-fallback-not-forceable : dans SafeFileIO, la surcharge openReadNoFollow qui reçoit forceFallback n'est
//   ni public ni protected, l'entrée de production lui passe SecurityPolicy.requireStrictPathIo() et false, et aucun
//   autre source de production ne mentionne forceFallback ; SafeFileIO s'appuie sur SecureDirectoryStream.
// - Décision : « REST `direct-https`, `reverse-proxy-https` et loopback avec hardening explicite exigent les deux
//   politiques `NEXUS_REQUIRE_PRIVATE_STORAGE=true` et `NEXUS_REQUIRE_STRICT_PATH_IO=true`. » Contrôle
//   hardened-rest-requires-private-storage-and-strict-traversal : SecurityPolicy.requireHardenedStorageAndTraversal()
//   teste SecurityPolicy.requirePrivateStorage() et requireStrictPathIo(), et NexusRestExposureGuard l'appelle.
// Ces contrôles complètent SafeFileIOTest, NexusRestExposureGuardTest et scripts/release/test-final-audit-contracts.sh
// (instantané d'identités du fallback) sans les remplacer.

// Frontière de support : sources de production.
const SOURCES = ["core/src/main/java/**/*.java", "adapters/*/src/main/java/**/*.java"];

// Traversée stricte et exposition REST durcie.
const SAFE_FILE_IO = "core/src/main/java/com/nexus/security/SafeFileIO.java";
const SECURITY_POLICY = "core/src/main/java/com/nexus/config/SecurityPolicy.java";
const REST_GUARD = "adapters/rest-quarkus/src/main/java/com/nexus/api/NexusRestExposureGuard.java";
const PRODUCTION = ["core/src/main/java/**/*.java", "adapters/*/src/main/java/**/*.java"];

/** Efface commentaires et littéraux Java (longueur et sauts de ligne conservés). */
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

/** Arguments de premier niveau de l'appel dont la parenthèse ouvrante est à `open`. */
function argumentsAt(code: string, open: number): { args: string[]; end: number } {
  const args: string[] = [];
  let depth = 0;
  let start = open + 1;
  for (let k = open; k < code.length; k++) {
    const c = code[k];
    if (c === "(" || c === "{" || c === "[") depth++;
    else if (c === ")" || c === "}" || c === "]") {
      depth--;
      if (depth === 0) {
        args.push(code.slice(start, k).trim());
        return { args: args.filter((arg) => arg.length > 0), end: k };
      }
    } else if (c === "," && depth === 1) {
      args.push(code.slice(start, k).trim());
      start = k + 1;
    }
  }
  return { args, end: code.length };
}

/** Corps (entre accolades) de la méthode `name`, ou undefined. */
function methodBody(code: string, name: string): string | undefined {
  const declaration = new RegExp(`\\b${name}\\s*\\([^)]*\\)\\s*(?:throws[^{]*)?\\{`).exec(code);
  if (!declaration) return undefined;
  let depth = 0;
  const open = declaration.index + declaration[0].length - 1;
  for (let k = open; k < code.length; k++) {
    if (code[k] === "{") depth++;
    else if (code[k] === "}" && --depth === 0) return code.slice(open, k + 1);
  }
  return undefined;
}

export default {
  rules: {
    "no-runtime-filesystem-type-gate": {
      description:
        "Aucun source de production qui manipule FileStore n'appelle .type() : pas de blocage runtime fondé sur FileStore.type()",
      severity: "error",
      async check(ctx) {
        const files = (await Promise.all(SOURCES.map((pattern) => ctx.glob(pattern)))).flat().sort();
        if (files.length === 0) {
          ctx.report.violation({ message: "Aucun source de production trouvé : la règle ne peut rien vérifier." });
          return;
        }
        for (const file of files) {
          const code = masquerJava(await ctx.readFile(file));
          if (!/\b(?:FileStore|getFileStore)\b/.test(code)) continue;
          for (const match of code.matchAll(/\.\s*type\s*\(\s*\)/g)) {
            ctx.report.violation({
              message: "Appel .type() dans un source qui manipule FileStore : SEC-0082 n'ajoute pas de blocage runtime fondé sur le type de filesystem.",
              file,
              line: ligne(code, match.index ?? 0),
              fix: "Tenir la frontière de support par la documentation et la qualification ; étendre le support par une PR de qualification dédiée.",
            });
          }
        }
      },
    },

    "safe-file-io-fallback-not-forceable": {
      description:
        "Aucune option de production ne force le fallback de SafeFileIO : la seam forceFallback est package-private et l'entrée de production lui passe false",
      severity: "error",
      async check(ctx) {
        const code = masquerJava(await ctx.readFile(SAFE_FILE_IO));
        if (!/\bSecureDirectoryStream\b/.test(code)) {
          ctx.report.violation({
            message: "SafeFileIO ne référence plus SecureDirectoryStream : la traversée stricte n'est plus utilisée lorsqu'elle est disponible.",
            file: SAFE_FILE_IO,
          });
        }
        let seamFound = false;
        let productionEntryFound = false;
        for (const match of code.matchAll(/\bopenReadNoFollow\s*\(/g)) {
          const index = match.index ?? 0;
          const { args, end } = argumentsAt(code, index + match[0].length - 1);
          const before = code.slice(code.lastIndexOf("\n", index) + 1, index);
          const isDeclaration = /^\s*(?:(?:public|protected|private|static|final|synchronized)\s+)*[\w<>?,\s\[\]]+\s+$/.test(before)
            && /^\s*(?:throws[^{;]*)?\{/.test(code.slice(end + 1));
          if (isDeclaration) {
            if (args.some((arg) => /\bforceFallback\b/.test(arg))) {
              seamFound = true;
              // Modificateurs : de la fin de l'instruction ou du bloc précédent jusqu'au nom de la méthode.
              const header = code.slice(Math.max(code.lastIndexOf(";", index), code.lastIndexOf("}", index)) + 1, index);
              if (/\b(?:public|protected)\b/.test(header)) {
                ctx.report.violation({
                  message: "La surcharge openReadNoFollow(…, forceFallback, …) est publique ou protégée : le fallback deviendrait forçable hors des tests.",
                  file: SAFE_FILE_IO,
                  line: ligne(code, index),
                  fix: "Garder cette seam package-private, réservée aux tests du paquet com.nexus.security.",
                });
              }
            }
            continue;
          }
          if (args.length === 4) {
            productionEntryFound = true;
            if (args[2] !== "false" || !/\brequireStrictPathIo\s*\(\s*\)/.test(args[1])) {
              ctx.report.violation({
                message: `Appel de production openReadNoFollow(${args.join(", ").replace(/\s+/g, " ")}) : il doit passer requireStrictPathIo() et forceFallback=false.`,
                file: SAFE_FILE_IO,
                line: ligne(code, index),
                fix: "Passer SecurityPolicy.requireStrictPathIo() et false ; seuls les tests forcent le fallback.",
              });
            }
          }
        }
        if (!seamFound || !productionEntryFound) {
          ctx.report.violation({
            message: "Structure de SafeFileIO non reconnue (seam forceFallback ou entrée de production absente) : la règle ne peut plus vérifier SEC-0082.",
            file: SAFE_FILE_IO,
            fix: "Mettre à jour la règle SEC-0082 avec la nouvelle structure, sans exposer le fallback.",
          });
        }

        const files = (await Promise.all(PRODUCTION.map((pattern) => ctx.glob(pattern)))).flat().sort();
        for (const file of files) {
          if (file === SAFE_FILE_IO) continue;
          const other = masquerJava(await ctx.readFile(file));
          for (const found of other.matchAll(/\bforceFallback\b/g)) {
            ctx.report.violation({
              message: "forceFallback hors de SafeFileIO dans un source de production : aucune option de production ne force le fallback (SEC-0082).",
              file,
              line: ligne(other, found.index ?? 0),
            });
          }
        }
      },
    },

    "hardened-rest-requires-private-storage-and-strict-traversal": {
      description:
        "Le guard REST exige ensemble NEXUS_REQUIRE_PRIVATE_STORAGE et NEXUS_REQUIRE_STRICT_PATH_IO par SecurityPolicy.requireHardenedStorageAndTraversal()",
      severity: "error",
      async check(ctx) {
        const policy = masquerJava(await ctx.readFile(SECURITY_POLICY));
        const body = methodBody(policy, "requireHardenedStorageAndTraversal");
        if (body === undefined) {
          ctx.report.violation({
            message: "SecurityPolicy.requireHardenedStorageAndTraversal() est introuvable.",
            file: SECURITY_POLICY,
          });
        } else {
          for (const [needle, what] of [
            [/\brequirePrivateStorage\s*\(\s*\)/, "NEXUS_REQUIRE_PRIVATE_STORAGE (SecurityPolicy.requirePrivateStorage())"],
            [/\brequireStrictPathIo\s*\(\s*\)/, "NEXUS_REQUIRE_STRICT_PATH_IO (requireStrictPathIo())"],
          ] as [RegExp, string][]) {
            if (!needle.test(body)) {
              ctx.report.violation({
                message: `requireHardenedStorageAndTraversal() ne vérifie plus ${what} : une exposition durcie exige les deux politiques (SEC-0082).`,
                file: SECURITY_POLICY,
                line: ligne(policy, policy.indexOf(body)),
              });
            }
          }
        }
        const guard = masquerJava(await ctx.readFile(REST_GUARD));
        if (!/\brequireHardenedStorageAndTraversal\s*\(\s*\)/.test(guard)) {
          ctx.report.violation({
            message: "NexusRestExposureGuard n'appelle plus SecurityPolicy.requireHardenedStorageAndTraversal() : les modes HTTPS et le loopback durci n'exigent plus les deux politiques.",
            file: REST_GUARD,
            fix: "Appeler SecurityPolicy.requireHardenedStorageAndTraversal() après la validation TLS et dans le loopback durci.",
          });
        }
      },
    },
  },
} satisfies RuleSet;
