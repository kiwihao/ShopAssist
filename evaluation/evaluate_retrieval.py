"""Run a small, repeatable retrieval check against the local Java service."""

import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CASES = ROOT / "evaluation" / "retrieval_cases.jsonl"
OUTPUT = ROOT / ".runtime" / "evaluation-retrieval.json"
URL = "http://127.0.0.1:8081/api/retrieval/search"
TOP_K = 3


def search(case):
    payload = {"productId": case["product_id"], "query": case["question"], "topK": TOP_K}
    request = urllib.request.Request(
        URL,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def main():
    cases = [json.loads(line) for line in CASES.read_text(encoding="utf-8").splitlines() if line.strip()]
    results = []
    for case in cases:
        try:
            response = search(case)
            matches = response["matches"]
            wrong_product = any(item["product_id"] != case["product_id"] for item in matches)
            hit = any(case["expected_heading"] in item["heading"] for item in matches)
            status = "PASS" if hit and not wrong_product else "FAIL"
            results.append({"case": case, "status": status, "response": response})
            print(f"{case['id']}: {status} (heading_hit={hit}, wrong_product={wrong_product})")
        except (urllib.error.URLError, TimeoutError, ValueError, KeyError, OSError) as exc:
            results.append({"case": case, "status": "ERROR", "error": str(exc)})
            print(f"{case['id']}: ERROR ({exc})")

    completed = [item for item in results if item["status"] != "ERROR"]
    hits = sum(item["status"] == "PASS" for item in completed)
    print(f"Passed: {hits}/{len(completed)} successful calls; errors: {len(results) - len(completed)}.")
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps({"top_k": TOP_K, "results": results}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Detailed results: {OUTPUT}")
    return 0 if results and all(item["status"] == "PASS" for item in results) else 1


if __name__ == "__main__":
    sys.exit(main())
