"""Compare paired drafts; score only genuinely verified/certified references."""
import argparse
import json
from pathlib import Path
from atc_dataset import read_rows, validate, attach_drafts, evaluate


def compare(rows, baseline, candidate):
    expected = {r['id'] for r in rows}
    for run in (baseline, candidate):
        attach_drafts(rows, run)  # validates IDs, uniqueness and matching audio hashes
        if {r['id'] for r in run} != expected:
            raise ValueError('Ambos experimentos deben cubrir el manifiesto completo')
    before = {r['id']: r for r in baseline}
    after = {r['id']: r for r in candidate}
    scored = {}
    for split in ('train', 'dev', 'test'):
        ids = {r['id'] for r in rows if r['split'] == split and r['status'] in ('verified', 'no_speech')}
        if ids:
            scored[split] = {'baseline': evaluate(rows, [before[i] for i in sorted(ids)], split),
                             'candidate': evaluate(rows, [after[i] for i in sorted(ids)], split)}
    return {'paired_clips': len(rows), 'changed_texts': sum(before[i]['text'] != after[i]['text'] for i in expected),
            'candidate_empty': sum(not after[i]['text'].strip() for i in expected),
            'verified_metrics_by_split': scored,
            'note': 'Text changes and empty outputs alone do not measure recognition quality; VAD may miss speech.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest', type=Path)
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    rows = validate(read_rows(args.manifest), args.manifest.parent)
    report = compare(rows, read_rows(args.baseline), read_rows(args.candidate))
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
