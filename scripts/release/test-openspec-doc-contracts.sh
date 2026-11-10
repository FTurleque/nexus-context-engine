#!/usr/bin/env bash
# Contrats documentaires OpenSpec : schéma detailed-uml, specs, matrice de traçabilité,
# liens et types de diagrammes Mermaid. Contrôle statique, sans la CLI OpenSpec.
set -euo pipefail

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
cd "$ROOT"

python3 scripts/release/openspec_doc_contracts.py

# Fixtures négatives du contrôle des liens des changements, archives comprises. Chacune vit sous
# target/<fixture>/archive/<changement>/ : la profondeur d'un changement archivé (quatre niveaux).
mkdir -p "$ROOT/target"
FIXTURES=()
trap 'rm -rf "${FIXTURES[@]}"' EXIT

new_fixture() {
  local base
  base="$(mktemp -d "$ROOT/target/openspec-link-fixture-$1.XXXXXX")"
  FIXTURES+=("$base")
  mkdir -p "$base/archive/2026-01-01-fixture"
  printf '# Design\n\n[page](%s)\n' "$2" > "$base/archive/2026-01-01-fixture/design.md"
  FIXTURE="$base"
}

expect_link_failure() {
  local name="$1" link="$2" diagnostic="$3" output
  new_fixture "$name" "$link"
  if output="$(python3 scripts/release/openspec_doc_contracts.py --links-under "$FIXTURE" 2>&1)"; then
    echo "openspec-doc-contracts fixture $name: le lien $link aurait dû être refusé" >&2
    exit 1
  fi
  for needle in "2026-01-01-fixture" "design.md" "$link" "$diagnostic"; do
    if [[ "$output" != *"$needle"* ]]; then
      echo "openspec-doc-contracts fixture $name: diagnostic sans « $needle » : $output" >&2
      exit 1
    fi
  done
}

expect_link_failure broken-file "../../../docs/developer/git-context.md" "lien cass"
expect_link_failure missing-anchor "../../../../docs/developer/secret-redaction.md#section-absente" "ancre introuvable"
new_fixture valid "../../../../docs/developer/secret-redaction.md#openspec-secret-redaction"
python3 scripts/release/openspec_doc_contracts.py --links-under "$FIXTURE" > /dev/null
echo "openspec-doc-contracts: fixtures de liens d'archive OK"
