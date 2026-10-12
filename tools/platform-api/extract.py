"""Turns `javap -v` dumps of the platform LSP / DAP modules into JSON: classes, members, stability marks, protocol types used by the implementation."""
import glob, io, json, os, re, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
STATUS = {
    'org.jetbrains.annotations.ApiStatus$Experimental': 'Experimental',
    'org.jetbrains.annotations.ApiStatus$Internal': 'Internal',
    'org.jetbrains.annotations.ApiStatus$NonExtendable': 'NonExtendable',
    'org.jetbrains.annotations.ApiStatus$OverrideOnly': 'OverrideOnly',
    'org.jetbrains.annotations.ApiStatus$ScheduledForRemoval': 'ScheduledForRemoval',
    'org.jetbrains.annotations.ApiStatus$Obsolete': 'Obsolete',
    'java.lang.Deprecated': 'Deprecated',
    'kotlin.Deprecated': 'Deprecated',
}


def marks(lines):
    found = []
    for line in lines:
        text = line.strip()
        if text in STATUS and STATUS[text] not in found:
            found.append(STATUS[text])
        if text == 'Deprecated: true' and 'Deprecated' not in found:
            found.append('Deprecated')
    return found


def parse(path):
    text = io.open(path, encoding='utf-8', errors='replace').read()
    classes = []
    for chunk in text.split('\nClassfile ')[0:] if text.startswith('Classfile ') else text.split('Classfile ')[1:]:
        lines = chunk.split('\n')
        header = next((l for l in lines if re.match(r'^(public |protected |private |final |abstract |static )*(class|interface|enum|@interface) ', l) or re.match(r'^(public |abstract |final )*(class|interface) ', l)), None)
        if header is None:
            header = next((l for l in lines if ' class ' in l or ' interface ' in l or l.startswith('interface ') or l.startswith('class ')), '')
        try:
            open_at = lines.index('{')
            close_at = len(lines) - 1 - lines[::-1].index('}')
        except ValueError:
            continue
        members = []
        current = None
        for line in lines[open_at + 1:close_at]:
            if re.match(r'^  \S', line):
                if current:
                    members.append(current)
                current = {'decl': line.strip(), 'attrs': []}
            elif current is not None:
                current['attrs'].append(line)
        if current:
            members.append(current)
        out_members = []
        for m in members:
            decl = m['decl']
            if decl.startswith('static {}') or '$' in decl.split('(')[0].split(' ')[-1]:
                continue
            flags = next((a.strip() for a in m['attrs'] if a.strip().startswith('flags:')), '')
            if 'ACC_SYNTHETIC' in flags or 'ACC_BRIDGE' in flags:
                continue
            signature = next((a.strip().split('//', 1)[1].strip() for a in m['attrs'] if a.strip().startswith('Signature:') and '//' in a), None)
            out_members.append({'decl': decl.rstrip(';'), 'generic': signature, 'marks': marks(m['attrs']), 'abstract': 'ACC_ABSTRACT' in flags})
        name_match = re.search(r'(?:class|interface|enum) ([\w.$]+)', header)
        kotlin = any('kotlin.Metadata' in l for l in lines[close_at:])
        classes.append({
            'name': name_match.group(1) if name_match else '?',
            'header': header.strip(),
            'kind': 'interface' if ' interface ' in ' ' + header else ('enum' if ' enum ' in ' ' + header else ('abstract class' if 'abstract class' in header else 'class')),
            'marks': marks(lines[close_at:]),
            'members': out_members,
        })
    return classes


def protocol_types(module_dir, package):
    """Types of lsp4j the classes of a module refer to: a request the platform sends needs its Params / Arguments type."""
    pattern = re.compile(rb'org/eclipse/lsp4j/' + package + rb'([A-Z][A-Za-z0-9]+)')
    used = {}
    for path in glob.glob(os.path.join(module_dir, '**', '*.class'), recursive=True):
        data = open(path, 'rb').read()
        owner = os.path.relpath(path, module_dir).replace(os.sep, '/')[:-6]
        for match in set(pattern.findall(data)):
            used.setdefault(match.decode(), set()).add(owner.split('/')[-1].split('$')[0])
    return {k: sorted(v) for k, v in sorted(used.items())}


def all_protocol_types(jar, package):
    z = zipfile.ZipFile(jar)
    prefix = 'org/eclipse/lsp4j/' + package
    return sorted(n[len(prefix):-6] for n in z.namelist() if n.startswith(prefix) and n.endswith('.class') and '$' not in n and '/' not in n[len(prefix):])


IDE_LIB = r'C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.4\lib'
data = {
    'lsp': parse(os.path.join(HERE, 'lsp.api.javap.txt')),
    'dap': parse(os.path.join(HERE, 'dap.api.javap.txt')),
    'lspUsed': protocol_types(os.path.join(HERE, 'lsp.impl'), b''),
    'lspUsedApi': protocol_types(os.path.join(HERE, 'lsp'), b''),
    'dapUsed': protocol_types(os.path.join(HERE, 'dap'), b'debug/'),
    'lsp4jAll': all_protocol_types(os.path.join(IDE_LIB, 'eclipse.lsp4j.jar'), ''),
    'dap4jAll': all_protocol_types(os.path.join(IDE_LIB, 'eclipse.lsp4j.debug.jar'), 'debug/'),
    'xml': {name: io.open(os.path.join(HERE, name), encoding='utf-8').read() for name in ('lsp/intellij.platform.lsp.xml', 'lsp.impl/intellij.platform.lsp.impl.xml', 'dap/intellij.platform.dap.xml')},
}
json.dump(data, io.open(os.path.join(HERE, 'api.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
for key in ('lsp', 'dap'):
    classes = data[key]
    print(key, 'classes:', len(classes), 'members:', sum(len(c['members']) for c in classes))
    from collections import Counter
    print('   class marks:', Counter(tuple(c['marks']) for c in classes).most_common(8))
print('lsp4j types used by lsp.impl:', len(data['lspUsed']), 'of', len(data['lsp4jAll']))
print('dap4j types used by dap:', len(data['dapUsed']), 'of', len(data['dap4jAll']))
