#!/usr/bin/env python3
"""One line per frame of `dumpsys gfxinfo <proc> framestats` (stdin): when, gap since the last, total, GPU.
Only the frames at least this slow with -s MS. Usage: ... | tools/frames.py [window] [-s 20]"""
import sys

args = sys.argv[1:]
slow = 0.0
if '-s' in args:
    i = args.index('-s')
    slow = float(args[i + 1])
    del args[i:i + 2]
want = args[0] if args else ''
hdr, rows, win = None, [], ''
for line in sys.stdin.read().splitlines():
    if line.startswith('Window: '):
        win = line[8:]
        continue
    if want and want not in win:
        continue
    if line.startswith('Flags,'):
        hdr = line.strip().strip(',').split(',')
        continue
    if hdr and line[:1].isdigit():
        v = line.strip().strip(',').split(',')
        if len(v) >= len(hdr):
            rows.append(dict(zip(hdr, map(int, v))))
if not rows:
    sys.exit()
t0, prev = rows[0]['IntendedVsync'], None
for r in rows:
    iv = r['IntendedVsync']
    gap = (iv - prev) / 1e6 if prev else 0
    prev = iv
    tot = (r['FrameCompleted'] - iv) / 1e6
    gpu = (r['GpuCompleted'] - r['IssueDrawCommandsStart']) / 1e6 if r.get('GpuCompleted') else -1
    issue = (r['SwapBuffers'] - r['IssueDrawCommandsStart']) / 1e6
    if max(tot, gpu) >= slow:
        print(f"{(iv - t0) / 1e6:8.0f} ms  gap {gap:6.1f}  total {tot:6.1f}  issue {issue:5.1f}  gpu {gpu:6.1f}")
