# Section 11 — Risques et dette technique

Probabilité : F faible, M moyenne, E élevée. Impact : F faible, M moyen, E élevé.

## Registre synthétique

| ID | Risque | Prob. | Impact | Statut / mitigation |
|---|---|---:|---:|---|
| R1 | Scale SQLite substring | M | M | surveillance ; benchmark avant nouveau moteur |
| R2 | Corruption SQLite | F | E | ACID + sauvegarde canonique/recovery |
| R3 | Race filesystem locale hostile | F | E | limite documentée ; `ProjectPathGuard`/`SafeFileIO` |
| R4 | `FileLock` filesystem réseau | M | E | non supporté sans qualification |
| R5 | provider externe non coopératif | M | M | timeout ; isolation conditionnée à un cas réel |
| R6 | migration forward-only | F | E | backup avant upgrade |
| R10 | compatibilité MCP | M | M | SDK 2.0.1 + tests STDIO/intégration |
| R13 | snapshots externes obsolètes | M | E | clôturé par invalidation/provenance |
| R14 | index sémantique incompatible | M | E | clôturé par manifeste + rebuild/garde |
| R15 | supply-chain incomplète | M | E | clôturé/renforcé : CodeQL, OSV, Trivy, SBOM, hashes |
| R17 | snapshot publié après mutation | M | E | clôturé par revalidation canonique |
| R18 | REST distant insuffisamment sécurisé | M | E | clôturé côté runtime : TLS effectif + auth + roots + proxy borné |
| R19 | travail graphe/fédération non borné | M | E | clôturé : projections + budget de travail + fail-fast 100 projets |
| R20 | recovery sémantique | M | M | watch item |
| R21 | cache Git persistant | M | M | non adopté sans mesure |
| R22 | lifecycle Lucene partagé | M | M | non adopté sans benchmark/recovery |
| R23 | découverte native pathologique | M | E | clôturé : `ContextDiscoveryLimits` + benchmark 1 000 skills |
| R24 | diff Git massif | M | M | clôturé : sink fixe + caps + test massif |
| R25 | dérive documentation opérationnelle | M | E | mitigé : contrats doc exécutés dans NEXUS CI |
| R26 | `develop` non protégé côté GitHub | M | E | **ouvert gouvernance** : appliquer ruleset/branch protection |

## Frontières de support

### Filesystem

NEXUS refuse les sorties de racine et symlinks sur les lectures durcies, mais ne revendique pas un sandbox absolu contre un acteur local hostile modifiant activement l'arborescence.

### Persistance

SQLite reste canonique. Les migrations ne sont pas rollbackables automatiquement. V005 protège désormais les invariants de lignes au niveau base.

### REST

Loopback est le défaut. Les modes distants exigent TLS backend effectif ; `reverse-proxy-https` exige également une frontière proxy bornée.

### Supply-chain

Le build vérifie les outils fixes par hashes versionnés. L'image Docker qualifiée est celle publiée. Les scanners automatisés ne remplacent pas une revue juridique d'une nouvelle licence inhabituelle.

### Gouvernance

Les workflows ne suffisent pas si `develop` accepte encore des pushes directs. NXA3-14 reste ouvert tant que l'API GitHub ne confirme pas la protection requise.

## Dette / choix conditionnés

Restent conditionnés à une preuve : moteur FTS/trigram supplémentaire, index distribué, vector DB, cache Git persistant, lifecycle Lucene partagé, isolation systématique des providers externes.

La preuve de qualification n'est pas un numéro de PR historique : utiliser les checks attachés au SHA exact concerné.
