#!/usr/bin/env python3
"""Phases of the frames in `dumpsys gfxinfo <proc> framestats` (read from stdin): where the time goes."""
import statistics as st
import sys

want = sys.argv[1] if len(sys.argv) > 1 else ''   # only this window (e.g. "Player")
hdr, rows, window = None, [], ''
for line in sys.stdin.read().splitlines():
    if line.startswith('Window: '):
        window = line[8:]
        continue
    if want and want not in window:
        continue
    if line.startswith('Flags,'):
        hdr = line.strip().strip(',').split(',')
        continue
    if hdr and line[:1].isdigit():
        v = line.strip().strip(',').split(',')
        if len(v) >= len(hdr):
            rows.append(dict(zip(hdr, map(int, v))))
ok = [r for r in rows if r['Flags'] == 0]
print(f"frames {len(rows)} (normal {len(ok)})")
if not ok:
    sys.exit()


def q(vals):
    vals = sorted(vals)
    return f"med {st.median(vals):6.2f}  p90 {vals[int(len(vals) * 0.9)]:6.2f}  max {vals[-1]:6.2f}"


phases = [('IntendedVsync', 'HandleInputStart', 'vsync->input'),
          ('HandleInputStart', 'AnimationStart', 'input'),
          ('AnimationStart', 'PerformTraversalsStart', 'animation'),
          ('PerformTraversalsStart', 'DrawStart', 'measure/layout'),
          ('DrawStart', 'SyncQueued', 'record'),
          ('SyncQueued', 'SyncStart', 'wait RT'),
          ('SyncStart', 'IssueDrawCommandsStart', 'sync'),
          ('IssueDrawCommandsStart', 'SwapBuffers', 'issue GL'),
          ('SwapBuffers', 'FrameCompleted', 'swap'),
          ('IntendedVsync', 'FrameCompleted', 'TOTAL')]
for a, b, name in phases:
    vals = [(r[b] - r[a]) / 1e6 for r in ok if r[a] > 0 and r[b] >= r[a]]
    if vals:
        print(f"{name:15s} {q(vals)}")
for k in ('DequeueBufferDuration', 'QueueBufferDuration'):
    if k in hdr:
        print(f"{k:22s} {q([r[k] / 1e6 for r in ok])}")
if 'GpuCompleted' in hdr:
    vals = [(r['GpuCompleted'] - r['SwapBuffers']) / 1e6 for r in ok if r['GpuCompleted'] > r['SwapBuffers'] > 0]
    if vals:
        print(f"{'gpu after swap':15s} {q(vals)}")
    vals = [(r['GpuCompleted'] - r['IssueDrawCommandsStart']) / 1e6 for r in ok if r['GpuCompleted'] > 0]
    if vals:
        print(f"{'gpu from issue':15s} {q(vals)}")
# frame-to-frame interval while animating
iv = [(ok[i]['IntendedVsync'] - ok[i - 1]['IntendedVsync']) / 1e6 for i in range(1, len(ok))]
iv = [x for x in iv if x < 100]
if iv:
    period = st.median(iv)
    late = [x for x in iv if x > period * 1.5]
    print(f"{'interval':15s} {q(iv)}")
    print(f"dropped: {len(late)} of {len(iv)} intervals over 1.5x the vsync ({period:.1f} ms)")
