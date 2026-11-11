# Plan d'action — audit du 17 octobre 2026

Classement par **urgence**. Pour l'**ordre d'exécution**, qui prime en cas de divergence, voir [`sprints.md`](sprints.md) : une action urgente dont le prérequis n'est pas livré ne peut pas être vérifiée.

État au 11 novembre 2026 : 101 constats fermés sur 109 actionnables ; ce plan ne liste que les 8 non fermés.

## Immédiat — Élevée et Moyennes rapides (2)

Le constat Élevé du 11/11, puis les Moyennes d'effort S :

| ID | Sévérité | Effort | Sprint | Statut | Dépend de | Constat |
|---|---|---|---|---|---|---|
| `AUD-QUA-28` | Élevée | M | S14 | Ouvert | aucun | L'installateur écrit la configuration native, le .env Docker et le fragment TOML de Codex dans la page ANSI : avec un profil accentué, NEXUS_HOME par défaut est introuvable |
| `AUD-SEC-24` | Moyenne | S | S14 | Ouvert | aucun | Le motif JWT de la redaction coûte environ 12 s par Mo sur une entrée forgée « eyJ- » répétée : un fichier de 8 Mo bloque l'indexation |

## Court terme — sécurité et release (3)

Redaction XML, gate YAML et réglages GitHub de la release.

| ID | Sévérité | Effort | Sprint | Statut | Dépend de | Constat |
|---|---|---|---|---|---|---|
| `AUD-DEP-36` | Faible | S | S14 | Ouvert | aucun | workflow_hygiene.py ignore les clés écrites avec un espace avant « : » et les corps de job indentés autrement que de 2 |
| `AUD-DEP-37` | Faible | S | S14 | Ouvert | aucun | La release GitHub v0.2.0 n'est pas immuable et le tag v0.2.0 a été recréé par le contournement admin du ruleset des tags |
| `AUD-SEC-25` | Faible | S | S14 | Ouvert | aucun | Un élément XML au nom composé par « - » ou « _ » (<db-password>, <db_password>) n'est pas redigé |

## Fond — autres (3)

Le reste : contrat Maven, plafond de charge et documentation.

| ID | Sévérité | Effort | Sprint | Statut | Dépend de | Constat |
|---|---|---|---|---|---|---|
| `AUD-QUA-26` | Faible | S | S14 | Ouvert | aucun | Le contrat « Maven via NexusScript.psm1 » ne voit pas un appel direct à mvn hors début de ligne |
| `AUD-QUA-27` | Faible | S | S14 | Ouvert | aucun | Guide de reproduction encore sur des JAR 0.1.0-SNAPSHOT à la racine alors que la version publiée est 0.2.0 |
| `AUD-TST-18` | Faible | S | S14 | Ouvert | aucun | Plafond de peuplement 1M relâché après l'échec de la release v0.2.0 : hors PR, un ralentissement uniforme jusqu'à 52 s passe, et la page affirme encore « plafonds inchangés » |
