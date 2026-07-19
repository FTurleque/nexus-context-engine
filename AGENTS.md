# NEXUS Agent Instructions

Ces règles s'appliquent à l'ensemble du repository.

- Compiler le cœur avec Java 21 et conserver le cœur métier en Java sans framework applicatif obligatoire.
- Préserver les frontières ports/adaptateurs : SQLite est canonique, Lucene est un index dérivé reconstructible.
- Garder NEXUS indépendant des fournisseurs LLM, IDE, agents et protocoles clients.
- Pour toute décision architecturale durable, créer ou compléter la chaîne d'ADR sans réécrire rétroactivement une décision acceptée.
- La documentation du projet est rédigée en français ; les identifiants de code restent en anglais.
- Toute sélection de contexte, score, troncature ou exclusion doit rester déterministe et explicable.
- Avant de considérer une itération validée, exécuter `mvn clean install` puis `scripts/self-smoke.ps1`.

Contexte détaillé à consulter :

- @docs/developer/README.md
- @docs/architecture.md
- @docs/roadmap.md
