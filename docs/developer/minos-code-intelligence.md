# Intégration MINOS Code Intelligence

Statut historique : **terminée, validée et livrée le 24 juillet 2026** via NEXUS issue #11 / PR #12.

Phase 6 conserve la frontière MINOS/NEXUS et optimise la validation des chemins dans le chemin applicatif.

## Responsabilités

```text
MINOS → faits de code, symboles, relations, provenance
NEXUS → persistance, recherche, ranking, sélection, budget, ContextBundle
```

NEXUS ne lance jamais MINOS, n'a aucune dépendance `com.minos`, ne configure aucun JAR MINOS et n'exige aucun réseau.

## Frontière Java

```text
MINOS Java 24
  nexus-export --root <project>
       │ JSON stdout
       ▼
NEXUS Java 21
  minos-import <project> < stdin
       ▼
SQLite → search/ranking/context
```

## Commande

```text
nexus minos-import <id-ou-nom> < export-minos.json [--json]
```

Le payload est borné à 128 MiB. Le projet doit désormais être `READY` avant remplacement du snapshot MINOS, comme les autres opérations dépendant d'un index cohérent.

## Contrat et chemins

NEXUS exige :

```text
contractVersion = 1
producer        = MINOS
```

La racine exportée doit correspondre à la racine canonique du projet ciblé.

Pour chaque `filePath` :

- chemin relatif obligatoire ;
- `..` refusé ;
- normalisation obligatoire ;
- présence dans une allow-list canonique ;
- aucune ouverture arbitraire d'un chemin fourni par le JSON.

### Optimisation Phase 6

Le chemin applicatif :

```java
NexusApplication.importMinos(...)
```

construit désormais l'allow-list depuis :

```text
IndexRepository.findFiles(projectId).keySet()
```

puis appelle :

```java
MinosCodeIndexImporter.importPayload(root, indexedFiles, payload)
```

Il n'effectue donc plus un `Files.walk`/`toRealPath` sur tout le repository pour valider le payload. La surcharge historique à deux arguments reste disponible pour tests/outils autonomes et conserve son comportement filesystem explicite.

## Mapping

Symboles représentables : CLASS, INTERFACE/TRAIT, RECORD, ENUM, ANNOTATION, METHOD/FUNCTION, CONSTRUCTOR et TYPE/STRUCT/TYPE_ALIAS.

Relations représentables : IMPORTS, EXTENDS, IMPLEMENTS, CALLS, REFERENCES, TYPE_DEFINITION et DEFINITION→DEFINITION_OF.

Seuls les faits `RESOLVED` représentables sont promus. La provenance reste :

```text
sourceProvider=minos
```

Une relation `FACTUAL` sans confiance explicite reçoit 1.0 ; une relation dérivée sans confiance explicite est rejetée.

## Qualification historique

La livraison initiale avait produit :

```text
Java 21.0.10 LTS Microsoft
Maven 3.9.11
80 tests
0 failure / 0 error / 6 skipped
BUILD SUCCESS
Sonar Quality Gate Passed
```

Replay réel : 11 symboles, 6 relations, symbole `GreetingPort` retrouvé avec provenance `minos`.

La Phase 6 ne modifie pas le contrat JSON MINOS ; elle modifie uniquement l'utilisation de l'état canonique NEXUS et le gate READY. Ces changements seront qualifiés via :

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\validate-phase-6.ps1
```

Décision historique : [ADR-0044](../adr/0044-consommer-minos-via-un-contrat-json-local-versionne.md).
