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
# harden-windows-installer-source.ps1. Compose documents single-quoted .env
# values as literal; only an embedded single quote needs escaping.
python3 - "$root" <<'PY'
import json
from pathlib import Path
import sys

root = Path(sys.argv[1])
value = 'C:\\NEXUS path\\$HOME\\#literal-hash\\backslash "quoted" and it\'s literal'
escaped = value.replace("'", "\\'")
(root / '.env').write_text(f"NEXUS_TEST_VALUE='{escaped}'\n", encoding='utf-8')
(root / 'compose.yml').write_text(
    'services:\n'
    '  probe:\n'
    '    image: scratch\n'
    '    environment:\n'
    '      NEXUS_TEST_VALUE: "${NEXUS_TEST_VALUE}"\n',
    encoding='utf-8')
(root / 'expected.json').write_text(json.dumps({'value': value}), encoding='utf-8')
PY

python3 - "$repo/scripts/release/harden-windows-installer-source.ps1" <<'PY'
from pathlib import Path
import sys

source = Path(sys.argv[1]).read_text(encoding='utf-8-sig')
required = (
    "function DotEnvQuoted(Value: String): String;",
    "StringChangeEx(Result, '''',",
    "Result := '''' + Result + '''';",
)
missing = [needle for needle in required if needle not in source]
if missing:
    raise SystemExit('DotEnvQuoted source contract missing: ' + ', '.join(repr(item) for item in missing))
if "StringChangeEx(Result, '$', '$$', True);" in source:
    raise SystemExit('DotEnvQuoted must not double dollar signs inside single-quoted .env values')
PY

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