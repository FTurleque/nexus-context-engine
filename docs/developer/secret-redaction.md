# Redaction des contenus sensibles

`SensitiveContentRedactor` (politique `secret-redaction-v8`) masque les clés
privées (y compris `PGP PRIVATE KEY BLOCK`), les jetons structurés, les JWT, les
credentials URI et les affectations sensibles :

| Forme | Exemples |
|---|---|
| Jetons à préfixe | `ghp_`, `github_pat_`, `AKIA`/`ASIA`, `sk-` (`sk-proj-`, `sk-ant-`), `sk_live_`/`rk_test_`, `xoxb-` et autres `xox?-`, `AIza`, `glpat-`, `npm_` |
| En-tête HTTP | `Bearer <jeton>`, quelle que soit la clé qui le porte |
| URL | `scheme://user:motdepasse@hôte`, `scheme://<jeton de 20 caractères ou plus>@hôte` |
| Ligne de commande | `--password <valeur>`, valeur citée ou non, et les options dont le dernier composant est `password`, `pwd`, `token`, `secret`, `api-key`, `access-token`… (`--db-password`, `-token`) |
| XML | `<password>…</password>` et les éléments `secret`, `token`, `api_key`, `client_secret`, `access_key`, `private_key`, y compris un nom pointé (`<db.password>`), des attributs ou un bloc CDATA, sur une ligne |
| Affectations | voir ci-dessous |

`SecretAssignmentScanner` traite les affectations sans parser JSON/YAML complet :
clé ASCII simple ou composite avec séparateurs `.`, `_`, `-`, éventuellement
citée, puis `:` ou `=`, avec au plus 32 espaces/tabulations autour du séparateur.
Les composants sensibles sont `api_key`, `access_token`, `auth_token`,
`client_secret`, `secret_access_key`, `access_key`, `private_key`, `secret_key`,
`password`, `passwd`, `pwd`, `secret`, `token`, `auth`, `credentials` et
`authorization` ; les séparateurs `_` ou `-` internes sont facultatifs
(`secretKey`, `accessKey`). Un composant est comparé à un segment entier de la
clé : `tokenizer`, `author`, `oauth`, `max_tokens` ou une sous-chaîne comme
`notasecretvalue` ne constituent pas une clé sensible.

Les valeurs simples/doubles citées sont masquées en conservant les quotes,
y compris les échappements et apostrophes YAML doublées. Les scalaires non cités
(incluant nombres, booléens, `null`, valeurs courtes et vides) deviennent
`[REDACTED]`. Les espaces internes, Unicode, `:` et `#` sans espace précédent
font partie du scalaire. Virgule, `}`, `]`, point-virgule et fin de ligne le
terminent ; un `#` initial ou précédé d’espace/tabulation introduit un commentaire.
Les formes ambiguës contenant une virgule ou un crochet dans une valeur doivent
être citées. Les structures imbriquées et scalaires YAML de bloc ne sont pas
interprétés comme des scalaires multilignes.

Une quote non fermée masque la valeur jusqu’à la fin de la ligne courante.
Le scanner ne traverse jamais une ligne pour chercher sa fermeture. LF, CRLF
et CR sont conservés à l’identique. La redaction est déterministe et idempotente.
Les noms composites sont parcourus linéairement, sans répétition regex récursive.
Une valeur dépassant 4 096 caractères (1 024 pour la partie utilisateur d’une
URL, 255 pour le corps d’un jeton à préfixe, compté après le préfixe `proj-` ou
`ant-` d’un jeton `sk-`) provoque un refus du contenu entier par `OversizedSecretException`, sans
suffixe secret ni valeur dans le message d’erreur. L’indexation exclut alors le
seul fichier concerné des index lexical et sémantique (diagnostic
`INDEX_REDACTION_LIMIT` dans le rapport) et le garde dans l’index structurel ; la
construction de contexte écarte le fragment, le skill ou l’élément concerné
(diagnostic `CONTEXT_REDACTION_LIMIT` ou exclusion publique). Le reste du projet
s’indexe et le contexte se construit avec les autres fragments.

Le refus d’un jeton à préfixe compte le corps sur la même classe de caractères
que le masquage (`\w` après `gh?_`, `github_pat_`, `sk_` et `rk_` ; lettres,
chiffres, `_` et `-` après `sk-`, `glpat-` et `xox?-`) et lit le même texte, après
remplacement des blocs de clé privée complets : un jeton collé à la ligne de fin
d’un tel bloc est masqué ou refusé comme un jeton isolé. Un jeton `sk-`, `glpat-`
ou `xox?-` précédé d’un `-` hors de ce cas (marqueur PEM d’un certificat, ligne
`-----END` sans bloc, identifiant à tirets comme `a-sk-…`) n’est ni masqué ni
refusé : l’ancrage écarte le milieu d’un identifiant à tirets.

Le test `SecretRedactionProbeTest` versionne la sonde de 35 cas de l’audit du
2026-10-17 : formats couverts, identifiants ordinaires laissés intacts, et les
deux formes hors périmètre (scalaire YAML de bloc, valeur placée sur la ligne
suivant sa clé), épinglées comme limite connue.

La protection s’applique avant indexation lexicale, embeddings, estimation et
sélection des fragments, puis à nouveau dans la projection publique
(`PublicOutputProjection`, `PublicContextPolicy`) aux frontières CLI/REST/MCP,
qui redige aussi `signature`, `qualifiedName`, `source` et `target` des symboles
et relations issus de SCIP, MINOS ou JDT. Les requêtes fournies par le client restent des données client et
ne sont pas assimilées aux diagnostics internes. Voir
[recovery V011](release-and-recovery.md#mise-à-niveau-v011--formats-de-secrets),
[recovery V018](release-and-recovery.md#mise-à-niveau-v018--borne-des-jetons-sk-)
et [recovery V019](release-and-recovery.md#mise-à-niveau-v019--voisinage-et-classe-des-jetons-à-préfixe)
pour l’invalidation des dérivés construits sous une politique antérieure.

## OpenSpec secret-redaction

Spec : [`openspec/specs/secret-redaction/spec.md`](../../openspec/specs/secret-redaction/spec.md) ; matrice de traçabilité : [openspec-traceability.md](openspec-traceability.md).

### Comportement existant vérifié

Les références renvoient aux méthodes et aux tests ; les numéros de ligne cités le sont pour les fichiers de redaction relus le 2026-11-10.

| Étiquette | Fait | Source |
|---|---|---|
| [Existant vérifié] | `POLICY_VERSION` vaut `secret-redaction-v8`. | `core/src/main/java/com/nexus/security/SensitiveContentRedactor.java:20` |
| [Existant vérifié] | Borne générale d'une valeur : `MAX_SECRET_CHARS = 4096` (redactor et scanner d'assignations). Bornes plus étroites : 1 024 pour la partie utilisateur d'une URL, et un corps de jeton à préfixe borné à 255 caractères (`OVERSIZED_TOKENS`). Pour `sk-`, `sk-proj-` et `sk-ant-`, masquage et refus comptent le même corps, après le préfixe optionnel : 255 caractères sont masqués, 256 refusés. Chaque famille bornée compte son corps sur la même classe au masquage et au refus : un jeton `xox?-`, `gh?_` ou `sk_live_` qui contient un `_` est masqué jusqu'à 255 caractères de corps et refusé à 256. | `SensitiveContentRedactor.java:27-28`, `:41-62` (`STRUCTURED_TOKENS`, un motif par famille), `:101-113` ; `SensitiveContentRedactorTest#masksSkTokenWhoseBodyHas255CharactersAfterItsPrefix`, `#refusesSkTokenWhoseBodyHas256CharactersWithoutPublishingAnyPartOfIt`, `#refusesSkTokensWhoseBodyHas256To260Characters`, `#masksShortTokenContainingAnUnderscore`, `#masksTokenWhoseBodyHas255CharactersIncludingAnUnderscore`, `#refusesTokenWhoseBodyHas256CharactersIncludingAnUnderscore` ; `SecretAssignmentScanner.java:8` |
| [Existant vérifié] | `redact` remplace d'abord les blocs de clés privées par `[REDACTED]`, puis refuse le contenu entier (`OversizedSecretException`) si une valeur de ce texte dépasse sa borne, puis masque sur ce même texte dans l'ordre : jetons à préfixe, JWT, `Bearer`, option `--password`, éléments XML, assignations, puis (si le texte contient `://`) jeton et mot de passe d'URL. Un jeton collé à la ligne de fin d'un bloc a donc le même voisinage au refus et au masquage. | `SensitiveContentRedactor.java:118-142` ; `SensitiveContentRedactorTest#masksTokenGluedToAPrivateKeyBlockWhoseBodyHas255Characters`, `#refusesTokenGluedToAPrivateKeyBlockWhoseBodyHas256Characters` |
| [Existant vérifié] | À 4 096 caractères la valeur est masquée ; à 4 097 le contenu est refusé et le message de l'exception ne contient pas la valeur. | `SecretAssignmentScanner.java:116-137` ; `SensitiveContentRedactorTest#rejectsOversizedValuesWithoutPublishingAnySuffix` |
| [Existant vérifié] | `redactField` convertit un refus en `[REDACTED]` pour un champ court de sortie publique (signature, nom qualifié) au lieu de faire échouer la réponse. | `SensitiveContentRedactor.java:144-157` ; `PublicContextPolicy.java:55`, `:64` |
| [Existant vérifié] | À l'indexation, le fichier fautif est exclu des index lexical et sémantique, reçoit le diagnostic `INDEX_REDACTION_LIMIT` (chemin relatif et `valeur sensible dépassant la limite de redaction`, jamais la valeur) et reste analysé pour l'index structurel (la mise à jour SQLite est enregistrée avant la redaction). | `core/src/main/java/com/nexus/index/service/IndexDocumentPublisher.java` (`redactedOrNull` et son appelant, qui ajoute le chemin à `excludedPaths` et le diagnostic) |
| [Existant vérifié] | Un fichier indexé puis devenu non redigeable est retiré des index lexical et sémantique (`removeStaleDocuments`) ; en reconstruction complète, il n'est simplement pas réécrit. | `IndexDocumentPublisher.removeStaleDocuments` ; `ProjectIndexingServiceTest#anOversizedSecretExcludesOnlyItsFileFromDerivedIndexes` (partie lexicale) |
| [Existant vérifié] | `LuceneSearchIndex` et `SemanticIndexingService` écartent aussi, document par document, un contenu refusé (`WARNING` sur `System.Logger`, chemin relatif). | `LuceneSearchIndex.toLuceneDocumentOrNull` ; `SemanticIndexingService.redactOnce` |
| [Existant vérifié] | Construction de contexte : le fragment refusé est écarté seul, par `BudgetedContextSelector` (exclusion `<chemin> exclu : …`), `ContextBundle`, `ContextBudgetAllocator`, `SkillContextSelector` et, à la matérialisation, `ContextFragmentFactory` (diagnostic `CONTEXT_REDACTION_LIMIT`). | `BudgetedContextSelector.java:45-50` ; `ContextBundle.java:22-28` ; `ContextBudgetAllocator.java:96-102` ; `SkillContextSelector.java:38-44` ; `ContextFragmentFactory.java:143-151` |
| [Existant vérifié] | Un index Lucene n'est « présent » que si la donnée de commit `nexus.redaction.policy` égale `POLICY_VERSION` ; sinon l'indexation force une reconstruction complète. | `LuceneSearchIndex.isPresent`, `hasCurrentRedactionPolicy` ; `ProjectIndexingService` (`!searchIndex.isPresent(projectId)` force `fullRebuild`) ; `LuceneSearchIndexTest#redactionPolicyCheckRejectsMissingOrObsoleteCommitPolicy` |
| [Existant vérifié] | Les migrations `V011`, `V018` et `V019` incrémentent `generation` de tous les projets et les repassent en `NOT_INDEXED` (`last_indexed_at = NULL`) ; le registre `projects` est conservé. | `core/src/main/resources/db/migration/V011__invalidate_secret_format_indexes.sql:6-21` ; `V018__invalidate_sk_token_bound_indexes.sql:6-15` ; `V019__invalidate_token_context_bound_indexes.sql:6-15` ; `SchemaMigratorSkTokenBoundUpgradeTest`, `SchemaMigratorTokenContextBoundUpgradeTest` |
| [Existant vérifié] | Le profil sémantique est `content-v` + `CONTENT_PROFILE_VERSION` (7) + `;redactionPolicy=` + `POLICY_VERSION` + bornes d'embedding ; un changement de `POLICY_VERSION` modifie donc le profil même sans toucher `CONTENT_PROFILE_VERSION`. | `SemanticIndexingService.java:27`, `:170-175` |
| [Existant vérifié] | `SemanticIndexingService.vectorize` redige chaque document une fois (`redactOnce`) puis part de ce texte pour l'embedding et l'extrait. | `SemanticIndexingService.vectorize`, `redactOnce` |

### Vues

Vue UML séquence — Que devient un fichier dont une valeur sensible dépasse la borne pendant l'indexation, et le reste du projet ? (existant)

```mermaid
sequenceDiagram
    participant Pub as IndexDocumentPublisher
    participant Red as SensitiveContentRedactor
    participant Lex as LuceneSearchIndex
    participant Sem as SemanticIndexingService
    participant Rep as IndexingReport

    loop chaque fichier scanné à publier
        Pub->>Pub: analyzeScannedFile puis updates.add (index structurel)
        Pub->>Red: redact(contenu) via redactedOrNull
        alt valeur sensible trop longue (OversizedSecretException)
            Red-->>Pub: refus du contenu entier
            Pub->>Rep: diagnostic INDEX_REDACTION_LIMIT (chemin relatif, sans valeur)
            Note over Pub: chemin ajouté à excludedPaths, aucun SearchDocument créé
        else contenu redigé
            Red-->>Pub: texte avec [REDACTED]
            Pub->>Lex: document dans le lot (redaction rejouée par toLuceneDocument)
            Pub->>Sem: document dans le lot (redactOnce avant embedding)
        end
    end
    Pub->>Lex: removeStaleDocuments (applyChanges avec excludedPaths)
    Pub->>Sem: removeStaleDocuments (applyChanges avec excludedPaths)
    Note over Pub,Rep: le projet reste READY, les autres fichiers restent recherchables
```

Le fichier fautif n'entre ni dans le lot lexical ni dans le lot sémantique et son éventuelle ancienne version en est retirée ; les autres fichiers sont indexés normalement. Il reste dans l'index structurel. Les index lexical et sémantique rejouent la redaction sur un texte déjà redigé : c'est la seconde barrière volontaire décrite dans [semantic-search.md](semantic-search.md).

Exigences : `secret-redaction › Une valeur sensible trop longue n'exclut que son fichier`, `secret-redaction › Chaque document est redigé une fois avant l'embedding`.

Vue Organigramme — Dans quel ordre `redact` refuse-t-il ou masque-t-il, et que font les appelants d'un refus ? (existant)

```mermaid
flowchart TD
    A["redact(contenu)"] --> C["blocs de clés privées, y compris PGP, remplacés par [REDACTED]"]
    C --> B{"valeur au-delà de sa borne dans ce texte ?; OVERSIZED_TOKENS, OVERSIZED_VALUE, OVERSIZED_URI"}
    B -- oui --> X["OversizedSecretException; contenu entier refusé, aucun suffixe publié"]
    B -- non --> D["jetons à préfixe, JWT, Bearer"]
    D --> E["option --password, éléments XML"]
    E --> F["SecretAssignmentScanner : assignations clé et valeur"]
    F --> G{"le texte contient ://"}
    G -- oui --> H["jeton et mot de passe d'URL"]
    G -- non --> R["texte redigé"]
    H --> R
    F -. "valeur d'assignation au-delà de 4 096" .-> X
    X --> I{"appelant"}
    I -- "indexation" --> I1["fichier exclu des index lexical et sémantique; INDEX_REDACTION_LIMIT"]
    I -- "construction de contexte" --> I2["fragment, skill ou élément écarté; CONTEXT_REDACTION_LIMIT ou exclusion publique"]
    I -- "sortie publique d'un champ court" --> I3["redactField renvoie [REDACTED]"]
```

Le refus et le masquage lisent le même texte : un jeton collé à un bloc de clé privée y a le même voisinage. Le refus du contenu entier évite de publier le suffixe d'un secret trop long ; chaque appelant le transforme en exclusion locale plutôt qu'en échec global. Le test de bornes `SensitiveContentRedactorTest#boundedFormsAreRedactedAtTheBoundAndRefusedJustAbove` épingle N et N+1 pour `Bearer`, `--password`, `<password>` et la partie utilisateur d'une URL.

Exigences : `secret-redaction › Une valeur sensible trop longue n'exclut que son fichier`, `secret-redaction › Les formats de secrets courants sont redigés`, `secret-redaction › Les bornes étroites de redaction refusent le contenu`, `secret-redaction › Un jeton collé à un bloc de clé privée est masqué ou refusé`, `secret-redaction › Le masquage et le refus d'un jeton comptent la même classe de caractères`.

Vue Organigramme — Un jeton de la famille `sk-` est-il masqué ou refusé ? (existant)

```mermaid
flowchart TD
    A[contenu] --> B{"OVERSIZED_TOKENS : sk-, préfixe proj- ou ant- optionnel, 256 caractères de corps"}
    B -- trouvé --> R["OversizedSecretException, aucun extrait du jeton"]
    B -- absent --> C{"motif sk- : corps de 20 à 255"}
    C -- trouvé --> M["remplacement par [REDACTED]"]
    C -- absent --> N["aucun jeton sk- reconnu"]
```

Les deux motifs comptent le corps après le même préfixe optionnel, consommé de façon possessive : la borne de refus vaut la borne de masquage plus un, et aucune longueur de corps ne laisse un jeton reconnu en clair. `SensitiveContentRedactorTest` épingle 255 (masqué) et 256 (refusé) pour `sk-`, `sk-proj-` et `sk-ant-`, puis chaque longueur de 256 à 260 pour `sk-` ; `SecretRedactionProbeTest#skTokenFormatIsRedactedAtItsBoundAndRefusedAboveIt` garde le format de la sonde à sa borne.

Exigences : `secret-redaction › Un jeton sk- est masqué ou refusé à toute longueur de corps`.

Vue Organigramme — Comment un changement de politique invalide-t-il les index dérivés ? (existant)

```mermaid
flowchart TD
    P["POLICY_VERSION = secret-redaction-v8"] --> L["Lucene : commit nexus.redaction.policy"]
    P --> S["profileId sémantique : content-v7;redactionPolicy=secret-redaction-v8;…"]
    M["migrations V011, V018 et V019 : generation + 1, NOT_INDEXED, last_indexed_at NULL"] --> N["registre des projets conservé"]
    L --> Q{"isPresent : version du commit égale POLICY_VERSION ?"}
    Q -- non --> F["fullRebuild lexical"]
    Q -- oui --> K["index lexical réutilisable"]
    S --> T{"SemanticIndexProvenance compatible ?"}
    T -- non --> V["vecteurs reconstruits à la prochaine indexation sémantique"]
    T -- oui --> W["vecteurs réutilisables"]
```

Trois mécanismes se complètent : la dernière migration d'invalidation (`V019`) invalide les projets déjà enregistrés, la donnée de commit Lucene invalide un index d'une autre politique, et le profil sémantique rend incompatible un index d'un autre profil. Le second mécanisme est testé sur son prédicat, pas de bout en bout (écart 1).

Exigences : `secret-redaction › Un changement de politique invalide les index dérivés`.

### Écarts et limites

- [Écart] 1 — Index lexical d'une ancienne politique : le code invalide et reconstruit (`LuceneSearchIndex.isPresent`, `ProjectIndexingService`). `LuceneSearchIndexTest#redactionPolicyCheckRejectsMissingOrObsoleteCommitPolicy` prouve qu'une donnée de commit `nexus.redaction.policy` absente ou différente de `POLICY_VERSION` est rejetée ; aucun test n'enchaîne ce rejet et la reconstruction complète dans `ProjectIndexingService`. Scénario partiellement prouvé.
- [Écart] 2 — « redigé une seule fois ». Vrai à l'intérieur de `SemanticIndexingService` (`redactOnce`), mais sur le chemin d'indexation le texte a déjà été redigé par `IndexDocumentPublisher` (`redactedOrNull`) et l'est encore par `LuceneSearchIndex` (`toLuceneDocument`). Aucun test ne compte les appels à `redact` ; la tâche archivée 8.1 prouvait ce point par une commande (`git grep -c`), pas par un test.
- [Écart] 3 — Profil `content-vN`. L'exigence demande d'incrémenter le profil sémantique à chaque changement de politique ; le profil inclut déjà `redactionPolicy=<POLICY_VERSION>` (`SemanticIndexingService.java:170-175`), donc il change de toute façon. L'incrément manuel de `CONTENT_PROFILE_VERSION` (règle de `.claude/rules/search-and-redaction.md`) n'est vérifié par aucun test ; seuls les contrats de documentation épinglent `secret-redaction-v8` et `content-v7` (`scripts/release/test-operational-doc-contracts.sh:240-253`).
- [Écart] 4 — Le motif « chaque modification de redaction s'accompagne d'une migration » est une règle de processus : aucun test ne détecte une évolution de `SensitiveContentRedactor` sans nouvelle migration (preuve manquante).
- Limites vérifiées : les 19 formats de la spec sont les 19 cas `auditFormat` de la sonde de 35 cas ; `SecretRedactionProbeTest#probeHasThirtyFiveCasesIncludingTheNineteenAuditFormats` affirme 35, 19 et 2 limites connues. Les valeurs `secret-redaction-v5`, `secret-redaction-v6` et `secret-redaction-v7` (scénarios de migration) et `secret-redaction-v8` (courante) correspondent à `SchemaMigratorSecretFormatUpgradeTest`, `SchemaMigratorSkTokenBoundUpgradeTest`, `SchemaMigratorTokenContextBoundUpgradeTest` et à `POLICY_VERSION`. Aucun écart sur ces trois points.
- Limite : un jeton `sk-`, `glpat-` ou `xox?-` précédé d'un `-` qui n'appartient pas à un bloc de clé privée complet (après `-----END CERTIFICATE-----`, après une ligne `-----END … PRIVATE KEY-----` sans `BEGIN`, ou dans `a-sk-…`) n'est ni masqué ni refusé, à toute longueur : le masquage et le refus partagent l'ancrage `(?<![A-Za-z0-9_-])` (`SensitiveContentRedactor.notAfterDashToken`, appelé par `dashToken` et `OVERSIZED_TOKENS`). Aucun test n'épingle ce comportement.
- Limite : seul le chemin lexical est couvert de bout en bout pour l'indexation d'un fichier trop long ; `ProjectIndexingServiceTest#anOversizedSecretExcludesOnlyItsFileFromDerivedIndexes` n'active pas l'index sémantique, le test sémantique `SemanticIndexingServiceTest#excludesOnlyTheDocumentWhoseSecretExceedsTheRedactionBound` reste au niveau du service.

### Propositions et questions ouvertes

- [Proposition] Écrire un test d'indexation qui part d'un index Lucene dont `nexus.redaction.policy` diffère de `POLICY_VERSION`, sur un projet `READY`, et vérifie la reconstruction complète.
- [Proposition] Écrire un test d'indexation sémantique de bout en bout (fichier de 5 000 caractères dans un projet de trois fichiers, index sémantique activé).
- [Question] « redigé une seule fois » vise-t-il `SemanticIndexingService` seul, ou l'ensemble du chemin d'indexation (trois appels de `redact` au total, dont deux volontaires) ?
- [Question] Faut-il un test d'architecture ou de contrat qui impose migration, `POLICY_VERSION` et `CONTENT_PROFILE_VERSION` ensemble ?
