#!/usr/bin/env python3
"""Generate glimmer-cui's tag table from CUI's headers.

    python3 gen/gen_tags.py [CUI-dir] [cui-jolt-dir]

writes src/glimmer_cui/tags.clj: one entry per CUI component, as data that
glimmer_cui.widgets compiles into replays. The classification of every C
parameter (string, string array, struct array, out-parameter, options struct
...) is cui-jolt's own, from its gen/gen.py, so the tag table and cui.core
agree on what each argument is.

CUI-dir defaults to native/CUI (cloned by `jolt native`). cui-jolt-dir
defaults to the checkout of the sha pinned in deps.edn under ~/.jolt/gitlibs,
then to ../../jlt-commons/cui-jolt. Needs pycparser, as cui-jolt's gen does.
"""
import os, re, shutil, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)


def cui_jolt_dir():
    if len(sys.argv) > 2:
        return os.path.abspath(sys.argv[2])
    deps = open(os.path.join(ROOT, 'deps.edn')).read()
    m = re.search(r'jlt-commons/cui-jolt\s*\{[^}]*:git/sha\s+"([0-9a-f]+)"', deps)
    if m:
        p = os.path.expanduser('~/.jolt/gitlibs/https___github.com_jlt-commons_cui-jolt/' + m.group(1))
        if os.path.isdir(p):
            return p
    return os.path.abspath(os.path.join(ROOT, '..', '..', 'jlt-commons', 'cui-jolt'))


CUI = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, 'native', 'CUI'))
CJ = cui_jolt_dir()

# cui-jolt's gen.py parses the headers when imported and writes its
# preprocessed all.i next to itself: import it from a scratch copy so neither
# checkout is touched.
_tmp = tempfile.mkdtemp(prefix='glimmer-cui-gen-')
shutil.copytree(os.path.join(CJ, 'gen'), os.path.join(_tmp, 'gen'))
sys.path.insert(0, os.path.join(_tmp, 'gen'))
sys.argv = [sys.argv[0], CUI]
import gen as G                       # noqa: E402
from model import tstr                # noqa: E402

FUNCS = G.FUNCS

# ---------------------------------------------------------------------------
# What counts as a component

# Components without an options struct (their signatures are all positional).
PLAIN_LEAVES = '''
spacer gap separator heading label secondary muted code kbd link icon
radio_group range_slider rating calendar color_picker nav_section breadcrumb
pagination stepper menu_separator menu_label tooltip spinner skeleton
avatar_group meter sparkline gauge command_palette otp_input copy_button
password_strength progress_ring reactions theme_switcher theme_toggle
filter_bar chat_divider typing_indicator presence dock_separator source_list
citation toc toc_anchor range_calendar time_dial avatar_upload shortcut_label
toolbar_separator toolbar_spacer status_bar_spacer inspector_group emoji
response_status table_row table_cell table_cell_text tree_cell
node_link data_grid_cell data_grid_cell_text data_grid_cell_edit data_grid_edit
tree_table_name
'''.split()

# Functions with an options struct that are not standalone components:
# actions (a toast queues a new toast on every call), and the begin halves
# of protocols glimmer-cui.protocols implements by hand (data grid rows,
# kanban drops, node editor links, card stacks).
NOT_LEAVES = {
    'cui_toast_ex',
    'cui_data_grid_begin_ex', 'cui_tree_table_begin_ex', 'cui_kanban_column_begin_ex',
    'cui_node_editor_begin_ex', 'cui_node_begin_ex', 'cui_card_stack_begin',
}

# Scope macros glimmer-cui.protocols implements by hand: virtual_list's
# children are the rows in [*first, *last), known only once begin has run,
# and reorder and card stack items are numbered by their parent.
NOT_CONTAINERS = {'cui_virtual_list', 'cui_card_stack_item', 'cui_reorder_item'}

# Functions whose int return is CUI_CHANGED / CUI_SUBMITTED flags.
FLAG_RETURNS = {'cui_input_ex', 'cui_textarea_ex', 'cui_search_ex', 'cui_inline_edit_ex'}

# Struct out-parameters that are C-owned state, passed through as a raw
# pointer the caller allocated (cui.core/terminal-buffer).
RAW_STRUCTS = {'cui_terminal_buffer'}

# Positional parameter -> prop renames, where the C name says nothing.
PROP_RENAMES = {('cui_push_id', 'str'): 'id'}


def tag_name(cname):
    n = cname[4:]
    if n.endswith('_ex'):
        n = n[:-3]
    return G.kebab(n)


def kw(s):
    return ':' + s


# ---------------------------------------------------------------------------
# Parameters -> arg specs

def ek_str(s):
    """'[:struct :cui_date]' as emitted by gen.py, kept verbatim."""
    return s


def args_of(cname, params):
    """The arg specs for C params after the context handle, and the cells."""
    out, cells, i = [], [], 1
    while i < len(params):
        pn, t = params[i]
        k, absorb = G.param_kind(params, i)
        # a text buffer named just `buf` is the field's value, as on :input
        pn2 = 'value' if pn == 'buf' and not any(q == 'value' for q, _ in params) else pn
        prop = G.kebab(PROP_RENAMES.get((cname, pn), pn2) or f'a{i}')
        nxt = params[i + 1] if i + 1 < len(params) else None
        nxt2 = params[i + 2] if i + 2 < len(params) else None
        if (k.startswith('ref ') and not absorb and nxt and nxt[0] == 'count'
                and tstr(nxt[1]) == 'int *' and nxt2 and nxt2[0] == 'capacity'):
            # keys + int *count + int capacity: an editable array that can grow
            ek = k[4:]
            out.append(f'[:cell-cap {kw(prop)} {ek}]')
            cells.append(f'{{:prop {kw(prop)} :kind {ek} :growable true}}')
            i += 3
            continue
        if k == 'str':
            out.append(f'[:str {kw(prop)}]')
        elif k == 'str-len':
            out.append(f'[:str-len {kw(prop)}]')
        elif k == 'strs':
            out.append(f'[:strs {kw(prop)}]')
        elif k == 'buf':
            out.append(f'[:buf {kw(prop)}]')
            cells.append(f'{{:prop {kw(prop)} :kind [:text]}}')
        elif k == 'mods':
            out.append(f'[:mods {kw(prop)}]')
        elif k == 'raw':
            out.append(f'[:raw {kw(prop)}]')
        elif k.startswith('vec '):
            out.append(f'[:vec {kw(prop)} {k[4:]}]')
        elif k.startswith('in '):
            ek = k[3:]
            m = re.match(r'\[:struct :(\w+)\]', ek)
            if pn == 'opts' and m and i == len(params) - 1:
                out.append(f'[:opts :{m.group(1)}]')
            else:
                out.append(f'[:in {kw(prop)} {ek}]')
        elif k.startswith('val '):
            out.append(f'[:val {kw(prop)} {k[4:]}]')
        elif k.startswith('ref '):
            ek = k[4:]
            m = re.match(r'\[:struct :(\w+)\]', ek)
            if m and m.group(1) in RAW_STRUCTS:
                out.append(f'[:raw {kw(prop)}]')
            else:
                out.append(f'[:cell {kw(prop)} {ek}{" :count" if absorb else ""}]')
                cells.append(f'{{:prop {kw(prop)} :kind {ek}}}')
        else:
            raise ValueError(f'{cname}: unhandled kind {k}')
        i += 2 if absorb else 1
    return out, cells


def ret_of(cname, ret):
    if cname in FLAG_RETURNS:
        return '[:flags]'
    rk = G.ret_kind(ret)
    if rk is None:
        return 'nil'
    if rk[0] == 'struct':
        return f'[:struct :{rk[1]}]'
    if rk[0] == 'enum':
        return f'[:enum :{rk[1]}]'
    if tstr(ret) == '_Bool' or tstr(ret) == 'bool':
        return '[:bool]'
    return '[:plain]'


def c_sig(name, ret, params):
    return f'{tstr(ret)} {name}(' + ', '.join(f'{tstr(t)} {pn}' for pn, t in params) + ')'


def has_ctx(params):
    return params and tstr(params[0][1]) == 'cui_ctx *'


def entry(tag, cname, fields):
    body = '\n    '.join(f'{k} {v}' for k, v in fields)
    return f'   {kw(tag)}\n   {{{body}}}'


def doc_str(s):
    return '"' + s.replace('\\', '\\\\').replace('"', '\\"') + '"'


# ---------------------------------------------------------------------------
# Leaves

def leaves(container_begins):
    out, seen = {}, {}
    for name, ret, params, variadic in G.M.funcs:
        if variadic or not has_ctx(params) or name in G.SKIP or name in NOT_LEAVES:
            continue
        if name in container_begins:
            continue
        base = name[4:-3] if name.endswith('_ex') else name[4:]
        has_opts = any(G.param_kind(params, i)[0].startswith('in [:struct')
                       and params[i][0] == 'opts' for i in range(1, len(params)))
        if not (has_opts or base in PLAIN_LEAVES):
            continue
        if name.endswith('_begin') or name.endswith('_begin_ex'):
            continue
        tag = tag_name(name)
        # prefer cui_foo_ex over cui_foo when both are components
        if tag in seen and not name.endswith('_ex'):
            continue
        args, cells = args_of(name, params)
        seen[tag] = name
        out[tag] = (name, ret, params, args, cells)
    return out


def containers():
    out = {}
    for sm in G.scope_macros():
        if sm['name'] in NOT_CONTAINERS:
            continue
        b = FUNCS[sm['begin']]
        args, cells = args_of(sm['begin'], b[2])
        out[tag_name(sm['name'])] = (sm, b, args, cells)
    return out


# ---------------------------------------------------------------------------
# Emit

def shadowed(args):
    """Positional props that are also fields of the options struct: the
    positional argument takes the prop, so the field cannot be set."""
    m = [re.match(r'\[:opts :(\w+)\]', a) for a in args]
    opts = next((x.group(1) for x in m if x), None)
    if not opts:
        return []
    tag = next(t for t, n in G.TAG_TO_NAME.items() if n == opts)
    fields = {G.kebab(n) for n, _ in G.field_list(tag)}
    props = [re.match(r'\[:[a-z-]+ :([a-z0-9-]+)', a).group(1) for a in args if not a.startswith('[:opts')]
    return sorted(set(props) & fields)


def emit():
    cs = containers()
    begins = {sm['begin'] for sm, *_ in cs.values()}
    ls = leaves(begins)
    o = [';; GENERATED by gen/gen_tags.py from the CUI headers, with cui-jolt\'s gen/gen.py',
         ';; classifying each parameter. Do not edit; rerun the generator.\n',
         '(ns glimmer-cui.tags',
         '  "Every CUI component as data: which C function a tag calls and how each',
         '  of its parameters maps to a prop. glimmer-cui.widgets compiles these into',
         '  replays; its hand-written specs take precedence over these.',
         '',
         '  An arg is [kind prop & more]:',
         '    [:str k]          a C string (icon keywords work)',
         '    [:str-len k]      a C string and its byte length',
         '    [:strs k]         a collection of strings, as char*[] and a count',
         '    [:vec k ek]       a collection, as a C array of ek and a count',
         '    [:in k ek]        a read-only struct (a map) or array',
         '    [:val k ek]       a by-value scalar, enum or struct',
         '    [:mods k]         a modifier mask',
         '    [:raw k]          a pointer passed through (a texture, a terminal buffer)',
         '    [:buf k]          a text cell: a C buffer and its capacity',
         '    [:cell k ek]      an out-parameter cell (:count: plus its element count)',
         '    [:cell-cap k ek]  an editable array: cell, int *count, capacity',
         '    [:opts t]         the options struct t, from every other prop',
         '',
         '  :ret is nil, [:bool], [:flags] (CUI_CHANGED / CUI_SUBMITTED), [:plain],',
         '  [:enum e] or [:struct t]."',
         '  (:require [cui.raw :as raw]))\n']

    o.append('(def leaves\n  {')
    rows = []
    for tag in sorted(ls):
        name, ret, params, args, cells = ls[tag]
        for f in shadowed(args):
            print(f'note: {tag}: positional :{f} shadows the {name} options field', file=sys.stderr)
        rows.append(entry(tag, name, [
            (':c', doc_str(c_sig(name, ret, params))),
            (':fn', f'raw/{name}'),
            (':args', '[' + ' '.join(args) + ']'),
            (':cells', '[' + ' '.join(cells) + ']'),
            (':ret', ret_of(name, ret))]))
    o.append('\n'.join(rows) + '})\n')

    o.append('(def containers\n  {')
    rows = []
    for tag in sorted(cs):
        sm, (bname, bret, bparams, _), args, cells = cs[tag]
        rows.append(entry(tag, bname, [
            (':c', doc_str(c_sig(bname, bret, bparams))),
            (':begin', f'raw/{sm["begin"]}'),
            (':end', f'raw/{sm["end"]}'),
            (':conditional', 'true' if sm['cond'] else 'false'),
            (':args', '[' + ' '.join(args) + ']'),
            (':cells', '[' + ' '.join(cells) + ']'),
            (':ret', ret_of(bname, bret))]))
    o.append('\n'.join(rows) + '})\n')
    return '\n'.join(o), len(ls), len(cs)


def main():
    src, nl, nc = emit()
    path = os.path.join(ROOT, 'src', 'glimmer_cui', 'tags.clj')
    open(path, 'w').write(src)
    print(f'{nl} leaves, {nc} containers -> {os.path.relpath(path, ROOT)}')
    shutil.rmtree(_tmp, ignore_errors=True)


if __name__ == '__main__':
    main()
