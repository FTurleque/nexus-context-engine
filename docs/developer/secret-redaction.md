# Redaction des contenus sensibles

`SensitiveContentRedactor` (politique `secret-redaction-v6`) masque les clés
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
URL, 255 pour le corps d’un jeton à préfixe) provoque un refus du contenu entier par `OversizedSecretException`, sans
suffixe secret ni valeur dans le message d’erreur. L’indexation exclut alors le
seul fichier concerné des index lexical et sémantique (diagnostic
`INDEX_REDACTION_LIMIT` dans le rapport) et le garde dans l’index structurel ; la
construction de contexte écarte le fragment, le skill ou l’élément concerné
(diagnostic `CONTEXT_REDACTION_LIMIT` ou exclusion publique). Le reste du projet
s’indexe et le contexte se construit avec les autres fragments.

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
[recovery V011](release-and-recovery.md#mise-à-niveau-v011--formats-de-secrets)
pour l’invalidation des dérivés construits sous une politique antérieure.
