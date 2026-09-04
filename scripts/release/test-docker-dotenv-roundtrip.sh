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

# `docker compose config` serializes a literal dollar as `$$` in the canonical
# Compose model so that a later Compose parse still produces one literal `$`.
# The installer contract above deliberately keeps the .env source at a single
# `$`; compare against Compose's canonical representation instead of treating
# its escaping layer as a mutation of the runtime value.
expected_config_value = expected.replace('$', '$$')
if value != expected_config_value:
    raise SystemExit(
        'Compose dotenv canonical serialization mismatch\n'
        f'Expected model: {expected_config_value!r}\n'
        f'Actual model:   {value!r}')
print('NEXUS Docker Compose dotenv literal serialization PASS')
PY

# The official Compose template must use one deployment source of truth for both
# the Docker host bind and the declaration consumed by the in-container guard.
# This is what makes the default safe and makes an explicit remote bind visible
# to NEXUS instead of pretending it is a loopback-forward deployment.
compose_template="$repo/packaging/docker/docker-compose.yml.template"
rest_token='6df1462d571a6925e3bc3934ee10c6c55a965116fb47e2bc4db77ac7a5d69d34'
NEXUS_REST_API_TOKEN="$rest_token" \
  docker compose -f "$compose_template" config --format json > "$root/compose-default.json"
NEXUS_REST_API_TOKEN="$rest_token" NEXUS_DOCKER_BIND_ADDRESS='0.0.0.0' \
  docker compose -f "$compose_template" config --format json > "$root/compose-remote.json"

python3 - "$root" <<'PY'
import json
from pathlib import Path
import sys

root = Path(sys.argv[1])
def load(name):
    return json.loads((root / name).read_text(encoding='utf-8'))['services']['nexus']

def host_ip(service):
    ports = service.get('ports', [])
    if len(ports) != 1:
        raise SystemExit(f'Expected exactly one REST port mapping, got {ports!r}')
    port = ports[0]
    if isinstance(port, dict):
        return port.get('host_ip')
    # Older Compose canonical JSON can keep the compact string form.
    return str(port).split(':', 1)[0]

def declared_forward(service):
    environment = service.get('environment', {})
    if isinstance(environment, list):
        values = dict(item.split('=', 1) for item in environment if '=' in item)
        return values.get('NEXUS_DOCKER_HOST_FORWARD_ADDRESS')
    return environment.get('NEXUS_DOCKER_HOST_FORWARD_ADDRESS')

def check(service, expected):
    actual_bind = host_ip(service)
    actual_declared = declared_forward(service)
    if actual_bind != expected or actual_declared != expected:
        raise SystemExit(
            'Docker loopback-forward Compose contract diverged: '
            f'expected bind/declaration={expected!r}, bind={actual_bind!r}, declaration={actual_declared!r}')

check(load('compose-default.json'), '127.0.0.1')
check(load('compose-remote.json'), '0.0.0.0')
print('NEXUS Docker Compose host-forward declaration PASS')
PY
