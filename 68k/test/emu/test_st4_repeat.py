#!/usr/bin/env python3
"""Differential test for the repeat: st4 -r streams through all three decoders.

A stream packed with -rR encodes the infinite input [0,R)[R,O)* - after one
whole pass the output continues from unit R and never stops, an endless match
O-R units back. This packs every corpus with the real packer at unit sizes 1,
2 and 4, decodes beyond two passes under Unicorn as a plain 68000 - linearly
resumed with ST4.S, through a counted ring with ST4_wrap.S, and through the
general ring with ST4_ring.S in both wrap modes - and checks every output byte
against the recurrence, that all four streams are consumed exactly (the loop
word in C included), and that no decoder ever claims to be done.

    python3 68k/test/emu/test_st4_repeat.py [--quick]
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
    UC_M68K_REG_A5, UC_M68K_REG_D1, UC_M68K_REG_D2, UC_M68K_REG_D3,
)


def assemble(name: str, unit: int) -> bytes:
    with tempfile.TemporaryDirectory() as directory:
        source = Path(directory) / 'build.S'
        binary = Path(directory) / 'build.bin'
        source.write_text(f'ST4_UNIT    equ     {unit}\n'
                          f'        include "{REPO / "68k" / name}"\n')
        result = subprocess.run(
            ['rmac', '-m68000', '-fr', '+o3', '-o', str(binary), str(source)],
            capture_output=True, text=True)
        if result.returncode:
            raise SystemExit(result.stdout + result.stderr)
        return binary.read_bytes()


def looped(data: bytes, unit: int, index: int, target: int) -> bytes:
    """The padded pass, continued to target bytes from its loop point."""
    expected = bytearray(data + bytes(-len(data) % unit))
    period = len(expected) - index * unit
    while len(expected) < target:
        expected.append(expected[-period])
    return bytes(expected)


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
    """After decoding past the end marker, every stream must be spent."""
    for name, register, base, stream in (
            ('A', UC_M68K_REG_A0, t.SRC, control),
            ('B', UC_M68K_REG_A2, st4.LITERAL, literal),
            ('C', UC_M68K_REG_A4, st4.BYTE_OFFSETS, byte_offsets),
            ('D', UC_M68K_REG_A5, st4.WORD_OFFSETS, word_offsets)):
        problem = st4.consumed(name, uc.reg_read(register) - base, stream)
        if problem:
            return problem
    return ''


def run_linear(control, literal, byte_offsets, word_offsets, expected, unit,
               distance, code, chunk) -> str:
    """ST4.S: resumed in chunks, well past the pass the container stores."""
    uc = t.make_emu(control)
    uc.mem_write(t.CODE, code)
    if len(expected) > 0x20000:         # the far cases outgrow the harness DST
        uc.mem_map(t.DST + 0x20000, 0x40000)
    seed(uc, control, literal, byte_offsets, word_offsets, t.DST)
    t.call(uc, t.CODE)                                  # ST4_init at +0

    done, total = 0, len(expected) // unit
    while done < total:
        budget = min(chunk, total - done)
        uc.reg_write(UC_M68K_REG_D3, 0xBEEF0000 | budget)
        t.call(uc, t.CODE + 8)                          # ST4_resume at +8
        done += budget
        # A repeating stream always has more, so every budget is spent whole.
        if uc.reg_read(UC_M68K_REG_A1) - t.DST != done * unit:
            return f'call stopped short at {uc.reg_read(UC_M68K_REG_A1) - t.DST}'
        if uc.reg_read(UC_M68K_REG_D1) & 0xFFFF == 0:
            return 'a repeating stream claimed to be done'

    if bytes(uc.mem_read(t.DST, len(expected))) != expected:
        return 'output differs'
    if uc.reg_read(UC_M68K_REG_D2) & 0xFFFF != (-distance * unit) & 0xFFFF:
        return 'the armed offset is not the loop distance'
    return drained(uc, control, literal, byte_offsets, word_offsets)


def run_wrap(control, literal, byte_offsets, word_offsets, expected, unit,
             code, ring_bytes, chunk_units) -> str:
    """ST4_wrap.S: T full-budget calls, the caller wrapping every F calls."""
    total_units = len(expected) // unit
    calls = total_units // chunk_units                  # target is whole calls
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
    previous = ring
    slot = 0
    for call in range(calls):
        uc.reg_write(UC_M68K_REG_D3, 0xBEEF0000 | chunk_units)
        t.call(uc, t.CODE + 4)                          # ST4_resume at +4
        current = uc.reg_read(UC_M68K_REG_A1)
        if current - previous != chunk_units * unit:
            return f'call {call + 1} emitted {current - previous} bytes'
        output.extend(uc.mem_read(previous, current - previous))
        if stray:
            return f'wrote outside the ring at {hex(stray[0])}'
        if uc.reg_read(UC_M68K_REG_D2) >> 16 != ring_bytes:
            return 'packed N changed'
        slot += 1
        if slot == calls_per_fill:
            uc.reg_write(UC_M68K_REG_A1, ring)
            previous, slot = ring, 0
        else:
            previous = current

    if bytes(output) != expected[:len(output)]:
        return 'output differs'
    return drained(uc, control, literal, byte_offsets, word_offsets)


def run_ring(control, literal, byte_offsets, word_offsets, expected, unit,
             code, ring_bytes, budget, caller_wraps: bool) -> str:
    """ST4_ring.S: any budgets, either wrap mode, and never a DONE."""
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
    previous = ring
    while len(output) < len(expected):
        uc.reg_write(UC_M68K_REG_D3, 0xBEEF0000 | budget)
        t.call(uc, t.CODE + 4)                          # ST4_resume at +4
        current = uc.reg_read(UC_M68K_REG_A1)
        if current >= previous:
            emitted, span = current - previous, previous
        else:
            emitted, span = current - ring, ring        # the decoder wrapped a1
        if not 0 < emitted <= budget * unit:
            return f'a call emitted {emitted} bytes for a budget of {budget}'
        output.extend(uc.mem_read(span, emitted))
        if stray:
            return f'wrote outside the ring at {hex(stray[0])}'
        if uc.reg_read(UC_M68K_REG_D1) & 0xFFFF == 0:
            return 'a repeating stream claimed to be done'
        if current == ring_end and caller_wraps:
            uc.reg_write(UC_M68K_REG_A1, ring)
            previous = ring
        else:
            previous = current

    if bytes(output[:len(expected)]) != expected:
        return 'output differs'
    return drained(uc, control, literal, byte_offsets, word_offsets)


def repeats_for(units_total: int, window: int) -> list[int]:
    """Two loop points: a whole-stream loop as far as the window allows, and a
    tail loop - as indices, the way -rR counts them."""
    reach = min(units_total, window)
    return sorted({units_total - reach, units_total - max(1, reach // 3)})


def main() -> int:
    failures = 0
    linear_chunks = [16] if QUICK else [16, 255]
    ring_shapes = [(1024, 16)] if QUICK else [(1024, 16), (256, 127)]
    wrap_shapes = [(1024, 16)] if QUICK else [(1024, 16), (256, 16)]
    for unit in (1, 2, 4):
        linear = assemble('ST4.S', unit)
        wrap = assemble('ST4_wrap.S', unit)
        ring = assemble('ST4_ring.S', unit)
        window = 32512 // unit
        cases = 0
        for name, data, _ in t.testcases():
            padded = data + bytes(-len(data) % unit)
            units_total = len(padded) // unit
            # Past two whole passes, so the repeat crosses its own output.
            target_units = 2 * units_total + 7

            for index in repeats_for(units_total, window):
                streams = st4.pack(data, unit, window, index)[:4]
                expected = looped(data, unit, index, target_units * unit)
                for chunk in linear_chunks:
                    problem = run_linear(*streams, expected, unit,
                                         units_total - index, linear, chunk)
                    if problem:
                        print(f'FAIL k={unit} {name} -r{index} '
                              f'(linear, chunks of {chunk}): {problem}')
                        failures += 1
                    cases += 1

            for ring_bytes, chunk_units in wrap_shapes + ring_shapes:
                ring_units = ring_bytes // unit
                for index in repeats_for(units_total, ring_units):
                    streams = st4.pack(data, unit, min(ring_units, window), index)[:4]
                    is_wrap = (ring_bytes, chunk_units) in wrap_shapes
                    if is_wrap:
                        whole = chunk_units * (ring_bytes // (chunk_units * unit))
                        wanted = math.ceil(target_units / whole) * whole
                        expected = looped(data, unit, index, wanted * unit)
                        problem = run_wrap(*streams, expected, unit, wrap,
                                           ring_bytes, chunk_units)
                        if problem:
                            print(f'FAIL k={unit} {name} -r{index} '
                                  f'(wrap, N={ring_bytes} C={chunk_units}): {problem}')
                            failures += 1
                        cases += 1
                    else:
                        expected = looped(data, unit, index, target_units * unit)
                        for caller_wraps in (True, False):
                            problem = run_ring(*streams, expected, unit, ring,
                                               ring_bytes, chunk_units, caller_wraps)
                            if problem:
                                who = 'caller' if caller_wraps else 'decoder'
                                print(f'FAIL k={unit} {name} -r{index} '
                                      f'(ring, N={ring_bytes} C={chunk_units}, '
                                      f'{who} wraps): {problem}')
                                failures += 1
                            cases += 1
        # Cross the 65535-unit counter: the endless match re-arms itself from
        # the pinned queue mid-flight, and each decoder carries its own copy of
        # that path, so each one has to be marched past the boundary.
        name, data, _ = next(c for c in t.testcases() if c[0] == 'text')
        padded = data + bytes(-len(data) % unit)
        units_total = len(padded) // unit
        index = units_total - min(units_total, 1024 // unit)
        streams = st4.pack(data, unit, min(1024 // unit, window), index)[:4]
        far = 65_600
        whole = 16 * (1024 // (16 * unit))
        wrap_far = math.ceil(far / whole) * whole
        for shape, problem in (
                ('linear', run_linear(*streams, looped(data, unit, index, far * unit),
                                      unit, units_total - index, linear, 255)),
                ('wrap', run_wrap(*streams, looped(data, unit, index, wrap_far * unit),
                                  unit, wrap, 1024, 16)),
                ('ring', run_ring(*streams, looped(data, unit, index, far * unit),
                                  unit, ring, 1024, 255, False))):
            if problem:
                print(f'FAIL k={unit} {name} -r{index} (far, {shape}): {problem}')
                failures += 1
            cases += 1
        print(f'{"OK  " if not failures else "    "}k={unit}: {cases} repeating decodes '
              f'across all three decoders')

    print('ALL ST4 REPEAT TESTS PASS' if not failures else f'{failures} FAILURES')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
