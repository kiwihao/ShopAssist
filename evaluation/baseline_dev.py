"""Provisional development-set retrieval baseline; no paid chat calls."""
import hashlib
import json
import statistics
import subprocess
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / 'evaluation' / 'retrieval_dev_draft.jsonl'
URL = 'http://127.0.0.1:8081/api/retrieval/search'
TOP_K = 3


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def evidence_hit(evidence, match, product):
    """Line overlap is a coarse proxy, not proof of semantic sufficiency."""
    return (match['product_id'] == product and match['filename'] == evidence['filename']
            and match['version'] == evidence['version']
            and match['line_start'] <= evidence['line_end']
            and evidence['line_start'] <= match['line_end'])


def score(case, matches):
    evidence = case['evidence']
    if not evidence:
        raise ValueError('Evidence is required for this provisional dataset')
    found = [any(evidence_hit(item, match, case['product_id']) for match in matches) for item in evidence]
    return {'hit_at_3': any(found), 'evidence_recall_at_3': sum(found) / len(found),
            'evidence_hits': found, 'wrong_product': any(m['product_id'] != case['product_id'] for m in matches)}


def search(case):
    body = {'productId': case['product_id'], 'query': case['question'], 'topK': TOP_K}
    request = urllib.request.Request(URL, data=json.dumps(body, ensure_ascii=False).encode('utf-8'),
        headers={'Content-Type': 'application/json; charset=utf-8'}, method='POST')
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def main():
    cases = [json.loads(line) for line in DATA.read_text(encoding='utf-8').splitlines() if line.strip()]
    if not cases or len({case['id'] for case in cases}) != len(cases):
        raise ValueError('Dataset must contain unique case IDs')
    try:
        commit = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip()
        dirty = bool(subprocess.run(['git', 'status', '--porcelain'], cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip())
    except (OSError, subprocess.CalledProcessError):
        commit, dirty = None, None
    results = []
    for case in cases:
        try:
            response = search(case)
            matches = response['matches']
            if not isinstance(matches, list) or len(matches) > TOP_K or response['productId'] != case['product_id']:
                raise ValueError('Unexpected retrieval response')
            result = {'case': case, 'status': 'OK', 'score': score(case, matches), 'response': response}
            print(f"{case['id']}: hit={result['score']['hit_at_3']} recall={result['score']['evidence_recall_at_3']:.2f} wrong_product={result['score']['wrong_product']}")
        except (urllib.error.URLError, TimeoutError, OSError, ValueError, KeyError, TypeError) as exc:
            result = {'case': case, 'status': 'ERROR', 'error': str(exc)}
            print(f"{case['id']}: ERROR {exc}")
        results.append(result)
    ok = [x for x in results if x['status'] == 'OK']
    summary = {'total': len(results), 'successful': len(ok), 'errors': len(results)-len(ok),
               'hit_at_3': sum(x['score']['hit_at_3'] for x in ok)/len(ok) if ok else None,
               'macro_evidence_recall_at_3': sum(x['score']['evidence_recall_at_3'] for x in ok)/len(ok) if ok else None,
               'wrong_product_requests': sum(x['score']['wrong_product'] for x in ok),
               'median_ms': {key: statistics.median(x['response'][key] for x in ok) if ok else None
                             for key in ('embeddingMs', 'searchMs', 'elapsedMs')}}
    now = datetime.now(timezone.utc)
    report = {'created_utc': now.isoformat(), 'dataset': DATA.name, 'dataset_sha256': sha256(DATA), 'script_sha256': sha256(Path(__file__)),
              'documents_sha256': {name: sha256(ROOT/'data'/'demo'/'documents'/name) for name in sorted({e['filename'] for c in cases for e in c['evidence']})},
              'git_commit': commit, 'working_tree_dirty': dirty, 'top_k': TOP_K,
              'warning': 'Provisional AI draft labels; line overlap is not semantic evidence; local document hashes do not verify database content.', 'summary': summary, 'results': results}
    output_dir = ROOT/'.runtime'/'retrieval-baselines'
    output_dir.mkdir(parents=True, exist_ok=True)
    path = output_dir / f"dev-{now.strftime('%Y%m%dT%H%M%S%fZ')}.json"
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=False)); print(f'Results: {path}')
    return 1 if summary['errors'] or summary['wrong_product_requests'] else 0


if __name__ == '__main__':
    sys.exit(main())
