#!/usr/bin/env python3
"""Sequential native-slicer check against an isolated backend, using the public fixture only."""
import argparse
import json
import time
import urllib.request
import uuid
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', required=True, help='Isolated backend URL; do not use the live site')
    parser.add_argument('--fixture', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    fixture = args.fixture.read_bytes()
    cases = [
        ('light', 'плата для микросхемы, просто подставочка, нужно совсем слабо', {}, 10, 2, .20, 'PLA'),
        ('high', 'огромная нагрузка будет, около 1500 Н', {}, 35, 4, .20, 'PLA'),
        ('outdoor', 'Корпус уличного датчика, будет постоянно под дождём и солнцем.', {}, 20, 3, .20, 'PETG'),
        ('impact', 'Красивый корпус датчика, важно выдерживать удары и падения.', {}, 35, 5, .12, 'PLA'),
        ('manual', 'огромная нагрузка будет, около 1500 Н', {'minimumInfillPercent': '10', 'minimumPerimeters': '2', 'materialProfileId': 'prusament-pla'}, 10, 2, .20, 'PLA'),
        ('vague', 'Нужна нормальная штука, сделайте хорошо.', {}, 15, 2, .20, 'PLA'),
    ]
    report = []
    for label, description, manual, fill, walls, layer, family in cases:
        boundary = 'printlab-check-' + uuid.uuid4().hex
        fields = {'description': description, **manual}
        parts = [f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode() for key, value in fields.items()]
        parts += [f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="stepped-block.stl"\r\nContent-Type: model/stl\r\n\r\n'.encode(), fixture, f'\r\n--{boundary}--\r\n'.encode()]
        started = time.monotonic()
        request = urllib.request.Request(args.url.rstrip('/') + '/api/print-plans/analyze-stl', b''.join(parts), {'Content-Type': 'multipart/form-data; boundary=' + boundary})
        with urllib.request.urlopen(request, timeout=200) as response:
            result = json.load(response)
        selected = next(c for c in result['candidates'] if c['id'] == result['selectedCandidateId'])
        advice = result['recommendation']
        assert advice['source'] == 'RULES', advice
        assert len(result['candidates']) == 6, result
        assert selected['infillPercent'] >= fill and selected['perimeters'] >= walls, selected
        assert selected['layerHeightMm'] == layer, selected
        assert selected['estimatedMaterialG'] > 0 and selected['estimatedPrintTimeMin'] > 0, selected
        assert result['materialName'] == 'Prusament ' + family, result['materialName']
        assert len(advice['items']) <= 12, advice
        if label in ('light', 'manual'):
            assert selected['infillPercent'] < 35 and selected['perimeters'] < 4, selected
        if label == 'high':
            assert '1500 Н' in str(advice) and 'не вычисленная несущая способность' in str(advice), advice
        if label == 'manual':
            assert 'Ручной выбор сохранён' in str(advice), advice
        if label == 'vague':
            assert 'Условия, которые нужно уточнить' in str(advice), advice
        row = {'case': label, 'seconds': round(time.monotonic() - started, 2), 'response': result}
        report.append(row)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        print(json.dumps({'case': label, 'seconds': row['seconds'], 'selected': selected, 'material': result['materialName']}, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
