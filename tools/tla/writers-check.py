#!/usr/bin/env python3
"""Check a TLA+ module against its writer table: which action writes which variable, in both directions.

TLC cannot show a wrong writer: `v' = e /\\ UNCHANGED v` is no error, it only disables the step, and the run stays green
on a narrower model. This script reads the module text and a table kept beside the model, and fails (exit 1) on any
mismatch. It says which variables an action writes; it says nothing about the value written (that is the configs' work).

Usage:
  writers-check.py --table <writers.tsv> --tla <Module.tla>
  writers-check.py --table <writers.tsv> --module <Module> <source roots...>     (extracts the module first)

The table (tab-separated, `#` comments):
  var     <name>  var|const|def:<variable>  <rows, comma-separated, or ->
          one line per state variable of the model. `const`: nothing writes it (a CONSTANT of the module, or a variable
          that no action primes). `def:<v>`: kept in the module as a definition over the variable <v>; it must carry
          the writing rows of <v>.
  row     <row>   <variables it writes, or ->                   the bold head of the row's cell "What it writes"
  part    <row>   <action>  <variables>                           a row whose actions write different parts of its head
  action  <name>  <operators, comma-separated>  <rows>            the map: each model action, its operators, its rows

Checks:
  1. variables: the module's VARIABLES are exactly the `var` lines of kind `var` (a `const` line may be either a
     CONSTANT or a variable); a `def` line is a definition of the module with the rows of its variable.
  2. the table, both ways: a variable's rows are exactly the rows whose head names it.
  3. writes: every operator of an action primes, outside UNCHANGED, exactly the variables expected of the action: the
     heads of its rows, or its `part` of a row that has parts. Every other variable is in an UNCHANGED of the operator.
     A definition that the operator uses counts as the operator's own text.
  4. the map: `Next` names exactly the operators of the map.
"""
import argparse
import os
import re
import subprocess
import sys
import tempfile

DEF = re.compile(r'^([A-Za-z_][A-Za-z0-9_]*)(\([^)]*\))?\s*==', re.M)
IDENT = re.compile(r'[A-Za-z_][A-Za-z0-9_]*')
UNCH = re.compile(r'UNCHANGED\s*(<<[^>]*>>|[A-Za-z_][A-Za-z0-9_]*)')


class Fail(Exception):
    pass


def strip_comments(text):
    text = re.sub(r'\(\*.*?\*\)', ' ', text, flags=re.S)
    return re.sub(r'\\\*[^\n]*', '', text)


def names(cell):
    return set() if cell.strip() in ('', '-') else {n.strip() for n in cell.split(',') if n.strip()}


def read_table(path):
    table = {'var': {}, 'row': {}, 'part': {}, 'action': {}}
    with open(path) as f:
        for number, line in enumerate(f, 1):
            line = line.rstrip('\n')
            if not line.strip() or line.lstrip().startswith('#'):
                continue
            cells = line.split('\t')
            kind = cells[0]
            want = {'var': 4, 'row': 3, 'part': 4, 'action': 4}.get(kind)
            if want is None or len(cells) != want:
                raise Fail(f'{path}:{number}: expected var|row|part|action with its cells, got {len(cells)} cells')
            if kind == 'var':
                table['var'][cells[1]] = (cells[2], names(cells[3]))
            elif kind == 'row':
                table['row'][cells[1]] = names(cells[2])
            elif kind == 'part':
                table['part'][(cells[1], cells[2])] = names(cells[3])
            else:
                table['action'][cells[1]] = (sorted(names(cells[2])), names(cells[3]))
    return table


def read_module(path):
    with open(path) as f:
        text = strip_comments(f.read())
    declared = {'VARIABLE': set(), 'CONSTANT': set()}
    for match in re.finditer(r'^(VARIABLES?|CONSTANTS?)\b(.*?)(?=^\S|\Z)', text, flags=re.M | re.S):
        declared[match.group(1).rstrip('S')] |= set(IDENT.findall(match.group(2)))
    heads = list(DEF.finditer(text))
    defs = {}
    for index, match in enumerate(heads):
        end = heads[index + 1].start() if index + 1 < len(heads) else len(text)
        defs[match.group(1)] = text[match.end():end]
    return declared['VARIABLE'], declared['CONSTANT'], defs


def closure(name, defs, seen=None):
    """The definitions that `name` uses, itself included."""
    seen = set() if seen is None else seen
    if name in seen or name not in defs:
        return seen
    seen.add(name)
    for ident in set(IDENT.findall(defs[name])):
        closure(ident, defs, seen)
    return seen


def tuple_vars(text, variables, defs, depth=0):
    """Variables named by an UNCHANGED operand: a tuple, a variable, or a definition of a tuple."""
    found = set()
    for ident in IDENT.findall(text):
        if ident in variables:
            found.add(ident)
        elif ident in defs and depth < 4:
            found |= tuple_vars(defs[ident], variables, defs, depth + 1)
    return found


def writes_of(name, variables, defs):
    written, unchanged = set(), set()
    for used in closure(name, defs):
        body = defs[used]
        for match in UNCH.finditer(body):
            unchanged |= tuple_vars(match.group(1), variables, defs)
        rest = UNCH.sub(' ', body)
        for variable in variables:
            if re.search(r'(?<![A-Za-z0-9_])' + re.escape(variable) + r"'", rest):
                written.add(variable)
    return written, unchanged


def check(table, variables, constants, defs):
    errors = []
    lines = table['var']
    kinds = {name: kind for name, (kind, _) in lines.items()}
    source = {name: (kind[4:] if kind.startswith('def:') else name) for name, kind in kinds.items()}

    # 1. the variables, both ways
    for name, kind in sorted(kinds.items()):
        if kind == 'var' and name not in variables:
            errors.append(f'variables: the table line `{name}` is no VARIABLE of the module')
        elif kind == 'const' and name not in variables and name not in constants:
            errors.append(f'variables: the constant line `{name}` is neither a CONSTANT nor a VARIABLE of the module')
        elif kind.startswith('def:'):
            if name not in defs:
                errors.append(f'variables: `{name}` is named a definition over `{source[name]}` and the module defines none')
            if source[name] not in variables:
                errors.append(f'variables: `{name}` is read from `{source[name]}`, which is no VARIABLE of the module')
            elif lines[name][1] != lines.get(source[name], ('', set()))[1]:
                errors.append(f'variables: `{name}` must carry the rows of `{source[name]}`: '
                              f'{sorted(lines[name][1])} against {sorted(lines[source[name]][1])}')
        elif kind not in ('var', 'const'):
            errors.append(f'variables: the line `{name}` has the unknown kind `{kind}`')
    for name in sorted(variables - set(lines)):
        errors.append(f'variables: the module VARIABLE `{name}` has no line in the table')

    # 2. the table, both ways
    for name, (kind, rows) in sorted(lines.items()):
        heads = {row for row, head in table['row'].items() if name in head}
        if kind == 'const' and (rows or heads):
            errors.append(f'table: the constant `{name}` is written by {sorted(rows | heads)}')
        if rows != heads:
            errors.append(f'table: `{name}` lists the rows {sorted(rows)} and the heads that name it are {sorted(heads)}')
    for row, head in sorted(table['row'].items()):
        for name in sorted(head - set(lines)):
            errors.append(f'table: the head of {row} names `{name}`, which has no line')
    for (row, action), part in sorted(table['part'].items()):
        if not part <= table['row'].get(row, set()):
            errors.append(f'table: the part of {row} for `{action}` is not inside the head of {row}')
    for row in sorted({row for row, _ in table['part']}):
        union = set().union(*(part for (r, _), part in table['part'].items() if r == row))
        if union != table['row'].get(row, set()):
            errors.append(f'table: the parts of {row} do not add up to its head')

    # 3. the writes of every operator of every action
    mapped = set()
    for action, (operators, rows) in sorted(table['action'].items()):
        expected = set()
        for row in sorted(rows):
            if row not in table['row']:
                errors.append(f'map: the action `{action}` names the row {row}, which the table does not have')
                continue
            parts = {a for (r, a) in table['part'] if r == row}
            if parts:
                if action not in parts:
                    errors.append(f'map: the row {row} has parts and none for the action `{action}`')
                    continue
                expected |= table['part'][(row, action)]
            else:
                expected |= table['row'][row]
        expected = {source.get(name, name) for name in expected}
        for operator in operators:
            mapped.add(operator)
            if operator not in defs:
                errors.append(f'map: the operator `{operator}` of the action `{action}` is not defined in the module')
                continue
            written, unchanged = writes_of(operator, variables, defs)
            for name in sorted(written - expected):
                errors.append(f'writes: `{operator}` ({action}) writes `{name}`, which its rows {sorted(rows)} do not name')
            for name in sorted(expected - written):
                errors.append(f'writes: `{operator}` ({action}) does not write `{name}`, which its rows {sorted(rows)} name')
            for name in sorted(written & unchanged):
                errors.append(f'writes: `{operator}` ({action}) both writes `{name}` and leaves it UNCHANGED')
            for name in sorted(variables - written - unchanged):
                errors.append(f'writes: `{operator}` ({action}) neither writes `{name}` nor leaves it in an UNCHANGED')
    for row in sorted(table['row']):
        if table['row'][row] and not any(row in rows for _, rows in table['action'].values()):
            errors.append(f'map: the row {row} writes {sorted(table["row"][row])} and no action names it')

    # 4. Next names exactly the mapped operators
    if 'Next' not in defs:
        errors.append('map: the module defines no Next')
    else:
        used = {ident for ident in IDENT.findall(defs['Next']) if ident in defs
                and (writes_of(ident, variables, defs)[0] or writes_of(ident, variables, defs)[1])}
        for name in sorted(used - mapped):
            errors.append(f'map: `Next` takes the step `{name}`, which no action of the map names')
        for name in sorted(mapped - used):
            errors.append(f'map: the operator `{name}` of the map is no step of `Next`')
    operators = sum(len(ops) for ops, _ in table['action'].values())
    return errors, (len(lines), len(table['row']), len(table['action']), operators)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--table', required=True)
    parser.add_argument('--tla')
    parser.add_argument('--module')
    parser.add_argument('roots', nargs='*')
    args = parser.parse_args()
    try:
        table = read_table(args.table)
        if args.tla:
            path = args.tla
        elif args.module and args.roots:
            out = tempfile.mkdtemp(prefix='writers-check-')
            extract = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'extract.py')
            done = subprocess.run([sys.executable, extract, '--module', args.module, '--out', out] + args.roots,
                                  capture_output=True, text=True)
            if done.returncode != 0:
                raise Fail('extract.py failed: ' + (done.stderr or done.stdout).strip())
            path = os.path.join(out, args.module + '.tla')
        else:
            raise Fail('give --tla <file>, or --module <Module> and the source roots')
        variables, constants, defs = read_module(path)
        if not variables:
            raise Fail(f'{path}: no VARIABLES found')
    except (Fail, OSError) as error:
        print(f'writers-check: ERROR {error}', file=sys.stderr)
        return 2
    errors, (lines, rows, actions, operators) = check(table, variables, constants, defs)
    for error in errors:
        print(f'writers-check: RED {error}')
    print(f'writers-check: {lines} variable lines, {len(variables)} module variables, {rows} rows, {actions} actions '
          f'({operators} operators), {len(errors)} mismatches')
    return 1 if errors else 0


if __name__ == '__main__':
    sys.exit(main())
