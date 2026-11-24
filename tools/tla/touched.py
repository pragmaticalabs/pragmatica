#!/usr/bin/env python3
"""Fail when code under a `tla` fragment changes and the fragment does not (tools/tla/README.md, "Fragment-touched check").

A region is the declaration that a `///` doc comment carrying a ```tla <Module>:<Part> fragment documents: from the first
line after the doc comment to the end of the method, field or other declaration (a class, interface, enum or record
documents only its header: the skeleton fragments sit there, and a class body is not one atomic step). The end of a
declaration is found by a brace and parenthesis depth scan over the source with comments, string literals, text blocks
and character literals blanked, so a brace inside a string or a comment cannot end a region early or late.

A PR is red when a changed line that holds code (not a blank line, not a comment) falls inside a region while the text
of that region's fragment (same Module:Part, same file's base version) is unchanged. It is excused, per module, by a
line `tla-unchanged: <Module>: <reason>` in the PR body: the reason is required.

Usage: touched.py --base REV [--head REV] [--repo DIR] [--pr-body-file FILE]   (the PR body is read from FILE, else from
the environment variable PR_BODY). The base is reduced to merge-base(base, head), so work that landed on the base branch
after the PR branched is not charged to the PR.
Exit: 0 green, 1 red (one `file:line: RED ...` line per region), 2 error.
"""
import argparse
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import extract  # noqa: E402

CLASS_HEADER = re.compile(r'^\s*(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed|strictfp)\s+|@\w+(?:\.\w+)*\s*(?:\([^)]*\))?\s+)*(?:class|interface|enum|record|@interface)\s+\w')
MARKER = re.compile(r'^[ \t]*tla-unchanged:[ \t]*([A-Za-z][A-Za-z0-9_]*)[ \t]*:[ \t]*(\S.*?)[ \t]*$', re.M)
COMMENT_ONLY = re.compile(r'^\s*(?://|/\*|\*|$)')


class Error(Exception):
    pass


def blank(text):
    """text with every comment, string literal, text block and character literal replaced by spaces (newlines kept)."""
    out = list(text)
    i, n = 0, len(text)

    def wipe(a, b):
        for k in range(a, min(b, n)):
            if out[k] != '\n':
                out[k] = ' '

    while i < n:
        c = text[i]
        if text.startswith('//', i):
            j = text.find('\n', i)
            j = n if j < 0 else j
            wipe(i, j)
            i = j
        elif text.startswith('/*', i):
            j = text.find('*/', i + 2)
            j = n if j < 0 else j + 2
            wipe(i, j)
            i = j
        elif text.startswith('"""', i):
            j = i + 3
            while j < n and not text.startswith('"""', j):
                j += 2 if text[j] == '\\' else 1
            j = min(n, j + 3)
            wipe(i, j)
            i = j
        elif c == '"' or c == "'":
            j = i + 1
            while j < n and text[j] != c and text[j] != '\n':
                j += 2 if text[j] == '\\' else 1
            j = min(n, j + 1)
            wipe(i, j)
            i = j
        else:
            i += 1
    return ''.join(out)


def region_end(blanked, start):
    """Offset of the last character of the declaration that begins at `start`, and whether it is a class-like header."""
    brace = paren = 0
    n = len(blanked)
    i = start
    while i < n:
        c = blanked[i]
        if c == '(':
            paren += 1
        elif c == ')':
            paren -= 1
        elif c == '{':
            if brace == 0 and paren == 0 and CLASS_HEADER.match(blanked[start:i]):
                return i, True
            brace += 1
        elif c == '}':
            brace -= 1
            if brace == 0 and paren == 0:
                return i, False
        elif c == ';' and brace == 0 and paren == 0:
            return i, False
        i += 1
    return n - 1, False


def regions(text, rel):
    """[(module, part, fragment line, region first line, region last line, fragment text)] for each `tla` fragment of one file."""
    lines = text.split('\n')
    blanked = blank(text)
    offs = [0]
    for l in lines:
        offs.append(offs[-1] + len(l) + 1)
    out = []
    for kind, module, part, _, fline, body in extract.scan_text(text, rel):
        if kind != 'tla':
            continue
        close = (body[-1][0] if body else fline) + 1
        k = close                      # 0-based index of the line after the closing fence
        while k < len(lines) and re.match(r'^\s*///', lines[k]):
            k += 1                     # the rest of the doc comment: more fragments and prose
        if k >= len(lines):
            continue
        a_off = offs[k]
        e_off, header_only = region_end(blanked, a_off)
        last = next(i for i in range(len(lines)) if offs[i + 1] > e_off)
        out.append((module, part, fline, k + 1, last + 1, '\n'.join(t.rstrip() for _, t in body)))
    return out


def code_lines(text):
    """The set of 1-based line numbers of `text` that hold code outside comments and literals."""
    return {n for n, l in enumerate(blank(text).split('\n'), 1) if l.strip()}


def git(repo, *args, check=True):
    p = subprocess.run(['git', '-c', 'core.quotepath=off', '-C', repo] + list(args), capture_output=True, text=True)
    if check and p.returncode != 0:
        raise Error(f'git {" ".join(args)}: {p.stderr.strip()}')
    return p.stdout


def show(repo, rev, path):
    p = subprocess.run(['git', '-C', repo, 'show', f'{rev}:{path}'], capture_output=True, text=True)
    return p.stdout if p.returncode == 0 else None


def diff_files(repo, base, head):
    """[(old path or None, new path or None, [(new_start, new_count, [removed lines], [added lines])])] for the Java files of
    the diff: new is None for a deleted file. `-M` is explicit so a `diff.renames=false` in a user or runner config cannot
    turn a rename into add + delete."""
    d = git(repo, 'diff', '-M', '-U0', '--no-color', base, head, '--', '*.java')
    files, cur = [], None
    for l in d.split('\n'):
        if l.startswith('diff --git '):
            cur = {'old': None, 'new': None, 'hunks': []}
            files.append(cur)
        elif cur is None:
            continue
        elif l.startswith('--- '):
            cur['old'] = None if l.endswith('/dev/null') else l[6:]
        elif l.startswith('+++ '):
            cur['new'] = None if l.endswith('/dev/null') else l[6:]
        elif l.startswith('@@'):
            m = re.match(r'^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@', l)
            cur['hunks'].append((int(m.group(1)), 1 if m.group(2) is None else int(m.group(2)), [], []))
        elif cur['hunks'] and l.startswith('-'):
            cur['hunks'][-1][2].append(l[1:])
        elif cur['hunks'] and l.startswith('+'):
            cur['hunks'][-1][3].append(l[1:])
    return [(f['old'], f['new'], f['hunks']) for f in files]


def changed_in(region, hunks, code):
    """The changed code lines (1-based, new file) of the hunks that fall inside the region [a, b]; a deletion is reported
    at the line it follows."""
    a, b = region
    hit = []
    for s, n, removed, added in hunks:
        removed_code = any(not COMMENT_ONLY.match(r) for r in removed)
        if n == 0:                                   # pure deletion between new lines s and s+1
            if a - 1 <= s < b and removed_code:      # a - 1: right under the doc comment, where a deleted declaration was
                hit.append(s)
            continue
        span = range(max(s, a), min(s + n - 1, b) + 1)
        lines = [ln for ln in span if ln in code]
        if not lines and removed_code and len(span) > 0:
            lines = [span[0]]
        hit += lines
    return sorted(set(hit))


def ranges(lines):
    out, i = [], 0
    while i < len(lines):
        j = i
        while j + 1 < len(lines) and lines[j + 1] == lines[j] + 1:
            j += 1
        out.append(f'L{lines[i]}' if i == j else f'L{lines[i]}-{lines[j]}')
        i = j + 1
    return ','.join(out)


def check(repo, base, head, body):
    mb = git(repo, 'merge-base', base, head).strip()
    excused = {m.group(1): m.group(2) for m in MARKER.finditer(body or '')}
    red, ok = [], []
    files = diff_files(repo, mb, head)

    def base_regions(path):
        text = show(repo, mb, path)
        return list(regions(text, path)) if text and '```tla' in text else []

    def fragments(path):
        return {(m, p): t for m, p, _, _, _, t in base_regions(path)}

    def fragment_lines(path):
        return {(m, p): fl for m, p, fl, _, _, _ in base_regions(path)}

    # a file git reports as added while another was deleted may be a move below git's similarity threshold: its base
    # fragments are those of the deleted files, matched by Module:Part
    gone = {}
    for old, new, _ in files:
        if new is None and old:
            gone.update(fragments(old))
    for old, new, hunks in files:
        if new is None:
            continue
        text = show(repo, head, new)
        if text is None or '```tla' not in text:
            continue
        old_frag = fragments(old) if old else dict(gone)
        code = code_lines(text)
        for module, part, fline, a, b, ftext in regions(text, new):
            hit = changed_in((a, b), hunks, code)
            if not hit:
                continue
            if old_frag.get((module, part)) != ftext:
                ok.append(f'{new}:{a}: ok {module}:{part} region L{a}-{b}: code changed at {ranges(hit)} and the fragment changed too')
            elif module in excused:
                ok.append(f'{new}:{a}: ok {module}:{part} region L{a}-{b}: code changed at {ranges(hit)}, fragment unchanged, excused by tla-unchanged: {excused[module]}')
            else:
                red.append(f'{new}:{a}: RED {module}:{part} region L{a}-{b} (fragment at L{fline}): code changed at {ranges(hit)} but the fragment is unchanged; '
                           f'update the fragment, or state `tla-unchanged: {module}: <reason>` in the PR body if the model still holds')
    # F8 (#3604): a fragment that existed at the merge base and exists nowhere in the head is a model change nobody reviewed:
    # the file was deleted, or the fragment was removed (or renamed to another Part) while its code stayed or changed. A fragment
    # that moved to another file is found there. The same per-module excuse applies.
    present = set()
    for old, new, _ in files:
        text = show(repo, head, new) if new else None
        if text and '```tla' in text:
            present.update((m, p) for m, p, _, _, _, _ in regions(text, new))
    for old, new, _ in files:
        if not old:
            continue
        for (module, part), fline in sorted(fragment_lines(old).items(), key=lambda kv: kv[1]):
            if (module, part) in present:
                continue
            how = 'its file was deleted' if new is None else 'it is gone from the file'
            if module in excused:
                ok.append(f'{old}:{fline}: ok {module}:{part} fragment removed ({how}), excused by tla-unchanged: {excused[module]}')
            else:
                red.append(f'{old}:{fline}: RED {module}:{part} fragment removed ({how}); '
                           f'restore it, or state `tla-unchanged: {module}: <reason>` in the PR body if the removal is intended')
    return red, ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--base', required=True)
    ap.add_argument('--head', default='HEAD')
    ap.add_argument('--repo', default='.')
    ap.add_argument('--pr-body-file')
    a = ap.parse_args()
    if a.pr_body_file:
        with open(a.pr_body_file, encoding='utf-8') as f:
            body = f.read()
    else:
        body = os.environ.get('PR_BODY', '')
    try:
        red, ok = check(a.repo, a.base, a.head, body)
    except (Error, extract.Fail) as e:
        print(f'touched: ERROR {e}', file=sys.stderr)
        sys.exit(2)
    for l in ok + red:
        print(l)
    print(f'touched: {"RED" if red else "GREEN"} {len(red)} unexcused, {len(ok)} accounted for')
    sys.exit(1 if red else 0)


if __name__ == '__main__':
    main()
