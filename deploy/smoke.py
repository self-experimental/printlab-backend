#!/usr/bin/env python3
"""A real, bounded STL calculation; uses only the public synthetic fixture."""
import json
import sys
import urllib.request
from pathlib import Path


def check(base_url, fixture):
    boundary = 'printlab-deployment-smoke'
    fields = dict(materialProfileId='prusament-pla', priority='BALANCED',
                  layerHeightMm='0.20', minimumInfillPercent='15',
                  minimumPerimeters='2', supportMode='AUTO',
                  maxPrintTimeMin='0', maxMaterialConsumptionG='0')
    parts = []
    for name, value in fields.items():
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
    parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="stepped-block.stl"\r\nContent-Type: application/octet-stream\r\n\r\n'.encode())
    parts.extend([Path(fixture).read_bytes(), f'\r\n--{boundary}--\r\n'.encode()])
    request = urllib.request.Request(base_url + '/api/print-plans/analyze-stl',
        data=b''.join(parts), headers={'Content-Type': 'multipart/form-data; boundary=' + boundary})
    with urllib.request.urlopen(request, timeout=200) as response:
        result = json.load(response)
    candidates = result['candidates']
    if len(candidates) != 6 or result['plannedCandidateCount'] != 6:
        raise RuntimeError('Synthetic STL did not produce all six candidates')
    selected = next(c for c in candidates if c['id'] == result['selectedCandidateId'])
    if not (selected['meetsConstraints'] and selected['estimatedMaterialG'] > 0
            and selected['estimatedPrintTimeMin'] > 0):
        raise RuntimeError('Invalid slicing result')
    print('Native slicing smoke passed: 6 candidates', flush=True)


if __name__ == '__main__':
    check(sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:8080',
          Path(__file__).with_name('stepped-block.stl'))
