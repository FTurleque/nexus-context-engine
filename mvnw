#!/bin/sh
set -eu

MAVEN_VERSION="3.9.11"
MAVEN_DIST_URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${MAVEN_VERSION}/apache-maven-${MAVEN_VERSION}-bin.zip"
MAVEN_DIST_SHA512_URL="${MAVEN_DIST_URL}.sha512"
WRAPPER_HOME="${HOME}/.m2/wrapper/dists/nexus/apache-maven-${MAVEN_VERSION}"
MAVEN_HOME="${WRAPPER_HOME}/apache-maven-${MAVEN_VERSION}"
ARCHIVE="${WRAPPER_HOME}/apache-maven-${MAVEN_VERSION}-bin.zip"

if [ ! -x "${MAVEN_HOME}/bin/mvn" ]; then
  echo "[NEXUS] Installation locale de Maven ${MAVEN_VERSION} via Maven Central..."
  mkdir -p "${WRAPPER_HOME}"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "${MAVEN_DIST_URL}" -o "${ARCHIVE}"
    EXPECTED="$(curl -fsSL "${MAVEN_DIST_SHA512_URL}" | awk '{print $1}')"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "${MAVEN_DIST_URL}" -O "${ARCHIVE}"
    EXPECTED="$(wget -qO- "${MAVEN_DIST_SHA512_URL}" | awk '{print $1}')"
  else
    echo "curl ou wget est requis pour initialiser Maven Wrapper." >&2
    exit 1
  fi

  if command -v sha512sum >/dev/null 2>&1; then
    ACTUAL="$(sha512sum "${ARCHIVE}" | awk '{print $1}')"
  elif command -v shasum >/dev/null 2>&1; then
    ACTUAL="$(shasum -a 512 "${ARCHIVE}" | awk '{print $1}')"
  else
    echo "sha512sum ou shasum est requis pour vérifier Maven." >&2
    exit 1
  fi
  if [ "${EXPECTED}" != "${ACTUAL}" ]; then
    rm -f "${ARCHIVE}"
    echo "Checksum SHA-512 Maven invalide." >&2
    exit 1
  fi
  if ! command -v unzip >/dev/null 2>&1; then
    echo "unzip est requis pour initialiser Maven Wrapper." >&2
    exit 1
  fi
  unzip -q -o "${ARCHIVE}" -d "${WRAPPER_HOME}"
  rm -f "${ARCHIVE}"
fi

exec "${MAVEN_HOME}/bin/mvn" "$@"
