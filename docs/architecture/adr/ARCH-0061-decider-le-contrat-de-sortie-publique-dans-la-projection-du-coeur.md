---
id: ARCH-0061
title: "Décider le contrat de sortie publique dans la projection du cœur"
domain: architecture
rules: false
---

# Décider le contrat de sortie publique dans la projection du cœur

- **Statut** : Proposed
- **Date** : 2026-10-30
- **Origine** : audit du 2026-10-17, constats AUD-QUA-09 (avec AUD-ARC-08), AUD-QUA-01 et AUD-SEC-03 ; changement OpenSpec `sprint-04-sortie-publique-et-redaction`
- **Complète** : [ARCH-0030](ARCH-0030-stabiliser-le-contrat-cli-avec-sorties-humaines-json-et-codes-de-sortie.md), [ARCH-0039](ARCH-0039-isoler-l-adaptateur-rest-quarkus-du-coeur-nexus.md), [ARCH-0040](ARCH-0040-exposer-nexus-via-un-adaptateur-mcp-stdio-mince.md)

## Context

[ARCH-0039](ARCH-0039-isoler-l-adaptateur-rest-quarkus-du-coeur-nexus.md) et [ARCH-0040](ARCH-0040-exposer-nexus-via-un-adaptateur-mcp-stdio-mince.md) font de REST et de MCP des adaptateurs minces qui délèguent à `NexusApplication`. Elles ne disent pas qui décide de la forme des réponses. L'audit du 2026-10-17 relève, sur `origin/develop` :

- trois projections de sortie écrites à part : `CliResultMapper` (103 lignes), `McpResultMapper` (159 lignes) et `ApiMapper` (180 lignes), chacune appelant `PublicContextPolicy.expose` et calculant ses chemins ;
- des clés différentes pour les mêmes objets : `excerpt` et `id` absents de la CLI et de REST, `rank` absent de MCP, `sourceProvider` absent de la CLI, `rootPath` présent à `null` sur la CLI et REST mais absent de MCP, résultats fédérés aplatis sur la CLI et imbriqués ailleurs ;
- `scoreComponents` transmis tel que `Map.copyOf` le produit par MCP et REST, dont l'ordre change d'une JVM à l'autre (AUD-QUA-01) ;
- `signature` et `qualifiedName` fournis par SCIP, MINOS ou JDT exposés sans redaction sur les trois canaux (AUD-SEC-03).

La règle de parité CLI/REST/MCP (`.claude/rules/adapters.md`) reposait sur la relecture de ces trois fichiers.

## Options envisagées

- **Option A — Projection unique dans le cœur, en records sérialisés tels quels** : un package `com.nexus.exposure` produit des records dont l'ordre des composantes est l'ordre des clés JSON ; les trois canaux, qui utilisent tous Jackson, les sérialisent sans les recomposer. Les divergences admises sont déclarées par le cœur. Coût : un changement de contrat ponctuel pour les clients qui dépendaient d'une divergence.
- **Option B — Projection en `Map` ordonnées** : même centralisation, mais la forme n'est plus typée et les adaptateurs peuvent ajouter une clé sans que le compilateur le voie.
- **Option C — Garder trois mappers et ajouter un test de parité** : aucun changement de contrat, mais chaque correctif de sortie (ordre, redaction, chemins) reste à écrire trois fois.

## Decision

**Option retenue : Option A.**

- `com.nexus.exposure.PublicOutputProjection` est le seul point qui construit une réponse publique de recherche, de recherche fédérée, de contexte, de contexte fédéré, d'indexation, de projet, de symbole et de relation. Il produit les records de `com.nexus.exposure.PublicOutput`.
- La projection applique la politique de sortie : chemins relatifs au projet par `PublicProjectPathPolicy`, diagnostics filtrés par `PublicDiagnosticPolicy`, `PublicContextPolicy` pour les contextes, redaction de `excerpt`, `id`, `qualifiedName`, `signature`, `source` et `target`, tri par clé de `scoreComponents` et des métadonnées.
- Le contrat est le plus complet et le plus sûr des trois canaux : `rank`, `id` et `excerpt` sur chaque résultat classé, `sourceProvider` sur chaque symbole, résultats fédérés imbriqués (`project`, `result` ou `item`), aucune clé `rootPath`.
- La CLI, REST et MCP sérialisent ces records sans ajouter, retirer, renommer ni réordonner de clé. Le rendu humain de la CLI lit les mêmes records.
- Les seules divergences sont déclarées par `com.nexus.exposure.PublicOutputSchema` : la clé `command` en tête des objets JSON de la CLI, et `find_symbol` et `find_usages`, outils MCP sans équivalent CLI ni REST.
- La décision ne change ni la sélection des résultats ni la persistance : la redaction des métadonnées de symbole se fait à la sortie.

## Do's and Don'ts

### Do

- Construire toute réponse publique CLI, REST ou MCP par `PublicOutputProjection`.
- Ajouter un champ public en ajoutant une composante au record de `PublicOutput` concerné, pour les trois canaux à la fois.
- Déclarer dans `PublicOutputSchema` toute divergence voulue entre canaux.
- Trier par clé toute map exposée.

### Don't

- Ne pas recomposer, compléter ou renommer dans un adaptateur une structure produite par la projection.
- Ne pas appeler `PublicContextPolicy`, `PublicProjectPathPolicy` ou `SensitiveContentRedactor` depuis un adaptateur pour façonner une réponse.
- Ne pas exposer une map dans son ordre d'itération interne.

## Consequences

### Positives

- Un correctif de sortie (ordre, chemins, redaction) s'écrit une fois et vaut pour les trois canaux.
- La parité CLI/REST/MCP est vérifiée par des tests au lieu de la relecture.
- Deux exécutions de la même requête produisent le même JSON, aux durées près.

### Négatives

- Les clients qui dépendaient d'une divergence voient leur contrat changer : la CLI gagne `id`, `excerpt`, `explain` et `sourceProvider` et imbrique ses résultats fédérés, REST gagne `id` et `excerpt`, MCP gagne `rank`, la CLI et REST perdent `rootPath`.
- Le cœur porte les noms des clés publiques des trois canaux.

### Risques

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Une composante ajoutée à un record de `PublicOutput` expose une donnée sans redaction | Élevé | La projection est le seul constructeur des records ; la revue vérifie la politique appliquée à chaque nouvelle composante |
| Jackson change l'ordre de sérialisation des records | Faible | Les tests de parité comparent l'ordre des clés de chaque canal au contrat |

## Compliance and Enforcement

- `PublicOutputProjectionTest` (cœur) : chemins relatifs, `excerpt` redigé, `id`, tri de `scoreComponents` pour deux ordres d'insertion, redaction des métadonnées de symbole, détection d'une clé ajoutée, retirée ou déplacée par `PublicOutputSchema`.
- `CliOutputParityTest` (cœur), `NexusRestOutputParityTest` (REST) et `NexusMcpOutputParityTest` (MCP) : sur la même fixture, les clés JSON de chaque canal, et leur ordre, égalent le contrat de `PublicOutputSchema` pour la recherche, la recherche fédérée, le contexte et le contexte fédéré ; MCP vérifie aussi `find_symbol` et `find_usages`.
- Revue : aucun adaptateur ne construit de `Map` de réponse ni n'appelle une politique de sortie directement.

## Conditions de réexamen

La décision est réévaluée si un canal doit exposer une forme qu'aucun autre ne peut porter, ou si un quatrième canal public apparaît. Une modification substantielle donne lieu à un nouvel ADR qui remplace explicitement celui-ci.

## References

- [ARCH-0039](ARCH-0039-isoler-l-adaptateur-rest-quarkus-du-coeur-nexus.md), [ARCH-0040](ARCH-0040-exposer-nexus-via-un-adaptateur-mcp-stdio-mince.md)
- Changement OpenSpec `sprint-04-sortie-publique-et-redaction` (capability `public-output-projection`)
- [`docs/developer/rest-api.md`](../../developer/rest-api.md), [`docs/developer/mcp.md`](../../developer/mcp.md), [`docs/developer/cli.md`](../../developer/cli.md)
- Audit du 2026-10-17 : `docs/project/audits/2026-10-17/findings.md` (AUD-QUA-09, AUD-ARC-08, AUD-QUA-01, AUD-SEC-03)
