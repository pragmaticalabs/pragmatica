#!/usr/bin/env python3
"""Bring the working tree to the licence boundary named by tools/license/bsl-modules.txt (#1989). Idempotent; re-runnable on any tip.

    python3 tools/license/relicense.py [--dry-run] [--check] [--list] [--report FILE]

BSL = the module paths in bsl-modules.txt; everything else is Apache-2.0. Per run:
  * .java inside a BSL module: the header is exactly docs/legal/bsl-header.txt (added, or replacing a stale licence-comment run or a legacy
    Apache block);
  * .java / .sh / .md outside: a BUSL-1.1 header becomes docs/legal/apache-header.txt in the file's comment style; other headers
    (an Apache block, none) are left alone;
  * poms: a BSL module declares the BSL <licenses> entry; no other pom declares it (the root default is Apache);
  * LICENSE: each BSL module carries docs/legal/bsl-license.txt as its own LICENSE; any other non-root LICENSE holding the BSL text is deleted.
--dry-run prints what would change and writes nothing; --check is --dry-run that exits 1 if anything would change.
A BUSL-1.1 header left in a non-exempt file the script cannot rewrite is reported as UNRESOLVED and fails the run."""
import argparse
import re
import subprocess
import sys
from collections import Counter
from pathlib import Path

ROOT = BSL_LIST = BSL_HEADER = APACHE_HEADER = BSL_LICENSE = None


def configure(root):
    global ROOT, BSL_LIST, BSL_HEADER, APACHE_HEADER, BSL_LICENSE
    ROOT = Path(root).resolve()
    BSL_LIST = ROOT / "tools/license/bsl-modules.txt"
    BSL_HEADER = ROOT / "docs/legal/bsl-header.txt"
    APACHE_HEADER = ROOT / "docs/legal/apache-header.txt"
    BSL_LICENSE = ROOT / "docs/legal/bsl-license.txt"


configure(Path(__file__).resolve().parent.parent.parent)
BUSL = "SPDX-License-Identifier: BUSL-1.1"
BODY = r"(?:SPDX-License-Identifier:|Copyright|Licensed|See LICENSE|Change Date:|Change License:)[^\n]*"
# files that talk ABOUT the licence; a BUSL-1.1 string in them is prose, not a header
EXEMPT = ("LICENSE", "CHANGELOG.md", "CLAUDE.md", "CONTRIBUTING.md", "docs/legal/", "tools/license/", "changelog.d/")
BSL_ENTRY = re.compile(r"\n[ \t]*<license>\s*<name>Business Source License 1\.1</name>.*?</license>[ \t]*(?=\n)", re.S)
BSL_POM_BLOCK = ("    <licenses>\n        <license>\n            <name>Business Source License 1.1</name>\n"
                 "            <url>https://mariadb.com/bsl11/</url>\n"
                 "            <comments>Converts to Apache License 2.0 on January 1, 2030</comments>\n        </license>\n    </licenses>\n\n")
POM_ANCHOR = re.compile(r"^    <(?:properties|modules|dependencyManagement|dependencies|build|profiles)>|</project>", re.M)


def bsl_paths():
    return [l.strip().rstrip("/") for l in BSL_LIST.read_text().splitlines() if l.strip() and not l.lstrip().startswith("#")]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8", errors="surrogateescape", newline="")


def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8", errors="surrogateescape", newline="")


def tracked():
    out = subprocess.run(["git", "ls-files", "-co", "--exclude-standard", "-z"], cwd=ROOT, capture_output=True, text=True).stdout
    return [p for p in out.split("\0") if p and (ROOT / p).is_file() and "/target/" not in "/" + p]


def under(path, entry):
    return path == entry or path.startswith(entry + "/")


def render(header_file, style):
    """The header file's `// ` lines in `style` ('java' | 'sh' | 'md'), as a list of lines (no newline)."""
    bodies = [l[3:] for l in header_file.read_text().splitlines() if l.startswith("// ")]
    return [{"java": "// ", "sh": "# "}[style] + b if style != "md" else f"<!-- {b} -->" for b in bodies]


def wrap(style):
    return {"java": (r"// ", ""), "sh": (r"# ", ""), "md": (r"<!-- ", r" -->")}[style]


def lead_run(text, style, start=0):
    """End offset of the run of licence comment lines beginning at `start` (== start when there is none)."""
    pre, post = wrap(style)
    pattern = re.compile(pre + BODY.replace("[^\\n]*", "[^\\n]*?") + post + r"[ \t]*\n")
    pos = start
    while (m := pattern.match(text, pos)):
        pos = m.end()
    return pos


MULTI_MD = re.compile(r"\A<!--[ \t]*\n" + BUSL + r"\n(?:(?!-->)[^\n]*\n)*?-->[ \t]*\n")
APACHE_BLOCK = re.compile(r"\A/\*(?:(?!\*/).)*?Licensed under the Apache License(?:(?!\*/).)*\*/[ \t]*\n(?:[ \t]*\n)*", re.S)


def rewrite_header(path, text, bsl):
    """(new text, category) or None. `bsl` = the file's module is BSL."""
    suffix = Path(path).suffix
    if suffix == ".java":
        if bsl:
            canon = "".join(l + "\n" for l in render(BSL_HEADER, "java"))
            if text.startswith(canon):
                return None
            end = lead_run(text, "java")
            if end:
                same = text.startswith(canon.splitlines(True)[0]) and text[:end].splitlines(True)[:3] == canon.splitlines(True)[:3]
                return canon + text[end:], "bsl: header pointer reworded" if same else "bsl: licence-comment run replaced"
            m = APACHE_BLOCK.match(text)
            if m:
                return canon + text[m.end():], "bsl: Apache block replaced"
            return canon + text, "bsl: header added"
        if text.startswith("// " + BUSL):
            end = lead_run(text, "java")
            return "".join(l + "\n" for l in render(APACHE_HEADER, "java")) + text[end:], "apache: BUSL header replaced (java)"
        return None
    if bsl or suffix not in (".sh", ".md"):
        return None
    style = suffix[1:]
    start = text.index("\n") + 1 if style == "sh" and text.startswith("#!") else 0
    new = "".join(l + "\n" for l in render(APACHE_HEADER, style))
    if style == "md" and (m := MULTI_MD.match(text)):
        return new + text[m.end():], "apache: BUSL header replaced (md)"
    pre, post = wrap(style)
    if re.match(re.escape(pre) + BUSL.replace(" ", r"\s") + re.escape(post) + r"[ \t]*\n", text[start:]):
        end = lead_run(text, style, start)
        return text[:start] + new + text[end:], f"apache: BUSL header replaced ({style})"
    return None


def plan(dry):
    bsl = bsl_paths()
    is_bsl = lambda p: any(under(p, e) for e in bsl)
    files = tracked()
    changes, unresolved = [], []   # (path, category, new text or None for delete)
    for p in files:
        if p.endswith(("/pom.xml",)) or p == "pom.xml":
            text = read(p)
            if is_bsl(p) and p.rsplit("/", 1)[0] in bsl:
                new = text
                if "Business Source License 1.1" not in text:
                    m = POM_ANCHOR.search(text)
                    new = text[:m.start()] + BSL_POM_BLOCK + text[m.start():]
            else:
                new = BSL_ENTRY.sub("", text)
                new = re.sub(r"\n[ \t]*<licenses>\s*</licenses>[ \t]*(?=\n)", "", new)
            if new != text:
                changes.append((p, "pom: BSL <licenses> " + ("added" if is_bsl(p) else "removed"), new))
            continue
        name = p.rsplit("/", 1)[-1]
        if name == "LICENSE" and p != "LICENSE":
            if not is_bsl(p) and "Business Source License 1.1" in read(p):
                changes.append((p, "LICENSE: BSL text deleted outside BSL modules", None))
            continue
        if p.endswith((".java", ".sh", ".md")):
            text = read(p)
            r = rewrite_header(p, text, is_bsl(p))
            if r:
                changes.append((p, r[1], r[0]))
            elif not is_bsl(p) and not p.startswith(EXEMPT) and BUSL in text[:1000]:
                unresolved.append(p)
    for e in bsl:
        target = f"{e}/LICENSE"
        if (ROOT / e).is_dir() and (not (ROOT / target).is_file() or read(target) != BSL_LICENSE.read_text()):
            changes.append((target, "LICENSE: BSL module LICENSE written", BSL_LICENSE.read_text()))
    return changes, unresolved


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--report")
    ap.add_argument("--root", help="repository root (default: the checkout this script lives in)")
    a = ap.parse_args()
    if a.root:
        configure(a.root)
    dry = a.dry_run or a.check
    missing = [e for e in bsl_paths() if not (ROOT / e).is_dir()]
    if missing:
        print(f"BSL list names paths that are not directories: {missing}", file=sys.stderr)
        return 2
    changes, unresolved = plan(dry)
    counts = Counter(c for _, c, _ in changes)
    lines = [f"{'would change' if dry else 'changed'} {len(changes)} file(s); {len(bsl_paths())} BSL path(s); tip {subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=ROOT, capture_output=True, text=True).stdout.strip()[:12]}"]
    lines += [f"  {n:6d}  {c}" for c, n in sorted(counts.items())]
    if a.list:
        lines += [f"  {c}: {p}" for p, c, _ in sorted(changes)]
    lines += [f"UNRESOLVED BUSL-1.1 header: {p}" for p in unresolved]
    if not dry:
        for p, _, new in changes:
            if new is None:
                (ROOT / p).unlink()
            else:
                write(p, new)
    out = "\n".join(lines)
    print(out)
    if a.report:
        Path(a.report).write_text(out + "\n")
    return 1 if unresolved or (a.check and changes) else 0


if __name__ == "__main__":
    sys.exit(main())
