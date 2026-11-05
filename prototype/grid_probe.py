# Probe: find the arrow-line colour and the grid pitch/offset in a phone screenshot.
import sys
import numpy as np
from PIL import Image
im = np.asarray(Image.open(sys.argv[1]).convert('RGB')).astype(int)
R, G, B = im[..., 0], im[..., 1], im[..., 2]
H, W = R.shape
# sample colour of lines: brightest bluish pixels in board band
band = im[500:2200]
cols, counts = np.unique(band.reshape(-1, 3), axis=0, return_counts=True)
top = np.argsort(-counts)[:8]
print('top colours in board band:', [(tuple(cols[i]), counts[i]) for i in top])
mask = (B > 200) & (R > 140) & (R < 200) & (G > 140) & (G < 200)
y0, y1 = 500, 2200
colproj = mask[y0:y1].sum(axis=0)
rowproj = mask[y0:y1].sum(axis=1)
def peaks(p, thr):
    idx = np.where(p > thr)[0]
    groups, cur = [], [idx[0]]
    for i in idx[1:]:
        if i - cur[-1] <= 2: cur.append(i)
        else: groups.append(cur); cur = [i]
    groups.append(cur)
    return [(round(float(np.average(g, weights=p[g])), 1), int(p[g].sum())) for g in groups]
cp = peaks(colproj, colproj.max() * 0.15)
rp = peaks(rowproj, rowproj.max() * 0.15)
print('col peaks', len(cp), cp)
print('row peaks', len(rp), [(r[0] + y0, r[1]) for r in rp])
