#!/usr/bin/env python3
"""Differential test for the rewind: loops longer than the ring, all three decoders.

A stream packed with -rR whose loop [R,O) is longer than the window cannot
loop by itself - no match reaches that far back - so the header names a
rewind point and the caller replays the encoded stream: it saves the decoder's
registers when the output reaches R and restores them, all but the write
pointer, when it reaches O, every pass. This packs corpora that way with the
real packer at unit sizes 1, 2 and 4, drives ST4.S, ST4_wrap.S and ST4_ring.S
through rings smaller than the loop with exactly that protocol under Unicorn
as a plain 68000, and checks every byte of more than two passes against
[0,R)[R,O)*, that the pass itself consumed every stream exactly, and that the
decoder had finished when the caller first rewound it.

    python3 68k/test/emu/test_st4_rewind.py [--quick]
"""
import importlib.util
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
    UC_M68K_REG_A5, UC_M68K_REG_D0, UC_M68K_REG_D1, UC_M68K_REG_D2,
    UC_M68K_REG_D3,
)

# The decoder's whole state but the write pointer: what a rewind saves and
# restores. a1 stays wherever the ring has got to.
STATE = (UC_M68K_REG_A0, UC_M68K_REG_A2, UC_M68K_REG_A4, UC_M68K_REG_A5,
         UC_M68K_REG_D0, UC_M68K_REG_D1, UC_M68K_REG_D2)


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


class Rewinder:
    """The caller's side of the protocol: budgets that land exactly on the
    rewind point and on every pass end, a snapshot at the one and a restore at
    the others, and the checks that the first pass ended as a stream should."""

    def __init__(self, uc, control, literal, byte_offsets, word_offsets, unit,
                 rewind_units: int, pass_units: int, done_check):
        self.uc = uc
        self.streams = (control, literal, byte_offsets, word_offsets)
        self.unit = unit
        self.rewind = rewind_units
        self.period = pass_units - rewind_units
        self.next_end = pass_units
        self.done_check = done_check
        self.done = 0
        self.saved = None
        self.problem = ''
        if rewind_units == 0:
            self.saved = self.snapshot()            # the loop starts at the start

    def snapshot(self):
        return {register: self.uc.reg_read(register) for register in STATE}

    def budget(self, wanted: int) -> int:
        """No call may run past the rewind point or a pass end."""
        events = [self.next_end]
        if self.saved is None:
            events.append(self.rewind)
        return min(wanted, min(events) - self.done)

    def advance(self, units: int):
        """After a call emitted units: save or restore where the protocol says."""
        self.done += units
        if self.saved is None and self.done == self.rewind:
            self.saved = self.snapshot()
        if self.done == self.next_end:
            if self.next_end == self.rewind + self.period:      # the first pass end
                problem = self.done_check(self.uc)
                if not problem:
                    problem = drained(self.uc, *self.streams)
                if problem:
                    self.problem = problem
            for register, value in self.saved.items():
                self.uc.reg_write(register, value)
            self.next_end += self.period


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
    """At the end of the pass, every stream must be spent."""
    for name, register, base, stream in (
            ('A', UC_M68K_REG_A0, t.SRC, control),
            ('B', UC_M68K_REG_A2, st4.LITERAL, literal),
            ('C', UC_M68K_REG_A4, st4.BYTE_OFFSETS, byte_offsets),
            ('D', UC_M68K_REG_A5, st4.WORD_OFFSETS, word_offsets)):
        problem = st4.consumed(name, uc.reg_read(register) - base, stream)
        if problem:
            return problem
    return ''


def linear_done(uc) -> str:
    if uc.reg_read(UC_M68K_REG_D1) & 0xFFFF or uc.reg_read(UC_M68K_REG_D2) & 0xFFFF:
        return 'the pass did not end in DONE'
    return ''


def ring_done(uc) -> str:
    if uc.reg_read(UC_M68K_REG_D1) & 0xFFFF:
        return 'the pass did not end in DONE'
    return ''


def run_linear(control, literal, byte_offsets, word_offsets, expected, unit,
               rewind_units, pass_units, code, chunk) -> str:
    """ST4.S: resumed in chunks into one buffer, rewound at every pass end."""
    uc = t.make_emu(control)
    uc.mem_write(t.CODE, code)
    if len(expected) > 0x20000:
        uc.mem_map(t.DST + 0x20000, 0x40000)
    seed(uc, control, literal, byte_offsets, word_offsets, t.DST)
    t.call(uc, t.CODE)                                  # ST4_init at +0
    total = len(expected) // unit
    caller = Rewinder(uc, control, literal, byte_offsets, word_offsets, unit,
                      rewind_units, pass_units, linear_done)
    while caller.done < total:
        budget = caller.budget(min(chunk, total - caller.done))
        uc.reg_write(UC_M68K_REG_D3, 0xBEEF0000 | budget)
        t.call(uc, t.CODE + 8)                          # ST4_resume at +8
        if uc.reg_read(UC_M68K_REG_A1) - t.DST != (caller.done + budget) * unit:
            return f'a call stopped short at {uc.reg_read(UC_M68K_REG_A1) - t.DST}'
        caller.advance(budget)
        if caller.problem:
            return caller.problem
    if bytes(uc.mem_read(t.DST, len(expected))) != expected:
        return 'output differs'
    return ''


def run_wrap(control, literal, byte_offsets, word_offsets, expected, unit,
             rewind_units, pass_units, code, ring_bytes, chunk_units) -> str:
    """ST4_wrap.S: calls never cross the ring end, the caller resets a1 there."""
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
    caller = Rewinder(uc, control, literal, byte_offsets, word_offsets, unit,
                      rewind_units, pass_units, lambda u: '')

    output = bytearray()
    total = len(expected) // unit
    while caller.done < total:
        write = uc.reg_read(UC_M68K_REG_A1)
        room = (ring + ring_bytes - write) // unit
        budget = caller.budget(min(chunk_units, total - caller.done, room))
        uc.reg_write(UC_M68K_REG_D3, 0xBEEF0000 | budget)
        t.call(uc, t.CODE + 4)                          # ST4_resume at +4
        current = uc.reg_read(UC_M68K_REG_A1)
        if current - write != budget * unit:
            return f'a call emitted {current - write} bytes for a budget of {budget}'
        output.extend(uc.mem_read(write, current - write))
        if stray:
            return f'wrote outside the ring at {hex(stray[0])}'
        caller.advance(budget)
        if caller.problem:
            return caller.problem
        if current == ring + ring_bytes:
            uc.reg_write(UC_M68K_REG_A1, ring)
    if bytes(output) != expected[:len(output)]:
        return 'output differs'
    return ''


def run_ring(control, literal, byte_offsets, word_offsets, expected, unit,
             rewind_units, pass_units, code, ring_bytes, chunk_units,
             caller_wraps: bool) -> str:
    """ST4_ring.S: the decoder clamps at the ring end; either side wraps a1."""
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
    caller = Rewinder(uc, control, literal, byte_offsets, word_offsets, unit,
                      rewind_units, pass_units, ring_done)

    output = bytearray()
    total = len(expected) // unit
    previous = ring
    while caller.done < total:
        budget = caller.budget(min(chunk_units, total - caller.done))
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
        caller.advance(emitted // unit)
        if caller.problem:
            return caller.problem
        if current == ring_end and caller_wraps:
            uc.reg_write(UC_M68K_REG_A1, ring)
            previous = ring
        else:
            previous = current
    if bytes(output[:len(expected)]) != expected:
        return 'output differs'
    return ''


def main() -> int:
    failures = 0
    shapes = [(256, 16)] if QUICK else [(256, 16), (512, 37)]
    linear_chunks = [16] if QUICK else [16, 255]
    for unit in (1, 2, 4):
        linear = assemble('ST4.S', unit)
        wrap = assemble('ST4_wrap.S', unit)
        ring = assemble('ST4_ring.S', unit)
        cases = 0
        for name, data, _ in t.testcases():
            padded = data + bytes(-len(data) % unit)
            units_total = len(padded) // unit
            for ring_bytes, chunk_units in shapes:
                ring_units = ring_bytes // unit
                if units_total < 3 * ring_units:
                    continue                            # a loop shorter than the ring
                # Loop points whose loop is longer than the window: from the
                # start, a third in, and the shortest loop that still exceeds it.
                for index in sorted({0, units_total // 3, units_total - ring_units - 1}):
                    file = st4.pack_file(data, unit, ring_units, index)
                    control, literal, byte_offsets, word_offsets, size, rewind = \
                        st4.streams(file, unit)
                    if rewind != index * unit:
                        print(f'FAIL k={unit} {name} -r{index}: the header says rewind '
                              f'{rewind}, not {index * unit}')
                        failures += 1
                        continue
                    period = units_total - index
                    target = (units_total + 2 * period + period // 2 + 3) * unit
                    expected = looped(data, unit, index, target)
                    runs = [(f'linear, chunks of {c}', run_linear(
                                control, literal, byte_offsets, word_offsets, expected,
                                unit, index, units_total, linear, c)) for c in linear_chunks]
                    runs.append((f'wrap, N={ring_bytes} C={chunk_units}', run_wrap(
                        control, literal, byte_offsets, word_offsets, expected, unit,
                        index, units_total, wrap, ring_bytes, chunk_units)))
                    for caller_wraps in (True, False):
                        who = 'caller' if caller_wraps else 'decoder'
                        runs.append((f'ring, N={ring_bytes} C={chunk_units}, {who} wraps',
                                     run_ring(control, literal, byte_offsets, word_offsets,
                                              expected, unit, index, units_total, ring,
                                              ring_bytes, chunk_units, caller_wraps)))
                    for shape, problem in runs:
                        if problem:
                            print(f'FAIL k={unit} {name} -r{index} ({shape}): {problem}')
                            failures += 1
                        cases += 1
        print(f'{"OK  " if not failures else "    "}k={unit}: {cases} rewound decodes '
              f'across all three decoders')

    print('ALL ST4 REWIND TESTS PASS' if not failures else f'{failures} FAILURES')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
