---
paths:
  - ".github/**"
  - "scripts/**"
  - "mvnw"
  - "mvnw.cmd"
  - ".mvn/**"
  - "config/**"
  - "packaging/**"
---

# CI, scripts et supply-chain

- Les actions GitHub sont épinglées par SHA complet avec la version en commentaire (`uses: owner/action@<sha> # vX.Y.Z`), une seule version majeure par action. Un input de workflow n'entre dans un bloc `run:` que par `env:` ; tout job avec `runs-on:` a un `timeout-minutes` ; les benchmarks tournent sur `ubuntu-24.04`/`windows-2025`. Contrôles : `scripts/release/workflow_hygiene.py`.
- Les scripts PowerShell actifs importent `scripts/lib/NexusScript.psm1` et appellent Maven par `Invoke-Maven` ; `scripts/archive/` n'est pas maintenu.
- `github/codeql-action/init` et `github/codeql-action/analyze` doivent toujours avoir **la même version** ; sinon l'analyse échoue (« Loaded a configuration file for version … »).
- Les outils téléchargés (Maven, JDT LS, runtime Ollama) sont vérifiés contre `config/tool-integrity.properties`. Les wrappers `mvnw`/`mvnw.cmd` sont durcis : ne jamais les remplacer par les scripts Apache standards (piège Dependabot). Procédure : skill `nexus-dependency-bump`.
- Fins de ligne : `*.sh` et `mvnw` en LF (`.gitattributes`) ; `*.cmd`/`*.ps1` doivent rester en CRLF dans la copie de travail Windows. `sed -i` sous Git Bash retire les CR et casse les labels `cmd` (`GOTO`) : utiliser l'outil Edit ou `perl -pi`.
- Les scripts de contrat bash appellent `python3`, absent sous Windows : passer par `bash .claude/scripts/contracts.sh` qui fournit un shim.
- L'image Docker est construite une fois, qualifiée, puis publiée sans rebuild ; les tags version/SHA sont immuables.
- Dependabot cible `develop`. Les checks obligatoires de `develop` : `Windows gate`, `Linux reactor Maven build`, `CodeQL Java analysis`, `OSV new-vulnerability delta gate / osv-scan`, `Build aggregate reactor SBOM`, `OSV aggregate SBOM vulnerability gate / osv-scan`, `SonarCloud Code Analysis`, et `Hermetic scale benchmark` une fois ajouté au ruleset par le mainteneur. Ce dernier est produit sur toute PR : la liste des chemins surveillés est `WATCHED_PATHS` dans `scale-benchmark.yml`, reprise à l'identique par le filtre push de `develop-push-scale-benchmark.yml` (contrat `test-operational-doc-contracts.sh`).
