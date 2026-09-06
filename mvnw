#!/bin/sh
set -eu

MAVEN_VERSION="3.9.16"
MAVEN_DIST_URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${MAVEN_VERSION}/apache-maven-${MAVEN_VERSION}-bin.zip"
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
INTEGRITY_FILE="${SCRIPT_DIR}/config/tool-integrity.properties"
VERIFY_SCRIPT="${SCRIPT_DIR}/scripts/release/verify-pinned-checksum.sh"
WRAPPER_HOME="${HOME}/.m2/wrapper/dists/nexus/apache-maven-${MAVEN_VERSION}"
MAVEN_HOME="${WRAPPER_HOME}/apache-maven-${MAVEN_VERSION}"
ARCHIVE="${WRAPPER_HOME}/apache-maven-${MAVEN_VERSION}-bin.zip"

EXPECTED="$(awk -F= -v key="maven.${MAVEN_VERSION}.sha512" '$1 == key { print $2 }' "${INTEGRITY_FILE}" 2>/dev/null || true)"
if [ -z "${EXPECTED}" ]; then
  echo "Ancre SHA-512 Maven absente de ${INTEGRITY_FILE} pour ${MAVEN_VERSION}." >&2
  exit 1
fi

if [ ! -x "${MAVEN_HOME}/bin/mvn" ]; then
  echo "[NEXUS] Installation locale de Maven ${MAVEN_VERSION} via Maven Central..."
  mkdir -p "${WRAPPER_HOME}"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "${MAVEN_DIST_URL}" -o "${ARCHIVE}"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "${MAVEN_DIST_URL}" -O "${ARCHIVE}"
  else
    echo "curl ou wget est requis pour initialiser Maven Wrapper." >&2
    exit 1
  fi

  chmod +x "${VERIFY_SCRIPT}"
  if ! "${VERIFY_SCRIPT}" sha512 "${EXPECTED}" "${ARCHIVE}"; then
    rm -f "${ARCHIVE}"
    exit 1
  fi

  if command -v unzip >/dev/null 2>&1; then
    unzip -q -o "${ARCHIVE}" -d "${WRAPPER_HOME}"
  elif command -v jar >/dev/null 2>&1; then
    # Les images JDK/Maven minimales n'embarquent pas toujours unzip. Le tool
    # standard du JDK sait extraire le ZIP déjà vérifié, sans installer de
    # paquet système supplémentaire dans les builds hermétiques.
    (
      cd "${WRAPPER_HOME}"
      jar xf "${ARCHIVE}"
    )
  else
    echo "unzip ou jar (JDK) est requis pour initialiser Maven Wrapper." >&2
    rm -f "${ARCHIVE}"
    exit 1
  fi
  rm -f "${ARCHIVE}"
fi

exec "${MAVEN_HOME}/bin/mvn" "$@"
