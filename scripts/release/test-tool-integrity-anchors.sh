#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
ROOT="$(CDPATH= cd -- "${SCRIPT_DIR}/../.." && pwd)"
VERIFY="${SCRIPT_DIR}/verify-pinned-checksum.sh"
INTEGRITY="${ROOT}/config/tool-integrity.properties"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

chmod +x "$VERIFY"
printf 'nexus-integrity-fixture\n' > "$TMP/fixture.txt"
SHA256="$(sha256sum "$TMP/fixture.txt" | awk '{print $1}')"
SHA512="$(sha512sum "$TMP/fixture.txt" | awk '{print $1}')"

"$VERIFY" sha256 "$SHA256" "$TMP/fixture.txt"
"$VERIFY" sha512 "$SHA512" "$TMP/fixture.txt"

if "$VERIFY" sha256 "0000000000000000000000000000000000000000000000000000000000000000" "$TMP/fixture.txt"; then
  echo 'mismatch SHA-256 unexpectedly accepted' >&2
  exit 1
fi
if "$VERIFY" sha512 "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" "$TMP/fixture.txt"; then
  echo 'mismatch SHA-512 unexpectedly accepted' >&2
  exit 1
fi

grep -Eq '^maven\.3\.9\.11\.sha512=[0-9a-f]{128}$' "$INTEGRITY"
grep -Eq '^jdtls\.1\.60\.0-202606262232\.sha256=[0-9a-f]{64}$' "$INTEGRITY"

if grep -q 'MAVEN_DIST_SHA512_URL' "$ROOT/mvnw"; then
  echo 'mvnw still trusts a remote checksum URL' >&2
  exit 1
fi
if grep -q '\$checksumUrl' "$ROOT/scripts/install-jdtls.ps1"; then
  echo 'JDT LS installer still trusts a remote checksum URL' >&2
  exit 1
fi

printf 'Pinned tool integrity anchor tests passed\n'
