# Design

**Profil :** complet

## Contexte

Ce changement n'ajoute aucun comportement : il rend contractuelles, si les questions de la proposition sont validées, des bornes que le code applique déjà.

- [Existant vérifié] Récence Git : `MAX_COMMITS = 50`, `MAX_CANDIDATE_PATHS = 512`, `MAX_CHANGED_PATHS_PER_COMMIT = 1_024`, `MAX_CUMULATIVE_CHANGED_PATHS = 4_096`, raison `récence Git ignorée : budget de chemins dépassé` (`core/src/main/java/com/nexus/context/source/git/GitRecencyCandidateEnricher.java:46-53`) ; contrôle des candidats à `:79`, des commits à `:140-141`.
- [Existant vérifié] Redaction : `MAX_URI_USER_CHARS = 1024` (`core/src/main/java/com/nexus/security/SensitiveContentRedactor.java:27`) ; un jeton `ghp_` (et `gho_`, `ghu_`, `ghs_`, `ghr_`), `github_pat_` ou `sk_live_` dont le corps atteint 256 caractères lève `OversizedSecretException` (`:77-85`, `:104-106`) ; une partie utilisateur d'URL de 20 à 1 024 caractères est masquée (`:63-64`, `:114`).
- [Existant vérifié] Vue de l'absorption fail-open de la récence : `docs/developer/git-context.md#openspec-git-recency-diagnostics` ; vue de la redaction avant indexation : `docs/developer/secret-redaction.md#openspec-secret-redaction`.

## Objectifs / Hors objectifs

**Objectifs :**
- Énoncer les bornes comme exigences observables : `git-recency-diagnostics › Le signal de récence Git est borné en chemins` ; `secret-redaction › Les bornes étroites de redaction refusent le contenu`.
- Prouver chaque borne à N et N+1 par un test.

**Hors objectifs :**
- Changer une valeur de borne ou la forme du refus.
- Trancher le désaccord entre le texte de « Une valeur sensible trop longue n'exclut que son fichier » et le code (question ouverte de la proposition).

## Composants et responsabilités

Non applicable : aucun composant n'est créé ni déplacé ; `GitRecencyCandidateEnricher` et `SensitiveContentRedactor` gardent leurs responsabilités.

## Modèle de domaine et invariants

Non applicable : aucune entité n'est ajoutée. Invariant visé : une borne dépassée ne produit jamais de contenu partiellement redigé ni de signal de récence partiel.

## Traitements et interactions

Les alternatives (hors Git, incident, budget, calcul) sont déjà dessinées dans `docs/developer/git-context.md#openspec-git-recency-diagnostics` (Vue UML séquence, existant). Aucune interaction nouvelle n'est introduite.

## États et transitions

Non applicable : aucun cycle de vie.

## Contrats d'interface

Non applicable : aucune API ni option nouvelle. Le refus de redaction reste `OversizedSecretException` ; `redactField` le convertit en `[REDACTED]` pour les champs publics courts (`SensitiveContentRedactor.java:122-131`).

## Persistance et intégrité

Non applicable : aucun format persisté ne change.

## Transactions, concurrence et idempotence

Non applicable : les deux traitements sont purs vis-à-vis de l'entrée.

## Sécurité et isolation

[Exigence] Le refus ne divulgue aucune partie de la valeur (test existant pour le seuil de 4 096 : `SensitiveContentRedactorTest#rejectsOversizedValuesWithoutPublishingAnySuffix`). Les scénarios « Partie utilisateur d'URL trop longue » et « Jeton à préfixe trop long » étendent cette vérification aux bornes étroites.

## Erreurs et reprise

Récence : fail-open, candidats inchangés. Redaction : refus, géré par l'appelant (exclusion du fichier des index lexical et sémantique à l'indexation, `IndexDocumentPublisher.java:111-117`).

## Observabilité

[Existant vérifié] Le dépassement de budget de récence ne produit pas de `WARNING` (seule la raison d'explication) ; le refus de redaction à l'indexation produit le diagnostic `INDEX_REDACTION_LIMIT` sans valeur.

## Performance et mesure

Non applicable : aucune performance n'est exigée ; les bornes limitent le travail mais aucun gain n'est revendiqué.

## Compatibilité, migration et retour arrière

Non applicable : aucun changement de code ni de format. Retour arrière : ne pas synchroniser les deltas.

## Décisions

- [Décision] Les deux ajouts sont des exigences ADDED, sans MODIFIED : le texte existant n'est pas réécrit.
- [Proposition] Les chiffres entrent dans le texte des exigences seulement si la première question de la proposition les déclare contractuels.
- Aucun ADR : la décision n'est ni structurante ni coûteuse à inverser.

## Risques / Compromis

- [Risque] Figer des bornes d'implémentation → question de la proposition avant synchronisation.
- [Risque] Les bornes par préfixe de jeton divergent (`sk-` a une borne composée) → l'exigence renvoie à « la borne de son motif » plutôt qu'à un nombre unique.

## Stratégie de vérification

- Tests existants : `GitRecencyCandidateEnricherTest#failsOpenWhenCandidatePathBudgetIsExceeded`, `#computesRecencyWithoutBudgetReasonAtExactlyTheCandidatePathBudget`.
- Tests à écrire : bornes de commit (1 024) et cumulée (4 096) ; partie utilisateur d'URL à 1 024 et 1 025 ; jeton `ghp_` de 255 et 256 caractères.
- Contrôles : `openspec validate preciser-bornes-redaction-recence-git --strict`, `bash .claude/scripts/contracts.sh`.

## Traçabilité

| Exigence (`capacité › titre`) | Scénario | Conception ou diagramme | Tâche | Test ou preuve |
|---|---|---|---|---|
| `git-recency-diagnostics › Le signal de récence Git est borné en chemins` | Plus de 512 chemins candidats | [git-context.md](../../../docs/developer/git-context.md#openspec-git-recency-diagnostics) | 1.1 | `core/src/test/java/com/nexus/context/source/git/GitRecencyCandidateEnricherTest.java#failsOpenWhenCandidatePathBudgetIsExceeded` (l'absence de `WARNING` n'est pas vérifiée : à compléter par 1.1) |
| `git-recency-diagnostics › Le signal de récence Git est borné en chemins` | Exactement 512 chemins candidats | [git-context.md](../../../docs/developer/git-context.md#openspec-git-recency-diagnostics) | 1.1 | `core/src/test/java/com/nexus/context/source/git/GitRecencyCandidateEnricherTest.java#computesRecencyWithoutBudgetReasonAtExactlyTheCandidatePathBudget` |
| `git-recency-diagnostics › Le signal de récence Git est borné en chemins` | Commit au-delà de 1 024 chemins | [git-context.md](../../../docs/developer/git-context.md#openspec-git-recency-diagnostics) | 1.2 | test non écrit |
| `secret-redaction › Les bornes étroites de redaction refusent le contenu` | Partie utilisateur d'URL trop longue | [secret-redaction.md](../../../docs/developer/secret-redaction.md#openspec-secret-redaction) | 2.1 | test non écrit |
| `secret-redaction › Les bornes étroites de redaction refusent le contenu` | Partie utilisateur d'URL à la borne | [secret-redaction.md](../../../docs/developer/secret-redaction.md#openspec-secret-redaction) | 2.1 | test non écrit |
| `secret-redaction › Les bornes étroites de redaction refusent le contenu` | Jeton à préfixe trop long | [secret-redaction.md](../../../docs/developer/secret-redaction.md#openspec-secret-redaction) | 2.2 | test non écrit |

## Questions ouvertes

Les trois questions qui conditionnent la synchronisation sont dans `proposal.md` : elles changent le texte des exigences, elles ne sont donc pas déférables.
