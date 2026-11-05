# Parse the arrows board from several panned screenshots into a model JSON.
# usage: python parse.py out.json img1.png:xoff img2.png:xoff ...   (xoff = screen x of column 0)
import sys, json
import numpy as np
from PIL import Image

P, NC, NR, YOFF = 40, 28, 42, 535.5

def load(path):
    im = np.asarray(Image.open(path).convert('RGB')).astype(int)
    R, G, B = im[..., 0], im[..., 1], im[..., 2]
    return (abs(R - 171) < 40) & (abs(G - 183) < 40) & (abs(B - 244) < 35)

def occluded(x, y):
    if x < 8 or x > 1072 or y < 8 or y > 2392: return True
    if x < 28 and 320 < y < 630: return True            # edge-handle overlay, left side
    if 855 < x < 1040 and 2120 < y < 2305: return True   # '#' button
    return False

def any_at(m, x, y, r=3):
    return bool(m[y - r:y + r + 1, x - r:x + r + 1].any())

feats = {}   # key -> list of observed values
def vote(k, v):
    feats.setdefault(k, []).append(v)

stats = []
for arg in sys.argv[2:]:
    path, xoff = arg.rsplit(':', 1)
    xoff = float(xoff)
    m = load(path)
    X = lambda c: int(round(xoff + P * c - 0.5))
    Y = lambda r: int(round(YOFF + P * r - 0.5))
    for c in range(NC):
        for r in range(NR):
            x, y = X(c), Y(r)
            if not occluded(x, y):
                vote(('n', c, r), any_at(m, x, y))
            # horizontal edge to (c+1, r)
            if c + 1 < NC and not occluded(x, y) and not occluded(x + P, y):
                frac = np.mean([m[y - 4:y + 5, x + t].any() for t in range(13, 28)])
                vote(('h', c, r), 'Y' if frac >= 0.95 else ('N' if frac <= 0.15 else f'?{frac:.2f}'))
            if r + 1 < NR and not occluded(x, y) and not occluded(x, y + P):
                frac = np.mean([m[y + t, x - 4:x + 5].any() for t in range(13, 28)])
                vote(('v', c, r), 'Y' if frac >= 0.95 else ('N' if frac <= 0.15 else f'?{frac:.2f}'))
            # arrowhead probes in each outward direction o: tip pixels at t=8..11 and base width
            for o, (dx, dy) in {'U': (0, -1), 'D': (0, 1), 'L': (-1, 0), 'R': (1, 0)}.items():
                if occluded(x + dx * 16, y + dy * 16) or occluded(x, y):
                    continue
                px, py = -dy, dx
                tip = sum(any(m[y + dy * t + py * s, x + dx * t + px * s] for s in (-2, -1, 0, 1, 2)) for t in (8, 9, 10, 11))
                width = max(sum(m[y + dy * t + py * s, x + dx * t + px * s] for s in range(-13, 14)) for t in (-2, 0, 2, 4))
                vote(('a', c, r, o), (tip, width))

model = {'conflicts': [], 'unknown': []}
def merged(k):
    vs = feats.get(k)
    if not vs: return None
    if k[0] == 'a':
        return vs
    s = set(vs)
    if len(s) > 1:
        model['conflicts'].append([list(k), vs])
    return vs[0]

nodes = {(c, r) for c in range(NC) for r in range(NR) if merged(('n', c, r))}
adj = {n: [] for n in nodes}
for c in range(NC):
    for r in range(NR):
        for kind, (dc, dr) in (('h', (1, 0)), ('v', (0, 1))):
            v = merged((kind, c, r))
            if v is None:
                if (c, r) in nodes and (c + dc, r + dr) in nodes:
                    model['unknown'].append([kind, c, r])
                continue
            if isinstance(v, str) and v.startswith('?'):
                model['unknown'].append([kind, c, r, v])
                continue
            if v == 'Y':
                a, b = (c, r), (c + dc, r + dr)
                if a in adj and b in adj:
                    adj[a].append(b); adj[b].append(a)
                else:
                    model['unknown'].append(['edge-without-node', kind, c, r])

seen, arrows, problems = set(), [], []
DIRS = {(0, -1): 'U', (0, 1): 'D', (-1, 0): 'L', (1, 0): 'R'}
for n in sorted(nodes):
    if n in seen: continue
    comp, stack = [], [n]
    seen.add(n)
    while stack:
        u = stack.pop(); comp.append(u)
        for w in adj[u]:
            if w not in seen: seen.add(w); stack.append(w)
    ends = [u for u in comp if len(adj[u]) == 1]
    if any(len(adj[u]) > 2 for u in comp) or len(ends) != 2:
        problems.append({'comp': comp, 'why': 'not a simple path', 'ends': ends})
        continue
    scores = []
    for e in ends:
        nb = adj[e][0]
        o = DIRS[(e[0] - nb[0], e[1] - nb[1])]
        obs = merged(('a', e[0], e[1], o)) or []
        scores.append((e, o, obs))
    heads = [s for s in scores if s[2] and all(t >= 3 and w >= 12 for t, w in s[2])]
    tails = [s for s in scores if s[2] and all(t == 0 and w <= 9 for t, w in s[2])]
    if len(heads) != 1 or len(tails) != 1:
        problems.append({'comp': comp, 'why': 'head ambiguous', 'scores': [[list(e), o, obs] for e, o, obs in scores]})
        continue
    head, hdir = heads[0][0], heads[0][1]
    tail = tails[0][0]
    path, prev = [tail], None
    while path[-1] != head:
        nxt = [w for w in adj[path[-1]] if w != prev][0]
        prev = path[-1]; path.append(nxt)
    arrows.append({'cells': [list(p) for p in path], 'dir': hdir})

model['arrows'] = arrows
model['problems'] = problems
json.dump(model, open(sys.argv[1], 'w'))
print('nodes', len(nodes), 'arrows', len(arrows), 'problems', len(problems),
      'conflicts', len(model['conflicts']), 'unknown', len(model['unknown']))
for p in problems: print('PROBLEM', p)
for c in model['conflicts'][:30]: print('CONFLICT', c)
for u in model['unknown'][:30]: print('UNKNOWN', u)
