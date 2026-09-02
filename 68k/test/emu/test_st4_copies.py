#!/usr/bin/env python3
"""Differential test for copies from the literal stream, in all three decoders.

A stream packed with -c at a window of M units writes a match beyond M as a
copy from the literal stream: offset minus M literal units behind the read
pointer, in stream B, without moving it. A decoder built with ST4_WINDOW set
to M tells the two apart by magnitude. This packs corpora that way at unit
sizes 1, 2 and 4 and windows of 16, 64 and 256 units, builds the decoders for
each window, and decodes under Unicorn as a plain 68000 - linearly with
ST4.S, through a ring the size of the window with ST4_wrap.S, and with
ST4_ring.S in both wrap modes - checking every output byte, that all four
streams are consumed exactly, and that nothing is written outside the ring.
The same builds also decode streams packed without copies, which a window
build must read exactly as the plain build does.

    python3 68k/test/emu/test_st4_copies.py [--quick]
"""
import importlib.util
import math
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]

spec = importlib.util.spec_from_file_location('st4', HERE / 'test_st4.py')
QUICK = '--quick' in sys.argv
sys.argv = ['x']
st4 = importlib.util.module_from_spec(spec)
sys.modules['st4'] = st4
spec.loader.exec_module(st4)
t = st4.t

from unicorn import UC_HOOK_MEM_WRITE                                # noqa: E402
from unicorn.m68k_const import (                                     # noqa: E402
    UC_M68K_REG_A0, UC_M68K_REG_A1, UC_M68K_REG_A2, UC_M68K_REG_A4,
    UC_M68K_REG_A5, UC_M68K_REG_D1, UC_M68K_REG_D3,
)


def assemble(name: str, unit: int, window: int) -> bytes:
    """A decoder built for one unit size and one window."""
    with tempfile.TemporaryDirectory() as directory:
        source = Path(directory) / 'build.S'
        binary = Path(directory) / 'build.bin'
        source.write_text(f'ST4_UNIT    equ     {unit}\n'
                          f'ST4_WINDOW  equ     {window}\n'
                          f'        include "{REPO / "68k" / name}"\n')
        result = subprocess.run(
            ['rmac', '-m68000', '-fr', '+o3', '-o', str(binary), str(source)],
            capture_output=True, text=True)
        if result.returncode:
            raise SystemExit(result.stdout + result.stderr)
        return binary.read_bytes()


def seed(uc, control, literal, byte_offsets, word_offsets, destination):
    uc.mem_map(st4.LITERAL, 0x20000)
    uc.mem_map(st4.BYTE_OFFSETS, 0x20000)
    uc.mem_map(st4.WORD_OFFSETS, 0x20000)
    uc.mem_write(st4.LITERAL, literal)
    uc.mem_write(st4.BYTE_OFFSETS, byte_offsets or b'\0')
    uc.mem_write(st4.WORD_OFFSETS, word_offsets or b'\0\0')
    uc.reg_write(UC_M68K_REG_A0, t.SRC)
    uc.reg_write(UC_M68K_REG_A1, destination)
    uc.reg_write(UC_M68K_REG_A2, st4.LITERAL)
    uc.reg_write(UC_M68K_REG_A4, st4.BYTE_OFFSETS)
    uc.reg_write(UC_M68K_REG_A5, st4.WORD_OFFSETS)


def drained(uc, control, literal, byte_offsets, word_offsets) -> str:
    for name, register, base, stream in (
            ('A', UC_M68K_REG_A0, t.SRC, control),
            ('B', UC_M68K_REG_A2, st4.LITERAL, literal),
            ('C', UC_M68K_REG_A4, st4.BYTE_OFFSETS, byte_offsets),
            ('D', UC_M68K_REG_A5, st4.WORD_OFFSETS, word_offsets)):
        problem = st4.consumed(name, uc.reg_read(register) - base, stream)
        if problem:
            return problem
    return ''


def run_linear(control, literal, byte_offsets, word_offsets, expected, unit, code, chunk) -> str:
    """ST4.S: resumed in chunks, the copies reaching D from a plain buffer."""
    uc = t.make_emu(control)
    uc.mem_write(t.CODE, code)
    seed(uc, control, literal, byte_offsets, word_offsets, t.DST)
    t.call(uc, t.CODE)                                  # ST4_init at +0
    calls = 0
    while True:
        calls += 1
        if calls > len(expected) // unit + 16:
            return 'resume did not terminate'
        uc.reg_write(UC_M68K_REG_D3, 0xBEEF0000 | chunk)
        t.call(uc, t.CODE + 8)                          # ST4_resume at +8
        if uc.reg_read(UC_M68K_REG_D1) & 0xFFFF == 0:
            break
    if uc.reg_read(UC_M68K_REG_A1) - t.DST != len(expected):
        return f'produced {uc.reg_read(UC_M68K_REG_A1) - t.DST} bytes'
    if bytes(uc.mem_read(t.DST, len(expected))) != expected:
        return 'output differs'
    return drained(uc, control, literal, byte_offsets, word_offsets)


def run_wrap(control, literal, byte_offsets, word_offsets, expected, unit, code,
             ring_bytes, chunk_units) -> str:
    """ST4_wrap.S: T calls through a ring the size of the window."""
    units_total = len(expected) // unit
    calls = math.ceil(units_total / chunk_units)
    calls_per_fill = ring_bytes // (chunk_units * unit)
    uc = t.make_emu(control)
    uc.mem_write(t.CODE, code)
    ring = t.DST + 16
    seed(uc, control, literal, byte_offsets, word_offsets, ring)
    uc.mem_write(ring - 8, b'\xAA' * (ring_bytes + 16))
    stray = []

    def guard(u, access, address, size, value, data):
        if not (ring <= address and address + size <= ring + ring_bytes):
            stray.append(address)

    uc.hook_add(UC_HOOK_MEM_WRITE, guard, begin=t.DST, end=t.DST + 0x1FFFF)
    uc.reg_write(UC_M68K_REG_D3, 0xBEEF0000 | ring_bytes)
    t.call(uc, t.CODE)                                  # ST4_init at +0
    output = bytearray()
    previous, slot = ring, 0
    for call in range(calls):
        budget = min(chunk_units, units_total - call * chunk_units)
        uc.reg_write(UC_M68K_REG_D3, 0xBEEF0000 | budget)
        t.call(uc, t.CODE + 4)                          # ST4_resume at +4
        current = uc.reg_read(UC_M68K_REG_A1)
        if current - previous != budget * unit:
            return f'call {call + 1} emitted {current - previous} bytes'
        output.extend(uc.mem_read(previous, current - previous))
        if stray:
            return f'wrote outside the ring at {hex(stray[0])}'
        slot += 1
        if call + 1 < calls and slot == calls_per_fill:
            uc.reg_write(UC_M68K_REG_A1, ring)
            previous, slot = ring, 0
        else:
            previous = current
    if bytes(output) != expected:
        return 'output differs'
    return drained(uc, control, literal, byte_offsets, word_offsets)


def run_ring(control, literal, byte_offsets, word_offsets, expected, unit, code,
             ring_bytes, budget, caller_wraps: bool) -> str:
    """ST4_ring.S: any budget, either wrap mode."""
    uc = t.make_emu(control)
    uc.mem_write(t.CODE, code)
    ring = t.DST + 16
    ring_end = ring + ring_bytes
    seed(uc, control, literal, byte_offsets, word_offsets, ring)
    uc.mem_write(ring - 8, b'\xAA' * (ring_bytes + 16))
    stray = []

    def guard(u, access, address, size, value, data):
        if not (ring <= address and address + size <= ring_end):
            stray.append(address)

    uc.hook_add(UC_HOOK_MEM_WRITE, guard, begin=t.DST, end=t.DST + 0x1FFFF)
    uc.reg_write(UC_M68K_REG_D3, ring_end)
    t.call(uc, t.CODE)                                  # ST4_init at +0
    output = bytearray()
    previous, calls = ring, 0
    while True:
        calls += 1
        if calls > len(expected) // unit + 64:
            return 'resume did not terminate'
        uc.reg_write(UC_M68K_REG_D3, 0xBEEF0000 | budget)
        t.call(uc, t.CODE + 4)                          # ST4_resume at +4
        current = uc.reg_read(UC_M68K_REG_A1)
        if current >= previous:
            emitted, span = current - previous, previous
        else:
            emitted, span = current - ring, ring        # the decoder wrapped a1
        if not 0 <= emitted <= budget * unit:
            return f'call {calls} emitted {emitted} bytes for a budget of {budget}'
        output.extend(uc.mem_read(span, emitted))
        if stray:
            return f'wrote outside the ring at {hex(stray[0])}'
        if uc.reg_read(UC_M68K_REG_D1) & 0xFFFF == 0:
            break
        if current == ring_end and caller_wraps:
            uc.reg_write(UC_M68K_REG_A1, ring)
            previous = ring
        else:
            previous = current
    if bytes(output) != expected:
        return 'output differs'
    return drained(uc, control, literal, byte_offsets, word_offsets)


def main() -> int:
    failures = 0
    windows = [16] if QUICK else [16, 64, 256]
    for unit in (1, 2, 4):
        cases = 0
        for window in windows:
            linear = assemble('ST4.S', unit, window)
            wrap = assemble('ST4_wrap.S', unit, window)
            ring = assemble('ST4_ring.S', unit, window)
            ring_bytes = window * unit
            chunk = 16 if window >= 16 * 1 else window
            for name, data, _ in t.testcases():
                if len(data) > 12000 and not QUICK and window > 16:
                    continue                            # the readable packer is slow
                if len(data) > 12000 and QUICK:
                    continue
                padded = data + bytes(-len(data) % unit)
                for copies in (False, True):
                    file = st4.pack_file(data, unit, window, None, copies)
                    control, literal, byte_offsets, word_offsets, size, _, m = \
                        st4.streams(file, unit)
                    if m != window:
                        print(f'FAIL k={unit} {name} M={window}: header says window {m}')
                        failures += 1
                        continue
                    label = f'k={unit} {name} M={window} {"copies" if copies else "plain"}'
                    runs = [('linear', run_linear(control, literal, byte_offsets, word_offsets,
                                                  padded, unit, linear, 7))]
                    if ring_bytes % (chunk * unit) == 0:
                        runs.append(('wrap', run_wrap(control, literal, byte_offsets,
                                                      word_offsets, padded, unit, wrap,
                                                      ring_bytes, chunk)))
                    for caller_wraps in (True, False):
                        who = 'caller wraps' if caller_wraps else 'decoder wraps'
                        runs.append((f'ring, {who}', run_ring(
                            control, literal, byte_offsets, word_offsets, padded, unit, ring,
                            ring_bytes, 5, caller_wraps)))
                    for shape, problem in runs:
                        if problem:
                            print(f'FAIL {label} ({shape}): {problem}')
                            failures += 1
                        cases += 1
        print(f'{"OK  " if not failures else "    "}k={unit}: {cases} decodes across all three '
              f'decoders, windows {windows}')
    print('ALL ST4 COPY TESTS PASS' if not failures else f'{failures} FAILURES')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
