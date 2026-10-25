#!/usr/bin/env python3
"""Verify pinned manufacturer PDFs. This never overwrites curated values or hashes."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, default=Path(__file__).resolve().parents[1]
                        / "src/main/resources/catalog/materials.json")
    args = parser.parse_args()
    catalog = json.loads(args.catalog.read_text(encoding="utf-8"))
    failures = 0
    with tempfile.TemporaryDirectory(prefix="printlab-evidence-") as directory:
        target = Path(directory) / "source.pdf"
        for material in catalog["materials"]:
            source = material["source"]
            try:
                subprocess.run(["curl", "--silent", "--show-error", "--location", "--fail",
                                "--proto", "=https", "--proto-redir", "=https",
                                "--max-time", "45", "--max-filesize", "10485760",
                                "--user-agent", "Mozilla/5.0", "--output", str(target), source["url"]],
                               check=True, timeout=50)
                data = target.read_bytes()
                if not data.startswith(b"%PDF-"):
                    raise ValueError("source is not a PDF")
                if hashlib.sha256(data).hexdigest() != source["sha256"]:
                    raise ValueError("source changed; inspect the new edition before updating data")
                print(f'OK {material["id"]} ({source["version"]}, {source["publishedOn"]})', flush=True)
            except (OSError, ValueError, subprocess.SubprocessError) as error:
                failures += 1
                print(f'FAILED {material["id"]}: {error}', flush=True)
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
