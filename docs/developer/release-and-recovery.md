# Release, installation et recovery

> État courant : Phase 6, hardening post-Phase 6, licence propriétaire, provenance des index et hardening CI/supply-chain sont intégrés dans `main`.

## Version produit

La baseline produit est **NEXUS Context Engine 0.2.0**.

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
├── licenses/
│   └── THIRD_PARTY_NOTICES.txt
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
├── LICENSE
├── THIRD_PARTY_NOTICES.txt
├── SBOM.cdx.json
└── README.md
```

Elle fonctionne sans clone du dépôt et sans Maven installé sur la machine cible. Une JVM Java 21 ou supérieure reste requise.

NEXUS est un logiciel **propriétaire source-available**. `LICENSE` est la source de vérité pour les droits accordés sur NEXUS. Les composants tiers restent soumis à leurs propres licences ; `THIRD_PARTY_NOTICES.txt` matérialise l'inventaire de licences compile/runtime utilisé pour la distribution.

## Intégrité et inventaire logiciel

Les deux livrables distribuables sont accompagnés de SHA-256.

Le build génère un SBOM CycloneDX agrégé et les notices tierces. Depuis PR #28 :

- `license-maven-plugin` s'exécute avec `failOnMissing=true` ;
- le ZIP embarque `LICENSE`, les notices et le SBOM ;
- la qualification Windows vérifie que les fichiers embarqués sont identiques aux fichiers générés ;
- la CI Linux vérifie leur présence et conserve SBOM/notices/JaCoCo pendant 90 jours dans un artefact nommé avec le head exact qualifié.

Aucun secret, requête utilisateur, contenu source ou chemin de projet n'est injecté dans le SBOM.

## Gates release et supply-chain

Le reactor applique un gate JaCoCo bloquant au module `core` :

- lignes : minimum 70 % ;
- branches : minimum 50 % ;
- baseline mesurée lors de PR #28 : 77,07 % lignes / 58,46 % branches.

GitHub Actions complète le reactor par :

- OSV-Scanner : nouvelles vulnérabilités bloquantes sur PR, scan courant/hebdomadaire ;
- CodeQL Java/Kotlin `security-extended` ;
- Dependabot Maven + GitHub Actions hebdomadaire ;
- pins immuables des Actions contrôlées dans le dépôt.

Voir [`ci-and-supply-chain.md`](ci-and-supply-chain.md).

## Migration SQLite

SQLite reste la source canonique. Les migrations sont forward-only et enregistrées dans `schema_migrations`.

La Phase 6 ajoute `V002__index_generation.sql` :

- génération monotone par projet pour invalider les vues dérivées ;
- index SQL pour les recherches de relations ciblées ;
- initialisation des projets existants sans écraser leur état.

Avant une montée de version de production, sauvegarder le fichier SQLite NEXUS lorsque le service est arrêté. Ne jamais restaurer uniquement les index Lucene en ignorant SQLite.

## Autorité et provenance des index

SQLite et l'état des fichiers canoniques restent l'autorité. Les index Lucene lexicaux et sémantiques ainsi que les snapshots d'intelligence externes sont dérivés/reconstructibles.

Depuis PR #24 :

- un fingerprint canonique déterministe représente l'état pertinent des fichiers indexés ;
- un changement SOURCE/TEST invalide les snapshots persistés des providers externes non embarqués ;
- l'index sémantique porte un manifeste Lucene avec fingerprint canonique, provider, modèle, dimensions, profil de préparation et version de schéma ;
- une provenance absente ou incompatible entraîne un rebuild sémantique ;
- une recherche sémantique ne consomme pas un index dont la compatibilité avec l'état canonique courant n'est pas démontrée.

Voir [`../index-provenance.md`](../index-provenance.md).

## Concurrence et verrouillage

Une mutation d'index par projet est protégée à deux niveaux :

1. mutex JVM par `projectId` ;
2. `FileLock` OS par projet sous `NEXUS_HOME/locks`.

Cette coordination couvre les mutations d'index, rebuilds/deep-Java et imports MINOS concernés par le même verrou de mutation. Le fichier de lock n'est pas un marqueur métier : sa simple présence ne signifie pas qu'un verrou est actif.

Le support cible est un `NEXUS_HOME` sur filesystem **local**. NEXUS ne revendique pas de garantie de cohérence inter-processus sur des filesystems réseau dont les sémantiques de `FileLock` diffèrent ou ne sont pas garanties.

## Recovery

En cas de panne pendant une indexation :

1. le projet est normalement marqué `FAILED` ;
2. un crash brutal peut laisser `INDEXING` persistant ;
3. les recherches et ContextBundle refusent tout projet qui n'est pas `READY` ;
4. la prochaine indexation d'un projet non-`READY` force une reconstruction complète ;
5. le verrou JVM + OS évite deux mutations concurrentes actives du même projet sur un `NEXUS_HOME` local partagé entre processus.

En cas de corruption d'un index Lucene, supprimer uniquement l'index dérivé puis lancer un rebuild. En cas de corruption SQLite, restaurer la sauvegarde canonique puis reconstruire les index dérivés.

Pour un index sémantique ancien sans métadonnées de provenance compatibles, le comportement attendu est également un rebuild : l'absence de preuve de compatibilité n'autorise pas sa réutilisation.

## Qualification release

Sous Windows, la qualification est pilotée par :

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\validate-phase-6.ps1
```

Le script impose notamment :

- JVM Java 21+ et `maven.compiler.release=21` ;
- Maven Wrapper ;
- `clean install` du reactor complet ;
- self-smoke ;
- checksums ;
- SBOM CycloneDX ;
- notices tierces et rapport JaCoCo ;
- archive autonome avec artefacts de conformité identiques aux outputs de build ;
- exact-head via `NEXUS_EXPECTED_HEAD_SHA` en CI.

Preuve récente : PR #28, head `a363e93dc97597d288389b4f4b9e8404abe4296c` : NEXUS CI run #31 Windows Java 24 PASS / Linux Java 21 PASS / JaCoCo 70/50 PASS / distribution-compliance PASS ; OSV run #4 PASS ; CodeQL run #6 PASS.

PR #28 est intégrée dans `main` via `4c9b7cd4e26913af42f687b48718c8e733fa06f7`.

Une release, un tag ou un merge ne doit pas être déclaré qualifié sans preuve exécutable sur le head concerné.
