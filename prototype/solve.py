# Solve: an arrow can leave when every cell on the straight ray from its head to the board edge is empty
# (snake movement: body follows the head, so only the ray matters; also reject rays that re-enter its own body).
# usage: python solve.py model.json [removed_ids_csv] [render.png]
import sys, json
NC, NR = 28, 42
D = {'U': (0, -1), 'D': (0, 1), 'L': (-1, 0), 'R': (1, 0)}
model = json.load(open(sys.argv[1]))
arrows = model['arrows']
removed = set(int(x) for x in sys.argv[2].split(',') if x) if __name__ == '__main__' and len(sys.argv) > 2 else set()

def ray(a):
    (c, r), (dc, dr) = arrows[a]['cells'][-1], D[arrows[a]['dir']]
    out = []
    c, r = c + dc, r + dr
    while 0 <= c < NC and 0 <= r < NR:
        out.append((c, r)); c, r = c + dc, r + dr
    return out

def blockers(a, gone):
    occ = {tuple(p): i for i, x in enumerate(arrows) if i not in gone for p in x['cells']}
    body = [tuple(p) for p in arrows[a]['cells']]
    hit = []
    for j, cell in enumerate(ray(a), start=1):
        if cell in occ and occ[cell] != a:
            hit.append(occ[cell])
        elif cell in body[j:]:
            hit.append(a)   # would run into its own not-yet-vacated body
    return hit

def straight(a):
    cs = arrows[a]['cells']
    return len({c for c, _ in cs}) == 1 or len({r for _, r in cs}) == 1

if __name__ == '__main__':
    gone, layers = set(removed), []
    while True:
        free = [a for a in range(len(arrows)) if a not in gone and not blockers(a, gone)]
        if not free: break
        layers.append(free); gone |= set(free)
    left = [a for a in range(len(arrows)) if a not in gone]
    print('arrows', len(arrows), 'already removed', len(removed), 'layers', len(layers), 'UNSOLVABLE' if left else 'solvable')
    for i, L in enumerate(layers):
        print(f'layer {i}:', ' '.join(f"{a}{arrows[a]['dir']}{'s' if straight(a) else ''}@{tuple(arrows[a]['cells'][-1])}" for a in L))
    if left: print('stuck:', left)
    if len(sys.argv) > 3:
        from PIL import Image, ImageDraw
        S = 24
        im = Image.new('RGB', (NC * S + 20, NR * S + 20), (30, 32, 45))
        dr = ImageDraw.Draw(im)
        pal = [(255, 99, 99), (255, 190, 80), (240, 240, 90), (120, 230, 120), (90, 200, 255), (190, 140, 255), (255, 140, 220)]
        lay = {a: i for i, L in enumerate(layers) for a in L}
        P = lambda c, r: (10 + S * c + S // 2, 10 + S * r + S // 2)
        for a, x in enumerate(arrows):
            if a in removed: continue
            col = pal[lay.get(a, 0) % len(pal)] if a in lay else (255, 255, 255)
            pts = [P(*c) for c in x['cells']]
            if len(pts) > 1: dr.line(pts, fill=col, width=3)
            hx, hy = pts[-1]; dx, dy = D[x['dir']]
            dr.polygon([(hx + dx * 9, hy + dy * 9), (hx - dy * 6, hy + dx * 6), (hx + dy * 6, hy - dx * 6)], fill=col)
            tx, ty = pts[len(pts) // 2]
            dr.text((tx + 2, ty - 11), str(a), fill=(255, 255, 255))
        im.save(sys.argv[3])
