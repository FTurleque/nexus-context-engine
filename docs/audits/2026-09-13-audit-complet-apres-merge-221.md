# Audit du code après fusion de la PR #221

Date : 13 septembre 2026. Référence analysée :
`6f863b78c1f7d384e234e1de64be850da31dda5c`.

**Suivi :** les corrections et leurs nouvelles validations sont consignées
dans [le bilan de correction](2026-09-13-corrections-audit-apres-merge-221.md).
Les observations ci-dessous décrivent le commit audité avant ces corrections.

La [PR #221](https://github.com/FTurleque/nexus-context-engine/pull/221)
a été fusionnée le 13 septembre à 16:54:14 UTC. Les 21 contrôles du dernier
commit de la PR, `68ff425c7896129d3c7985964b6c34448a8313f1`, étaient réussis.
L'analyse Sonar de ce commit ne signalait aucune nouvelle issue ni hotspot.

Cet audit repart des sources fusionnées et de nouvelles exécutions. Les
rapports historiques ne servent pas de preuve de correction. Il confirme
trois défauts fonctionnels ou de sécurité et un dépassement local du budget
graphe. **Ces constats restent ouverts : cette branche contient leur analyse
et leurs reproductions, sans correction du code de production.**

## Constats prioritaires

### A221-01 — P1 — Des mots de passe littéraux échappent à la rédaction

Localisation :
[`SensitiveContentRedactor.java:38–42`](../../core/src/main/java/com/nexus/security/SensitiveContentRedactor.java).

La reconnaissance des affectations accepte les guillemets doubles autour de
la clé, mais pas les simples. La reconnaissance d'une valeur entre guillemets
ne tient pas compte des échappements. Un guillemet échappé termine donc la
valeur reconnue prématurément.

Reproduction sur la vraie méthode `SensitiveContentRedactor.redact` :

```text
Entrée : {"password":"AuditPrefix123\"VISIBLE_SECRET_SUFFIX"}
Sortie : {"password":"[REDACTED]"VISIBLE_SECRET_SUFFIX"}

Entrée : {'password': 'AuditSyntheticPassword98765'}
Sortie : {'password': 'AuditSyntheticPassword98765'}
```

Dans le premier cas, le suffixe secret reste visible et le JSON est déformé.
Dans le second, le mot de passe entier subsiste, avec une syntaxe courante
dans le code Python ou JavaScript. Les valeurs utilisées ici sont synthétiques.

Impact : les chemins de matérialisation du contexte, d'indexation Lucene et
de préparation des embeddings utilisent ce filtre. Son application ne garantit
donc pas le masquage de ces littéraux. La reproduction porte directement sur
le filtre ; aucun secret réel ni envoi à un fournisseur externe n'a été utilisé.

Correction attendue : reconnaître les délimiteurs de clé et de valeur et leurs
échappements, conserver les limites de travail et les séparateurs de lignes,
ajouter ces cas aux tests et incrémenter la version de politique afin de
reconstruire les dérivés concernés.

### A221-02 — P1 — Le décodage des métadonnées YAML permet une amplification mémoire

Localisation :
[`SkillFrontmatterParser.java:149–158`](../../core/src/main/java/com/nexus/context/source/skill/SkillFrontmatterParser.java),
ainsi que les conversions génériques des champs facultatifs aux lignes 144,
167 et 173.

Le parseur limite la taille physique du frontmatter, mais convertit ensuite
des valeurs YAML arbitraires avec `String.valueOf`. Des listes partageant des
alias YAML sont compactes à la lecture ; leur conversion récursive en chaîne
développe à nouveau chaque occurrence.

Reproduction : un fichier `SKILL.md` de **498 octets**, contenant 18 niveaux
de listes à deux alias du niveau précédent, produit une seule valeur de
métadonnée de **2 621 436 caractères**. Le parseur accepte ce fichier.
La sonde s'exécute dans une JVM limitée à 256 Mio ; elle imprime uniquement
la longueur du résultat.

Impact : un skill présent dans une source de découverte configurée peut
consommer beaucoup plus de mémoire que le budget d'octets lus ne l'indique.
Cette conversion intervient pendant la découverte des métadonnées, avant la
sélection du contexte. L'amplification est reproduite ; un épuisement complet
de la mémoire n'a pas été provoqué pendant cet audit.

Correction attendue : imposer les types scalaires prévus pour les champs,
refuser les collections imbriquées avant toute conversion en chaîne et borner
le volume décodé cumulé. Si des collections restent admises, leur parcours
doit disposer de limites de profondeur, d'expansion et de taille. La seule
limite sur le nombre d'alias ou sur les octets du fichier ne suffit pas.

### A221-03 — P1 — Un rebuild sémantique invalide les lecteurs d'une autre instance

Localisation :
[`LuceneSemanticSearchIndex.java:106`](../../core/src/main/java/com/nexus/search/semantic/lucene/LuceneSemanticSearchIndex.java)
et `clearDerivedIndexDirectory`, lignes 216–237 ;
[`PersistentLuceneSemanticSearchIndex.java:73`](../../core/src/main/java/com/nexus/search/semantic/lucene/PersistentLuceneSemanticSearchIndex.java).

Le rebuild supprime manuellement les fichiers du répertoire Lucene avant de
créer le nouvel index. L'invalidation du cache ne concerne que l'instance
qui effectue le rebuild. Un lecteur persistant ouvert par une autre instance
peut encore référencer les anciens segments.

La sonde utilise deux instances sur le même stockage et exécute les opérations
séquentiellement : indexer `before.java`, effectuer une recherche avec le
lecteur persistant, reconstruire avec `after.java`, rechercher à nouveau.

Résultats avec Java 21 :

- **Windows** : le rebuild échoue avec `AccessDeniedException` pendant la
  suppression de `_0.cfs`, encore mappé par l'autre lecteur.
- **Linux** : le rebuild termine ; un lecteur neuf renvoie `after.java`,
  mais le lecteur persistant renvoie toujours `before.java`.

Impact : échec de reconstruction ou recherche sémantique obsolète lorsque
plusieurs instances partagent un projet, par exemple des clients persistants
et un processus d'indexation. Aucun chevauchement de recherches actives n'est
nécessaire : le lecteur est simplement gardé en cache entre deux opérations.

Correction attendue : publier les reconstructions en respectant le cycle de
vie des lecteurs et des générations Lucene, sans supprimer les segments
encore utilisés ni réutiliser une identité de commit devenue ambiguë pour
un lecteur existant. Tester deux instances indépendantes, les changements
de modèle/dimension et les deux systèmes. Une publication par répertoires
versionnés exigerait une décision architecturale et une gestion explicite de
leur durée de vie ; ce rapport ne tranche pas cette architecture.

### A221-04 — P2 — Le profil graphe complet dépasse localement le budget de livraison

Localisation :
[`SqliteIndexRepository.java:404`](../../core/src/main/java/com/nexus/persistence/sqlite/SqliteIndexRepository.java)
et [`verify-scale-budgets.py:145`](../../scripts/verify-scale-budgets.py).

Nouvelle exécution de `GraphScaleRegressionBenchmarkTest` sur le commit
fusionné, sous Ubuntu/WSL, OpenJDK 21.0.11, avec
`-XX:ActiveProcessorCount=4 -Xmx4g` et le profil `full` :

| Mesure | Résultat |
| --- | ---: |
| Symboles / relations | 1 000 000 / 1 000 000 |
| Symboles de types structurels | 800 000 |
| Fichiers / fichiers graines | 10 000 / 20 |
| Candidats graphe | 120 |
| Échantillons chronométrés | 3 |
| p95 rapporté | **32 251,22 ms** |
| Plafond du vérificateur | **15 000 ms** |
| Population | 41 178 ms |

Le [rapport brut](reproductions/2026-09-13/graph-full.json) a été généré le
13 septembre à 17:00:50 UTC. Le test JUnit produit la mesure et réussit ;
c'est l'assertion du vérificateur de budgets qui rejette cette valeur.
Le workflow de livraison demande le profil `full`. La qualification PR à
plus petite échelle ne couvre pas cette charge.

Impact : le budget annoncé à cette échelle n'est pas tenu sur cette machine
de qualification. Ce résultat ne prouve ni une régression par rapport au
commit précédent, ni un échec systématique sur les runners GitHub : aucun
comparatif de base ni lancement complet du workflow de livraison n'a été
effectué pour ce constat.

Correction attendue : profiler notamment les recherches de relations
entrantes et leur plan SQLite, puis réduire le travail effectué avant
`DISTINCT`, le tri et la limitation des résultats. La requête est un axe
d'investigation ; son plan n'a pas été mesuré dans cet audit. Conserver le
plafond et requalifier le profil complet sur un runner comparable.

## Périmètre et méthode

Inventaire : **199 fichiers Java de production, 24 750 lignes Java et
19 workflows**. Recherches statiques transversales, lecture manuelle des
chemins critiques et exécutions ciblées couvrent les domaines suivants :

| Domaine | Vérifications réalisées |
| --- | --- |
| Application et concurrence | Composition, état des projets, orchestration d'indexation, acquisition/libération des verrous, tâches externes et timeouts |
| Persistance et recherche | SQLite, migrations structurantes, transactions, requêtes de registre/graphe, Lucene lexical et sémantique, caches de lecteurs |
| Contexte | Classement, fusion des candidats, fédération, budgets, matérialisation des fichiers, traçabilité et rédaction des contenus |
| Sources et parseurs | Scan du dépôt, chemins et liens, Java/Markdown, import SCIP, frontière de confiance JDT LS, MINOS, Git et skills |
| Adaptateurs | Authentification et exposition REST, racines autorisées, services et mapping des réponses, outils MCP et génération des intégrations |
| Distribution | Workflows CI/livraison, images et entrypoint, intégrité des outils téléchargés, publication immuable et contrats documentaires |

La couverture est transversale et centrée sur les risques ; elle ne constitue
pas une preuve formelle ni une lecture exhaustive de chaque ligne de chaque
fichier. Les constats ci-dessus sont séparés des hypothèses non reproduites.
Les rapports d'audit antérieurs n'ont pas été utilisés pour les établir.

## Validations exécutées sur la référence fusionnée

- `mvnw.cmd -B clean install`, Microsoft OpenJDK **21.0.10** : tous les
  modules réussissent. **557 tests recensés, 533 exécutés avec succès,
  24 ignorés, zéro échec et zéro erreur**. Les tests opt-in ignorés ne sont
  pas comptés comme validés.
- `scripts/self-smoke.ps1`, après le build : réussite des 13 étapes.
- Six suites Linux réussies : `test-tool-integrity-anchors.sh`,
  `test-operational-doc-contracts.sh`, `test-final-audit-contracts.sh`,
  `test-release-tag-policy.sh`, `test-ghcr-immutable-preflight.sh`,
  `test-publish-github-release-assets.sh`.
- **15 tests Python** de `scripts/tests/test_scale_budgets.py` réussis.
- Sondes additionnelles : fuite de littéraux, amplification YAML,
  reconstruction sémantique Windows/Linux et benchmark graphe complet.

Les suites de publication utilisent des commandes simulées : elles ne
publient aucune image ni release réelle. La copie Linux issue de l'archive
Git Windows a nécessité une normalisation CRLF vers LF des fichiers de
configuration/workflow pour les vérifications textuelles ancrées ; aucun
changement de source de production n'a été appliqué.

Les qualifications réelles Docker, JDT LS, MINOS, modèles distants et tous
les profils coûteux de performance n'ont pas été rejoués intégralement dans
cet audit. Les contrôles GitHub réussis avant fusion restent des preuves
distinctes, limitées à leur commit et à leur scénario. Aucun nouveau scan
exhaustif des CVE n'est revendiqué.

Les deux conversations CodeQL relatives aux handles de verrou avaient été
résolues après vérification du transfert de propriété au `AutoCloseable`
retourné et de sa fermeture par les appelants. Les alertes elles-mêmes
n'ont pas été masquées ou déclarées corrigées. Le modèle documenté de
mainteneur unique n'est pas reclassé comme défaut d'approbation humaine.

## Reproductions et suite attendue

Les sources des sondes, leurs commandes et les mesures sont conservées dans
[`reproductions/2026-09-13`](reproductions/2026-09-13/README.md).
Traiter A221-01 et A221-02 en priorité pour les contenus de dépôt non fiables,
puis A221-03 pour les instances partageant le stockage. A221-04 nécessite une
qualification de performance complète avant de considérer l'échelle maximale
comme validée. Chaque correction devra ajouter un test de non-régression
qui reproduit le comportement décrit ici.
