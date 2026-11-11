# Section 1 — Introduction et objectifs

## 1.1 Résumé du système

**NEXUS Context Engine** est un moteur local d'intelligence de contexte, indépendant des
modèles de langage. Il transforme un ensemble de repositories enregistrés, une requête et
un budget de tokens en un **ContextBundle** : contexte minimal, pertinent, explicable et
borné, prêt à être injecté dans n'importe quel assistant IA ou agent.

NEXUS se positionne **entre** les sources d'information (code, documentation, instructions,
skills, Git) **et** les consommateurs (GitHub Copilot, Claude, agents maison, CLI). Il ne
génère pas de réponses, n'exécute pas d'agents et ne route pas de modèles.

> Preuve : `core/src/main/java/com/nexus/application/NexusApplication.java` — façade applicative
> partagée entre CLI, REST et MCP ; aucune dépendance vers un fournisseur LLM.

**Version actuelle** : 0.2.1 — Phase 6 (Consolidation, hardening et industrialisation).

## 1.2 Objectifs métier

| # | Objectif | Source |
|---|----------|--------|
| M1 | Construire un contexte IA pertinent et minimal pour toute requête sur un repository local, sans appel de modèle | ARCH-0077, `docs/architecture/overview.md` |
| M2 | Fonctionner sans dépendance réseau obligatoire (local-first) | ADR-0005 |
| M3 | Exposer le même moteur à la CLI, à une API REST et aux clients MCP | `docs/architecture/overview.md` § Surfaces |
| M4 | Permettre la recherche et le contexte fédérés sur plusieurs projets enregistrés | `docs/project/governance/ROADMAP.md` Phase 5 |
| M5 | Maintenir l'explicabilité complète de chaque sélection, score et exclusion | BE-0071 |

## 1.3 Parties prenantes

| Partie prenante | Rôle | Attentes vis-à-vis de NEXUS |
|-----------------|------|------------------------------|
| Développeur CLI | Utilisateur direct de la CLI | Commandes stables, sorties JSON/humain, codes de sortie fiables (ARCH-0085) |
| Intégrateur assistant (Copilot, Claude…) | Configure l'assistant pour appeler NEXUS | API MCP ou REST stable, ContextBundle documenté |
| Administrateur local | Gère le runtime, `NEXUS_HOME`, variables d'environnement | Documentation opérationnelle claire, health checks |
| Développeur du moteur | Contribue au cœur Java | Frontières ports/adaptateurs respectées, tests reproductibles |
| Architecte logiciel | Valide les décisions et l'évolution | ADR maintenus, arc42 à jour, risques documentés |

## 1.4 Objectifs qualité priorisés

| Priorité | Attribut | Formulation opérationnelle | ADR de référence |
|----------|----------|---------------------------|------------------|
| 1 | **Correctness** | Toute sélection, score et exclusion doit être déterministe, reproductible et explicable | BE-0071, BE-0072 |
| 2 | **Fiabilité / Sécurité locale** | Aucun fichier hors du périmètre projet ne doit être lu ; les ressources (verrou, index) sont protégées contre les redirections symlink | H1, H2 — issue #16 |
| 3 | **Indépendance fournisseur** | Zéro dépendance obligatoire vers un LLM, un IDE ou un orchestrateur ; tout provider externe est opt-in et borné dans le temps | ARCH-0077, ADR-0005 |
| 4 | **Opérabilité** | La CLI démarre sans configuration préalable ; le service REST expose liveness/readiness et métriques Micrometer | ARCH-0085, ADR-0031 |
| 5 | **Évolutivité contrôlée** | Les intégrations nouvelles s'ajoutent comme adaptateurs sans modifier le cœur | ARCH-0076, ARCH-0080, ARCH-0078 |
