#!/usr/bin/env python3
"""Differential test for ST4_wrap.S at every unit size.

Drives the counted ring as its caller must: from a known output size,
T = ceil(O/C) calls, resetting the write pointer after every F = N/(C*UNIT)
calls, and never polling for a done state it does not have. Checks every byte
of every returned span, that all four streams are consumed exactly, that
nothing is written outside the ring, and that the state's high words survive.

    python3 68k/test/emu/test_st4_wrap.py [--quick]
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
                          f'        include "{REPO / "68k" / "ST4_wrap.S"}"\n')
        result = subprocess.run(
            ['rmac', '-m68000', '-fr', '+o3', '-o', str(binary), str(source)],
            capture_output=True, text=True)
        if result.returncode:
            raise SystemExit(result.stdout + result.stderr)
        return binary.read_bytes()


def run(control, literal, byte_offsets, word_offsets, expected, unit, code, ring_bytes,
        chunk_units) -> str:
    """One whole stream through an N-byte ring, C units per call."""
    units_total = len(expected) // unit
    calls = math.ceil(units_total / chunk_units)
    calls_per_fill = ring_bytes // (chunk_units * unit)

    uc = t.make_emu(control)
    uc.mem_map(st4.LITERAL, 0x20000)
    uc.mem_map(st4.BYTE_OFFSETS, 0x20000)
    uc.mem_map(st4.WORD_OFFSETS, 0x20000)
    uc.mem_write(t.CODE, code)
    uc.mem_write(st4.LITERAL, literal)
    uc.mem_write(st4.BYTE_OFFSETS, byte_offsets or b'\0')
    uc.mem_write(st4.WORD_OFFSETS, word_offsets or b'\0\0')
    ring = t.DST + 16               # unit-aligned, with room for a guard band
    uc.mem_write(ring - 8, b'\xAA' * (ring_bytes + 16))
    stray = []

    def guard(u, access, address, size, value, data):
        if not (ring <= address and address + size <= ring + ring_bytes):
            stray.append(address)

    uc.hook_add(UC_HOOK_MEM_WRITE, guard, begin=t.DST, end=t.DST + 0x1FFFF)

    uc.reg_write(UC_M68K_REG_A0, t.SRC)
    uc.reg_write(UC_M68K_REG_A1, ring)
    uc.reg_write(UC_M68K_REG_A2, st4.LITERAL)
    uc.reg_write(UC_M68K_REG_A4, st4.BYTE_OFFSETS)
    uc.reg_write(UC_M68K_REG_A5, st4.WORD_OFFSETS)
    uc.reg_write(UC_M68K_REG_D3, 0xBEEF0000 | ring_bytes)
    t.call(uc, t.CODE)                              # ST4_init at +0

    if uc.reg_read(UC_M68K_REG_D1) >> 16 != ((-ring) & 0xFFFF):
        return 'init did not pack -ring_start.low'
    if uc.reg_read(UC_M68K_REG_D2) >> 16 != ring_bytes:
        return 'init did not pack N'

    output = bytearray()
    previous = ring
    slot = 0
    for call in range(calls):
        budget = min(chunk_units, units_total - call * chunk_units)
        uc.reg_write(UC_M68K_REG_D3, 0xBEEF0000 | budget)
        t.call(uc, t.CODE + 4)                      # ST4_resume at +4
        current = uc.reg_read(UC_M68K_REG_A1)
        emitted = current - previous
        if emitted != budget * unit:
            return f'call {call + 1} emitted {emitted}, expected {budget * unit}'
        output.extend(uc.mem_read(previous, emitted))
        if stray:
            return f'wrote outside the ring at {hex(stray[0])}'
        if uc.reg_read(UC_M68K_REG_D2) >> 16 != ring_bytes:
            return 'packed N changed'

        slot += 1
        if call + 1 < calls and slot == calls_per_fill:
            if current != ring + ring_bytes:
                return 'a full group did not end at the ring end'
            uc.reg_write(UC_M68K_REG_A1, ring)
            previous, slot = ring, 0
        else:
            previous = current

    if bytes(output) != expected:
        return 'output differs'
    for name, register, base, stream in (
            ('A', UC_M68K_REG_A0, t.SRC, control),
            ('B', UC_M68K_REG_A2, st4.LITERAL, literal),
            ('C', UC_M68K_REG_A4, st4.BYTE_OFFSETS, byte_offsets),
            ('D', UC_M68K_REG_A5, st4.WORD_OFFSETS, word_offsets)):
        problem = st4.consumed(name, uc.reg_read(register) - base, stream)
        if problem:
            return problem
    return ''


def main() -> int:
    failures = 0
    for unit in (1, 2, 4):
        code = assemble(unit)
        window = 32512 // unit
        # ring bytes, units per call: the ring must be whole groups of C units
        shapes = [(1024, 16), (256, 16), (64 * unit, 8)]
        if not QUICK:
            shapes += [(2048, 128), (32 * unit, 4), (4096, 16)]
        for name, data, _ in t.testcases():
            padded = data + bytes(-len(data) % unit)
            for ring_bytes, chunk_units in shapes:
                if ring_bytes % (chunk_units * unit):
                    continue
                # A stream is only safe for the ring it was packed for: -mN is
                # what stops a match reaching data that has already left it.
                control, literal, byte_offsets, word_offsets, _ = st4.pack(
                    data, unit, min(ring_bytes // unit, window))
                problem = run(control, literal, byte_offsets, word_offsets, padded, unit, code,
                              ring_bytes, chunk_units)
                if problem:
                    print(f'FAIL k={unit} {name} N={ring_bytes} C={chunk_units}: {problem}')
                    failures += 1
        print(f'{"OK  " if not failures else "    "}k={unit}: {len(code)} bytes of decoder, '
              f'{len(t.testcases())} corpora x {len(shapes)} ring shapes')

    print('ALL ST4 WRAP TESTS PASS' if not failures else f'{failures} FAILURES')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
