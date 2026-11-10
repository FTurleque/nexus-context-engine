# Plan d'action — audit du 17 octobre 2026

Classement par **urgence**. Pour l'**ordre d'exécution**, qui prime en cas de divergence, voir [`sprints.md`](sprints.md) : une action urgente dont le prérequis n'est pas livré ne peut pas être vérifiée.

État au 9 novembre 2026 : 82 constats fermés sur 100 actionnables ; ce plan ne liste que les 18 non fermés.

## Immédiat — partiels et Moyennes rapides (4)

Aucun constat Critique ni Élevé. Les quatre constats partiels, puis les Moyennes d'effort S :

| ID | Sévérité | Effort | Sprint | Statut | Dépend de | Constat |
|---|---|---|---|---|---|---|
| `AUD-QUA-22` | Élevée | S | S13 | Ouvert | aucun | L'installateur réécrit les configurations MCP des clients IA sans sauvegarde : chemins accentués corrompus, JSON invalide remplacé par un fichier vide |
| `AUD-TST-03` | Moyenne | S | S1 | Partiel | aucun | Gates JaCoCo au seul niveau BUNDLE, seuils effectifs différents de la doc : 6 classes de sécurité ou de bornes sous 55 % de branches passent sans alerte |
| `AUD-PRF-27` | Moyenne | S | S6 | Ouvert | aucun | La publication du staging pagine index_staged_files sans index (project_id, id) : coût quadratique, 5,6 s à 100 000 fichiers sous verrou d'écriture |
| `AUD-PRF-29` | Moyenne | S | S6 | Ouvert | aucun | La migration V015 (préfiltre approché scopé par fichier) ralentit la recherche de symboles d'un gros projet seul dans sa base : 0,10 → 0,36 s à 100 000 fichiers |

## Court terme — sprints 6, 7, 8 et 12 (10)

Les restes des sprints 6 à 8 et le durcissement des gates ajoutés (sprint 12).

| ID | Sévérité | Effort | Sprint | Statut | Dépend de | Constat |
|---|---|---|---|---|---|---|
| `AUD-PRF-28` | Faible | S | S6 | Ouvert | aucun | IndexRepository.applyChanges est mort en production mais reste le chemin mesuré par les benchmarks de réindexation |
| `AUD-QUA-23` | Faible | S | S6 | Ouvert | aucun | La clé du vecteur de requête partagé ignore le texte de la requête : un SearchQueryContext réutilisé rendrait le vecteur de la requête précédente |
| `AUD-ARC-20` | Faible | S | S7 | Ouvert | aucun | Le test d'architecture ne lit que les imports : 39 références pleinement qualifiées entre packages échappent au contrôle des cycles |
| `AUD-TST-16` | Faible | S | S7 | Ouvert | aucun | Aucun test ne couvre un échec entre le commit Lucene et la publication SQLite, ni l'échec du commit sémantique après le lexical |
| `AUD-SEC-20` | Faible | S | S8 | Ouvert | aucun | Un jeton sk- dont le corps fait 256 à 260 caractères n'est ni redigé ni refusé (bornes 255 et 261 désalignées) |
| `AUD-DEP-32` | Faible | S | S12 | Ouvert | aucun | workflow_hygiene.py ne lit pas toutes les formes YAML (run: multi-lignes simple, flow mapping, clés citées, with: script:) ni les actions composites |
| `AUD-DEP-33` | Faible | S | S12 | Ouvert | aucun | Le contrôle des épinglages compare les commentaires entre eux, pas au tag amont : AUD-DEP-10 peut revenir sans alerte |
| `AUD-QUA-21` | Faible | S | S12 | Ouvert | aucun | Le contrat « Maven uniquement via NexusScript.psm1 » se contourne par indirection ($m = "mvn" ; & $m, cmd /c mvn) |
| `AUD-TST-15` | Faible | S | S12 | Ouvert | aucun | Planchers JaCoCo déjà loin de la mesure (api 78/67 contre 88,4/73,7 ; security 85/77 contre 91,7/82,9), contrairement à la doc « 2 à 3 points sous la mesure » |
| `AUD-TST-17` | Faible | S | S12 | Ouvert | aucun | Les 40 règles Archgate (*.rules.ts) ne tournent dans aucun workflow et leur outillage n'est pas épinglé |

## Fond — autres (4)

Le reste : installateur et publication (sprint 13, hors constats immédiats), mesures et finitions.

| ID | Sévérité | Effort | Sprint | Statut | Dépend de | Constat |
|---|---|---|---|---|---|---|
| `AUD-DEP-34` | Faible | S | S13 | Ouvert | aucun | Des tags GHCR :0.2.0 et :latest posés le 9 août par l'ancienne chaîne Docker existent hors de la release et bloqueront le préflight immuable de la release finale v0.2.0 |
| `AUD-DEP-35` | Faible | S | S13 | Ouvert | aucun | Les labels OCI revision et version sont vides dans l'image construite par l'installateur Windows |
| `AUD-QUA-24` | Faible | S | S13 | Ouvert | aucun | L'échappement PowerShell de l'installateur ne double pas les apostrophes typographiques : un chemin contenant « ’ » casse les scripts générés |
| `AUD-QUA-25` | Faible | S | S13 | Ouvert | aucun | 9 liens relatifs cassés dans le changement OpenSpec archivé le 09/11, que le contrat OpenSpec ne contrôle pas |
