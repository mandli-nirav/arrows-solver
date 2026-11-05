#!/usr/bin/env python3
"""
Build android/app/src/main/assets/levels.bin from YOUR OWN copy of the Arrows game.

The level data belongs to the game's developer, so it is not part of this repository.
Pull the APK that is installed on your phone and convert it locally:

    adb shell pm path com.ecffri.arrows            # shows .../base.apk
    adb pull /data/app/.../base.apk game.apk
    pip install UnityPy==1.*
    python tools/extract_levels.py game.apk

Without levels.bin the app still works, but only through the slower screen-reading fallback.

It also copies the game's own arrow sprites (ArrowHead, ArrowEnd) into android/app/src/main/assets/game/ so the
overlay can draw arrows exactly like the game. These files are the developer's art: they stay local (gitignored).

levels.bin format (big-endian):
    "ARW1"  u32 level_count
    per level: u8 mode (0 main, 1 daily, 2 thunder rush), u16 name_len, name (UTF-8),
               u8 width, u8 height, i32 flags, i32 special_arrow_id, u16 arrow_count,
               per arrow: u8 vertex_count, u16 vertex[vertex_count]
    A vertex is a cell index x + width*y (y = 0 is the BOTTOM row on screen); vertices list the
    arrow's path corners head first, consecutive vertices are joined by straight or diagonal runs.
"""
import argparse
import io
import os
import struct
import sys
import zipfile

MODES = {'levels-main': 0, 'levels-daily': 1, 'levels-thunderrush': 2}


def read_levels(apk_path):
    try:
        import UnityPy
    except ImportError:
        sys.exit('UnityPy is required: pip install UnityPy')
    levels = []
    with zipfile.ZipFile(apk_path) as z:
        bundles = [n for n in z.namelist()
                   if n.startswith('assets/aa/Android/levels-') and n.endswith('.bundle')]
        if not bundles:
            sys.exit('No levels-*.bundle found. Pass the base.apk of com.ecffri.arrows.')
        for name in sorted(bundles):
            mode = os.path.basename(name).split('_')[0]
            if mode not in MODES:
                print('skipping unknown bundle', name)
                continue
            env = UnityPy.load(io.BytesIO(z.read(name)))
            n = 0
            for obj in env.objects:
                if obj.type.name != 'MonoBehaviour':
                    continue
                tree = obj.read_typetree()
                data = tree.get('Data')
                if not data or 'XSize' not in data:
                    continue
                levels.append((
                    MODES[mode], tree['m_Name'], data['XSize'], data['YSize'],
                    data.get('_staticFlags', 0), data.get('SpecialArrowId', -1),
                    [a['CompactIndices'] for a in data['Arrows']],
                ))
                n += 1
            print(f'{mode}: {n} levels')
    return levels


def export_sprites(apk_path, out_dir, names=('ArrowHead', 'ArrowEnd')):
    """Save the game's white arrow sprites as PNG (the overlay tints them)."""
    import tempfile
    import UnityPy
    with zipfile.ZipFile(apk_path) as z, tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:  # UnityPy keeps files open on Windows
        data = [n for n in z.namelist() if n.startswith('assets/bin/Data/') and '/Managed/' not in n]
        for n in data:
            z.extract(n, tmp)
        env = UnityPy.load(os.path.join(tmp, 'assets', 'bin', 'Data'))
        os.makedirs(out_dir, exist_ok=True)
        found = set()
        for obj in env.objects:
            if obj.type.name != 'Sprite':
                continue
            sprite = obj.read()
            if sprite.m_Name in names and sprite.m_Name not in found:
                sprite.image.save(os.path.join(out_dir, sprite.m_Name + '.png'))
                found.add(sprite.m_Name)
        print('sprites:', ', '.join(sorted(found)) or 'none found')


def pack(levels):
    out = bytearray(b'ARW1') + struct.pack('>I', len(levels))
    for mode, name, w, h, flags, special, arrows in levels:
        nb = name.encode('utf-8')
        assert w < 256 and h < 256 and w * h < 65536, name
        out += struct.pack('>BH', mode, len(nb)) + nb
        out += struct.pack('>BBiiH', w, h, flags, special if isinstance(special, int) else -1, len(arrows))
        for verts in arrows:
            assert 2 <= len(verts) < 256, name
            out += struct.pack('>B', len(verts)) + struct.pack(f'>{len(verts)}H', *verts)
    return bytes(out)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('apk', help='base.apk of com.ecffri.arrows pulled from your phone')
    ap.add_argument('-o', '--out', default=os.path.join(here, '..', 'android', 'app', 'src', 'main', 'assets', 'levels.bin'))
    args = ap.parse_args()
    data = pack(read_levels(args.apk))
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, 'wb') as f:
        f.write(data)
    print(f'wrote {args.out} ({len(data):,} bytes)')
    export_sprites(args.apk, os.path.join(os.path.dirname(os.path.abspath(args.out)), 'game'))


if __name__ == '__main__':
    main()
