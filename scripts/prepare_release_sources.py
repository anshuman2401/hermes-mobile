#!/usr/bin/env python3
"""Prepare a source-and-notice ZIP to distribute alongside a release APK.

Uses the reviewed upstream source archive hash. This does not build, sign,
publish, or certify an APK. It requires network unless --desugar-archive is set.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets/licenses'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--desugar-archive', type=Path)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/release-sources.zip')
    args = parser.parse_args()
    try:
        inventory = json.loads((ASSETS / 'release-inventory.json').read_text())
        source = inventory['source_archives'][0]
        if args.desugar_archive:
            data = args.desugar_archive.read_bytes()
        else:
            with urllib.request.urlopen(source['url'], timeout=60) as response:
                data = response.read()
        if hashlib.sha256(data).hexdigest() != source['sha256']:
            raise ValueError('Upstream source archive hash differs; review it before distribution.')
        for text in inventory['texts']:
            if hashlib.sha256((ASSETS / text['file']).read_bytes()).hexdigest() != text['sha256']:
                raise ValueError('Notice text hash differs: ' + text['file'])
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(args.output, 'w', compression=zipfile.ZIP_DEFLATED) as out:
            out.writestr(source['filename'], data)
            for file in sorted(ASSETS.iterdir()):
                if file.is_file():
                    out.write(file, 'licenses/' + file.name)
        print('Release source bundle prepared; distribute it with the matching APK.')
        print('SHA-256: ' + hashlib.sha256(args.output.read_bytes()).hexdigest())
        return 0
    except OSError:
        print('Source bundle preparation failed: source download or local input/output unavailable.', file=sys.stderr)
        return 1
    except (ValueError, KeyError, TypeError):
        print('Source bundle preparation failed: source or notice hash mismatch, or invalid inventory; review inputs.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
