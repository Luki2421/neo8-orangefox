#!/usr/bin/env python3
"""Use public HTTPS remotes and pin the AERA forks for the Neo8 experiment."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

PROJECT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest-root', type=Path, required=True)
    args = parser.parse_args()
    pins = json.loads((PROJECT / 'aera-sources.json').read_text())['projects']
    seen = set()
    for name in ('twrp-default.xml', 'aera.xml'):
        path = args.manifest_root / name
        tree = ET.parse(path)
        for remote in tree.getroot().findall('remote'):
            if remote.get('name') == 'AERA':
                remote.set('fetch', 'https://github.com/AERA-Recovery')
        for item in tree.getroot().findall('project'):
            if item.get('remote') == 'AERA':
                pin = pins[item.get('path')]
                if pin['name'] != item.get('name'):
                    raise ValueError('Unexpected AERA project: ' + item.get('path'))
                item.set('revision', pin['commit'])
                seen.add(item.get('path'))
        tree.write(path, encoding='utf-8', xml_declaration=True)
    if seen != set(pins):
        raise ValueError('AERA manifest does not match pinned projects')
    print(f'Configured HTTPS and pinned {len(seen)} AERA projects')

if __name__ == '__main__':
    main()
