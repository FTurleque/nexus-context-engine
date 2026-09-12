# Section 11 — Risques et dette technique

Probabilité : F faible, M moyenne, E élevée. Impact : F faible, M moyen, E élevé.

## Registre synthétique

| ID | Risque | Prob. | Impact | Statut / mitigation |
|---|---|---:|---:|---|
| R1 | Scale SQLite substring | M | M | surveillance ; benchmark avant nouveau moteur |
| R2 | Corruption SQLite | F | E | ACID + sauvegarde canonique/recovery |
| R3 | Race filesystem locale hostile | F | E | `SecureDirectoryStream` lorsque disponible ; fallback capture/revalidation identité filesystem ; limite résiduelle documentée |
| R4 | `FileLock` filesystem réseau | M | E | local qualifié + preuve SMB loopback ciblée ; autres sémantiques non supportées sans qualification |
| R5 | provider externe in-process non coopératif | F | M | runtime courant sans provider tiers arbitraire ; timeout + max 8 workers + circuit-breaker ; isolation processus conditionnée à un cas réel |
| R6 | migration forward-only | F | E | backup avant upgrade |
| R10 | compatibilité MCP | M | M | SDK 2.0.1 + tests STDIO/intégration |
| R13 | snapshots externes obsolètes | M | E | clôturé par invalidation/provenance |
| R14 | index sémantique incompatible | M | E | profil `content-v3` + rebuild/garde |
| R15 | supply-chain incomplète | M | E | clôturé/renforcé : CodeQL, OSV, Trivy, SBOM, hashes |
| R17 | snapshot publié après mutation | M | E | clôturé par revalidation canonique |
| R18 | REST distant/management insuffisamment sécurisé | M | E | TLS effectif + token CSPRNG conforme au gate structurel + roots + proxy borné + management loopback séparé |
| R19 | travail graphe/fédération non borné | M | E | clôturé : projections + budget de travail + fail-fast 100 projets |
| R20 | recovery/qualité sémantique | F | M | rebuild/quarantaine + fallback lecture + qualification réelle Ollama périodique/manuelle |
| R23 | découverte native pathologique | M | E | clôturé : `ContextDiscoveryLimits` + benchmark 1 000 skills |
| R24 | diff Git massif | M | M | clôturé : sink fixe + caps + test massif |
| R25 | dérive documentation opérationnelle | M | E | mitigé : contrats doc exécutés dans NEXUS CI |
| R26 | `develop` non protégé côté GitHub | F | E | **clôturé** : ruleset actif `Protect main & develop`, NXA3-14 / #130 satisfait |
| R27 | JDT LS hostile/défectueux ou lancé sur dépôt non approuvé | F | E | framing 16 MiB/64 KiB/8 KiB + queue 256 + confiance racine exacte + fail-closed |
| R28 | requête Lucene à forte cardinalité dépasse le budget de clauses | F | M | cap 128 termes analysés + test de non-régression |
| R29 | fuite accidentelle de secrets vers embeddings/contexte | M | E | exclusions sensibles + redaction forte confiance + profil `content-v3` |
| R30 | endpoint Ollama distant en HTTP / credentials URI | M | E | HTTPS distant par défaut, HTTP distant opt-in, userinfo refusé |
| R31 | stockage NEXUS trop largement accessible | F | E | POSIX 0700/0600 ; ACL inspectées ; `NEXUS_REQUIRE_PRIVATE_STORAGE=true` pour mode fail-closed |
| R32 | amplification métadonnées Code Intelligence | F | E | champs UTF-8 bornés + 100k symboles + 250k relations + 64 MiB cumulés |
| R33 | adoption prématurée du Vector API | F | M | non adopté après ABBA ; contrat CI empêche l'activation silencieuse dans les launchers |

## Frontières de support

### Filesystem et stockage

NEXUS refuse les sorties de racine et symlinks sur les lectures durcies. `SafeFileIO` utilise `SecureDirectoryStream` lorsqu'il existe ; le fallback portable capture et revalide chemin réel + `fileKey` avant/après l'ouverture. NEXUS ne revendique toujours pas un sandbox absolu contre un acteur local capable d'un échange/rétablissement extrêmement rapide ou de manipuler des sémantiques de montage particulières.

Sur POSIX, le stockage persistant est privé. Sur Windows, les ACL natives ne sont pas réécrites destructivement. Le mode `NEXUS_REQUIRE_PRIVATE_STORAGE=true` exige toutefois une preuve de confidentialité et échoue fermé lorsque l'inspection ne permet pas de l'établir.

### Persistance

SQLite reste canonique. Les migrations ne sont pas rollbackables automatiquement. V005 protège les invariants de lignes au niveau base.

### Providers externes

Les tâches externes sont bornées en temps et en concurrence. La composition de production actuelle utilise SCIP en import local et JDT LS en subprocess ; elle n'exécute pas de provider Java tiers arbitraire. Une isolation processus générique supplémentaire ne sera ajoutée qu'après scénario reproductible sur un provider réel.

### REST

Loopback est le défaut. Les modes distants exigent un token généré par CSPRNG et conforme au gate structurel, ainsi qu'un TLS backend effectif ; `reverse-proxy-https` exige également une frontière proxy bornée. Le gate structurel de token ne prétend pas mesurer l'entropie cryptographique. Health/metrics restent sur le listener management loopback `127.0.0.1:9000` et ne doivent pas être publiés avec l'API métier.

### Sémantique

La redaction de secrets est conservatrice et ciblée sur les formats à forte confiance ; elle ne remplace pas un scanner de secrets spécialisé. Ollama distant utilise HTTPS par défaut. Le pipeline réel dispose d'une qualification périodique/manuelle avec Ollama vérifié par SHA-256, modèle fixé et seuils de non-régression.

### Supply-chain

Le build vérifie les outils fixes par hashes versionnés. L'image Docker qualifiée est celle publiée. Les scanners automatisés ne remplacent pas une revue juridique d'une nouvelle licence inhabituelle.

### Gouvernance

Le ruleset actif protège `develop` et `main`, impose les pull requests, interdit suppression/non-fast-forward et exige les checks permanents approuvés. NXA3-14 / #130 est satisfait.

NEXUS est actuellement maintenu par **une seule personne**. Le modèle solo n'exige pas de seconde approbation humaine ni de resynchronisation stricte avec la base juste avant merge. Ces absences ne sont pas des risques ouverts ; elles ne doivent être reconsidérées que si le modèle de maintenance change explicitement.

## Dette / choix conditionnés

Restent conditionnés à une preuve : moteur FTS/trigram supplémentaire, index distribué/vector DB, extension à un filesystem réseau précis, isolation processus plus forte d'un futur provider in-process réellement non coopératif et réévaluation du Vector API après nouvelle mesure same-runner.

La preuve de qualification n'est pas un numéro de PR historique : utiliser les checks attachés au SHA exact concerné et, pour le benchmark sémantique réel, le rapport correspondant au runtime/modèle qualifiés.
