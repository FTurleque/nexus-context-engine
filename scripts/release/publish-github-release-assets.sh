#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: $0 <tag> <asset> [asset ...]" >&2
  exit 64
fi

TAG="$1"
shift

if ! command -v gh >/dev/null 2>&1; then
  echo "GitHub CLI (gh) is required to publish release assets." >&2
  exit 69
fi
if [[ -z "${GH_TOKEN:-}" ]]; then
  echo "GH_TOKEN is required to publish release assets." >&2
  exit 77
fi

for asset in "$@"; do
  if [[ ! -s "$asset" ]]; then
    echo "Release asset is missing or empty: $asset" >&2
    exit 66
  fi
done

if gh release view "$TAG" >/dev/null 2>&1; then
  echo "GitHub Release $TAG already exists; verifying immutable assets before resume."
else
  gh release create "$TAG" \
    --verify-tag \
    --title "NEXUS ${TAG#v}" \
    --notes "Immutable NEXUS release qualified by CI for tag ${TAG}."
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

for asset in "$@"; do
  name="$(basename "$asset")"
  destination="$WORK/$name"
  rm -f "$destination"

  if gh release download "$TAG" --pattern "$name" --dir "$WORK" >/dev/null 2>&1; then
    if [[ ! -f "$destination" ]]; then
      echo "GitHub reported an existing asset but it was not downloaded: $name" >&2
      exit 70
    fi
    if ! cmp -s "$asset" "$destination"; then
      expected="$(sha256sum "$asset" | awk '{print $1}')"
      actual="$(sha256sum "$destination" | awk '{print $1}')"
      echo "Refusing to replace immutable GitHub Release asset $name: local=$expected remote=$actual" >&2
      exit 73
    fi
    echo "Release asset already exists with identical bytes: $name"
    continue
  fi

  gh release upload "$TAG" "$asset"
  rm -f "$destination"
  gh release download "$TAG" --pattern "$name" --dir "$WORK" >/dev/null
  if ! cmp -s "$asset" "$destination"; then
    echo "Uploaded GitHub Release asset failed byte-for-byte verification: $name" >&2
    exit 74
  fi
  echo "Published immutable GitHub Release asset: $name"
done
