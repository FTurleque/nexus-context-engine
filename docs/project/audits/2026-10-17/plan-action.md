# Plan d'action — audit du 17 octobre 2026

Classement par **urgence**. Pour l'**ordre d'exécution**, qui prime en cas de divergence, voir [`sprints.md`](sprints.md) : une action urgente dont le prérequis n'est pas livré ne peut pas être vérifiée.

État au 15 novembre 2026 : 113 constats fermés sur 115 actionnables ; ce plan ne liste que les 2 non fermés.

## Immédiat — Élevée et Moyennes rapides (0)

Aucun constat Critique, Élevé ni Moyen ouvert.

| ID | Sévérité | Effort | Sprint | Statut | Dépend de | Constat |
|---|---|---|---|---|---|---|

## Court terme — sécurité et release (1)

Redaction.

| ID | Sévérité | Effort | Sprint | Statut | Dépend de | Constat |
|---|---|---|---|---|---|---|
| `AUD-SEC-27` | Faible | S | S16 | Ouvert | aucun | La reconnaissance des blocs de clé v10 laisse en clair deux formes que v9 masquait : un -----END suivi d'un -----BEGIN sur la même ligne, et un marqueur BEGIN coupé sur deux lignes |

## Fond — autres (1)

Le reste : installateur.

| ID | Sévérité | Effort | Sprint | Statut | Dépend de | Constat |
|---|---|---|---|---|---|---|
| `AUD-QUA-33` | Faible | S | S16 | Ouvert | aucun | Installation silencieuse en mode Docker bloquée par une MsgBox non supprimable après l'installation automatique de Docker Desktop |
