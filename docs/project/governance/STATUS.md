# État de NEXUS

Ce que NEXUS livre aujourd'hui, et où le vérifier.

| Question | Source |
|---|---|
| fonctionnalités livrées et usage | [README du dépôt](../../../README.md) |
| version, toolchain et contrats techniques courants | [Guide développeur](../../developer/README.md) |
| limites connues et dette de consolidation | [Limites actuelles](../../developer/current-limitations.md) |
| travaux ouverts | [Feuille de route](ROADMAP.md) |
| constats d'audit en cours | [Audits](../audits/README.md) |

Un fait chiffré (version, borne, seuil) est écrit dans l'une de ces pages et vérifié par les contrats CI (`scripts/release/test-operational-doc-contracts.sh`) ; cette page ne le recopie pas.

La preuve de qualification d'un état est le run CI attaché au SHA exact concerné, jamais un ancien run vert.
