# Redaction des contenus sensibles

`SensitiveContentRedactor` masque les clés privées, tokens structurés, JWT,
credentials URI et affectations sensibles. `SecretAssignmentScanner` traite ces
dernières sans parser JSON/YAML complet : clé ASCII simple ou composite avec
séparateurs `.`, `_`, `-`, éventuellement citée, puis `:` ou `=`, avec au plus
32 espaces/tabulations autour du séparateur. Les composants sensibles restent
`api_key`, `access_token`, `auth_token`, `client_secret`, `secret_access_key`,
`password`, `passwd`, `secret` (avec les variantes historiques). Une sous-chaîne
comme `notasecretvalue` ne constitue pas une clé sensible.

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
Une valeur dépassant 4 096 caractères provoque un refus du contenu entier par
exception générique, sans suffixe secret ni valeur dans le message d’erreur.

La protection s’applique avant indexation lexicale, embeddings, estimation et
sélection des fragments, puis à nouveau via `PublicContextPolicy` aux frontières
CLI/REST/MCP. Les requêtes fournies par le client restent des données client et
ne sont pas assimilées aux diagnostics internes. Voir
[recovery V010](release-and-recovery.md#mise-à-niveau-v010--scalaires-sensibles)
pour l’invalidation des dérivés historiques.
