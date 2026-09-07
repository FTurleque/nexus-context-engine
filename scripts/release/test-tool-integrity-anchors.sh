#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
ROOT="$(CDPATH= cd -- "${SCRIPT_DIR}/../.." && pwd)"
VERIFY="${SCRIPT_DIR}/verify-pinned-checksum.sh"
CACHE_VERIFY="${SCRIPT_DIR}/ToolArchiveVerifier.java"
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

grep -Eq '^maven\.3\.9\.16\.sha512=[0-9a-f]{128}$' "$INTEGRITY"
grep -Eq '^jdtls\.1\.60\.0-202606262232\.sha256=[0-9a-f]{64}$' "$INTEGRITY"
grep -q 'MAVEN_VERSION="3.9.16"' "$ROOT/mvnw"
grep -q 'MAVEN_VERSION=3.9.16' "$ROOT/mvnw.cmd"

# Both wrappers must keep the repository-pinned digest as the trust anchor and
# verify the extracted cache itself before executing Maven.
grep -q 'verify-pinned-checksum.sh' "$ROOT/mvnw"
grep -q 'ToolArchiveVerifier.java' "$ROOT/mvnw"
grep -q 'verify_installation' "$ROOT/mvnw"
grep -q 'System.Security.Cryptography.SHA512' "$ROOT/mvnw.cmd"
grep -q 'ComputeHash' "$ROOT/mvnw.cmd"
grep -q 'tool-integrity.properties' "$ROOT/mvnw.cmd"
grep -q 'ToolArchiveVerifier.java' "$ROOT/mvnw.cmd"
grep -q 'Cache Maven extrait altere' "$ROOT/mvnw.cmd"

# Unix bootstrap must remain usable in minimal JDK/Maven images where unzip is
# absent: use unzip when present, otherwise the standard JDK jar tool. Because
# jar does not restore POSIX modes, the Maven launcher must be made executable
# explicitly after extraction.
grep -q 'command -v unzip' "$ROOT/mvnw"
grep -q 'command -v jar' "$ROOT/mvnw"
grep -q 'jar xf "${ARCHIVE}"' "$ROOT/mvnw"
grep -q 'chmod +x "${MAVEN_HOME}/bin/mvn"' "$ROOT/mvnw"

# Windows bootstrap resilience must not regress to a single HTTP client: curl is
# preferred, then PowerShell is a fallback, and verification happens afterwards.
grep -q 'WHERE curl.exe' "$ROOT/mvnw.cmd"
grep -q 'GOTO DOWNLOAD_POWERSHELL' "$ROOT/mvnw.cmd"
grep -q '^:DOWNLOAD_POWERSHELL' "$ROOT/mvnw.cmd"
grep -q '^:VERIFY_MAVEN' "$ROOT/mvnw.cmd"

# JDT LS must not trust an already extracted plugins directory. The pinned
# archive is cached, re-hashed, staged and only then replaces the installation.
grep -q '\.cache' "$ROOT/scripts/install-jdtls.ps1"
grep -q 'Test-PinnedArchive' "$ROOT/scripts/install-jdtls.ps1"
grep -q 'Reconstruction depuis l.archive SHA-256 verifiee' "$ROOT/scripts/install-jdtls.ps1"
grep -q 'stagingDirectory' "$ROOT/scripts/install-jdtls.ps1"

# Prove the extracted-cache verifier detects both mutations and unexpected
# files. This runs before Maven itself is bootstrapped and therefore exercises
# the Java source-file verifier exactly as mvnw does.
mkdir -p "$TMP/archive-root/tool/bin" "$TMP/extracted"
printf 'trusted-tool\n' > "$TMP/archive-root/tool/bin/tool"
(
  cd "$TMP/archive-root"
  jar cf "$TMP/tool.zip" tool
)
cp -R "$TMP/archive-root/tool" "$TMP/extracted/tool"
java "$CACHE_VERIFY" zip "$TMP/tool.zip" "$TMP/extracted/tool" 'tool/'

printf 'tampered\n' >> "$TMP/extracted/tool/bin/tool"
if java "$CACHE_VERIFY" zip "$TMP/tool.zip" "$TMP/extracted/tool" 'tool/'; then
  echo 'tampered extracted tool cache unexpectedly accepted' >&2
  exit 1
fi

rm -rf "$TMP/extracted/tool"
cp -R "$TMP/archive-root/tool" "$TMP/extracted/tool"
printf 'unexpected\n' > "$TMP/extracted/tool/extra.txt"
if java "$CACHE_VERIFY" zip "$TMP/tool.zip" "$TMP/extracted/tool" 'tool/'; then
  echo 'unexpected file in extracted tool cache unexpectedly accepted' >&2
  exit 1
fi

if grep -q 'MAVEN_DIST_SHA512_URL' "$ROOT/mvnw"; then
  echo 'mvnw still trusts a remote checksum URL' >&2
  exit 1
fi
if grep -q 'MAVEN_DIST_SHA512_URL' "$ROOT/mvnw.cmd"; then
  echo 'mvnw.cmd still trusts a remote checksum URL' >&2
  exit 1
fi
if grep -q '\$checksumUrl' "$ROOT/scripts/install-jdtls.ps1"; then
  echo 'JDT LS installer still trusts a remote checksum URL' >&2
  exit 1
fi

printf 'Pinned tool integrity anchor tests passed\n'
