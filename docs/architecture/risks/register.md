# Registre des risques — NEXUS Context Engine

Ce registre décrit les risques **courants**. Les documents d'itération conservent les preuves historiques ; ici, la qualification applicable est toujours celle du SHA exact concerné.

## Risques actifs

### Scale SQLite lexical

Les recherches substring peuvent se dégrader sur des corpus plus grands. Mitigation : Scale Benchmark et optimisations locales avant tout FTS/trigram/autre moteur.

### Filesystem local hostile

`ProjectPathGuard`, `SafeFileIO`, confinement SCIP/skills et budgets réduisent la surface, sans constituer un sandbox absolu contre un acteur local capable de muter l'arborescence pendant l'opération.

### `FileLock` réseau

La garantie inter-processus vise `NEXUS_HOME` sur filesystem local. SMB/NFS exigeraient une qualification dédiée.

### Provider externe non coopératif

Les tâches sont bornées en wall-clock. L'isolation processus généralisée reste conditionnée à un cas reproductible.

### Recovery sémantique

L'indisponibilité d'un provider ou une corruption physique Lucene nécessite encore des procédures opérationnelles spécifiques selon le scénario. SQLite reste l'autorité.

### Gouvernance `develop`

Le contrat est versionné mais l'état GitHub doit être effectif : PR obligatoire, checks retenus, suppression/force-push interdits et exceptions administratives limitées. Tant que `develop` retourne `protected=false`, ce risque reste ouvert.

## Risques clôturés/fortement mitigés par NXA3

### REST distant

Mitigation : token robuste + roots + mode explicite + listener TLS effectif ; reverse proxy avec forwarding et trusted proxies bornés.

### SCIP / skills / customisations hors racine

Mitigation : `ProjectPathGuard`, refus traversal/symlink final/symlink d'ancêtre et tests ciblés.

### Découverte native pathologique

Mitigation : budget partagé visites/candidats/octets/deadline avant sélection + benchmark filesystem de 1 000 skills.

### Portée fédérée surdimensionnée

Mitigation : maximum 100 projets uniques appliqué avant résolution/readiness dans les surfaces concernées.

### Diff Git massif

Mitigation : chemins/historique capés et sink de patch à capacité fixe, qualifié par test massif.

### Supply-chain outils

Mitigation : Maven 3.9.16 contrôlé par SHA-512 versionné et JDT LS fixe contrôlé par SHA-256 ; test exécuté dans NEXUS CI.

### Publication Docker divergente

Mitigation : build unique, gates sur cette image, handoff hash/ID, publication sans rebuild.

### GHCR ambigu/partiel

Mitigation : préflight fail-closed ; reprise idempotente uniquement pour contenu identique ; tags version/SHA immuables.

### Données SQLite incompatibles

Mitigation : V004 invalide les anciens index aux plages impossibles ; V005 impose les `CHECK` de `CodeSymbol`.

### Dérive documentaire

Mitigation : documentation courante réconciliée et `test-operational-doc-contracts.sh` exécuté par NEXUS CI.

## Mise à jour

Mettre à jour ce registre après changement de frontière de support, nouveau risque majeur ou clôture matérialisée par code + preuve + documentation + qualification exact-head.
