#!/usr/bin/env python3
"""Historical LLM-first experiment, not the current application or its quality gate.

Replays the archived experimental prompt, schema and decoding options.
No CAD, user files, credentials or paid services are involved.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path
import statistics
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
PROMPT = (ROOT / "tools/experiments/requirements/requirements-prompt.txt").read_text()
QUESTION_TEXT = (ROOT / "tools/experiments/requirements/requirement-questions.json").read_text()
QUESTIONS = json.loads(QUESTION_TEXT)
EVIDENCE = json.loads((ROOT / "tools/experiments/requirements/requirement-evidence.json").read_text())
PROMPT_HASH = hashlib.sha256((PROMPT + "\n" + QUESTION_TEXT).encode()).hexdigest()
FIELDS = {
    "use": ["UNKNOWN", "FUNCTIONAL", "DECORATIVE", "PROTOTYPE"],
    "load": ["UNKNOWN", "LIGHT", "NORMAL", "HIGH"],
    "loadKind": ["UNKNOWN", "STATIC", "IMPACT", "CYCLIC"],
    "environment": ["UNKNOWN", "INDOOR", "OUTDOOR", "HEAT"],
    "flexibility": ["UNKNOWN", "FLEXIBLE", "RIGID"],
    "goal": ["UNKNOWN", "DETAIL", "FIT", "SPEED", "ECONOMY"],
}


def evidence_choices(text):
    clauses = []
    for part in re.split(r"[.!?;,\n\r]+", text):
        remaining = part.strip()
        while remaining:
            end = min(180, len(remaining))
            if end < len(remaining):
                space = remaining.rfind(" ", 0, end + 1)
                if space > 0:
                    end = space
            clause = remaining[:end].strip()
            if clause:
                clauses.append(clause)
            remaining = remaining[end:].strip()
    return list(dict.fromkeys(clauses)) or [text[:180] or " "]


def verified(field, value, quote, description):
    if field == "use" and value == "FUNCTIONAL":
        words = re.findall(r"[^\W\d_]+", quote)
        if words and all(re.fullmatch(EVIDENCE["genericUseWords"], word, flags=re.I) for word in words):
            return "UNKNOWN"
    rule = EVIDENCE.get(field, {}).get(value)
    return value if not rule or re.search(rule, description, flags=re.I) else "UNKNOWN"


def payload(text, model, field):
    return {
        "model": model, "stream": False, "think": False, "keep_alive": "2m",
        "messages": [
            {"role": "system", "content": PROMPT},
            {"role": "user", "content": json.dumps({"description": text, "fragments": evidence_choices(text)}, ensure_ascii=False, separators=(",", ":"))
                + "\n\nQUESTION: " + field + ": " + QUESTIONS[field]},
        ],
        "format": {"anyOf": [{"const": ["UNKNOWN", ""]},
                       {"type": "array", "prefixItems": [
                           {"type": "string", "enum": [v for v in FIELDS[field] if v != "UNKNOWN"]},
                           {"type": "string", "enum": evidence_choices(text)}], "minItems": 2, "maxItems": 2}]},
        "options": {"temperature": 0, "num_predict": 128, "num_ctx": 4096, "num_thread": 3},
    }


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--url", required=True, help="Private Ollama base URL, e.g. http://127.0.0.1:11434")
    cli.add_argument("--model", default="qwen2.5:7b-instruct-q3_K_M")
    cli.add_argument("--limit", type=int, default=0)
    cli.add_argument("--case", action="append", default=[], help="Run only these case ids")
    cli.add_argument("--output", type=Path)
    args = cli.parse_args()
    cases = json.loads((ROOT / "src/test/resources/recommendation/requirements-cases.json").read_text())
    if args.case:
        cases = [case for case in cases if case["id"] in args.case]
    results = []
    for case in cases[:args.limit or None]:
        start = time.monotonic()
        result = {"id": case["id"], "expected": case["expected"], "model": args.model, "promptSha256": PROMPT_HASH}
        try:
            body = {}
            actual = {}
            for key, values in FIELDS.items():
                remaining = 90 - (time.monotonic() - start)
                assert remaining > 0, "overall interpretation deadline"
                request = urllib.request.Request(args.url.rstrip("/") + "/api/chat",
                    json.dumps(payload(case["text"], args.model, key)).encode(), {"Content-Type": "application/json"})
                with urllib.request.urlopen(request, timeout=remaining) as response:
                    envelope = json.load(response)
                assert envelope.get("done") and envelope.get("done_reason") != "length", "incomplete response"
                pair = json.loads(envelope["message"]["content"])
                assert isinstance(pair, list) and len(pair) == 2 and pair[0] in values, "invalid requirement"
                value, quote = pair
                assert isinstance(quote, str) and len(quote.strip()) <= 180, "invalid quote"
                if value == "UNKNOWN":
                    assert not quote, "unknown requirement has evidence"
                else:
                    assert quote.strip() and quote.strip().lower() in case["text"].lower(), "ungrounded fact: " + key
                body[key] = pair
                actual[key] = verified(key, value, quote, case["text"])
            if actual["goal"] == "UNKNOWN":
                found = {}
                for clause in evidence_choices(case["text"]):
                    if re.search(r"\b(не|нет|без|not|no|never)\b", clause, flags=re.I):
                        continue
                    for value, pattern in EVIDENCE["goalRecovery"].items():
                        if re.search(pattern, clause, flags=re.I):
                            found.setdefault(value, clause)
                if len(found) == 1:
                    actual["goal"] = next(iter(found))
            result["raw"] = body
            result["actual"] = actual
            result["pass"] = all(actual[key] in (value if isinstance(value, list) else [value]) for key, value in case["expected"].items())
        except Exception as error:
            result.update({"pass": False, "error": str(error)})
        result["seconds"] = round(time.monotonic() - start, 2)
        results.append(result)
        print(json.dumps(result, ensure_ascii=False), flush=True)
        if args.output:
            args.output.write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n")
    passed = sum(row["pass"] for row in results)
    durations = sorted(row["seconds"] for row in results)
    print(json.dumps({"model": args.model, "promptSha256": PROMPT_HASH, "passed": passed, "total": len(results),
                      "medianSeconds": statistics.median(durations), "maxSeconds": max(durations)}), flush=True)
    raise SystemExit(0 if passed == len(results) else 1)


if __name__ == "__main__":
    main()
