---
id: ARCH-0030
title: "Stabiliser le contrat CLI avec sorties humaines, JSON et codes de sortie"
domain: architecture
rules: true
files: ["core/src/main/java/**"]
---

# Stabiliser le contrat CLI avec sorties humaines, JSON et codes de sortie

- **Statut** : Accepted
- **Date** : 2026-07-19

## Context

Les Itérations 1 à 3 ont progressivement exposé les capacités de NEXUS via `NexusCli` : registre de projets, indexation, inspection, recherche et construction de contexte. La CLI sert à la fois de moyen de validation du moteur et de premier adaptateur consommable par un humain.

L'Itération 4 doit rendre cette surface exploitable par des scripts et outils externes sans transformer la CLI en API métier parallèle.

Plusieurs besoins apparaissent :

- conserver une sortie lisible pour le développeur ;
- fournir une sortie structurée et déterministe pour les scripts ;
- distinguer clairement données normales et erreurs ;
- fournir des codes de sortie stables ;
- rendre la logique CLI testable sans appeler `System.exit` au milieu des tests ;
- éviter que les DTO ou détails JSON contaminent le cœur métier.

## Facteurs de décision

- NEXUS doit rester indépendant des consommateurs et fournisseurs IA ;
- la CLI est un adaptateur, pas le lieu de la logique métier ;
- les sorties machine doivent être sans ambiguïté et faciles à parser ;
- la sortie humaine existante doit rester le comportement par défaut ;
- les erreurs doivent être exploitables par PowerShell, scripts CI et futurs wrappers ;
- le format JSON ne doit pas imposer Jackson ou un format spécifique aux packages métier.

## Options envisagées

1. conserver uniquement les sorties texte actuelles ;
2. remplacer toutes les sorties par JSON ;
3. proposer texte par défaut et JSON opt-in avec `--json` ;
4. introduire immédiatement un framework CLI complet et des DTO publics versionnés.

## Decision

**Option retenue : conserver une sortie humaine par défaut et ajouter une sortie JSON opt-in avec `--json`, avec codes de sortie stables et séparation stdout/stderr.**

Règles :

- sans `--json`, la CLI conserve une sortie destinée au développeur ;
- avec `--json`, chaque commande émet un document JSON unique sur `stdout` ;
- les erreurs sont émises sur `stderr` ;
- en mode JSON, les erreurs sont elles-mêmes structurées ;
- code `0` : succès ;
- code `2` : erreur d'utilisation ou arguments invalides ;
- code `1` : erreur d'exécution NEXUS ou erreur inattendue ;
- la logique principale retourne un code de sortie testable ; seul `main` appelle `System.exit` lorsque nécessaire ;
- les objets métier sont convertis à la frontière CLI vers des structures sérialisables ;
- Jackson est utilisé uniquement comme infrastructure de sérialisation de l'adaptateur CLI.

Le flag `--json` est traité comme une option globale et peut être placé avec les options de la commande.

## Do's and Don'ts

### Do

- Garder la sortie humaine par défaut et émettre, avec `--json`, un document JSON unique sur `stdout`.
- Émettre les erreurs sur `stderr`, structurées en mode JSON.
- Retourner `0` en cas de succès, `2` pour une erreur d'utilisation ou d'arguments invalides, `1` pour une erreur d'exécution ou inattendue.
- Convertir les objets métier à la frontière CLI vers des structures sérialisables.

### Don't

- Ne pas appeler `System.exit` ailleurs que dans `main`.
- Ne pas utiliser Jackson hors de l'infrastructure de sérialisation de l'adaptateur CLI.

## Conséquences positives

- scripts et outils externes peuvent consommer la CLI sans parser du texte localisé ;
- le comportement humain existant reste disponible ;
- les tests peuvent appeler la CLI sans tuer la JVM ;
- les futurs adaptateurs REST/MCP pourront conserver leurs propres DTO sans dépendre du format CLI ;
- stdout reste réservé aux données de succès et stderr aux diagnostics d'échec.

## Conséquences négatives et compromis acceptés

- deux formats de sortie doivent être maintenus ;
- le contrat JSON devient une surface à faire évoluer avec prudence ;
- une dépendance de sérialisation est ajoutée au module Maven actuel ;
- le parsing manuel des arguments reste présent pour le MVP.

## Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| divergence entre sortie humaine et JSON | Moyen | construire les deux sorties depuis les mêmes résultats métier |
| JSON contenant des chemins absolus | Élevé | normaliser les chemins de résultats par rapport à la racine du projet |
| bruit de logs sur stdout | Élevé | réserver stdout au résultat de commande ; diagnostics sur stderr |
| dépendance Jackson dans le cœur | Moyen | imports Jackson limités au package/adaptateur CLI |
| changement accidentel de code de sortie | Moyen | tests dédiés aux succès et erreurs d'utilisation |

## Confirmation

La décision est respectée lorsque :

- toutes les commandes MVP acceptent `--json` ;
- une commande JSON produit un document JSON parseable unique ;
- les erreurs d'arguments retournent `2` ;
- les erreurs d'exécution retournent `1` ;
- les tests peuvent invoquer la CLI avec des flux injectés ;
- aucune classe du cœur `index`, `search`, `ranking`, `context` ou `project` ne dépend de Jackson.

## Analyse détaillée des options

### Texte uniquement

**Avantages :** aucun changement et lecture simple.

**Inconvénients :** fragile pour l'automatisation, dépendant de la langue et des changements de présentation.

### JSON uniquement

**Avantages :** contrat machine simple.

**Inconvénients :** expérience développeur médiocre et rupture avec les usages actuels.

### Texte + JSON opt-in

**Avantages :** combine ergonomie humaine et interopérabilité machine.

**Inconvénients :** deux renderers à maintenir.

### Framework CLI complet immédiatement

**Avantages :** parsing et aide sophistiqués.

**Inconvénients :** dépendance et complexité supplémentaires avant d'avoir démontré le besoin. Le parsing actuel reste limité et maîtrisable pour le MVP.

## Impact architectural

```text
NexusCli
  ├── parse arguments
  ├── invoke core services
  └── render
       ├── Human output
       └── JSON output

Core services
  └── aucune connaissance du format CLI
```

## Conditions de réévaluation

Réévaluer si :

- le nombre de commandes/options rend le parsing manuel difficile à maintenir ;
- un contrat CLI public versionné nécessite des DTO dédiés plus formels ;
- plusieurs plateformes imposent une bibliothèque CLI spécialisée.

## Compliance and Enforcement

- Règle Archgate `ARCH-0030-stabiliser-le-contrat-cli-avec-sorties-humaines-json-et-codes-de-sortie.rules.ts` / `cli-only-main-calls-system-exit` : dans `core/src/main/java`, commentaires et littéraux effacés, `System.exit` (ou `Runtime.exit`/`halt`) n'apparaît que dans le corps de `NexusCli.main`.
- Tests `NexusCliTest` (`rendersHelpAsJsonWithoutInitializingAProject`, `returnsStructuredUsageErrorForUnknownCommand`, `executesTheMvpFlowThroughStableJsonOutputs`, `core/src/test/java/com/nexus/cli`) ; tests lancés par `./mvnw -B clean install` (job « Linux reactor Maven build » de `.github/workflows/ci.yml`).
- Confinement de Jackson à l'adaptateur CLI : aucun contrôle automatisé, la décision n'étant pas respectée (voir « Écarts constatés »).

## Écarts constatés

Constat du 2026-10-29 (la décision ci-dessus est inchangée) :

- Jackson est importé hors de l'adaptateur CLI (`com.nexus.cli`), y compris dans les packages `index` et `search` que la confirmation exclut : `core/src/main/java/com/nexus/index/jdt/JdtDocumentMessages.java:3`, `JdtJsonRpcFrameReader.java:3`, `JdtLanguageServerCodeIntelligenceProvider.java:6`, `JdtLocationMapper.java:5`, `JdtRelationCollector.java:3`, `JdtStdioSession.java:3`, `JdtSymbolMapper.java:3`, `JdtWorkspaceMessages.java:3` ; `core/src/main/java/com/nexus/index/minos/MinosCodeIndexImporter.java:4`, `MinosDocumentParser.java:3`, `MinosFactMapper.java:5` ; `core/src/main/java/com/nexus/search/semantic/ollama/OllamaEmbeddingProvider.java:3` ; `core/src/main/java/com/nexus/persistence/sqlite/SqliteIndexSql.java:3`.

## Décisions liées

- ADR-0003 — conserver un cœur Java sans framework applicatif ;
- ADR-0015 — valider le MVP par la CLI avant les intégrations ;
- ADR-0017 — découpler NEXUS des outils externes ;
- ADR-0013 — construire un `ContextBundle` sous budget de tokens.
