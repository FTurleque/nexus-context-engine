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

declare -A LOCAL_ASSET_NAMES=()
for asset in "$@"; do
  if [[ ! -s "$asset" ]]; then
    echo "Release asset is missing or empty: $asset" >&2
    exit 66
  fi

  name="$(basename "$asset")"
  if [[ -n "${LOCAL_ASSET_NAMES[$name]:-}" ]]; then
    echo "Duplicate release asset basename is not allowed: $name" >&2
    exit 65
  fi
  LOCAL_ASSET_NAMES[$name]="$asset"
done

release_exists=false
release_is_draft=false
if gh release view "$TAG" --json isDraft >/dev/null 2>&1; then
  release_exists=true
  release_is_draft="$(gh release view "$TAG" --json isDraft --jq '.isDraft')"
  if [[ "$release_is_draft" != "true" && "$release_is_draft" != "false" ]]; then
    echo "Unable to determine GitHub Release draft state for $TAG: $release_is_draft" >&2
    exit 70
  fi

  if [[ "$release_is_draft" == "true" ]]; then
    echo "GitHub Release $TAG already exists as a draft; resuming immutable asset publication."
  else
    echo "GitHub Release $TAG is already published; verifying immutable assets without mutating it."
  fi
else
  echo "Creating GitHub Release $TAG as a draft until every asset is verified."
  gh release create "$TAG" \
    --verify-tag \
    --draft \
    --title "NEXUS ${TAG#v}" \
    --notes "Immutable NEXUS release qualified by CI for tag ${TAG}."
  release_exists=true
  release_is_draft=true
fi

if [[ "$release_exists" != "true" ]]; then
  echo "GitHub Release $TAG could not be created or loaded." >&2
  exit 70
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mapfile -t REMOTE_ASSET_NAMES < <(gh release view "$TAG" --json assets --jq '.assets[].name')

remote_asset_exists() {
  local candidate="$1"
  local remote_name
  for remote_name in "${REMOTE_ASSET_NAMES[@]:-}"; do
    if [[ "$remote_name" == "$candidate" ]]; then
      return 0
    fi
  done
  return 1
}

for asset in "$@"; do
  name="$(basename "$asset")"
  destination="$WORK/$name"
  rm -f "$destination"

  if remote_asset_exists "$name"; then
    if ! gh release download "$TAG" --pattern "$name" --dir "$WORK" >/dev/null; then
      echo "Existing GitHub Release asset could not be downloaded for verification: $name" >&2
      exit 70
    fi
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

  if [[ "$release_is_draft" != "true" ]]; then
    echo "Published GitHub Release $TAG is missing immutable asset $name; refusing to mutate a public release." >&2
    exit 75
  fi

  gh release upload "$TAG" "$asset"
  REMOTE_ASSET_NAMES+=("$name")
  rm -f "$destination"
  gh release download "$TAG" --pattern "$name" --dir "$WORK" >/dev/null
  if [[ ! -f "$destination" ]] || ! cmp -s "$asset" "$destination"; then
    echo "Uploaded GitHub Release asset failed byte-for-byte verification: $name" >&2
    exit 74
  fi
  echo "Published immutable GitHub Release draft asset: $name"
done

if [[ "$release_is_draft" == "true" ]]; then
  echo "All release assets are present with verified bytes; publishing GitHub Release $TAG."
  gh release edit "$TAG" --draft=false >/dev/null
  final_draft_state="$(gh release view "$TAG" --json isDraft --jq '.isDraft')"
  if [[ "$final_draft_state" != "false" ]]; then
    echo "GitHub Release $TAG did not transition from draft to published state." >&2
    exit 76
  fi
  echo "Published complete immutable GitHub Release: $TAG"
fi
