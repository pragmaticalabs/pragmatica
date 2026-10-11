#!/usr/bin/env python3
"""Dangling relative links in the tracked markdown of a git tree.

    scripts/check-md-links.py [ROOT] [--list] [--scope=a/,b/] [--include-history]

A link is `](target)` or a reference definition `[label]: target`. Not links, so not checked: http(s)/mailto/#anchor targets, footnote
definitions `[^x]: ...`, fenced and inline code, and placeholder targets (a bare word with no `/` and no `.`, such as the `url` in a template).
`/x` is repo-root relative. `--scope` keeps only links in files under, or resolving into, the given prefixes.

History rule: CHANGELOG.md and changelog.d/ record what was true when an entry was written, so their links are left as written and excluded
unless --include-history is given. Exit status 1 when any checked link dangles."""
import os
import re
import subprocess
import sys

HISTORY = ("CHANGELOG.md", "changelog.d/")
LINK = re.compile(r"\]\(\s*<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\s*\)|^\s*\[(?!\^)[^\]]+\]:\s*(\S+)", re.M)


def scan(root, scope=None, include_history=False):
    files = subprocess.run(["git", "ls-files", "*.md"], cwd=root, capture_output=True, text=True).stdout.split("\n")
    bad, total = [], 0
    for f in filter(None, files):
        p = os.path.join(root, f)
        if not os.path.isfile(p) or (not include_history and f.startswith(HISTORY)):
            continue
        text = open(p, errors="replace").read()
        text = re.sub(r"```.*?```", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)
        text = re.sub(r"`[^`\n]*`", "", text)
        for m in LINK.finditer(text):
            t = (m.group(1) or m.group(2) or "").strip()
            if not t or re.match(r"(?i)^([a-z][a-z0-9+.-]*:|#|//)", t):
                continue
            t = re.sub(r"[#?].*$", "", t)
            if not t or ("/" not in t and "." not in t):
                continue
            dest = os.path.join(root, t.lstrip("/")) if t.startswith("/") else os.path.normpath(os.path.join(os.path.dirname(p), t))
            if scope and not (f.startswith(tuple(scope)) or os.path.relpath(dest, root).startswith(tuple(scope))):
                continue
            total += 1
            if not os.path.exists(dest):
                bad.append((f, text[:m.start()].count("\n") + 1, t))
    return len(list(filter(None, files))), total, bad


def main(argv):
    args = [a for a in argv if not a.startswith("--")]
    root = os.path.abspath(args[0] if args else ".")
    scope = next((a.split("=", 1)[1].split(",") for a in argv if a.startswith("--scope=")), None)
    n, total, bad = scan(root, scope, "--include-history" in argv)
    print(f"{n} markdown files, {total} relative links, {len(bad)} dangling")
    if "--list" in argv:
        for f, line, t in bad:
            print(f"{f}:{line}: {t}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
