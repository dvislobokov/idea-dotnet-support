import io, re
ROOT = 'C:/Users/dvislobokov/idea-dotnet-support/build/compare/'
rx = re.compile(r'^\s*(?P<file>[A-Za-z]:[^(]+?)\((?P<line>\d+),(?P<col>\d+)\): (?P<sev>error|warning) (?P<code>CS\d+): (?P<msg>.*?)(?: \[[^\]]*\])?$')
seen = {}
for raw in io.open(ROOT + 'broken/build.log', encoding='utf-8', errors='replace'):
    m = rx.match(raw.rstrip())
    if not m:
        continue
    f = m['file'].replace('\\', '/')
    f = f[f.lower().index('/jellyfin/') + len('/jellyfin/'):]
    key = (f, int(m['line']), m['code'])
    seen.setdefault(key, (int(m['col']), m['sev'], m['msg']))
out = ["%s\t%d\t%d\t%s\t%s\t%s" % (f, l, v[0], c, v[1], v[2]) for (f, l, c), v in sorted(seen.items())]
io.open(ROOT + 'broken/dotnet-build.txt', 'w', encoding='utf-8').write('\n'.join(out) + '\n')
print(len(out))
