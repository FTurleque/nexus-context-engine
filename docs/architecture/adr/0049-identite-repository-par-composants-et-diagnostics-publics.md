# ADR-0049 — Identité repository par composants et diagnostics publics

Statut : accepté. Date : 2026-09-09.

## Contexte

Le remplacement textuel du backslash par `/` confondait sous POSIX le fichier
`a\b.java` avec `a/b.java`. Cette ambiguïté affectait l'identité SQLite, les index
dérivés et les relectures. Une ancienne clé ne permet pas de reconstruire son
origine physique. Les diagnostics libres pouvaient aussi exposer le suffixe d'un
chemin interne comportant des espaces.

## Décision

`com.nexus.paths.RepositoryPath` définit le contrat unique. L'encodage filesystem
itère les composants du `Path` relatif et les joint par `/`. Il conserve la casse,
les espaces et les caractères de chaque composant. L'identité fichier refuse
racine, composants vides, `.` et `..`, et NUL. La chaîne vide n'est admise que par
`encode` pour représenter explicitement un scope racine.

La reconstruction utilise le filesystem du projet, un composant à la fois. Un
composant non représentable à l'identique est refusé : un backslash POSIX ne peut
pas devenir silencieusement un séparateur Windows. Le contrôle de confinement est
lexical ; `ProjectPathGuard` puis `SafeFileIO` restent responsables des accès
physiques, des symlinks, de la revalidation et des lectures bornées. Les modes
strict et compatibility de l'ADR-0048 restent inchangés.

Les frontières restent explicites :

- filesystem : `fromPath` / `encode` ;
- identités persistées : constructeur validant, puis `resolve` / `toPath` ;
- SCIP : `Document.relative_path` utilise `/`, sans composants vides, `.` ou `..`,
  conformément au [protocole SCIP](https://github.com/sourcegraph/scip/blob/main/scip.proto) ;
- MINOS : le contrat d'import `filePath` est une chaîne relative à séparateurs `/`,
  sans correction ni suppression d'espaces ; l'appartenance à l'allowlist NEXUS est exacte ;
- Git : les chaînes JGit ne sont pas réinterprétées, les cibles filesystem sont
  encodées par composants pour les comparer aux chemins Git ;
- JDT/LSP : parser l'URI `file:` puis le `Path` natif avant encodage, et conserver
  les frontières de confinement et de sélection des sources ;
- instructions : références relatives à séparateurs `/`, validation avant résolution ;
- sortie publique : `PublicProjectPathPolicy`, distincte du contrôle d'accès I/O.

Les formes drive/UNC externes sont refusées par le parseur provider. Les noms POSIX
contenant un backslash non initial restent littéraux. Aucun repli ne cherche un
autre fichier quand un chemin provider est inconnu ou incompatible.

## Upgrade et invalidation

V007 est une nouvelle migration checksumée ; V001–V006 sont inchangées. Elle
supprime les fichiers indexés, donc les symboles et relations via les FK en
cascade, incrémente les générations et remet tous les projets à `NOT_INDEXED`
avec `last_indexed_at = NULL`. Projets, racines, langues et technologies restent
disponibles. Les chemins historiques ne sont jamais devinés.

Le service refuse les recherches hors `READY`. L'indexation suivante reconstruit
complètement SQLite et Lucene avant `READY`. Le fingerprint porte un domaine
`nexus-repository-path-v2`, également pour les corpus sans backslash : un index
sémantique resté désactivé pendant l'upgrade ne peut redevenir compatible plus
tard par hasard. La reconstruction sémantique suit son mécanisme de provenance.
Les caches Git sont en mémoire d'instance et disparaissent au redémarrage ;
aucune migration de cache Git sur disque n'est nécessaire.

## Diagnostics

Un diagnostic structuré conserve code, message, chemin repository et catégorie
de cause séparés. La matérialisation utilise cette projection. Les métadonnées
publiques restent bornées, récursivement filtrées et fermées aux objets inconnus.

Un chemin interne inconnu détecté dans du texte libre entraîne le remplacement
du message entier par `[INTERNAL_PATH]`. Un espace, une virgule ou une parenthèse
peuvent appartenir au nom physique ; aucune de ces ponctuations n'est une borne
de confidentialité sûre. Les racines connues peuvent être projetées en chemin
repository relatif. Les requêtes du client restent séparées des diagnostics.

## Conséquences

Un rebuild après upgrade est obligatoire. Les diagnostics libres ambigus perdent
leur détail plutôt que de divulguer un suffixe ; les producteurs structurés
conservent un code et un chemin relatif utiles. Aucune dépendance supplémentaire,
aucune modification des garanties REST, SafeFileIO ou des seuils de couverture.


### Compatibilité du producteur MINOS historique

L'inspection de `NexusExportService` du producteur MINOS a confirmé un encodage
historique par remplacement textuel. Un consommateur ne peut pas réparer une
information déjà perdue. Sur un corpus contenant un backslash, NEXUS exige donc
`repositoryPathFormat=repository-components-v2` et contrôle cette déclaration
avant toute lecture source. Le producteur doit être corrigé pour émettre ce
format. Le corpus ordinaire reste compatible avec les exports v1 existants.
