#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PREFLIGHT="$SCRIPT_DIR/ghcr-immutable-preflight.sh"
LOCAL_ID="sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
SAME_DIGEST="sha256:1111111111111111111111111111111111111111111111111111111111111111"
OTHER_DIGEST="sha256:2222222222222222222222222222222222222222222222222222222222222222"
OTHER_CONFIG="sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin"

cat > "$TMP/bin/docker" <<'SH'
#!/usr/bin/env bash
set -euo pipefail

LOCAL_ID="sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
SAME_DIGEST="sha256:1111111111111111111111111111111111111111111111111111111111111111"
OTHER_DIGEST="sha256:2222222222222222222222222222222222222222222222222222222222222222"
OTHER_CONFIG="sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

if [[ "$1 $2" == "image inspect" ]]; then
  echo "$LOCAL_ID"
  exit 0
fi

if [[ "$1 $2" != "manifest inspect" ]]; then
  echo "unexpected docker invocation: $*" >&2
  exit 98
fi

REF="${@: -1}"
LABEL="version"
[[ "$REF" == *":sha-"* ]] && LABEL="sha"

case "${SCENARIO:?}" in
  absent)
    echo "no such manifest: $REF" >&2
    exit 1
    ;;
  same)
    DIGEST="$SAME_DIGEST"
    CONFIG="$LOCAL_ID"
    ;;
  different)
    DIGEST="$SAME_DIGEST"
    CONFIG="$OTHER_CONFIG"
    ;;
  split-digest)
    CONFIG="$LOCAL_ID"
    if [[ "$LABEL" == "version" ]]; then DIGEST="$SAME_DIGEST"; else DIGEST="$OTHER_DIGEST"; fi
    ;;
  registry-error)
    echo "unauthorized: authentication required" >&2
    exit 1
    ;;
  *)
    echo "unknown scenario: $SCENARIO" >&2
    exit 97
    ;;
esac

cat <<JSON
{
  "Ref": "$REF",
  "Descriptor": {"digest": "$DIGEST"},
  "SchemaV2Manifest": {"config": {"digest": "$CONFIG"}}
}
JSON
SH
chmod +x "$TMP/bin/docker" "$PREFLIGHT"

run_success() {
  local scenario="$1"
  local output="$TMP/${scenario}.out"
  PATH="$TMP/bin:$PATH" SCENARIO="$scenario" \
    "$PREFLIGHT" ghcr.io/example/nexus 1.2.3 abcdef qualified:abc "$output"
  test -s "$output"
}

run_failure() {
  local scenario="$1"
  local output="$TMP/${scenario}.out"
  if PATH="$TMP/bin:$PATH" SCENARIO="$scenario" \
      "$PREFLIGHT" ghcr.io/example/nexus 1.2.3 abcdef qualified:abc "$output"; then
    echo "scenario '$scenario' unexpectedly succeeded" >&2
    exit 1
  fi
}

run_success absent
grep -q '^version_exists=false$' "$TMP/absent.out"
grep -q '^sha_exists=false$' "$TMP/absent.out"

run_success same
grep -q '^version_exists=true$' "$TMP/same.out"
grep -q '^sha_exists=true$' "$TMP/same.out"
grep -q "^version_digest=$SAME_DIGEST$" "$TMP/same.out"
grep -q "^sha_digest=$SAME_DIGEST$" "$TMP/same.out"

run_failure different
run_failure split-digest
run_failure registry-error

printf 'GHCR immutable preflight tests passed\n'
