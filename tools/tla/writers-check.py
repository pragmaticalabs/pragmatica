#!/usr/bin/env python3
"""Check a TLA+ module against its writer table: which action writes which variable, and that nothing bounds a step unseen.

TLC cannot show a wrong writer: `v' = e /\\ UNCHANGED v` is no error, it only disables the step, and the run stays green
on a narrower model. Nor can it show a bound written as a conjunct of `Next` or as a cfg CONSTRAINT: the fair step that
the bound cuts is dropped from the liveness check. This script reads the module text and a table kept beside the model,
and fails (exit 1) on any mismatch. It says which variables a step constrains; it says nothing about the value written.

Usage:
  writers-check.py --table <writers.tsv> --tla <Module.tla> [--cfgdir <dir with the module's cfg files>]
  writers-check.py --table <writers.tsv> --module <Module> <source roots...>     (extracts the module and its cfgs first)

The one invariant of the writes: ON EVERY PATH OF A STEP, EVERY VARIABLE IS CONSTRAINED EXACTLY ONCE, IN ONE OF TWO
LITERAL FORMS: `v' = e` or `v' \\in S` at the start of a conjunct, with `e` not `v` itself; or `v` inside an UNCHANGED (a
named tuple is expanded). A path is one disjunct of an operator whose body is a `\\/` list (IF, CASE and an inner `\\/`
may not hold a prime: the script counts their primes into one path, and the count then fails). A definition that a step
uses counts as the step's own text, once per use. Any other prime fails: a prime of a parameter, of a tuple, of a
definition, a comparison, `v' = v`.

The table (tab-separated, `#` comments):
  var     <name>  var|const|def:<variable>  <rows or ->   one line per state variable. `const`: nothing writes it. `def:<v>`:
                                                          a definition over the variable <v>, carrying the rows of <v>.
  row     <row>   <variables or ->                        the variables the row writes
  part    <row>   <action>  <variables>                   a row whose actions write different parts of it
  action  <name>  <operators>  <rows>                     the map: a model action, its operators (the steps of Next), its rows
  op      <operator>  <variables>                         an operator that writes a part of its action's variables
  fair    <operator>  <rows>                              an operator under WF in `Fairness`, and the fair rows it stands for
  bound   <constant>  line|input  <note>                  a bound of the instrument: `input` = it may stop only steps that are
                                                          not fair; `line` = it stops a fair or needed step and has a Dom line
  param   <constant>                                      a constant that is no bound
  spec    <name>  <variants>                              one more specification, for the named cfg variants only

Checks:
  1. variables: the module's VARIABLES are the `var` lines of kind `var` (a `const` line may be a CONSTANT or a variable that
     nothing writes); a `def` line is a definition with the rows of its variable; `vars` is the tuple of all of them.
  2. the table, both ways: a variable's rows are exactly the rows that name it; the parts of a row add up to it.
  3. the writes: the invariant above on every path of every mapped operator; each path writes exactly what is expected of
     its operator (its `op` line, else the heads of its action's rows or its part of them); an action's operators together
     write exactly what is expected of the action.
  4. the steps: `Next` is a plain disjunction (with `\\E`) of exactly the mapped operators, with no conjunct of its own;
     `Spec` is exactly `Init /\\ [][Next]_vars /\\ Fairness`; `Fairness` holds WF_vars of exactly the `fair` operators.
  5. the bounds: every CONSTANT is a `bound` or a `param`; a bound sits in the enabling of at least one step (so it is not
     in `Next` or in a cfg), and an `input` bound in the enabling of no step that a fair operator takes. The steps that hold
     each bound are printed.
  6. the cfg files (when given): none holds the word CONSTRAINT; each names the specification `Spec`, or the `spec` of
     its variant.
"""
import argparse
import collections
import os
import re
import subprocess
import sys
import tempfile

DEF = re.compile(r'^([A-Za-z_][A-Za-z0-9_]*)(\([^)]*\))?\s*==', re.M)
IDENT = re.compile(r'[A-Za-z_][A-Za-z0-9_]*')
UNCH = re.compile(r'UNCHANGED\s*(<<[^>]*>>|[A-Za-z_][A-Za-z0-9_]*)')
CELLS = {'var': 4, 'row': 3, 'part': 4, 'action': 4, 'op': 3, 'fair': 3, 'bound': 4, 'param': 2, 'spec': 3}


class Fail(Exception):
    pass


def strip_comments(text):
    text = re.sub(r'\(\*.*?\*\)', lambda m: re.sub(r'[^\n]', ' ', m.group(0)), text, flags=re.S)
    return re.sub(r'\\\*[^\n]*', '', text)


def names(cell):
    return set() if cell.strip() in ('', '-') else {n.strip() for n in cell.split(',') if n.strip()}


def read_table(path):
    table = {kind: {} for kind in CELLS}
    with open(path) as f:
        for number, line in enumerate(f, 1):
            line = line.rstrip('\n')
            if not line.strip() or line.lstrip().startswith('#'):
                continue
            cells = line.split('\t')
            kind = cells[0]
            if CELLS.get(kind) != len(cells):
                raise Fail(f'{path}:{number}: expected one of {sorted(CELLS)} with its cells, got {len(cells)} cells')
            if kind == 'var':
                table['var'][cells[1]] = (cells[2], names(cells[3]))
            elif kind == 'part':
                table['part'][(cells[1], cells[2])] = names(cells[3])
            elif kind == 'action':
                table['action'][cells[1]] = (sorted(names(cells[2])), names(cells[3]))
            elif kind == 'bound':
                table['bound'][cells[1]] = cells[2]
            elif kind == 'param':
                table['param'][cells[1]] = True
            else:
                table[kind][cells[1]] = names(cells[2])
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


def idents_of(name, defs):
    return set().union(*(set(IDENT.findall(defs[used])) for used in closure(name, defs))) if name in defs else set()


def split_paths(body):
    """The disjuncts of a body that is a `\\/` list at one column; else the body as its one path."""
    lines = body.split('\n')
    first = next((line for line in lines if line.strip()), '')
    if not first.lstrip().startswith('\\/'):
        return [body]
    column = len(first) - len(first.lstrip())
    paths = []
    for line in lines:
        if line.strip() and len(line) - len(line.lstrip()) == column and line.lstrip().startswith('\\/'):
            paths.append([line[:column] + '  ' + line[column + 2:]])
        elif paths:
            paths[-1].append(line)
    return ['\n'.join(path) for path in paths]


class Module:
    def __init__(self, variables, constants, defs):
        self.variables, self.constants, self.defs = variables, constants, defs
        self.stateful = {name for name in defs
                         if any("'" in defs[used] or 'UNCHANGED' in defs[used] for used in closure(name, defs))}

    def tuple_vars(self, text, where, errors, depth=0):
        found = []
        for ident in IDENT.findall(text):
            if ident in self.variables:
                found.append(ident)
            elif ident in self.defs and depth < 4 and re.fullmatch(r'\s*<<[^>]*>>\s*', self.defs[ident]):
                found += self.tuple_vars(self.defs[ident], where, errors, depth + 1)
            else:
                errors.append(f'writes: {where} leaves `{ident}` UNCHANGED, which is no variable and no tuple of variables')
        return found

    def constraints(self, text, where, errors, own=None, depth=0):
        """How often one path constrains each variable: (written, unchanged) counters."""
        written, unchanged = collections.Counter(), collections.Counter()
        for match in UNCH.finditer(text):
            unchanged.update(self.tuple_vars(match.group(1), where, errors))
        rest = UNCH.sub(lambda m: ' ' * len(m.group(0)), text)
        for match in re.finditer("'", rest):
            before, after = rest[:match.start()], rest[match.end():]
            name = re.search(r'([A-Za-z_][A-Za-z0-9_]*)\s*$', before)
            if not name or name.group(1) not in self.variables:
                shown = (name.group(1) if name else before[-12:].strip()) or '?'
                errors.append(f'writes: {where} primes `{shown}`, which is no variable written by name')
                continue
            variable = name.group(1)
            form = re.match(r'\s*(=(?![=>])|\\in\b)\s*', after)
            if not form:
                errors.append(f"writes: {where} primes `{variable}` outside the forms `{variable}' = e` and `{variable}' \\in S`")
                continue
            value = re.split(r'/\\|\\/|\n', after[form.end():], maxsplit=1)[0].strip().rstrip(')').strip()
            if value == variable:
                errors.append(f"writes: {where} holds `{variable}' = {variable}`: no write; say UNCHANGED")
                continue
            lead = before[:name.start()].rstrip()
            if lead and lead != 'IN' and not lead.endswith(('/\\', ':', ' IN', '\nIN')):
                errors.append(f"writes: {where} writes `{variable}` in the middle of a formula, not at the start of a conjunct")
                continue
            written[variable] += 1
        if depth < 6:
            for ident in IDENT.findall(rest):
                if ident in self.stateful and ident != own:
                    if len(split_paths(self.defs[ident])) != 1:
                        errors.append(f'writes: {where} uses `{ident}`, a definition with several paths')
                        continue
                    more = self.constraints(self.defs[ident], f'{where} (through `{ident}`)', errors, ident, depth + 1)
                    written.update(more[0])
                    unchanged.update(more[1])
        return written, unchanged

    def disjuncts(self, text, where, errors):
        """The operators of a plain disjunction with `\\E`: no conjunct, no prime."""
        text = re.sub(r'\\E\s+[^:]+:', ' ', text)
        if '/\\' in text or "'" in text or 'UNCHANGED' in text:
            errors.append(f'steps: `{where}` is no plain disjunction of steps: it holds a conjunct or a prime of its own')
        found = []
        for piece in text.split('\\/'):
            piece = piece.strip()
            if not piece:
                continue
            match = re.fullmatch(r'\(*\s*([A-Za-z_][A-Za-z0-9_]*)(\([^()]*\))?\s*\)*', piece)
            if match:
                found.append(match.group(1))
            else:
                errors.append(f'steps: `{where}` holds `{" ".join(piece.split())[:60]}`, which is no step by name')
        return found


def check(table, variables, constants, defs, cfgdir=None, module_name=None):
    errors, notes = [], []
    module = Module(variables, constants, defs)
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
    if set(IDENT.findall(defs.get('vars', ''))) != variables:
        errors.append('variables: `vars` is not the tuple of all the module\'s variables')

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

    # 3. the writes: every path of every mapped operator
    mapped, paths_checked = {}, 0
    for action, (operators, rows) in sorted(table['action'].items()):
        expected = set()
        for row in sorted(rows):
            if row not in table['row']:
                errors.append(f'map: the action `{action}` names the row {row}, which the table does not have')
                continue
            parts = {a for (r, a) in table['part'] if r == row}
            if parts and action not in parts:
                errors.append(f'map: the row {row} has parts and none for the action `{action}`')
            expected |= table['part'][(row, action)] if action in parts else (set() if parts else table['row'][row])
        expected = {source.get(name, name) for name in expected}
        together = set()
        for operator in operators:
            mapped[operator] = action
            if operator not in defs:
                errors.append(f'map: the operator `{operator}` of the action `{action}` is not defined in the module')
                continue
            wanted = {source.get(name, name) for name in table['op'][operator]} if operator in table['op'] else expected
            if not wanted <= expected:
                errors.append(f'map: the `op` line of `{operator}` names variables its action `{action}` is not expected to write')
            for index, path in enumerate(split_paths(defs[operator]), 1):
                paths_checked += 1
                where = f'`{operator}` ({action}), path {index}'
                written, unchanged = module.constraints(path, where, errors, operator)
                for name in sorted(variables):
                    times = written[name] + unchanged[name]
                    if times == 0:
                        errors.append(f'writes: {where} neither writes `{name}` nor leaves it in an UNCHANGED')
                    elif times > 1:
                        errors.append(f'writes: {where} constrains `{name}` {times} times '
                                      f'({written[name]} written, {unchanged[name]} UNCHANGED): the step is disabled where they differ')
                for name in sorted(set(written) - wanted):
                    errors.append(f'writes: {where} writes `{name}`, which is not expected of it ({sorted(wanted)})')
                for name in sorted(wanted - set(written)):
                    errors.append(f'writes: {where} does not write `{name}`, which is expected of it ({sorted(wanted)})')
                together |= set(written)
        if together != expected and all(operator in defs for operator in operators):
            errors.append(f'writes: the action `{action}` writes {sorted(together)} and its rows {sorted(rows)} name {sorted(expected)}')
    for operator in sorted(set(table['op']) - set(mapped)):
        errors.append(f'map: the `op` line `{operator}` is no operator of the map')
    for row in sorted(table['row']):
        if table['row'][row] and not any(row in rows for _, rows in table['action'].values()):
            errors.append(f'map: the row {row} writes {sorted(table["row"][row])} and no action names it')

    # 4. the steps: Next, Spec, Fairness, the extra specifications
    if 'Next' not in defs:
        errors.append('steps: the module defines no Next')
    else:
        steps = set(module.disjuncts(defs['Next'], 'Next', errors))
        for name in sorted(steps - set(mapped)):
            errors.append(f'steps: `Next` takes the step `{name}`, which no action of the map names')
        for name in sorted(set(mapped) - steps):
            errors.append(f'steps: the operator `{name}` of the map is no step of `Next`')
    if ' '.join(defs.get('Spec', '').split()) != 'Init /\\ [][Next]_vars /\\ Fairness':
        errors.append('steps: `Spec` is not exactly `Init /\\ [][Next]_vars /\\ Fairness`')
    fairness = defs.get('Fairness', '')
    fair = re.findall(r'\b(WF|SF)_([A-Za-z_][A-Za-z0-9_]*)\(\s*([A-Za-z_][A-Za-z0-9_]*)', fairness)
    if len(fair) != len(re.findall(r'\b(?:WF|SF)_', fairness)) or any(kind != 'WF' or sub != 'vars' for kind, sub, _ in fair):
        errors.append('steps: `Fairness` holds something other than WF_vars(<operator>...)')
    for name in sorted({name for _, _, name in fair} ^ set(table['fair'])):
        errors.append(f'steps: `{name}` is under WF in `Fairness` or on a `fair` line, and not both')
    fair_steps = set()
    for operator, rows in sorted(table['fair'].items()):
        taken = closure(operator, defs) & set(mapped)
        fair_steps |= taken
        reach = set().union(*(table['action'][mapped[step]][1] for step in taken)) if taken else set()
        if not rows or not rows <= reach:
            errors.append(f'steps: the fair operator `{operator}` stands for the rows {sorted(rows)} and takes steps of {sorted(reach)}')
    spec_of = {}
    for name, variants in sorted(table['spec'].items()):
        body = ' '.join(defs.get(name, '').split())
        match = re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]* /\\ \[\]\[(.+)\]_vars', body)
        if not match:
            errors.append(f'steps: the specification `{name}` is not `<Init> /\\ [][<steps>]_vars`')
        else:
            for step in sorted(set(module.disjuncts(match.group(1), name, errors)) - set(mapped)):
                errors.append(f'steps: the specification `{name}` takes the step `{step}`, which no action of the map names')
        for variant in variants:
            spec_of[variant] = name

    # 5. the bounds
    for name in sorted(constants - set(table['bound']) - set(table['param'])):
        errors.append(f'bounds: the CONSTANT `{name}` is neither a `bound` nor a `param` of the table')
    for name in sorted((set(table['bound']) | set(table['param'])) - constants):
        errors.append(f'bounds: the table names `{name}`, which is no CONSTANT of the module')
    for name, kind in sorted(table['bound'].items()):
        holders = sorted(step for step in mapped if step in defs and name in idents_of(step, defs))
        fair_holders = sorted(set(holders) & fair_steps)
        notes.append(f'bound {name} ({kind}): in the enabling of {", ".join(holders) or "NO STEP"}'
                     + (f'; fair: {", ".join(fair_holders)}' if fair_holders else '; none of them fair'))
        if kind not in ('line', 'input'):
            errors.append(f'bounds: `{name}` has the unknown kind `{kind}`')
        if not holders:
            errors.append(f'bounds: `{name}` is in the enabling of no step: a bound outside the steps is unseen by ENABLED')
        if kind == 'input' and fair_holders:
            errors.append(f'bounds: the input bound `{name}` is in the enabling of the fair step(s) {fair_holders}: it needs a Dom line')

    # 6. the cfg files
    cfgs = 0
    if cfgdir:
        for file in sorted(os.listdir(cfgdir)):
            if not file.endswith('.cfg') or (module_name and not file.startswith(module_name + '_')):
                continue
            cfgs += 1
            variant = file[:-4].split('_', 1)[1] if '_' in file else file[:-4]
            with open(os.path.join(cfgdir, file)) as f:
                text = f.read()
            if 'CONSTRAINT' in text:
                errors.append(f'cfg: {file} holds a CONSTRAINT: a bound there is unseen by ENABLED')
            named = re.findall(r'^\s*SPECIFICATION\s+(\S+)', text, flags=re.M)
            if named != [spec_of.get(variant, 'Spec')]:
                errors.append(f'cfg: {file} names the specification {named}, expected [{spec_of.get(variant, "Spec")!r}]')
    operators = sum(len(ops) for ops, _ in table['action'].values())
    return errors, notes, (len(lines), len(table['row']), len(table['action']), operators, paths_checked, cfgs)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--table', required=True)
    parser.add_argument('--tla')
    parser.add_argument('--cfgdir')
    parser.add_argument('--module')
    parser.add_argument('roots', nargs='*')
    args = parser.parse_args()
    try:
        table = read_table(args.table)
        cfgdir, module_name = args.cfgdir, args.module
        if args.tla:
            path = args.tla
        elif args.module and args.roots:
            cfgdir = tempfile.mkdtemp(prefix='writers-check-')
            extract = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'extract.py')
            done = subprocess.run([sys.executable, extract, '--module', args.module, '--out', cfgdir] + args.roots,
                                  capture_output=True, text=True)
            if done.returncode != 0:
                raise Fail('extract.py failed: ' + (done.stderr or done.stdout).strip())
            path = os.path.join(cfgdir, args.module + '.tla')
        else:
            raise Fail('give --tla <file>, or --module <Module> and the source roots')
        variables, constants, defs = read_module(path)
        if not variables:
            raise Fail(f'{path}: no VARIABLES found')
    except (Fail, OSError) as error:
        print(f'writers-check: ERROR {error}', file=sys.stderr)
        return 2
    errors, notes, (lines, rows, actions, operators, paths, cfgs) = check(table, variables, constants, defs, cfgdir, module_name)
    for note in notes:
        print(f'writers-check: {note}')
    for error in errors:
        print(f'writers-check: RED {error}')
    print(f'writers-check: {lines} variable lines, {len(variables)} module variables, {rows} rows, {actions} actions '
          f'({operators} operators, {paths} paths), {cfgs} cfg files, {len(errors)} mismatches')
    return 1 if errors else 0


if __name__ == '__main__':
    sys.exit(main())
