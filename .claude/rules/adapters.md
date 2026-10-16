---
paths:
  - "adapters/**"
---

# Adaptateurs CLI / REST / MCP

- Les adaptateurs sont **minces** : validation d'entrée, appel à `NexusApplication`, projection de sortie. Aucune logique métier, de ranking ou de budget ne vit ici.
- Parité CLI/REST/MCP : une capacité exposée sur un canal doit avoir le même contrat (limites, erreurs, projection de chemins) sur les autres, ou une justification documentée.
- REST (`adapters/rest-quarkus`) : listener applicatif `127.0.0.1:8080`, management séparé `127.0.0.1:9000`. Hors loopback, `NexusRestExposureGuard` est fail-closed (token CSPRNG conforme au gate structurel, `NEXUS_REST_ALLOWED_PROJECT_ROOTS`, TLS effectif). Ne jamais affaiblir ce garde ; `scripts/release/test-final-audit-contracts.sh` le vérifie.
- Les limites fédérées REST sont alignées sur les politiques centrales (pas de constantes divergentes).
- MCP (`adapters/mcp-java`) : SDK Java officiel, STDIO, entrées bornées ; stdout réservé au protocole (journaux sur stderr).
- Les versions Quarkus / SDK MCP sont documentées : un bump touche aussi `docs/developer/README.md`, `docs/developer/architecture-implementation.md`, `docs/developer/rest-api.md` et `scripts/release/test-operational-doc-contracts.sh`.
