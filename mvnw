#!/bin/sh
set -eu

MAVEN_VERSION="3.9.16"
MAVEN_DIST_URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${MAVEN_VERSION}/apache-maven-${MAVEN_VERSION}-bin.zip"
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
INTEGRITY_FILE="${SCRIPT_DIR}/config/tool-integrity.properties"
VERIFY_SCRIPT="${SCRIPT_DIR}/scripts/release/verify-pinned-checksum.sh"
CACHE_VERIFY_SCRIPT="${SCRIPT_DIR}/scripts/release/ToolArchiveVerifier.java"
WRAPPER_HOME="${HOME}/.m2/wrapper/dists/nexus/apache-maven-${MAVEN_VERSION}"
MAVEN_HOME="${WRAPPER_HOME}/apache-maven-${MAVEN_VERSION}"
ARCHIVE="${WRAPPER_HOME}/apache-maven-${MAVEN_VERSION}-bin.zip"
ARCHIVE_PREFIX="apache-maven-${MAVEN_VERSION}/"

EXPECTED="$(awk -F= -v key="maven.${MAVEN_VERSION}.sha512" '$1 == key { print $2 }' "${INTEGRITY_FILE}" 2>/dev/null || true)"
if [ -z "${EXPECTED}" ]; then
  echo "Ancre SHA-512 Maven absente de ${INTEGRITY_FILE} pour ${MAVEN_VERSION}." >&2
  exit 1
fi
if [ ! -f "${CACHE_VERIFY_SCRIPT}" ]; then
  echo "Vérificateur de cache Maven absent : ${CACHE_VERIFY_SCRIPT}." >&2
  exit 1
fi
if ! command -v java >/dev/null 2>&1; then
  echo "Java 21+ est requis pour vérifier et exécuter Maven Wrapper." >&2
  exit 1
fi

mkdir -p "${WRAPPER_HOME}"
chmod +x "${VERIFY_SCRIPT}"

download_archive() {
  echo "[NEXUS] Téléchargement de Maven ${MAVEN_VERSION} via Maven Central..."
  rm -f "${ARCHIVE}"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "${MAVEN_DIST_URL}" -o "${ARCHIVE}"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "${MAVEN_DIST_URL}" -O "${ARCHIVE}"
  else
    echo "curl ou wget est requis pour initialiser Maven Wrapper." >&2
    exit 1
  fi
}

verify_archive() {
  "${VERIFY_SCRIPT}" sha512 "${EXPECTED}" "${ARCHIVE}"
}

ensure_verified_archive() {
  if [ ! -f "${ARCHIVE}" ]; then
    download_archive
  fi
  if ! verify_archive; then
    rm -f "${ARCHIVE}"
    echo "Archive Maven locale invalide ; nouveau téléchargement depuis l'URL épinglée." >&2
    download_archive
    verify_archive
  fi
}

verify_installation() {
  java "${CACHE_VERIFY_SCRIPT}" zip "${ARCHIVE}" "${MAVEN_HOME}" "${ARCHIVE_PREFIX}"
}

install_from_verified_archive() {
  echo "[NEXUS] Extraction de Maven ${MAVEN_VERSION} depuis l'archive vérifiée..."
  rm -rf "${MAVEN_HOME}"
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
    exit 1
  fi

  if [ ! -f "${MAVEN_HOME}/bin/mvn" ]; then
    echo "L'archive Maven vérifiée n'a pas produit le launcher attendu : ${MAVEN_HOME}/bin/mvn" >&2
    exit 1
  fi
  # `jar xf` ne restaure pas les bits POSIX stockés dans le ZIP. Réappliquer
  # explicitement le bit d'exécution rend le bootstrap identique avec les deux
  # extracteurs sans élargir les permissions des autres fichiers Maven.
  chmod +x "${MAVEN_HOME}/bin/mvn"
  verify_installation
}

ensure_verified_archive

if [ -d "${MAVEN_HOME}" ]; then
  if ! verify_installation; then
    echo "[NEXUS] Cache Maven extrait altéré ; reconstruction depuis l'archive SHA-512 vérifiée." >&2
    install_from_verified_archive
  fi
else
  install_from_verified_archive
fi

if [ ! -x "${MAVEN_HOME}/bin/mvn" ]; then
  echo "Launcher Maven absent ou non exécutable après vérification : ${MAVEN_HOME}/bin/mvn" >&2
  exit 1
fi

exec "${MAVEN_HOME}/bin/mvn" "$@"
