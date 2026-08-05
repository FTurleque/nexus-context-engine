# Section 11 — Risques et dette technique

Probabilité : **F** = faible, **M** = moyenne, **E** = élevée  
Impact : **F** = faible, **M** = moyen, **E** = élevé  
Exposition = Probabilité × Impact

## 11.1 Registre des risques

| ID | Risque | Prob. | Impact | Exp. | Statut | Mitigation | Propriétaire | Date cible |
|----|--------|-------|--------|------|--------|-----------|--------------|------------|
| R1 | **Scale SQLite lexical** — `LOWER(...) LIKE '%...%'` peut se dégrader sur 100k+ symboles | M | M | MM | Surveillance | Benchmark différé sur jeux de 10k/100k/500k/1M symboles avant toute modification (H8, `docs/roadmap.md`) | Équipe cœur | Sur benchmark |
| R2 | **Corruption SQLite** lors d'une coupure de courant en cours d'écriture | F | E | FM | Accepté | Transactions ACID, rollback automatique, Lucene reconstructible, runbook de recovery | Équipe hardening | Continu |
| R3 | **Symlink race** — acteur local remplaçant un répertoire ancêtre pendant le traitement | F | E | FM | Accepté (limite documentée) | `ProjectPathGuard` + `SafeFileIO` NOFOLLOW_LINKS — protection contre les cas statiques uniquement | Équipe hardening | Phase 7 si besoin |
| R4 | **FileLock FS réseau** — sémantique non qualifiée sur filesystem réseau | M | E | ME | Non-support documenté | NEXUS_HOME doit être local ; documentation explicite dans roadmap H2 | Équipe hardening | Sur demande |
| R5 | **Provider JDT LS non-coopératif** — ignore l'interruption indéfiniment | M | M | MM | Accepté | Worker daemon, timeout wall-clock, risque accepté si ce cas apparaît avec de futurs providers (H3) | Équipe hardening | Sur incident |
| R6 | **Migration SQLite sans rollback** — une V002 défectueuse ne peut pas être annulée | F | E | FM | Watch item | Tester les migrations sur copie avant production ; runbook de restore depuis backup | Équipe cœur | Avant V002 |
| R7 | **Glissement fonctionnel** — NEXUS devient orchestrateur ou chatbot | F | E | FM | Maîtrisé | ADR-0001 + revues de frontière régulières | Architecte | Continu |
| R8 | **Dépendance Quarkus dans le cœur** — importation accidentelle | F | M | FM | Maîtrisé | Build séparé (reactor Maven), pas de dépendance Quarkus dans `core/pom.xml` | Équipe cœur | Continu |
| R9 | **Dérive de qualité de recherche** lors de l'ajout de nouveaux langages | M | M | MM | Watch item | Baseline `hit@3` / `MRR@3` maintenue, qualification avant intégration | Équipe qualité | Par itération |
| R10 | **Compatibilité MCP SDK** — rupture de contrat lors d'une mise à jour du SDK MCP Java | M | M | MM | Watch item | ADR-0016, test d'intégration `NexusMcpServerIntegrationTest` | Équipe adaptateur | Sur upgrade |
| R11 | **Registre AI Skills** indisponible | M | F | MF | Accepté | Provider local en fallback (snapshot) ; `AiSkillsRegistryProvider` opt-in (ADR-0042) | Équipe cœur | N/A |
| R12 | **Qualification post-Phase 6 non exécutée** (issue #16) — état actuel de la branche | E | M | EM | Bloquant | Gate explicite dans `docs/roadmap.md` — aucun merge avant validation du propriétaire | Propriétaire | Branche actuelle |

## 11.2 Dette technique identifiée

| ID | Dette | Impact | Effort | Priorité |
|----|-------|--------|--------|---------|
| D1 | **Sources historiques dans `src/`** — `core/pom.xml` les référence via `sourceDirectory` au lieu d'un déplacement physique | Faible (fonctionne, inconvenant en IDEs) | Moyen | Faible |
| D2 | **Pas de rollback de schéma SQLite** — toute migration est forward-only sans procédure de downgrade documentée | Moyen | Faible | Moyen |
| D3 | **Sélection de skills lexicale uniquement** — pas de sélection sémantique | Moyen (qualité skill matching) | Élevé | Faible (attendu phase future) |
| D4 | **Instructions utilisateur home non chargées** — limitation documentée | Faible | Moyen | Faible |
| D5 | **H8 — scale SQLite lexical non benchmarké** sur grands corpus | Moyen potentiel | Élevé (benchmark + décision) | Moyen — différé intentionnellement |

## 11.3 Choix volontairement non adoptés

> Ces choix ne sont pas de la dette mais des décisions conscientes documentées dans
> `docs/architecture.md` § Choix volontairement non adoptés.

- Pas de Zoekt/OpenGrok/OpenSearch, index distribué, vector DB ou FTS5 supplémentaire
  sans benchmark démontrant le besoin.
- Pas de cache Git persistant ni de lifecycle Lucene partagé plus complexe.
- Pas d'isolation en processus/circuit-breaker pour les providers — différé si un cas réel l'exige.
