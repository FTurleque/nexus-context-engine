#!/bin/sh
set -eu

if [ "$#" -ne 3 ]; then
  echo "usage: $0 <sha256|sha512> <expected-hex> <file>" >&2
  exit 64
fi

ALGORITHM="$1"
EXPECTED="$(printf '%s' "$2" | tr 'A-F' 'a-f')"
FILE="$3"

case "$ALGORITHM" in
  sha256)
    LENGTH=64
    if command -v sha256sum >/dev/null 2>&1; then
      ACTUAL="$(sha256sum "$FILE" | awk '{print $1}')"
    elif command -v shasum >/dev/null 2>&1; then
      ACTUAL="$(shasum -a 256 "$FILE" | awk '{print $1}')"
    else
      echo "sha256sum ou shasum est requis." >&2
      exit 1
    fi
    ;;
  sha512)
    LENGTH=128
    if command -v sha512sum >/dev/null 2>&1; then
      ACTUAL="$(sha512sum "$FILE" | awk '{print $1}')"
    elif command -v shasum >/dev/null 2>&1; then
      ACTUAL="$(shasum -a 512 "$FILE" | awk '{print $1}')"
    else
      echo "sha512sum ou shasum est requis." >&2
      exit 1
    fi
    ;;
  *)
    echo "Algorithme non supporté : $ALGORITHM" >&2
    exit 64
    ;;
esac

case "$EXPECTED" in
  *[!0-9a-f]*|'')
    echo "Hash attendu invalide." >&2
    exit 1
    ;;
esac
if [ "${#EXPECTED}" -ne "$LENGTH" ]; then
  echo "Longueur de hash attendue invalide pour $ALGORITHM." >&2
  exit 1
fi

ACTUAL="$(printf '%s' "$ACTUAL" | tr 'A-F' 'a-f')"
if [ "$EXPECTED" != "$ACTUAL" ]; then
  echo "Checksum $ALGORITHM invalide. Attendu=$EXPECTED, obtenu=$ACTUAL" >&2
  exit 1
fi
