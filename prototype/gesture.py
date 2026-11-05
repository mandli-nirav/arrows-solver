# Build a sendevent shell script for a 2-finger gesture on /dev/input/event3 (fts_ts, protocol B).
# usage: python gesture.py out.sh ax0 ay0 ax1 ay1 bx0 by0 bx1 by1 [steps]
import sys
out = sys.argv[1]
ax0, ay0, ax1, ay1, bx0, by0, bx1, by1 = map(int, sys.argv[2:10])
steps = int(sys.argv[10]) if len(sys.argv) > 10 else 15
D = '/dev/input/event3'
L = []
ev = lambda t, c, v: L.append(f'sendevent {D} {t} {c} {v}')
syn = lambda: ev(0, 0, 0)
# finger down
ev(3, 47, 0); ev(3, 57, 900); ev(3, 48, 6); ev(3, 53, ax0); ev(3, 54, ay0)
ev(3, 47, 1); ev(3, 57, 901); ev(3, 48, 6); ev(3, 53, bx0); ev(3, 54, by0)
ev(1, 330, 1); syn()
for i in range(1, steps + 1):
    f = i / steps
    ev(3, 47, 0); ev(3, 53, round(ax0 + (ax1 - ax0) * f)); ev(3, 54, round(ay0 + (ay1 - ay0) * f))
    ev(3, 47, 1); ev(3, 53, round(bx0 + (bx1 - bx0) * f)); ev(3, 54, round(by0 + (by1 - by0) * f))
    syn()
# lift both
ev(3, 47, 0); ev(3, 57, 4294967295); ev(3, 47, 1); ev(3, 57, 4294967295)
ev(1, 330, 0); syn()
open(out, 'w', newline='\n').write('\n'.join(L) + '\n')
