#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 5 ]]; then
  echo "usage: $0 <image> <version> <head-sha> <local-image-ref> <github-output-file>" >&2
  exit 64
fi

IMAGE="$1"
VERSION="$2"
HEAD_SHA="$3"
LOCAL_IMAGE_REF="$4"
OUTPUT_FILE="$5"

VERSION_REF="${IMAGE}:${VERSION}"
SHA_REF="${IMAGE}:sha-${HEAD_SHA}"

LOCAL_IMAGE_ID="$(docker image inspect "$LOCAL_IMAGE_REF" --format '{{.Id}}')"
if [[ ! "$LOCAL_IMAGE_ID" =~ ^sha256:[0-9a-f]{64}$ ]]; then
  echo "::error::Unable to resolve local qualified image id: $LOCAL_IMAGE_ID" >&2
  exit 1
fi

classify_missing_manifest() {
  local message="$1"
  shopt -s nocasematch
  if [[ "$message" == *"manifest unknown"* \
     || "$message" == *"no such manifest"* \
     || "$message" == *"name unknown"* \
     || "$message" == *"not found"* ]]; then
    shopt -u nocasematch
    return 0
  fi
  shopt -u nocasematch
  return 1
}

inspect_ref() {
  local label="$1"
  local ref="$2"
  local stdout_file stderr_file
  stdout_file="$(mktemp)"
  stderr_file="$(mktemp)"
  trap 'rm -f "$stdout_file" "$stderr_file"' RETURN

  if ! docker manifest inspect --verbose "$ref" >"$stdout_file" 2>"$stderr_file"; then
    local message
    message="$(cat "$stderr_file")"
    if classify_missing_manifest "$message"; then
      printf '%s\t%s\t%s\n' "false" "" ""
      return 0
    fi
    echo "::error::GHCR preflight failed closed while inspecting ${label} tag ${ref}: ${message:-unknown registry error}" >&2
    return 1
  fi

  local parsed
  if ! parsed="$(python3 - "$stdout_file" <<'PY'
import json
import re
import sys

with open(sys.argv[1], encoding='utf-8') as handle:
    payload = json.load(handle)

if isinstance(payload, list):
    if len(payload) != 1:
        raise SystemExit('expected a single-platform manifest, got %d entries' % len(payload))
    payload = payload[0]

if not isinstance(payload, dict):
    raise SystemExit('unexpected manifest JSON type')

descriptor = payload.get('Descriptor') or {}
schema = payload.get('SchemaV2Manifest') or payload
config = schema.get('config') or {}
manifest_digest = descriptor.get('digest') or ''
config_digest = config.get('digest') or ''
pattern = re.compile(r'^sha256:[0-9a-f]{64}$')
if not pattern.match(manifest_digest):
    raise SystemExit('missing or invalid registry manifest digest')
if not pattern.match(config_digest):
    raise SystemExit('missing or invalid registry config digest')
print(manifest_digest + '\t' + config_digest)
PY
  )"; then
    echo "::error::Unable to parse registry manifest for ${label} tag ${ref}" >&2
    return 1
  fi

  local manifest_digest config_digest
  IFS=$'\t' read -r manifest_digest config_digest <<<"$parsed"
  if [[ "$config_digest" != "$LOCAL_IMAGE_ID" ]]; then
    echo "::error::Immutable ${label} tag already points to different image content: ref=$ref registry-config=$config_digest qualified-image=$LOCAL_IMAGE_ID" >&2
    return 1
  fi

  printf '%s\t%s\t%s\n' "true" "$manifest_digest" "$config_digest"
}

VERSION_RESULT="$(inspect_ref version "$VERSION_REF")"
SHA_RESULT="$(inspect_ref sha "$SHA_REF")"

IFS=$'\t' read -r VERSION_EXISTS VERSION_DIGEST VERSION_CONFIG <<<"$VERSION_RESULT"
IFS=$'\t' read -r SHA_EXISTS SHA_DIGEST SHA_CONFIG <<<"$SHA_RESULT"

if [[ "$VERSION_EXISTS" == "true" && "$SHA_EXISTS" == "true" && "$VERSION_DIGEST" != "$SHA_DIGEST" ]]; then
  echo "::error::Immutable version/SHA tags exist but resolve to different manifest digests: version=$VERSION_DIGEST sha=$SHA_DIGEST" >&2
  exit 1
fi

{
  echo "local_image_id=$LOCAL_IMAGE_ID"
  echo "version_ref=$VERSION_REF"
  echo "sha_ref=$SHA_REF"
  echo "version_exists=$VERSION_EXISTS"
  echo "version_digest=$VERSION_DIGEST"
  echo "sha_exists=$SHA_EXISTS"
  echo "sha_digest=$SHA_DIGEST"
} >> "$OUTPUT_FILE"

printf 'GHCR immutable preflight: version_exists=%s sha_exists=%s local_image_id=%s\n' \
  "$VERSION_EXISTS" "$SHA_EXISTS" "$LOCAL_IMAGE_ID"
