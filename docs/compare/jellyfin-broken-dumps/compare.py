import io, re
B = 'C:/Users/dvislobokov/idea-dotnet-support/build/compare/broken/'
roslyn = {}
for l in io.open(B + 'dotnet-build.txt', encoding='utf-8'):
    f, line, col, code, sev, msg = l.rstrip('\n').split('\t', 5)
    roslyn[(f, int(line), code)] = (sev, msg)
plugin = {}
cur = None
for l in io.open(B + 'plugin-raw.txt', encoding='utf-8', errors='replace'):
    if l.startswith('== '):
        cur = l[3:].strip()
        continue
    m = re.match(r'^([EW]) (\d+) \[(.*?)\] (CS\d+): (.*)$', l.rstrip('\n'))
    if m and cur:
        plugin.setdefault((cur, int(m[2]), m[4]), ('error' if m[1] == 'E' else 'warning', m[5], m[3]))
ok = sorted(k for k in roslyn if k in plugin)
missing = sorted(k for k in roslyn if k not in plugin)
extra = sorted(k for k in plugin if k not in roslyn)
out = io.open(B + 'compare.txt', 'w', encoding='utf-8')
def w(s):
    out.write(s + '\n')
w('roslyn=%d plugin=%d ok=%d missing=%d extra=%d' % (len(roslyn), len(plugin), len(ok), len(missing), len(extra)))
w('roslyn errors=%d ok=%d missing=%d' % (sum(1 for v in roslyn.values() if v[0] == 'error'), sum(1 for k in ok if roslyn[k][0] == 'error'), sum(1 for k in missing if roslyn[k][0] == 'error')))
w('plugin errors extra=%d warnings extra=%d' % (sum(1 for k in extra if plugin[k][0] == 'error'), sum(1 for k in extra if plugin[k][0] == 'warning')))
for name, keys in (('OK', ok), ('MISSING', missing), ('EXTRA', extra)):
    w('## ' + name)
    for k in keys:
        v = roslyn.get(k) or plugin.get(k)
        w('%s:%d %s %s | %s' % (k[0], k[1], k[2], v[0], v[1][:170]))
out.close()
print(io.open(B + 'compare.txt', encoding='utf-8').read())
