#!/usr/bin/env bash
# Contrats documentaires OpenSpec : schéma detailed-uml, specs, matrice de traçabilité,
# liens et types de diagrammes Mermaid. Contrôle statique, sans la CLI OpenSpec.
set -euo pipefail

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
cd "$ROOT"

python3 scripts/release/openspec_doc_contracts.py
