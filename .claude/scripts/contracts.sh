#!/usr/bin/env bash
# Lance localement les contrats machine-vérifiables exécutés par le job Linux de NEXUS CI.
# Sous Windows (Git Bash), python3 n'existe souvent pas : un shim temporaire vers python est fourni.
set -euo pipefail

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
cd "$ROOT"

# Depuis PowerShell, `bash` désigne souvent WSL (System32\bash.exe) : sur une copie de
# travail Windows en CRLF, plusieurs contrats y échouent à tort. Utiliser Git Bash ou Linux.
if grep -qi microsoft /proc/version 2>/dev/null && [[ "$ROOT" == /mnt/* ]]; then
  echo "contracts: WSL détecté sur une copie de travail Windows ; relancer avec Git Bash (\"C:\\Program Files\\Git\\bin\\bash.exe\" .claude/scripts/contracts.sh)." >&2
  exit 2
fi

if ! command -v python3 >/dev/null 2>&1; then
  if ! command -v python >/dev/null 2>&1; then
    echo "contracts: python3 ou python est requis" >&2
    exit 1
  fi
  SHIM_DIR="$(mktemp -d)"
  trap 'rm -rf "$SHIM_DIR"' EXIT
  printf '#!/bin/sh\nexec python "$@"\n' > "$SHIM_DIR/python3"
  chmod +x "$SHIM_DIR/python3"
  PATH="$SHIM_DIR:$PATH"
fi

LOG="$(mktemp)"
status=0
for contract in \
  scripts/release/test-tool-integrity-anchors.sh \
  scripts/release/test-operational-doc-contracts.sh \
  scripts/release/test-final-audit-contracts.sh; do
  # Les contrats incluent des cas négatifs bruyants : la sortie n'est montrée qu'en cas d'échec.
  if bash "$contract" >"$LOG" 2>&1; then
    echo "PASS $contract"
  else
    echo "FAIL $contract" >&2
    cat "$LOG" >&2
    status=1
  fi
done
rm -f "$LOG"
exit "$status"
