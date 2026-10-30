# Tasks

Les commandes `./mvnw` s'entendent avec `-B -ntp` ; `TEST_CIBLE` désigne `-Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`.

## 1. Lecture cohérente unique (AUD-ARC-09)

- [ ] 1.1 AUD-ARC-09 — Extraire dans `NexusApplication` les deux gabarits de lecture cohérente (mono-projet, fédéré) qui portent READY → verrou partagé → session → revalidation READY (design D1) et y faire passer `search`, `searchAcrossProjects`, `context`, `contextAcrossProjects`, `findSymbols`, `findUsages` et `inspect` ; vérifier par `./mvnw -pl core test -Dtest='NexusApplication*' TEST_CIBLE` puis `./mvnw -pl core test` (tests de cohérence d'ADR-0052)
- [ ] 1.2 AUD-ARC-09 — Ajouter un test qui vérifie qu'`inspect` refuse un projet repassé hors READY pendant la lecture, comme les autres lectures ; vérifier par `./mvnw -pl core test -Dtest='NexusApplication*' TEST_CIBLE`

## 2. Contrat de façade sans JDBC (AUD-ARC-11)

- [ ] 2.1 AUD-ARC-11 — Envelopper `SQLException` dans `PersistenceException` dans `NexusApplication.create*` et `NexusApplicationFactory`, cause conservée (design D2) ; vérifier par `./mvnw -pl core test -Dtest='NexusApplication*' TEST_CIBLE`
- [ ] 2.2 AUD-ARC-11 — Retirer `java.sql.SQLException` de `NexusCli`, `NexusMcpServer` et `NexusApiApplicationService`, codes de sortie et réponses d'erreur inchangés ; vérifier par `git grep -n "java.sql" -- core/src/main/java/com/nexus/cli adapters/*/src/main` (aucun résultat) puis `./mvnw clean install`

## 3. Configuration résolue en composition root (AUD-ARC-12)

- [ ] 3.1 AUD-ARC-12 — Introduire le record de configuration construit par `NexusApplicationFactory` depuis une `Map<String, String>` et l'injecter dans les douze classes qui lisent l'environnement, classe par classe, valeurs par défaut et messages d'erreur inchangés (design D3) ; vérifier par `./mvnw -pl core test` (tests de limites existants inchangés)
- [ ] 3.2 AUD-ARC-12 — Remplacer dans les tests les constructeurs package-private de configuration par la `Map` injectée là où ils n'existaient que pour contourner l'environnement ; vérifier par `./mvnw -pl core test`
- [ ] 3.3 AUD-ARC-12 — Ajouter au test d'architecture du cœur (introduit par `AUD-ARC-07`) la règle « aucun appel à `System.getenv` dans `core/src/main` hors de la composition root » ; vérifier par `./mvnw -pl core test -Dtest='*Architecture*' TEST_CIBLE` et par `git grep -n "System.getenv" -- core/src/main` (seule la composition root apparaît)
- [ ] 3.4 AUD-ARC-12 — Nouvel ADR (skill `nexus-adr`) qui complète ADR-0003 : la composition root manuelle résout aussi la configuration ; mettre à jour le registre des ADR, la vue arc42 des décisions et `docs/developer/architecture-implementation.md` ; vérifier par `bash .claude/scripts/contracts.sh`

## 4. Hygiène des packages (AUD-ARC-14, AUD-ARC-19)

- [ ] 4.1 AUD-ARC-14 — Déplacer le durcissement POSIX/ACL de `NexusPaths` vers `security.PrivateStoragePolicy` (design D4), appels de la composition root adaptés ; vérifier par `./mvnw -pl core test -Dtest='NexusPathsTest,*PrivateStorage*' TEST_CIBLE` sous Windows et Linux (gate Windows et reactor Linux de la PR)
- [ ] 4.2 AUD-ARC-14 — Déplacer `search.evaluation.SearchQualityMetrics` sous `core/src/test` ; vérifier par `./mvnw -pl core test -Dtest='*GoldenSearchCorpusTest,SearchQualityMetricsTest' TEST_CIBLE` puis, après `./mvnw -pl core package -DskipTests`, `jar tf core/target/nexus-context-engine-*.jar | grep search/evaluation` (aucun résultat)
- [ ] 4.3 AUD-ARC-19 — Renommer la classe JDT `ProcessBuilder` en `BoundedJdtProcessBuilder` et nommer explicitement ses usages (design D5) ; vérifier par `./mvnw -pl core test -Dtest='Jdt*' TEST_CIBLE` et `git ls-files core/src/main | grep -c '/ProcessBuilder.java'` (0)

## 5. Hôte et origine sur le listener loopback (AUD-SEC-04)

- [ ] 5.1 AUD-SEC-04 — Ajouter à l'adaptateur REST le filtre de transport loopback (design D6) : hôte loopback exigé, origine non loopback refusée, requête sans origine acceptée, refus 403 sans détail ; vérifier par `./mvnw -pl adapters/rest-quarkus -am test -Dtest='NexusRest*' TEST_CIBLE`
- [ ] 5.2 AUD-SEC-04 — Couvrir en `@QuarkusTest` les scénarios de `specs/rest-transport-hardening` en trust-local et en jeton loopback, et vérifier que le garde hors loopback est inchangé (`NexusRestExposureGuardTest`) ; vérifier par `./mvnw -pl adapters/rest-quarkus -am test -Dtest='NexusRest*,NexusResourceTest' TEST_CIBLE`
- [ ] 5.3 AUD-SEC-04 — Documenter le contrôle et le risque navigateur du mode trust-local, avec la recommandation d'une allowlist de racines, dans `docs/developer/rest-exposure-security.md` et `docs/developer/rest-api.md`, et ajouter l'assertion correspondante à `scripts/release/test-final-audit-contracts.sh` ; vérifier par `bash .claude/scripts/contracts.sh`

## 6. Plafond de la découverte Git (AUD-SEC-05)

- [ ] 6.1 AUD-SEC-05 — Introduire une fabrique unique de dépôt Git plafonnée à la racine du projet, avec contrôle du propriétaire pour un dépôt parent (design D7), et l'utiliser dans `LocalGitContextSourceProvider`, `PersistentGitContextSourceProvider` et `GitRecencyCandidateEnricher` ; vérifier par `./mvnw -pl core test -Dtest='*GitContextSourceProvider*,GitRecencyCandidateEnricherTest' TEST_CIBLE`
- [ ] 6.2 AUD-SEC-05 — Couvrir les scénarios de `specs/git-context-discovery` (dépôt racine, sous-module de l'utilisateur, dépôt parent écarté avec diagnostic sans chemin absolu), le cas de propriétaire non déterminable étant simulé par injection ; vérifier par `./mvnw -pl core test -Dtest='*Git*' TEST_CIBLE` sur le gate Windows et le reactor Linux
- [ ] 6.3 AUD-SEC-05 — Nouvel ADR (skill `nexus-adr`) qui complète ADR-0035 : portée de la découverte du dépôt Git ; mettre à jour `docs/developer/git-context.md`, le registre des ADR et la vue arc42 des décisions ; vérifier par `bash .claude/scripts/contracts.sh`

## 7. Validation du sprint

- [ ] 7.1 Contrôler le critère de sortie : `git grep -n "SQLException" -- core/src/main/java/com/nexus/application/NexusApplication.java` sans signature publique, `git grep -n "System.getenv" -- core/src/main` limité à la composition root, tests 5.2 et 6.2 verts ; puis `/nx-validate full` (contrats, `./mvnw clean install`, `scripts/self-smoke.ps1`)

## Workflow follow-up

- Ouvrir la PR depuis une branche issue de `origin/develop` vers `develop` (skill `nexus-pr-workflow`), relue par `/nx-review` (agent `nexus-security-auditor` pour les sections 5 et 6) ; merge exact-head en merge commit (`gh pr merge --merge --match-head-commit <sha>`).
- Une fois la PR fusionnée, mettre à jour le statut des sept constats dans `docs/project/audits/2026-10-17/findings.json` lors du prochain suivi d'audit, puis archiver le changement OpenSpec (`/opsx:archive sprint-08-facade-configuration-durcissements`).
