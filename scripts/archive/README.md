# Scripts archivés

Les scripts de ce dossier sont **non maintenus**. Ce sont les runners de validation des itérations 9 à 17 (`validate-iteration-*.ps1`), conservés pour l'historique daté qui les cite (`docs/project/history/`).

- Ils ne sont pas des points d'entrée de validation et aucun workflow ne les appelle.
- Ils ne suivent pas les règles des scripts actifs : ils définissent leurs propres fonctions, appellent le Maven installé et visent des artefacts de versions antérieures. Leurs chemins relatifs supposent une exécution depuis `scripts/`.
- Le contrat `scripts/release/test-operational-doc-contracts.sh` les exclut de ses vérifications.

Les points d'entrée de validation sont `scripts/validate-phase-6.ps1` et `scripts/self-smoke.ps1` (voir [`docs/developer/ci-and-supply-chain.md`](../../docs/developer/ci-and-supply-chain.md)).
