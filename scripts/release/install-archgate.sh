#!/bin/sh
# Installe le binaire Archgate linux-x64 de la version fixée dans config/tool-integrity.properties,
# après vérification de l'archive contre l'empreinte versionnée. Rien n'est extrait si l'empreinte
# diverge.
#
# usage: install-archgate.sh <répertoire-cible>
# ARCHGATE_ARCHIVE=<fichier> remplace le téléchargement par une archive locale (tests hors réseau).
# NEXUS_TOOL_INTEGRITY=<fichier> remplace config/tool-integrity.properties (tests).
set -eu

if [ "$#" -ne 1 ]; then
  echo "usage: $0 <target-dir>" >&2
  exit 64
fi

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
ROOT="$(CDPATH= cd -- "${SCRIPT_DIR}/../.." && pwd)"
INTEGRITY="${NEXUS_TOOL_INTEGRITY:-${ROOT}/config/tool-integrity.properties}"
TARGET="$1"

VERSION="$(awk -F= '$1 == "archgate.version" { sub(/\r$/, "", $2); print $2 }' "$INTEGRITY")"
if ! printf '%s\n' "$VERSION" | grep -Eqx '[0-9]+\.[0-9]+\.[0-9]+'; then
  echo "archgate.version absent ou invalide dans ${INTEGRITY}" >&2
  exit 1
fi
KEY="archgate.${VERSION}.linux-x64.sha256"
EXPECTED="$(awk -F= -v key="$KEY" '$1 == key { sub(/\r$/, "", $2); print $2 }' "$INTEGRITY")"
if [ -z "$EXPECTED" ]; then
  echo "${KEY} absent de ${INTEGRITY}" >&2
  exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
ARCHIVE="${WORK}/archgate-linux-x64.tar.gz"
if [ -n "${ARCHGATE_ARCHIVE:-}" ]; then
  cp "$ARCHGATE_ARCHIVE" "$ARCHIVE"
else
  curl --fail --location --silent --show-error --retry 4 --retry-all-errors \
    --proto '=https' --proto-redir '=https' --tlsv1.2 \
    --output "$ARCHIVE" \
    "https://github.com/archgate/cli/releases/download/v${VERSION}/archgate-linux-x64.tar.gz"
fi

sh "${SCRIPT_DIR}/verify-pinned-checksum.sh" sha256 "$EXPECTED" "$ARCHIVE"

mkdir -p "${WORK}/extract" "$TARGET"
tar --no-same-owner --no-same-permissions -xzf "$ARCHIVE" -C "${WORK}/extract" archgate
if [ ! -f "${WORK}/extract/archgate" ] || [ -L "${WORK}/extract/archgate" ]; then
  echo "L'archive ne contient pas de fichier régulier archgate" >&2
  exit 1
fi
mv "${WORK}/extract/archgate" "${TARGET}/archgate"
chmod +x "${TARGET}/archgate"
echo "archgate ${VERSION} installé dans ${TARGET}/archgate (SHA-256 vérifié)"
