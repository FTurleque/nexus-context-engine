#!/usr/bin/env bash
set -euo pipefail

if ! command -v docker >/dev/null 2>&1; then
  echo 'docker is required for the Compose dotenv round-trip smoke' >&2
  exit 1
fi
if ! docker compose version >/dev/null 2>&1; then
  echo 'docker compose is required for the Compose dotenv round-trip smoke' >&2
  exit 1
fi

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
root="$repo/target/docker-dotenv-roundtrip"
rm -rf "$root"
mkdir -p "$root"
trap 'rm -rf "$root"' EXIT

# Keep this transformation intentionally identical to DotEnvQuoted in
# harden-windows-installer-source.ps1. The source contract checks below make a
# drift in the generated Inno helper fail this smoke rather than silently diverge.
python3 - "$root" <<'PY'
import json
from pathlib import Path
import sys

root = Path(sys.argv[1])
value = 'C:\\NEXUS path\\$HOME\\#literal-hash\\backslash "quoted" and spaces'
escaped = value.replace('$', '$$').replace('\\', '\\\\').replace('"', '\\"')
(root / '.env').write_text(f'NEXUS_TEST_VALUE="{escaped}"\n', encoding='utf-8')
(root / 'compose.yml').write_text(
    'services:\n'
    '  probe:\n'
    '    image: scratch\n'
    '    environment:\n'
    '      NEXUS_TEST_VALUE: "${NEXUS_TEST_VALUE}"\n',
    encoding='utf-8')
(root / 'expected.json').write_text(json.dumps({'value': value}), encoding='utf-8')
PY

grep -F "StringChangeEx(Result, '$', '$$', True);" "$repo/scripts/release/harden-windows-installer-source.ps1" >/dev/null
grep -F "StringChangeEx(Result, '\\', '\\\\', True);" "$repo/scripts/release/harden-windows-installer-source.ps1" >/dev/null
grep -F "StringChangeEx(Result, '\"', '\\\"', True);" "$repo/scripts/release/harden-windows-installer-source.ps1" >/dev/null

docker compose --env-file "$root/.env" -f "$root/compose.yml" config --format json > "$root/actual.json"
python3 - "$root" <<'PY'
import json
from pathlib import Path
import sys

root = Path(sys.argv[1])
expected = json.loads((root / 'expected.json').read_text(encoding='utf-8'))['value']
actual = json.loads((root / 'actual.json').read_text(encoding='utf-8'))
environment = actual['services']['probe']['environment']
value = environment['NEXUS_TEST_VALUE']
if value != expected:
    raise SystemExit(f'Compose dotenv round-trip mismatch\nExpected: {expected!r}\nActual:   {value!r}')
print('NEXUS Docker Compose dotenv literal round-trip PASS')
PY
