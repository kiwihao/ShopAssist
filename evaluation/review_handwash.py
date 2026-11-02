"""Collect two real answers for manual comparison; do not grade semantics automatically."""

import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CASES = ROOT / "evaluation" / "handwash_cases.jsonl"
OUTPUT = ROOT / ".runtime" / "handwash-review.json"
URL = "http://127.0.0.1:8081/api/qa"


def main():
    cases = [json.loads(line) for line in CASES.read_text(encoding="utf-8").splitlines() if line.strip()]
    results = []
    for case in cases:
        payload = {"productId": case["product_id"], "question": case["question"]}
        request = urllib.request.Request(
            URL, data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={"Content-Type": "application/json; charset=utf-8"}, method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                result = {"case": case, "http": response.status, "response": json.load(response), "manual_review": "PENDING"}
            print(f"{case['id']}: HTTP {result['http']}, manual review pending")
        except (urllib.error.URLError, TimeoutError, OSError, ValueError) as exc:
            result = {"case": case, "error": str(exc), "manual_review": "NOT_REVIEWABLE"}
            print(f"{case['id']}: ERROR ({exc})")
        results.append(result)
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Detailed results: {OUTPUT}")
    return 1 if any("error" in result for result in results) else 0


if __name__ == "__main__":
    sys.exit(main())
