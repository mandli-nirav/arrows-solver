# Safe auto-player: capture -> locate board -> verify board == model and hearts == 3 -> tap only
# arrows whose exit ray is clear in the model -> repeat. Stops on ANY mismatch.
# usage: python runner.py model.json state.json max_taps
import sys, json, time, subprocess, io, os
import numpy as np
from PIL import Image
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import solve  # noqa: E402  (reads argv[1] model)

ADB = os.path.expandvars(r'%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe')
SER = os.environ.get('ANDROID_SERIAL', '')  # adb serial of your phone, e.g. from `adb devices`
P, NC, NR = 40, 28, 42
arrows = solve.arrows
state_path, max_taps = sys.argv[2], int(sys.argv[3])
st = json.load(open(state_path)) if os.path.exists(state_path) else {'removed': [], 'log': []}
removed = set(st['removed'])

def adb(*a, binary=False):
    return subprocess.run([ADB, '-s', SER, *a], capture_output=True, timeout=30).stdout if binary else \
        subprocess.run([ADB, '-s', SER, *a], capture_output=True, text=True, timeout=30).stdout

def shot():
    png = adb('exec-out', 'screencap', '-p', binary=True)
    im = np.asarray(Image.open(io.BytesIO(png)).convert('RGB')).astype(int)
    return im

def lav(im):
    R, G, B = im[..., 0], im[..., 1], im[..., 2]
    return ((abs(R - 171) < 40) & (abs(G - 183) < 40) & (abs(B - 244) < 35)) | ((R > 200) & (G < 130) & (B < 140))

def hearts(im):
    return sum(1 for x in (468, 540, 610) if im[266, x, 0] > 200 and im[266, x, 1] < 130)

def safe_xy(x, y):
    if not (50 <= x <= 1030 and 500 <= y <= 2215): return False
    if x < 45 and 300 < y < 650: return False
    if 830 < x < 1060 and 2100 < y < 2320: return False
    return True

def occ_now():
    return {tuple(p): i for i, a in enumerate(arrows) if i not in removed for p in a['cells']}

def locate(m):
    """Find (xoff, yoff) = screen pos of node (0,0) by matching model occupancy to the image."""
    occ = occ_now()
    colp = m[480:2240].sum(axis=0).astype(float)
    best = []
    for frac in np.arange(0, 40, 0.5):
        best.append((sum(colp[int(round(frac + 40 * k))] for k in range(-1, 28) if 0 <= frac + 40 * k < 1079), frac))
    fx = max(best)[1]
    rowp = m[:, 60:1020].sum(axis=1).astype(float)
    fy = max((sum(rowp[int(round(f + 40 * k))] for k in range(10, 58) if f + 40 * k < 2390), f) for f in np.arange(0, 40, 0.5))[1]
    cands = []
    for kx in range(-30, 30):
        xoff = fx + 40 * kx
        for ky in range(-3, 4):
            yoff = 535.5 + ((fy - 535.5) % 40 if (fy - 535.5) % 40 < 20 else (fy - 535.5) % 40 - 40) + 40 * ky
            good = bad = 0
            for c in range(NC):
                x = int(round(xoff + P * c - 0.5))
                if not (8 <= x <= 1072): continue
                for r in range(NR):
                    y = int(round(yoff + P * r - 0.5))
                    if not (8 <= y <= 2392) or (x < 40 and 300 < y < 650) or (830 < x < 1060 and 2100 < y < 2320): continue
                    seen = bool(m[y - 3:y + 4, x - 3:x + 4].any())
                    if seen == ((c, r) in occ): good += 1
                    else: bad += 1
            if good + bad > 200: cands.append((bad / (good + bad), bad, good, xoff, yoff))
    cands.sort()
    return cands[0], (cands[1] if len(cands) > 1 else None)

def verify():
    im = shot(); m = lav(im)
    h = hearts(im)
    (rate, bad, good, xoff, yoff), second = locate(m)
    return im, h, rate, bad, good, xoff, yoff, second

def screen(c, r, xoff, yoff):
    return int(round(xoff + P * c - 0.5)), int(round(yoff + P * r - 0.5))

def save():
    st['removed'] = sorted(removed); json.dump(st, open(state_path, 'w'))

taps = pans = 0
while True:
    for attempt in range(8):
        im, h, rate, bad, good, xoff, yoff, second = verify()
        if h == st.get('hearts', 3) and bad == 0: break
        time.sleep(0.8)
    print(f'hearts={h} mismatches={bad}/{good + bad} xoff={xoff} yoff={yoff} 2nd={second[:3] if second else None}', flush=True)
    if h != st.get('hearts', 3): print(f'STOP: heart count changed to {h}'); break
    if bad != 0: print('STOP: screen does not match model'); Image.fromarray(im.astype('uint8')).save(state_path + '.stop.png'); break
    if second and second[1] <= 3: print('STOP: board position ambiguous'); break
    remaining = [a for a in range(len(arrows)) if a not in removed]
    if not remaining: print('DONE: all arrows cleared'); break
    if taps >= max_taps: print(f'paused after {taps} taps; removed so far {len(removed)}'); break
    free = [a for a in remaining if not solve.blockers(a, removed)]
    if not free: print('STOP: no free arrow in model'); break
    # pick a batch of mutually independent free arrows that are tappable in this view
    import math
    MIN_CLEAR = float(os.environ.get('MIN_CLEAR', '3.5'))
    blocked = [b for b in remaining if b not in free]
    bcells = [(tuple(p), b) for b in blocked for p in arrows[b]['cells']]
    batch, cands = [], []
    for a in free:
        for c, r in map(tuple, arrows[a]['cells']):
            xy = screen(c, r, xoff, yoff)
            if not safe_xy(*xy): continue
            # every other arrow's cell within 1 cell of the tap point must not exist (unambiguous hit)
            d_blk = min((math.dist((c, r), p) for p, _ in bcells), default=99)
            d_any = min((math.dist((c, r), tuple(p)) for b in remaining if b != a for p in arrows[b]['cells']), default=99)
            cands.append((d_blk, d_any, a, (xy, (c, r))))
    cands.sort(reverse=True)
    if cands and cands[0][0] >= MIN_CLEAR:
        batch = [(cands[0][2], cands[0][3])]
        print(f'chose arrow {cands[0][2]} clearance-to-blocked={cands[0][0]:.2f} clearance-to-any={cands[0][1]:.2f}', flush=True)
    elif cands:
        print(f'STOP: best tap clearance {cands[0][0]:.2f} < {MIN_CLEAR} (arrow {cands[0][2]} cell {cands[0][3][1]})'); break
    if not batch:
        # pan toward the free arrows (start drag in the empty strip above the board)
        cx = np.mean([screen(*arrows[a]['cells'][-1], xoff, yoff)[0] for a in free])
        if cx > 540: adb('shell', 'input', 'swipe', '760', '456', '360', '456', '1200')
        else: adb('shell', 'input', 'swipe', '320', '456', '720', '456', '1200')
        print(f'pan toward x={cx:.0f}', flush=True); time.sleep(1.0)
        pans += 1
        if pans > 6: print('STOP: panned 6 times without a tappable free arrow'); break
        continue
    pans = 0
    for a, ((x, y), cell) in batch:
        adb('shell', 'input', 'tap', str(x), str(y))
        removed.add(a); taps += 1
        st['log'].append({'arrow': a, 'tap': [x, y], 'cell': cell, 't': time.time()})
        print(f'tap arrow {a} dir={arrows[a]["dir"]} cell={cell} at ({x},{y})', flush=True)
        time.sleep(0.35)
    save()
    time.sleep(1.2)
save()
