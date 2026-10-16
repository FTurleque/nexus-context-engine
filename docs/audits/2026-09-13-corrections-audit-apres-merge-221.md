# Corrections de l'audit après fusion #221

Date : 13 septembre 2026.
Référence de l'audit initial : `6f863b78c1f7d384e234e1de64be850da31dda5c`.

Les quatre constats de [l'audit](2026-09-13-audit-complet-apres-merge-221.md)
sont traités par cette modification, avec quinze nouveaux tests automatiques.
Les observations initiales et les sondes restent conservées pour comparaison.

## A221-01 — Rédaction des secrets

Le filtre reconnaît les clés à guillemets simples ou doubles et parcourt
les valeurs en tenant compte des échappements. Les longs littéraux sont
consommés en un passage sans coupure laissant un suffixe visible. Les
séparateurs de lignes sont préservés, y compris sur un littéral tronqué.
La politique passe de `secret-redaction-v2` à `secret-redaction-v3` pour
invalider les dérivés construits avec l'ancien filtre.

Les tests couvrent les deux reproductions initiales, les suites paires de
barres obliques inverses, les longs secrets, les fins de valeur manquantes,
l'idempotence et la conservation des champs voisins.

## A221-02 — Métadonnées YAML bornées

Les alias de collections sont refusés et les champs sont validés avant toute
conversion. Les métadonnées contiennent des chaînes ; les listes d'outils et
de compatibilité du registre restent admises lorsqu'elles sont plates et
composées de chaînes. Le texte décodé cumulé est limité à 65 536 caractères,
chaque occurrence d'un alias scalaire étant comptabilisée.

Les tests rejettent l'amplification de 498 octets, les collections imbriquées
dans chaque champ et la répétition excessive d'alias scalaires. Les fixtures
existantes de découverte du registre continuent de passer.

## A221-03 — Lecteurs sémantiques indépendants

Les rebuilds sains laissent Lucene gérer les générations et les anciens
segments. Les commits corrompus sont récupérés dans un répertoire distinct,
publié atomiquement après sa construction complète. Le cache remplace son
lecteur si le répertoire actif change. Les détails et conséquences de stockage
sont documentés dans [ADR-0055](../adr/0055-preserver-les-lecteurs-lors-des-reconstructions-semantiques.md).

Les neuf tests de `LuceneSemanticSearchIndexTest` passent sous Windows et
Linux. Ils comprennent deux instances indépendantes, des rebuilds répétés,
un index vide, le changement de dimension, la corruption physique d'un commit,
une mutation après récupération externe et un rebuild échoué qui conserve le
corpus précédemment validé.

Les anciens fichiers corrompus sont conservés pour les lecteurs encore ouverts.
Le nettoyage après des corruptions répétées nécessite l'arrêt des processus
partageant le stockage, selon la procédure de quarantaine documentée.

## A221-04 — Requêtes de graphe ciblées

Les plans du SQLite embarqué révélaient trois parcours trop larges : imports
sortants filtrés après lecture des relations du projet, propriétaires résolus
après parcours des types du projet et relations entrantes sans recherche
indexée sur leur cible. La correction impose le départ depuis les clés
demandées et sépare l'égalité et le préfixe pour les relations entrantes.

Les tests de plans vérifient la recherche par `source_ref`, `qualified_name`
et les deux recherches par `target_ref`. Les tests fonctionnels conservent
la déduplication, les frontières de projet et de préfixe, le déterminisme
et le budget global d'arêtes.

Une première modification limitée aux relations entrantes donnait encore
32 291,178 ms : elle n'a pas été considérée comme suffisante. Après correction
des trois requêtes, le même benchmark complet donne :

| Mesure | Audit initial | Après correction |
| --- | ---: | ---: |
| Symboles / relations | 1 000 000 / 1 000 000 | 1 000 000 / 1 000 000 |
| Types structurels | 800 000 | 800 000 |
| Candidats graphe | 120 | 120 |
| Échantillons | 3 | 3 |
| p95 | 32 251,22 ms | **37,499 ms** |
| Taille SQLite | 779 321 344 octets | 779 321 344 octets |

Le plafond du vérificateur reste **15 000 ms**. La nouvelle mesure a été
produite le 13 septembre à 17:45:34 UTC, sous Ubuntu/WSL, Java 21.0.11,
`-XX:ActiveProcessorCount=4 -Xmx4g`. Le
[rapport brut corrigé](reproductions/2026-09-13/graph-full-corrected.json)
est conservé à côté du rapport initial. Il s'agit de mesures locales avec
charge système variable, pas d'une promesse de latence universelle.

## Validation

- `mvnw.cmd -B clean install`, Java 21.0.10 : cinq modules réussis,
  contrôles de couverture réussis, **572 tests recensés, 548 exécutés avec
  succès, 24 ignorés, zéro échec et zéro erreur**.
- `scripts/self-smoke.ps1` : réussi après le build final, y compris les
  contextes strict, multi-source, skills et Git.
- Linux : les cinq tests de projection graphe, les neuf tests Lucene et le
  benchmark graphe complet passent, soit quinze tests exécutés.
- Les six suites de contrats de livraison et les quinze tests Python du
  vérificateur de budgets passent. Les tests de publication utilisent leurs
  commandes simulées, sans publier de release ni d'image.

La qualification GitHub doit porter sur le commit poussé. Les résultats
locaux ci-dessus ne sont pas présentés comme des résultats de CI distante.

## Complément du 14 septembre — alertes Sonar de la PR #222

La reconnaissance des clés secrètes utilise désormais un parcours explicite
des composants et une liste fixe de noms, sans répétition régulière récursive
ni retour arrière. Les valeurs restent traitées avec leurs échappements.
La politique passe à `secret-redaction-v4`, notamment pour masquer également
les clés privées comme `_password`.

Trois tests supplémentaires couvrent les clés de 20 000 composants, les
variantes de noms sensibles, les frontières avec des identifiants ordinaires
ou Unicode et les affectations incomplètes. Les trente tests ciblés de
rédaction, YAML, provenance et récupération Lucene passent.

Les noms de champs YAML répétés sont centralisés, les objets utilisés dans
les assertions d'exception sont préparés hors des lambdas et les deux lignes
du pointeur de récupération sont stockées avant validation. Ces ajustements
conservent les bornes et les assertions fonctionnelles de l'audit.

Validation locale finale : `mvnw.cmd -B clean install` réussi avec 575 tests
recensés, 551 exécutés avec succès et 24 ignorés, puis `scripts/self-smoke.ps1`
réussi. Les contrôles de couverture restent conformes.
