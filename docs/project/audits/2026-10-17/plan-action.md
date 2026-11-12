# Plan d'action — audit du 17 octobre 2026

Classement par **urgence**. Pour l'**ordre d'exécution**, qui prime en cas de divergence, voir [`sprints.md`](sprints.md) : une action urgente dont le prérequis n'est pas livré ne peut pas être vérifiée.

État au 12 novembre 2026 : 109 constats fermés sur 113 actionnables ; ce plan ne liste que les 4 non fermés.

## Immédiat — Élevée et Moyennes rapides (0)

Aucun constat Critique, Élevé ni Moyen ouvert.

| ID | Sévérité | Effort | Sprint | Statut | Dépend de | Constat |
|---|---|---|---|---|---|---|

## Court terme — sécurité et release (2)

Redaction et chaîne de release.

| ID | Sévérité | Effort | Sprint | Statut | Dépend de | Constat |
|---|---|---|---|---|---|---|
| `AUD-DEP-38` | Faible | S | S15 | Ouvert | aucun | Code 79 posé après la publication : une version finale non immuable reçoit déjà le badge Latest GitHub, contrairement à release-and-recovery.md |
| `AUD-SEC-26` | Faible | S | S15 | Ouvert | aucun | Un marqueur « -----BEGIN » incomplet placé avant une clé privée fait sauter le vrai bloc : la clé reste en clair |

## Fond — autres (2)

Le reste : installateur et documentation.

| ID | Sévérité | Effort | Sprint | Statut | Dépend de | Constat |
|---|---|---|---|---|---|---|
| `AUD-QUA-29` | Faible | S | S15 | Ouvert | aucun | L'assistant refuse « ^ » dans NEXUS_HOME, y compris sur la page Runtime sautée : un profil Windows contenant ^ bloque le mode Docker (installation silencieuse figée sur une MsgBox) |
| `AUD-QUA-30` | Faible | S | S15 | Ouvert | aucun | Le guide de reproduction et le pipeline d'indexation prescrivent encore `mvn` direct (Maven non ancré) alors que le dépôt impose le wrapper |
