# Release, installation et recovery

> État Phase 6 + Hardening : intégré dans `develop` (PR #15 + issue #16). Qualification post-Phase 6 exécutée localement sous Windows le 2026-08-05 — gates A–D PASS, self-smoke 13/13. HEAD develop : `5a7a6f4`.

## Version produit

La Phase 6 prépare **NEXUS Context Engine 0.2.0**.

Le reactor Maven est piloté par le `pom.xml` racine et contient :

- `core` — `io.github.fturleque:nexus-context-engine:0.2.0` ;
- `adapters/rest-quarkus` ;
- `adapters/mcp-java` ;
- `adapters/assistant-clients`.

Maven doit être exécuté avec un JDK 21 ou supérieur ; le bytecode et les API restent ciblés sur Java 21 via `maven.compiler.release=21`. Maven est reproductible via `mvnw.cmd` / `mvnw`, épinglé sur Maven 3.9.11.

## Livrables

`mvnw.cmd clean install` produit notamment :

```text
target/
├── nexus-context-engine-0.2.0.jar
├── nexus-context-engine-0.2.0-cli.jar
├── nexus-context-engine-0.2.0-cli.jar.sha256
├── distribution/
│   ├── nexus-context-engine-0.2.0.zip
│   └── nexus-context-engine-0.2.0.zip.sha256
└── sbom/
    └── bom.json
```

L'archive autonome contient :

```text
nexus-context-engine-0.2.0/
├── bin/
│   ├── nexus.cmd
│   └── nexus
├── lib/
│   └── nexus-cli.jar
└── README.md
```

Elle fonctionne sans clone du dépôt et sans Maven installé sur la machine cible. Une JVM Java 21 ou supérieure reste requise.

## Intégrité et inventaire logiciel

Les deux livrables distribuables sont accompagnés de SHA-256. Le build génère aussi un SBOM CycloneDX agrégé de l'ensemble du reactor.

Aucun secret, requête utilisateur, contenu source ou chemin de projet n'est injecté dans le SBOM.

## Migration SQLite

SQLite reste la source canonique. Les migrations sont forward-only et enregistrées dans `schema_migrations`.

La Phase 6 ajoute `V002__index_generation.sql` :

- génération monotone par projet pour invalider les vues dérivées ;
- index SQL pour les recherches de relations ciblées ;
- initialisation des projets existants sans écraser leur état.

Avant une montée de version de production, sauvegarder le fichier SQLite NEXUS lorsque le service est arrêté. Ne jamais restaurer uniquement les index Lucene en ignorant SQLite.

## Recovery

Les index Lucene lexicaux et sémantiques sont des données dérivées et reconstructibles.

En cas de panne pendant une indexation :

1. le projet est normalement marqué `FAILED` ;
2. un crash brutal peut laisser `INDEXING` persistant ;
3. les recherches et ContextBundle refusent tout projet qui n'est pas `READY` ;
4. la prochaine indexation d'un projet non-`READY` force une reconstruction complète ;
5. le verrou single-flight protège uniquement les indexations concurrentes réellement actives dans le processus courant.

En cas de corruption d'un index Lucene, supprimer uniquement l'index dérivé puis lancer un rebuild. En cas de corruption SQLite, restaurer la sauvegarde canonique puis reconstruire les index dérivés.

## Qualification release

Sous Windows, la source de vérité Phase 6 est :

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\validate-phase-6.ps1
```

Le script impose :

- une JVM d'exécution Java 21 ou supérieure et `maven.compiler.release=21` ;
- Maven Wrapper fonctionnel ;
- `clean install` du reactor complet ;
- `scripts/self-smoke.ps1` ;
- existence et vérification des SHA-256 ;
- SBOM CycloneDX ;
- extraction et exécution Windows de l'archive autonome, contrôle POSIX local et smoke réel dans la CI Linux ;
- branche exact-head `phase-6-consolidation-hardening`.

Une release, un tag ou un merge ne doit pas être déclaré qualifié sans le log `=== PHASE 6 PASS ===` du head concerné.
