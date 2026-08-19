"""Corpus sweep: pack each tune and verify the player's chip writes against
the YM truth, frame by frame, in the Unicorn rig.

    python3 yx6/test/sweep.py song.ym [more.ym ...]

Each tune is packed at k=1 - no padding, so the YM registers are the exact
expectation - then played through the real 68000 player under emulation.
Every chip write is compared against the masked YM data: strict for all
registers no effect owns, R13's hold/shape semantics included, the loop
crossing exercised for tunes up to 3000 frames (longer ones play their
first 1200). One status line per tune: OK, ISSUE, PACKFAIL or SKIP.

Honest limits: volume registers of effect-coded voices and R7 are excluded
from the comparison (a conservative ownership model - in emulation no ISR
ever fires, so a triggered drum's marker never reopens its voice), and the
tick handlers' own audio is not rendered here; that side is covered by the
directed effect-stage test in test_yx6.py. Needs mvn compile, rmac and
unicorn, like the rigs.
"""
import subprocess, sys, struct, tempfile, os

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
EMU = os.path.join(REPO, 'yx6', 'test', 'emu')
CLASSES = os.path.join(REPO, 'target', 'classes')
WORK = os.path.join(EMU, '.work')
sys.path.insert(0, EMU)
os.chdir(EMU)
import test_yx6 as T

MASK = [0xFF, 0x0F, 0xFF, 0x0F, 0xFF, 0x0F, 0x1F, 0x3F,
        0x1F, 0x1F, 0x1F, 0xFF, 0xFF, 0xFF]
PREDIV = [0, 4, 10, 16, 50, 64, 100, 200]

def dumper():
    """Compiles DumpYm.java into the rig's work directory once."""
    java = os.path.join(REPO, 'yx6', 'test', 'DumpYm.java')
    klass = os.path.join(WORK, 'DumpYm.class')
    if not os.path.exists(klass) or os.path.getmtime(klass) < os.path.getmtime(java):
        subprocess.run(['javac', '-cp', CLASSES, '-d', WORK, java], check=True)
    return WORK


def read_ym(path):
    """The YM registers, via the Java reader (it unpacks LHA itself)."""
    out = subprocess.run(['java', '-cp', CLASSES + ':' + dumper(), 'DumpYm', path],
                         capture_output=True)
    if out.returncode:
        err = out.stderr.decode().strip().splitlines()
        msg = next((l for l in err if 'Exception' in l), err[-1] if err else 'reader failed')
        raise ValueError(msg.split(':', 1)[-1].strip()[:70])
    raw = out.stdout
    fmt, frames, drums = struct.unpack('>III', raw[:12])
    regs = [raw[12 + r*frames: 12 + (r+1)*frames] for r in range(16)]
    return fmt, frames, drums, regs

def owned_voices(fmt, frames, drums, regs):
    """Which voices an effect ever validly codes (sticky, conservative)."""
    owned = set()
    ym6 = fmt == 6
    for f in range(frames):
        slots = []
        if ym6:
            slots = [(regs[1][f], (regs[6][f] >> 5) & 7, regs[14][f]),
                     (regs[3][f], (regs[8][f] >> 5) & 7, regs[15][f])]
        else:
            slots = [(regs[1][f] & 0x30, (regs[6][f] >> 5) & 7, regs[14][f]),
                     (0x40 | (regs[3][f] & 0x30) if regs[3][f] & 0x30 else 0,
                      (regs[8][f] >> 5) & 7, regs[15][f])]
        for code, tp, tc in slots:
            v = (code >> 4) & 3
            if v == 0 or tp == 0 or tc == 0:
                continue
            kind = code & 0xC0
            if ym6 and kind == 0x80:
                continue                        # sinus: dropped at pack
            if kind == 0x40:
                n = regs[8 + v - 1][f] & 0x1F
                if n >= drums:
                    continue                    # missing drum: dropped
            elif 2457600 // (PREDIV[tp] * tc) > 25600:
                continue                        # too-fast SID/buzzer: dropped
            owned.add(v - 1)                    # (a too-fast DRUM downsamples
                                                # and plays, so it owns)
    return owned

def sweep(path):
    name = os.path.basename(path)
    try:
        fmt, frames, drums, regs = read_ym(path)
    except ValueError as e:
        return f'SKIP {name}: {e}'
    with tempfile.NamedTemporaryFile(suffix='.yx6', delete=False) as tf:
        yx6 = tf.name
    try:
        out = subprocess.run(['java', '-cp', CLASSES, 'org.yx6.Yx6',
                              '-f', '-k1', path, yx6], capture_output=True, text=True)
        if out.returncode:
            return f'PACKFAIL {name}: {(out.stderr or out.stdout).strip().splitlines()[-1][:70]}'
        warns = [l for l in out.stdout.splitlines()
                 if 'Warning' in l or 'sinus' in l or 'too fast' in l or 'missing' in l]
        packed = open(yx6, 'rb').read()
        ring = struct.unpack('>H', packed[16:18])[0]
        loop_frame = struct.unpack('>I', packed[20:24])[0]
        loops = struct.unpack('>H', packed[6:8])[0] & 1
        player = T.Player(packed, T.YX6_FIXED + 18 * ring)
        if player.init() != 0:
            return f'INITFAIL {name}'
        owned = owned_voices(fmt, frames, drums, regs)
        strict = [r for r in (0, 1, 2, 3, 4, 5, 6, 11, 12)]
        vol = {8 + v for v in (0, 1, 2) if v not in owned}
        budget = frames + 200 if frames <= 3000 else 1200
        wrapped = False
        src = 0
        for f in range(budget):
            result, writes = player.frame()
            if result == -1:
                if f < frames:
                    return f'ISSUE {name}: ended early at frame {f}/{frames}'
                break
            if result == 1:
                wrapped = True
            got = dict(writes)
            for r in strict:
                want = regs[r][src] & MASK[r]
                if got.get(r) != want:
                    return (f'ISSUE {name}: frame {f} R{r} wrote '
                            f'{got.get(r)} want {want}')
            for r in vol:
                want = regs[r][src] & MASK[r]
                if got.get(r) != want:
                    return f'ISSUE {name}: frame {f} R{r} wrote {got.get(r)} want {want}'
            r13 = regs[13][src] & 0xFF
            if r13 == 0xFF and 13 in got:
                return f'ISSUE {name}: frame {f} wrote held R13'
            if r13 != 0xFF and got.get(13) != (r13 & 0x0F):
                return (f'ISSUE {name}: frame {f} R13 {got.get(13)} '
                        f'want {r13 & 0x0F}')
            src += 1
            if src == frames:
                if not loops:
                    src = frames - 1        # the next call returns -1
                else:
                    src = loop_frame
        loop = 'looped' if wrapped else 'partial' if frames > 3000 else 'once'
        w = (' [' + '; '.join(warns)[:60] + ']') if warns else ''
        return f'OK {name} ({min(budget, frames + 200)}f {loop}){w}'
    except AssertionError as e:
        return f'ISSUE {name}: {e}'
    finally:
        os.unlink(yx6)

if __name__ == '__main__':
    for p in sys.argv[1:]:
        print(sweep(p), flush=True)
