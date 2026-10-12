#!/usr/bin/env python3
"""Verify the reviewed release artifact/notice inventory without network access.

Export current inputs with license-inputs.init.gradle first. Optional --apk checks
that a particular APK contains the exact checked-in notice assets.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets/licenses'


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def verify(inputs, assets=ASSETS, apk=None):
    inventory = json.loads((assets / 'release-inventory.json').read_text())
    expected = {(r['coordinate'], r['configuration'], r['sha256']) for r in inventory['artifacts']}
    actual = {(r['coordinate'], r['configuration'], sha256(Path(r['file']).read_bytes())) for r in inputs}
    if actual != expected:
        raise ValueError('Resolved release artifacts differ from the reviewed notice inventory; audit dependency changes.')
    texts = {r['file']: r for r in inventory['texts']}
    for name, entry in texts.items():
        if Path(name).name != name or sha256((assets / name).read_bytes()) != entry['sha256']:
            raise ValueError('Notice asset hash or filename mismatch: ' + name)
    for row in inventory['artifacts']:
        if not row['notice_files'] or any(n not in texts for n in row['notice_files']):
            raise ValueError('Missing notice coverage: ' + row['coordinate'])
    if apk:
        with zipfile.ZipFile(apk) as archive:
            for path in assets.iterdir():
                if path.is_file() and archive.read('assets/licenses/' + path.name) != path.read_bytes():
                    raise ValueError('APK notice differs: ' + path.name)
    return len(expected), len(texts)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inputs', type=Path, default=ROOT / 'build/license-inputs.json')
    parser.add_argument('--apk', type=Path)
    args = parser.parse_args()
    try:
        artifacts, texts = verify(json.loads(args.inputs.read_text()), apk=args.apk)
    except OSError:
        print('Release license inventory check failed: required input or artifact unavailable.', file=sys.stderr)
        return 1
    except (ValueError, KeyError, TypeError, zipfile.BadZipFile):
        print('Release license inventory check failed: artifact, notice, inventory or APK mismatch; review dependency and asset changes.', file=sys.stderr)
        return 1
    print(f'Release license inventory: {artifacts} artifacts, {texts} notice texts verified' +
          ('; APK assets verified' if args.apk else '; APK not checked'))
    return 0


if __name__ == '__main__':
    sys.exit(main())
