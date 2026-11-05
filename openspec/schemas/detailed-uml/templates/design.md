# Design

<!-- Profil : `complet` (changement fonctionnel ou architectural) ou `court` (documentaire, outillage).
     Étiquettes : [Exigence] [Existant vérifié] [Décision] [Proposition] [Question] [Écart].
     Une section sans objet reste présente avec « Non applicable : <fait qui le justifie> ».
     Les diagrammes Mermaid répondent à une question précise, nomment leur type réel
     (UML, C4, entité-relation, organigramme) et citent les exigences concernées. -->

**Profil :** <!-- complet | court -->

## Contexte

<!-- État actuel et contraintes utiles à l'approche, avec sources (`chemin:ligne`, test, document).
     Composants impactés. Motivation : voir proposal.md, sans la répéter. -->

## Objectifs / Hors objectifs

**Objectifs :**
<!-- Ce que cette conception atteint, avec les exigences visées (`capacité › titre`). -->

**Hors objectifs :**
<!-- Limites propres à la conception, sans répéter le périmètre de la proposition. -->

## Composants et responsabilités

<!-- Frontières, responsabilités et sens des dépendances. Vue de composants ou C4 si la structure change.
     Distinguer l'existant de la cible. -->

## Modèle de domaine et invariants

<!-- Entités, relations, cardinalités, invariants. classDiagram (UML) si utile. -->

## Traitements et interactions

<!-- Interactions nominales et alternatives d'erreur : sequenceDiagram (UML) ou flowchart (organigramme).
     Nommer les exigences couvertes. -->

## États et transitions

<!-- Cycle de vie : stateDiagram-v2 (UML) si une entité change d'état. -->

## Contrats d'interface

<!-- Référencer le contrat exécutable existant (OpenAPI, schéma JSON, port Java, CLI, outil MCP).
     API : opération et chemin, authentification et autorisations, champs requis ou facultatifs,
     types et contraintes, exemples de requête et de réponse, erreurs et codes,
     pagination, tri, filtrage, compatibilité, idempotence. -->

## Persistance et intégrité

<!-- Tables, contraintes, migrations. erDiagram (entité-relation) si le schéma change. -->

## Transactions, concurrence et idempotence

<!-- Frontières transactionnelles, verrous, rejeu. -->

## Sécurité et isolation

<!-- Confinement, droits d'accès, données sensibles, redaction. -->

## Erreurs et reprise

<!-- Erreurs attendues, diagnostics publics, reprise après échec. -->

## Observabilité

<!-- Journaux, métriques, diagnostics. -->

## Performance et mesure

<!-- Seulement des bornes sourcées et la stratégie de mesure. Ne pas inventer de seuil. -->

## Compatibilité, migration et retour arrière

<!-- Formats persistés, contrats publics, étapes de déploiement, retour arrière. -->

## Décisions

<!-- [Décision] ou [Proposition] avec alternatives écartées et compromis. Un ADR n'est justifié que pour
     une décision structurante, transversale, coûteuse à inverser ou à alternatives réelles :
     nommer l'ADR existant ou à écrire (skill `nexus-adr`). -->

## Risques / Compromis

<!-- [Risque] → mitigation. -->

## Stratégie de vérification

<!-- Tests existants, tests à écrire, commandes, contrats CI. Valider la syntaxe des diagrammes. -->

## Traçabilité

<!-- Une ligne par scénario. Écrire « test non écrit », « tâche non réalisée » ou « preuve manquante »
     quand c'est le cas. Références réelles uniquement. -->

| Exigence (`capacité › titre`) | Scénario | Conception ou diagramme | Tâche | Test ou preuve |
|---|---|---|---|---|
| | | | | |

## Questions ouvertes

<!-- [Question] qui peuvent attendre sans changer specs, approche ni tâches. Omettre s'il n'y en a pas. -->
