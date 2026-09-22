"""交付工具公共读写；调用方不得输出秘密值。"""
import hashlib
import json
import re
from pathlib import Path


def properties(path):
    result = {}
    if not Path(path).is_file():
        return result
    for line in Path(path).read_text().splitlines():
        if line.strip() and not line.lstrip().startswith(('#', '!')):
            key, value = line.split('=', 1)
            result[key.strip()] = value.strip()
    return result


def write_properties(path, values):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(''.join(f'{k}={v}\n' for k, v in sorted(values.items())))


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def endpoints(root):
    source = (root / 'app/build.gradle.kts').read_text()
    table = re.search(r'val flavorEndpoints = mapOf\((.*?)\n\)', source, re.S)[1]
    dev = re.search(r'"dev" to mapOf\((.*?)\)', table, re.S)[1]
    return dict(re.findall(r'"([A-Z_]+)" to "([^"\n]*)"', dev))
