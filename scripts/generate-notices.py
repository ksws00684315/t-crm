#!/usr/bin/env python3
"""Combine Maven's runtime inventory with original notices from published artifacts.

Run after: mvn generate-resources dependency:build-classpath -DincludeScope=runtime
-Dmdep.outputFile=target/runtime-classpath.txt
Only the Python standard library is required. Sources are exact-version Maven Central
artifacts and cached under target, never fetched from moving upstream branches.
"""
import hashlib
import json
import fnmatch
import os
from pathlib import Path
import re
import sys
import urllib.request
import zipfile

engine = Path(sys.argv[1]).resolve()
root = engine.parent
inventory = (engine / 'target/generated-sources/license/THIRD-PARTY.txt').read_text()
classpath = (engine / 'target/runtime-classpath.txt').read_text().strip().split(os.pathsep)
coordinates = re.findall(r'\(([\w.\-]+):([\w.\-]+):([^ :]+) - ', inventory)
by_filename = {f'{a}-{v}.jar': (g, a, v) for g, a, v in coordinates}
if len(coordinates) != len(classpath):
    raise SystemExit(f'Inventory/classpath mismatch: {len(coordinates)} != {len(classpath)}')
parts = ['THIRD-PARTY-NOTICES\nGenerated; do not edit. Runtime dependencies only.\n', inventory]
parts.append('License selections for dual/composite licenses: see osv-scanner.toml.\n')
parts.append('Common Apache-2.0 full license text:\n' + (root / 'config/licenses/originals/Apache-2.0.txt').read_text())
cache = engine / 'target/license-sources'
cache.mkdir(parents=True, exist_ok=True)
supplements = json.loads((root / 'config/licenses/originals.json').read_text())

def texts(archive):
    result = {}
    for name in sorted(archive.namelist()):
        if name.endswith('/') or name.endswith('.class'):
            continue
        if re.search(r'(?:^|/)(?:LICENSE[^/]*|NOTICE[^/]*|COPYING[^/]*|COPYRIGHT[^/]*)$', name, re.I):
            result[name] = archive.read(name).decode('utf-8', errors='replace').strip()
    return result

for filename in sorted(classpath, key=lambda x: Path(x).name):
    jar = Path(filename)
    g, a, v = by_filename[jar.name]
    coord = f'{g}:{a}:{v}'
    with zipfile.ZipFile(jar) as archive:
        notices = texts(archive)
    source = 'packaged JAR'
    if not notices:
        source_name = f'{a}-{v}-sources.jar'
        downloaded = cache / source_name
        url = f'https://repo.maven.apache.org/maven2/{g.replace(".", "/")}/{a}/{v}/{source_name}'
        if not downloaded.exists():
            urllib.request.urlretrieve(url, downloaded)
        source = url
        with zipfile.ZipFile(downloaded) as archive:
            notices = texts(archive)
            # Preserve original source copyright/license headers when upstream omits LICENSE.
            for name in sorted(archive.namelist()):
                if not name.endswith('.java'):
                    continue
                content = archive.read(name).decode('utf-8', errors='replace')
                header = content[:content.find('package ')] if 'package ' in content else ''
                if re.search(r'copyright|license|public domain|permission', header, re.I):
                    notices['Source copyright/license header: ' + name] = header.strip()
        for pattern, entry in supplements.items():
            if fnmatch.fnmatchcase(coord, pattern):
                notices['Upstream original: ' + entry['url']] = (root / 'config/licenses/originals' / entry['file']).read_text()
        if not notices:
            raise SystemExit(f'No original license/copyright text for {coord}; audit required')
    parts.append(f'\n=== {coord} ===\nOriginal source: {source}\n')
    seen = set()
    for name, content in sorted(notices.items()):
        digest = hashlib.sha256(content.encode()).hexdigest()
        if digest in seen:
            continue
        seen.add(digest)
        parts.append(f'\n--- {name} ---\n{content}\n')
output = engine / 'THIRD-PARTY-NOTICES'
output.write_text('\n'.join(parts), encoding='utf-8')
print(f'{engine.name}: {len(coordinates)} runtime dependencies; {output.stat().st_size} bytes')
