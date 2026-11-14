#!/usr/bin/env python3
"""Check local links in maintained repository guides without fetching remote URLs."""
import argparse
from pathlib import Path
import re
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
INLINE_LINK = re.compile(r"!?\[[^\]\n]*\]\(\s*(?:<([^>\n]+)>|([^\s)]+))(?:\s+[^)\n]*)?\)")
REFERENCE = re.compile(r"^\s{0,3}\[[^\]\n]+\]:\s*(?:<([^>\n]+)>|(\S+))", re.MULTILINE)
ROOT_TEMPLATES = {"tools/fdroid/README.md"}


def prose(text):
    fence = None
    for line in text.splitlines():
        match = re.match(r"\s{0,3}(`{3,}|~{3,})", line)
        if match:
            marker = match[1]
            if fence is None:
                fence = marker
            elif marker[0] == fence[0] and len(marker) >= len(fence):
                fence = None
            yield ""
        elif fence is not None:
            yield ""
        else:
            yield re.sub(r"(`+).*?\1", "", line)


def check_document(root, path):
    relative = path.relative_to(root).as_posix()
    base = root if relative in ROOT_TEMPLATES else path.parent
    failures = []
    for number, line in enumerate(prose(path.read_text(encoding="utf-8")), 1):
        for match in list(INLINE_LINK.finditer(line)) + list(REFERENCE.finditer(line)):
            target = match[1] or match[2]
            url = urlsplit(target)
            if url.scheme or url.netloc or not url.path:
                continue
            destination = ((root if url.path.startswith("/") else base) /
                           unquote(url.path).lstrip("/")).resolve()
            if not destination.is_relative_to(root):
                failures.append(f"{relative}:{number}: link escapes repository: {target}")
            elif not destination.exists():
                failures.append(f"{relative}:{number}: missing target: {target}")
    return failures


def documents(root):
    yield from sorted(root.glob("*.md"))
    for directory in ("docs", ".github", "backend"):
        yield from sorted((root / directory).rglob("*.md"))
    yield from sorted((root / "tools").glob("*.md"))
    yield root / "tools/fdroid/README.md"
    yield root / "tools/build-readiness/README.md"
    yield root / "tools/tests/README.md"
    yield root / "app/src/test/README.md"


def audit(root):
    failures = []
    for path in documents(root):
        if not path.is_file():
            failures.append(f"{path.relative_to(root)}: required guide missing")
        else:
            failures.extend(check_document(root, path))
    return failures


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args()
    failures = audit(args.root.resolve())
    if failures:
        raise SystemExit("REPOSITORY_DOCS_FAILED\n" + "\n".join(failures))
    print("REPOSITORY_DOCS_OK (local guide targets; no remote availability claim)")


if __name__ == "__main__":
    main()
