# Section 2 — Contraintes

## 2.1 Contraintes techniques imposées

| ID | Contrainte | Nature | Preuve |
|----|-----------|--------|--------|
| T1 | JVM d'exécution ≥ 21 ; bytecode ciblé Java 21 | Imposée | `core/pom.xml`, ADR-0002 |
| T2 | SQLite est la source de vérité canonique ; Lucene est un index dérivé reconstructible | Imposée | ADR-0006, ADR-0007, ADR-0022 |
| T3 | Aucune dépendance obligatoire vers un LLM, un IDE ou un orchestrateur externe | Imposée | ADR-0001, ADR-0005 |
| T4 | Le cœur Java est sans framework applicatif obligatoire (`NexusApplication` instanciée manuellement) | Imposée | ADR-0003 |
| T5 | Maven ≥ 3.9 requis pour le build ; Maven Wrapper (SHA-512) fourni | Imposée | `core/pom.xml`, ADR-0020 |
| T6 | Micrometer (Prometheus) pour les métriques REST | Imposée | `application.properties`, `docs/architecture.md` |
| T7 | MCP transport : STDIO uniquement (pas de HTTP côté MCP) | Imposée | ADR-0040, `NexusMcpServer.java` |
| T8 | Le schéma SQLite est migré par des scripts SQL embarqués, forward-only | Imposée | ADR-0020 |
| T9 | Les données locales sont stockées dans `NEXUS_HOME` configurable | Imposée | ADR-0019 |
| T10 | Toute ouverture de fichier final passe par `SafeFileIO` avec `NOFOLLOW_LINKS` | Imposée (hardening #16) | H1, `docs/roadmap.md` |

## 2.2 Contraintes organisationnelles

| ID | Contrainte | Nature | Preuve |
|----|-----------|--------|--------|
| O1 | Documentation rédigée en français ; identifiants de code en anglais | Imposée | `AGENTS.md` |
| O2 | Toute décision architecturale durable doit faire l'objet d'un ADR MADR ; aucun ADR accepté ne peut être réécrit rétroactivement | Imposée | ADR-0000, `docs/adr/README.md` |
| O3 | Avant toute itération validée : `mvn clean install` puis `scripts/self-smoke.ps1` | Imposée | `AGENTS.md` |
| O4 | Aucune CI ni merge dans `develop` avant validation explicite du propriétaire | Imposée | `docs/roadmap.md` § Gate #16 |

## 2.3 Contraintes réglementaires

Aucune contrainte réglementaire identifiée dans les sources disponibles.

> **Hypothèse à valider** : le projet n'est pas soumis à RGPD, SOC 2 ou toute autre
> réglementation de conformité des données. Si NEXUS est déployé dans un contexte
> d'entreprise, cette hypothèse doit être vérifiée auprès de l'équipe juridique.

## 2.4 Distinction contrainte imposée / préférence

| Élément | Statut |
|---------|--------|
| SQLite comme source canonique | **Contrainte** — tout rebuild Lucene doit repartir de SQLite |
| Lucene comme index de recherche | **Contrainte** — pas de moteur alternatif sans benchmark (ADR-0043) |
| Ollama pour les embeddings | **Préférence** — opt-in uniquement, `NEXUS_SEMANTIC_PROVIDER=ollama` |
| JDT Language Server | **Préférence** — opt-in via `NEXUS_JDTLS_HOME`, borné par timeout |
| MINOS | **Préférence** — enrichissement optionnel via JSON local |
| Quarkus pour REST | **Contrainte** de l'adaptateur REST (ADR-0039), pas du cœur |
| 127.0.0.1 comme adresse d'écoute REST | **Contrainte** de sécurité (H6), configurable mais avec fail-fast |
