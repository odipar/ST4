#!/usr/bin/env python3
"""Differential test for ST4_ring.S at every unit size.

The general ring minds the destination itself, so this drives it the way a
caller would: ask for a budget, drain whatever came back, repeat until d1.w
reports done - in both the mode where the caller wraps the write pointer at
the ring end and the mode where it leaves that to the decoder. Checks every
output byte, exact consumption of both streams, that nothing is written outside
the ring, and that the packed metadata survives.

    python3 68k/test/emu/test_st4_ring.py [--quick]
"""
import importlib.util
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]

spec = importlib.util.spec_from_file_location('st4', HERE / 'test_st4.py')
sys.argv = ['x'] + [a for a in sys.argv[1:] if a.startswith('-')]
st4 = importlib.util.module_from_spec(spec)
sys.modules['st4'] = st4
spec.loader.exec_module(st4)
t = st4.t

from unicorn import UC_HOOK_MEM_WRITE                                # noqa: E402
from unicorn.m68k_const import (                                     # noqa: E402
    UC_M68K_REG_A0, UC_M68K_REG_A1, UC_M68K_REG_A2, UC_M68K_REG_A4,
    UC_M68K_REG_A5, UC_M68K_REG_D1, UC_M68K_REG_D2, UC_M68K_REG_D3,
)

QUICK = '--quick' in sys.argv


def assemble(unit: int) -> bytes:
    with tempfile.TemporaryDirectory() as directory:
        source = Path(directory) / 'build.S'
        binary = Path(directory) / 'build.bin'
        source.write_text(f'ST4_UNIT    equ     {unit}\n'
                          f'        include "{REPO / "68k" / "ST4_ring.S"}"\n')
        result = subprocess.run(
            ['rmac', '-m68000', '-fr', '+o3', '-o', str(binary), str(source)],
            capture_output=True, text=True)
        if result.returncode:
            raise SystemExit(result.stdout + result.stderr)
        return binary.read_bytes()


def run(control, literal, byte_offsets, word_offsets, expected, unit, code, ring_bytes, budget,
        caller_wraps: bool) -> str:
    uc = t.make_emu(control)
    uc.mem_map(st4.LITERAL, 0x20000)
    uc.mem_map(st4.BYTE_OFFSETS, 0x20000)
    uc.mem_map(st4.WORD_OFFSETS, 0x20000)
    uc.mem_write(t.CODE, code)
    uc.mem_write(st4.LITERAL, literal)
    uc.mem_write(st4.BYTE_OFFSETS, byte_offsets or b'\0')
    uc.mem_write(st4.WORD_OFFSETS, word_offsets or b'\0\0')
    ring = t.DST + 16                   # unit-aligned, with room for a guard band
    ring_end = ring + ring_bytes
    uc.mem_write(ring - 8, b'\xAA' * (ring_bytes + 16))
    stray = []

    def guard(u, access, address, size, value, data):
        if not (ring <= address and address + size <= ring_end):
            stray.append(address)

    uc.hook_add(UC_HOOK_MEM_WRITE, guard, begin=t.DST, end=t.DST + 0x1FFFF)

    uc.reg_write(UC_M68K_REG_A0, t.SRC)
    uc.reg_write(UC_M68K_REG_A1, ring)
    uc.reg_write(UC_M68K_REG_A2, st4.LITERAL)
    uc.reg_write(UC_M68K_REG_A4, st4.BYTE_OFFSETS)
    uc.reg_write(UC_M68K_REG_A5, st4.WORD_OFFSETS)
    uc.reg_write(UC_M68K_REG_D3, ring_end)
    t.call(uc, t.CODE)                                  # ST4_init at +0

    start_meta = (-ring) & 0xFFFF
    if uc.reg_read(UC_M68K_REG_D1) >> 16 != start_meta:
        return 'init did not pack -ring_start.low'
    if uc.reg_read(UC_M68K_REG_D2) >> 16 != (ring_end & 0xFFFF):
        return 'init did not pack end.low'

    output = bytearray()
    previous = ring
    calls = 0
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
            # The decoder wrapped the write pointer on entry, which it does when
            # the previous call left it on the ring end: this call wrote from
            # the ring start, so the span is not previous..current.
            emitted, span = current - ring, ring
        if not 0 <= emitted <= budget * unit:
            return f'call {calls} emitted {emitted} bytes for a budget of {budget}'
        output.extend(uc.mem_read(span, emitted))
        if stray:
            return f'wrote outside the ring at {hex(stray[0])}'
        if uc.reg_read(UC_M68K_REG_D1) >> 16 != start_meta:
            return 'packed -ring_start.low changed'
        if uc.reg_read(UC_M68K_REG_D2) >> 16 != (ring_end & 0xFFFF):
            return 'packed end.low changed'

        if uc.reg_read(UC_M68K_REG_D1) & 0xFFFF == 0:
            break
        if current == ring_end and caller_wraps:
            uc.reg_write(UC_M68K_REG_A1, ring)
            previous = ring
        else:
            previous = current

    if bytes(output) != expected:
        return 'output differs'
    for name, register, base, stream in (
            ('A', UC_M68K_REG_A0, t.SRC, control),
            ('D', UC_M68K_REG_A2, st4.LITERAL, literal),
            ('B', UC_M68K_REG_A4, st4.BYTE_OFFSETS, byte_offsets),
            ('C', UC_M68K_REG_A5, st4.WORD_OFFSETS, word_offsets)):
        problem = st4.consumed(name, uc.reg_read(register) - base, stream)
        if problem:
            return problem
    return ''


def main() -> int:
    failures = 0
    for unit in (1, 2, 4):
        code = assemble(unit)
        window = 32512 // unit
        # ring bytes, budget in units - deliberately including budgets that do
        # not divide the ring, which is what this variant exists for.
        # Budgets deliberately include ones larger than the ring: a call that
        # wraps and then fills it is exactly what used to lose a whole ring.
        shapes = [(1024, 16), (256, 127), (48 * unit, 5), (256, 256 // unit)]
        if not QUICK:
            shapes += [(1000, 16), (2048, 255), (32 * unit, 1), (4096, 64),
                       (512, 65535), (128, 200)]
        for name, data, _ in t.testcases():
            padded = data + bytes(-len(data) % unit)
            for ring_bytes, budget in shapes:
                if ring_bytes % unit:
                    continue
                control, literal, byte_offsets, word_offsets, _ = st4.pack(
                    data, unit, min(ring_bytes // unit, window))
                for caller_wraps in (True, False):
                    problem = run(control, literal, byte_offsets, word_offsets, padded, unit, code,
                                  ring_bytes, budget, caller_wraps)
                    if problem:
                        who = 'caller wraps' if caller_wraps else 'decoder wraps'
                        print(f'FAIL k={unit} {name} N={ring_bytes} C={budget} '
                              f'({who}): {problem}')
                        failures += 1
        print(f'{"OK  " if not failures else "    "}k={unit}: {len(code)} bytes of decoder, '
              f'{len(t.testcases())} corpora x {len(shapes)} shapes x both wrap modes')

    print('ALL ST4 RING TESTS PASS' if not failures else f'{failures} FAILURES')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
