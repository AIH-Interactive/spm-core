"""Audit the actual Core tree and built jars; reports are artifacts, not origin proof by keyword."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import zipfile

IGNORED = {'.git', '.gradle', 'build', 'target', '.idea'}
FORBIDDEN = ('com/ysm/parser', 'com.ysm.parser', 'com/elfmcys/yesstevemodel',
             'com.elfmcys.yesstevemodel', 'ysm-core.dll', 'libysm-core.so', 'libysm-core.dylib')
FOREIGN_API = re.compile(r'^import\s+(?!java\.|dev\.spm\.core\.api\.)[^;]+;', re.MULTILINE)

def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()

def audit(root: Path, require_jars: bool = False) -> dict:
    errors, sources, artifacts = [], [], []
    for path in sorted(root.rglob('*')):
        if not path.is_file() or any(part in IGNORED for part in path.relative_to(root).parts):
            continue
        rel = path.relative_to(root).as_posix()
        if path.suffix not in {'.java', '.rs', '.gradle', '.toml', '.ps1', '.py'}:
            continue
        if rel == 'scripts/audit_core.py':
            sources.append({'path': rel, 'sha256': digest(path.read_bytes())})
            continue
        data = path.read_bytes()
        text = data.decode('utf-8-sig')
        executable = path.suffix in {'.java', '.rs', '.gradle', '.toml'}
        if executable:
            for symbol in FORBIDDEN:
                if symbol in text:
                    errors.append(f'{rel}: forbidden old-runtime reference {symbol}')
        if rel.startswith('api/src/main/java/') and FOREIGN_API.search(text):
            errors.append(f'{rel}: non-JDK public API import')
        if path.suffix in {'.rs', '.toml'} and re.search(r'target-cpu\s*=\s*["\x27]?native', text):
            errors.append(f'{rel}: development-machine CPU target in portable source')
        if path.suffix == '.java' and 'jdk.incubator.vector' in text:
            errors.append(f'{rel}: explicit SIMD belongs in Rust')
        sources.append({'path': rel, 'sha256': digest(data)})
    jars = sorted(root.glob('*/build/libs/*.jar'))
    runtime_jars = [p for p in jars if not p.name.endswith(('-sources.jar', '-javadoc.jar'))
                    and p.parent.parent.parent.name not in {'verification', 'integration-tests'}]
    if require_jars:
        expected = {'api', 'codec', 'format-ysm', 'format-bedrock', 'format-bbmodel', 'scene',
                    'acceleration-api', 'native-jni', 'render-cpu', 'runtime', 'format-v3d', 'format-common', 'parser'}
        built = {p.parent.parent.parent.name for p in runtime_jars}
        for missing in sorted(expected - built):
            errors.append(f'{missing}: expected runtime jar missing')
    for path in jars:
        rel = path.relative_to(root).as_posix()
        try:
            with zipfile.ZipFile(path) as jar:
                names = jar.namelist()
                if not path.name.endswith(('-sources.jar', '-javadoc.jar')) and not {'META-INF/spm-core/LICENSE', 'META-INF/spm-core/NOTICE'} <= set(names):
                    errors.append(f'{rel}: missing bundled license/notice')
                for name in names:
                    if name.endswith(('.dll', '.so', '.dylib', '.jar')):
                        errors.append(f'{rel}: unexpected nested executable {name}')
                    if not name.endswith('.class'):
                        continue
                    data = jar.read(name)
                    if len(data) < 8 or data[:4] != b'\xca\xfe\xba\xbe' or struct.unpack('>H', data[6:8])[0] != 61:
                        errors.append(f'{rel}/{name}: class is not Java17 bytecode')
                    for symbol in FORBIDDEN:
                        if symbol.encode() in data:
                            errors.append(f'{rel}/{name}: forbidden old-runtime constant {symbol}')
            artifacts.append({'path': rel, 'sha256': digest(path.read_bytes()), 'entries': len(names)})
        except zipfile.BadZipFile:
            errors.append(f'{rel}: corrupt jar')
    return {'schema': 1, 'scope': 'source/bytecode isolation and artifact contents; not blanket upstream origin certification',
            'sources': sources, 'artifacts': artifacts, 'errors': errors}

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--output', type=Path)
    parser.add_argument('--require-jars', action='store_true')
    args = parser.parse_args()
    report = audit(args.root.resolve(), args.require_jars)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({'sources': len(report['sources']), 'jars': len(report['artifacts']), 'errors': report['errors']}, ensure_ascii=False))
    return int(bool(report['errors']))

if __name__ == '__main__':
    raise SystemExit(main())
