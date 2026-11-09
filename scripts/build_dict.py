#!/usr/bin/env python3
"""
Builds the bundled English-Chinese dictionary for Lingua.

Downloads the ECDICT CSV (https://github.com/skywind3000/ECDICT, MIT license),
keeps the most frequent words, and writes a compact SQLite database to
app/src/main/assets/databases/ecdict.db (committed to the repo; the app copies
it out of the APK on first run).

Usage:
    python3 scripts/build_dict.py [--top N] [--out PATH]

Requires only the Python standard library.
"""

import argparse
import csv
import io
import sqlite3
import sys
import urllib.request
from datetime import datetime, timezone

CSV_URL = "https://raw.githubusercontent.com/skywind3000/ECDICT/master/ecdict.csv"
DEFAULT_TOP = 60_000
DEFAULT_OUT = "app/src/main/assets/databases/ecdict.db"

# ECDICT CSV columns (fixed order since 2017).
COLUMNS = [
    "word", "phonetic", "definition", "translation", "pos", "collins",
    "oxford", "tag", "bnc", "frq", "exchange", "detail", "audio",
]

MAX_FIELD_CHARS = 2_000


def download_csv(url: str) -> str:
    print(f"Downloading {url} ...")
    # Prefer curl: the macOS python.org build ships no CA bundle, and curl's TLS
    # setup follows the system keychain.
    try:
        import subprocess

        result = subprocess.run(
            ["curl", "-fsSL", "--retry", "3", url],
            capture_output=True,
            timeout=600,
        )
        if result.returncode == 0 and result.stdout:
            data = result.stdout
            if len(data) < 1_000_000:
                raise RuntimeError(f"Download looks truncated ({len(data)} bytes)")
            print(f"Downloaded {len(data) / 1e6:.1f} MB")
            return data.decode("utf-8", errors="replace")
    except (FileNotFoundError, subprocess.SubprocessError):
        pass

    request = urllib.request.Request(url, headers={"User-Agent": "lingua-build/1.0"})
    try:
        with urllib.request.urlopen(request, timeout=300) as response:
            data = response.read()
    except urllib.error.URLError:
        # Last resort: unverified TLS (some corporate MITM proxies break cert checks).
        import ssl

        context = ssl._create_unverified_context()
        with urllib.request.urlopen(request, timeout=300, context=context) as response:
            data = response.read()
    if len(data) < 1_000_000:
        raise RuntimeError(f"Download looks truncated ({len(data)} bytes)")
    print(f"Downloaded {len(data) / 1e6:.1f} MB")
    return data.decode("utf-8", errors="replace")


def parse_int(value: str) -> int:
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return 0


def entry_score(row: dict) -> int:
    """Higher = keep. Frequency dominates; Oxford/core tags get a strong boost."""
    frq = parse_int(row.get("frq", ""))
    collins = parse_int(row.get("collins", ""))
    oxford = parse_int(row.get("oxford", ""))
    bnc = parse_int(row.get("bnc", ""))
    score = frq * 100_000
    score += min(collins, 5) * 10_000
    score += min(oxford, 1) * 5_000
    if bnc > 0:
        score += max(0, 80_000 - bnc)
    # Oxford core words and starred Collins words are always valuable.
    if oxford == 1 or collins >= 1:
        score += 200_000
    return score


def clamp(value: str) -> str:
    return value[:MAX_FIELD_CHARS] if value else ""


def main() -> None:
    parser = argparse.ArgumentParser(description="Build the Lingua ECDICT subset.")
    parser.add_argument("--top", type=int, default=DEFAULT_TOP)
    parser.add_argument("--out", default=DEFAULT_OUT)
    parser.add_argument("--csv", help="use a local CSV file instead of downloading")
    args = parser.parse_args()

    text = (
        open(args.csv, encoding="utf-8", errors="replace").read()
        if args.csv
        else download_csv(CSV_URL)
    )

    print("Parsing CSV ...")
    rows = []
    seen = set()
    for row in csv.DictReader(io.StringIO(text)):
        word = (row.get("word") or "").strip()
        translation = (row.get("translation") or "").strip()
        definition = (row.get("definition") or "").strip()
        if not word:
            continue
        if not translation and not definition:
            continue
        if word in seen:
            continue
        seen.add(word)
        rows.append((entry_score(row), word, row))

    rows.sort(key=lambda item: (item[0], item[1]), reverse=True)
    selected = rows[: args.top]
    print(f"Kept {len(selected)} of {len(rows)} usable entries")

    print(f"Writing SQLite to {args.out} ...")
    import os

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    if os.path.exists(args.out):
        os.remove(args.out)

    db = sqlite3.connect(args.out)
    db.execute(
        """
        CREATE TABLE dict_entries(
          word TEXT NOT NULL,
          word_lc TEXT NOT NULL,
          phonetic TEXT NOT NULL DEFAULT '',
          definition TEXT NOT NULL DEFAULT '',
          translation TEXT NOT NULL DEFAULT '',
          pos TEXT NOT NULL DEFAULT '',
          collins INTEGER NOT NULL DEFAULT 0,
          oxford INTEGER NOT NULL DEFAULT 0,
          tag TEXT NOT NULL DEFAULT '',
          bnc INTEGER NOT NULL DEFAULT 0,
          frq INTEGER NOT NULL DEFAULT 0,
          exchange TEXT NOT NULL DEFAULT ''
        )
        """
    )
    db.execute("CREATE INDEX idx_dict_word_lc ON dict_entries(word_lc)")
    db.execute(
        "CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)"
    )

    count = 0
    for _, _, row in selected:
        db.execute(
            """INSERT INTO dict_entries
               (word, word_lc, phonetic, definition, translation, pos,
                collins, oxford, tag, bnc, frq, exchange)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (
                row["word"],
                row["word"].lower(),
                clamp(row.get("phonetic") or ""),
                clamp(row.get("definition") or ""),
                clamp(row.get("translation") or ""),
                clamp(row.get("pos") or ""),
                parse_int(row.get("collins", "")),
                parse_int(row.get("oxford", "")),
                clamp(row.get("tag") or ""),
                parse_int(row.get("bnc", "")),
                parse_int(row.get("frq", "")),
                clamp(row.get("exchange") or ""),
            ),
        )
        count += 1

    db.execute(
        "INSERT INTO meta(key, value) VALUES (?, ?)",
        ("source", "ECDICT https://github.com/skywind3000/ECDICT (MIT)"),
    )
    db.execute(
        "INSERT INTO meta(key, value) VALUES (?, ?)",
        ("built_at", datetime.now(timezone.utc).isoformat()),
    )
    db.execute("INSERT INTO meta(key, value) VALUES (?, ?)", ("top_n", str(args.top)))
    db.execute("INSERT INTO meta(key, value) VALUES (?, ?)", ("entries", str(count)))
    db.commit()

    size = os.path.getsize(args.out)
    print(f"Wrote {count} entries, {size / 1e6:.1f} MB")
    # Smoke test: a few words any English learner will look up.
    for probe in ("apple", "run", "beautiful", "the"):
        row = db.execute(
            "SELECT word, phonetic, translation FROM dict_entries WHERE word_lc = ?",
            (probe,),
        ).fetchone()
        print("  probe:", row[0] if row else f"{probe}: MISSING", row[1] if row else "")
    db.close()


if __name__ == "__main__":
    sys.exit(main())
