#!/usr/bin/env python3
"""Combine Maven's runtime inventory with original notices from published artifacts.

Run after: mvn generate-resources dependency:build-classpath -DincludeScope=runtime
-Dmdep.outputFile=target/runtime-classpath.txt
Only the Python standard library is required. Sources are exact-version Maven Central
artifacts and cached under target, never fetched from moving upstream branches.
"""
import hashlib
import fnmatch
import json
import os
from pathlib import Path
import re
import sys
import urllib.request
import zipfile

def normalized_text(content):
    # Identity ignores layout/comment decoration and the Apache license URL scheme only.
    content = re.sub(r"(?m)^[ \t]*(?:/\*+|\*/|\*|//|#+)[ \t]?", "", content)
    content = content.replace("https://www.apache.org/licenses/", "http://www.apache.org/licenses/")
    return " ".join(content.split())


def register(content, catalog):
    """Share original paragraphs without losing notices, copyrights, or their order."""
    references = []
    content = content.replace("\r\n", "\n").replace("\r", "\n")
    for paragraph in re.split(
            r"\n(?:[ \t]*\*?[ \t]*\n|(?=[ \t]*Apache License[ \t]*\n))",
            content.strip()):
        key = normalized_text(paragraph)
        if not key:
            continue
        identifier = "T" + hashlib.sha256(key.encode("utf-8")).hexdigest()[:16]
        if identifier in catalog and normalized_text(catalog[identifier]) != key:
            raise ValueError("Shared-text identifier collision; refusing to lose an original")
        catalog.setdefault(identifier, paragraph.strip())
        references.append(identifier)
    return references


def register_document(content, name, catalog, documents):
    references = register(content, catalog)
    reconstructed = "\n\n".join(catalog[reference] for reference in references)
    if normalized_text(content) != normalized_text(reconstructed):
        raise ValueError(f"Incomplete original after deduplication: {name}")
    identifier = "D" + hashlib.sha256(" ".join(references).encode("utf-8")).hexdigest()[:16]
    if identifier in documents and documents[identifier]["references"] != references:
        raise ValueError("Original-document identifier collision")
    document = documents.setdefault(identifier, {"references": references, "paths": set()})
    document["paths"].add(name)
    return identifier


def generate(engine):
    engine = Path(engine).resolve()
    root = engine.parent
    inventory = (engine / 'target/generated-sources/license/THIRD-PARTY.txt').read_text()
    classpath = (engine / 'target/runtime-classpath.txt').read_text().strip().split(os.pathsep)
    coordinates = re.findall(r'\(([\w.\-]+):([\w.\-]+):([^ :]+) - ', inventory)
    by_filename = {f'{a}-{v}.jar': (g, a, v) for g, a, v in coordinates}
    if len(coordinates) != len(classpath):
        raise SystemExit(f'Inventory/classpath mismatch: {len(coordinates)} != {len(classpath)}')
    parts = ['THIRD-PARTY-NOTICES\nGenerated; do not edit. Runtime dependencies only.\n', inventory]
    parts.append('License selections for dual/composite licenses: see osv-scanner.toml.\n')
    catalog = {}
    documents = {}
    common = register_document(
        (root / 'config/licenses/originals/Apache-2.0.txt').read_text(),
        'Common Apache-2.0 original', catalog, documents)
    parts.append('Common Apache-2.0 original: ' + common + '\n')
    parts.append('Original texts below reference shared text blocks in their original order.\n'
                 'Read every referenced block, including repeated references, to reconstruct an original.\n')
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
        source = f'https://repo.maven.apache.org/maven2/{g.replace(".", "/")}/{a}/{v}/{a}-{v}.jar'
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
        references = []
        for name, content in sorted(notices.items()):
            identifier = register_document(content, name, catalog, documents)
            if identifier not in references:
                references.append(identifier)
        parts.append('Original documents: ' + ' '.join(references) + '\n')
    parts.append('\n=== SHARED ORIGINAL DOCUMENTS ===\n')
    for identifier, document in documents.items():
        parts.append(f'\n--- {identifier} ---\nOriginal paths: ' + '\n'.join(sorted(document["paths"])) + '\n')
        parts.append('Text blocks: ' + ' '.join(document["references"]) + '\n')
    parts.append('\n=== SHARED ORIGINAL TEXT BLOCKS ===\n')
    for identifier, content in catalog.items():
        parts.append(f'\n--- {identifier} ---\n{content}\n')
    output = engine / 'THIRD-PARTY-NOTICES'
    combined = '\n'.join(parts).replace('\r\n', '\n').replace('\r', '\n')
    output.write_text('\n'.join(line.rstrip() for line in combined.splitlines()) + '\n',
                      encoding='utf-8')
    print(f'{engine.name}: {len(coordinates)} runtime dependencies; {output.stat().st_size} bytes')


if __name__ == "__main__":
    generate(sys.argv[1])
