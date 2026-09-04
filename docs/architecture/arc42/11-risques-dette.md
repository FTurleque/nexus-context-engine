# Section 11 — Risques et dette technique

Probabilité : F faible, M moyenne, E élevée. Impact : F faible, M moyen, E élevé.

## Registre synthétique

| ID | Risque | Prob. | Impact | Statut / mitigation |
|---|---|---:|---:|---|
| R1 | Scale SQLite substring | M | M | surveillance ; benchmark avant nouveau moteur |
| R2 | Corruption SQLite | F | E | ACID + sauvegarde canonique/recovery |
| R3 | Race filesystem locale hostile | F | E | limite documentée ; `ProjectPathGuard`/`SafeFileIO` |
| R4 | `FileLock` filesystem réseau | M | E | non supporté sans qualification |
| R5 | provider externe non coopératif | M | M | timeout + max 8 workers actifs ; isolation processus plus forte conditionnée à un cas réel |
| R6 | migration forward-only | F | E | backup avant upgrade |
| R10 | compatibilité MCP | M | M | SDK 2.0.1 + tests STDIO/intégration |
| R13 | snapshots externes obsolètes | M | E | clôturé par invalidation/provenance |
| R14 | index sémantique incompatible | M | E | profil `content-v2` + rebuild/garde |
| R15 | supply-chain incomplète | M | E | clôturé/renforcé : CodeQL, OSV, Trivy, SBOM, hashes |
| R17 | snapshot publié après mutation | M | E | clôturé par revalidation canonique |
| R18 | REST distant/management insuffisamment sécurisé | M | E | TLS effectif + auth + roots + proxy borné + management loopback séparé |
| R19 | travail graphe/fédération non borné | M | E | clôturé : projections + budget de travail + fail-fast 100 projets |
| R20 | recovery sémantique | M | M | watch item |
| R21 | cache Git persistant | M | M | non adopté sans mesure |
| R22 | lifecycle Lucene partagé | M | M | non adopté sans benchmark/recovery |
| R23 | découverte native pathologique | M | E | clôturé : `ContextDiscoveryLimits` + benchmark 1 000 skills |
| R24 | diff Git massif | M | M | clôturé : sink fixe + caps + test massif |
| R25 | dérive documentation opérationnelle | M | E | mitigé : contrats doc NXA3+NXA4 exécutés dans NEXUS CI |
| R26 | `develop` non protégé côté GitHub | M | E | **ouvert gouvernance** : appliquer ruleset/branch protection |
| R27 | JDT LS hostile/défectueux provoque allocation/backlog non borné | F | E | framing 16 MiB/64 KiB/8 KiB + queue 256 + fail-closed |
| R28 | requête Lucene à forte cardinalité dépasse le budget de clauses | F | M | cap 128 termes analysés + test de non-régression |
| R29 | fuite accidentelle de secrets vers embeddings/contexte | M | E | exclusions sensibles + redaction forte confiance + profil `content-v2` |
| R30 | endpoint Ollama distant en HTTP / credentials URI | M | E | HTTPS distant par défaut, HTTP distant opt-in, userinfo refusé |
| R31 | stockage NEXUS lisible trop largement sur POSIX | F | E | répertoires 0700, SQLite 0600, symlinks persistants refusés |

## Frontières de support

### Filesystem et stockage

NEXUS refuse les sorties de racine et symlinks sur les lectures durcies, mais ne revendique pas un sandbox absolu contre un acteur local hostile modifiant activement l'arborescence.

Sur POSIX, le stockage persistant est privé ; sur Windows, les ACL natives ne sont pas réécrites destructivement.

### Persistance

SQLite reste canonique. Les migrations ne sont pas rollbackables automatiquement. V005 protège les invariants de lignes au niveau base.

### Providers externes

Les tâches externes sont bornées en temps et en concurrence. Un provider qui ignore l'interruption peut continuer jusqu'à sa terminaison réelle ; la capacité globale empêche toutefois l'accumulation de threads non bornée. Une isolation processus systématique plus forte reste un choix conditionné à un scénario reproductible.

### REST

Loopback est le défaut. Les modes distants exigent TLS backend effectif ; `reverse-proxy-https` exige également une frontière proxy bornée. Health/metrics restent sur le listener management loopback `127.0.0.1:9000` et ne doivent pas être publiés avec l'API métier.

### Sémantique

La redaction de secrets est conservatrice et ciblée sur les formats à forte confiance ; elle ne remplace pas un scanner de secrets spécialisé. Ollama distant utilise HTTPS par défaut.

### Supply-chain

Le build vérifie les outils fixes par hashes versionnés. L'image Docker qualifiée est celle publiée. Les scanners automatisés ne remplacent pas une revue juridique d'une nouvelle licence inhabituelle.

### Gouvernance

Les workflows ne suffisent pas si `develop` accepte encore des pushes directs. NXA3-14 / #130 reste ouvert tant que l'API GitHub ne confirme pas la protection requise.

## Dette / choix conditionnés

Restent conditionnés à une preuve : moteur FTS/trigram supplémentaire, index distribué, vector DB, cache Git persistant, lifecycle Lucene partagé, isolation processus plus forte des providers externes.

La preuve de qualification n'est pas un numéro de PR historique : utiliser les checks attachés au SHA exact concerné.
