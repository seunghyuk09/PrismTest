"""Build and exercise the exact shared C++ detector, including the user-labelled video frame.

Requires a C++17 compiler; no Python imaging/ML packages. This is a two-point localization
regression, not a population-level sensitivity/specificity or physical-size validation.
"""
import argparse
import json
import math
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--sanitize', action='store_true')
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    cpp = root / 'android/app/src/main/cpp'
    fixture = root / 'android/app/src/test/resources/user-dig-frame-02s.pgm'
    labels = json.loads(fixture.with_suffix('.json').read_text())
    with tempfile.TemporaryDirectory(prefix='prism-dig-tests-') as tmp:
        executable = str(Path(tmp) / 'dig-test')
        flags = ['-O1', '-g', '-fsanitize=address,undefined', '-fno-omit-frame-pointer'] if args.sanitize else ['-O2']
        subprocess.run(['g++', '-std=c++17', '-Wall', '-Wextra', '-Werror', *flags,
                        str(cpp / 'dig_detector.cpp'), str(cpp / 'tests/dig_detector_test.cpp'),
                        '-o', executable], check=True)
        subprocess.run([executable], check=True)
        output = subprocess.run([executable, str(fixture)], check=True, capture_output=True, text=True)
        result = json.loads(output.stdout)
    matches = []
    for x, y in labels['confirmed_spots_approx_xy']:
        candidates = result['candidates']
        rank, nearest = min(enumerate(candidates, 1), key=lambda item: math.hypot(item[1]['x'] - x, item[1]['y'] - y))
        distance = math.hypot(nearest['x'] - x, nearest['y'] - y)
        assert distance <= labels['annotation_tolerance_px'], (x, y, distance)
        assert rank <= 2, (x, y, rank)
        matches.append({'annotation_xy': [x, y], 'candidate_rank': rank, 'distance_px': round(distance, 3)})
    assert len({m['candidate_rank'] for m in matches}) == 2
    report = {'matched_user_points': matches, 'physical_pieces': 1,
              'other_candidates_unlabelled': len(result['candidates']) - len(matches),
              'result': result, 'scope': 'Single labelled frame; no false-positive rate or mm calibration claimed.'}
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + '\n')
    print('PASS user-marked dig locations are the top two candidates; other points remain unlabelled.')


if __name__ == '__main__':
    main()
