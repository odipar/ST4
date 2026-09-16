#!/usr/bin/env python3
"""Cycles a unit for the ST4 decoders, counted on an MC68000 model.

Packs each corpus with the real packer, assembles the real decoder, runs the
decode under emulation and costs every instruction with m68k_cycles. A plain
build and a window build of the same decoder read the same stream, so what
the copy code costs is a subtraction rather than a recollection.

    python3 68k/test/emu/bench_decode.py [-kK] [-mN] [-c] [--wrap N,C]

    -kK         unit size: 1, 2 or 4, 1 by default
    -mN         the window in units, the widest the format reaches by default
    -c          pack with copies, which only a window build reads
    --wrap N,C  ST4_wrap through an N-byte ring, C units a call, in place of
                ST4.S into one buffer
    --one NAME  one corpus rather than all of them
"""
import importlib.util
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]

ARGUMENTS = sys.argv[1:]            # the rigs read sys.argv on import
_spec = importlib.util.spec_from_file_location('st4', HERE / 'test_st4.py')
sys.argv = ['x']
st4 = importlib.util.module_from_spec(_spec)
sys.modules['st4'] = st4
_spec.loader.exec_module(st4)
t = st4.t
cm = sys.modules.get('cm')
if cm is None:
    _cmspec = importlib.util.spec_from_file_location('cm', HERE / 'm68k_cycles.py')
    cm = importlib.util.module_from_spec(_cmspec)
    sys.modules['cm'] = cm
    _cmspec.loader.exec_module(cm)

from unicorn import UC_HOOK_CODE                                     # noqa: E402
from unicorn.m68k_const import (                                     # noqa: E402
    UC_M68K_REG_A0, UC_M68K_REG_A1, UC_M68K_REG_A2, UC_M68K_REG_A4,
    UC_M68K_REG_A5, UC_M68K_REG_D1, UC_M68K_REG_D3,
)


def assemble(name: str, unit: int, window: bool):
    """One decoder built for one unit size, with its instruction listing."""
    with tempfile.TemporaryDirectory() as directory:
        source = Path(directory) / 'build.S'
        binary = Path(directory) / 'build.bin'
        listing = Path(directory) / 'build.lst'
        source.write_text(f'ST4_UNIT    equ     {unit}\n'
                          + ('ST4_WINDOW  equ     1\n' if window else '')
                          + f'        include "{REPO / "68k" / (name + ".S")}"\n')
        result = subprocess.run(
            ['rmac', '-m68000', '-fr', '+o3', f'-l*{listing}',
             '-o', str(binary), str(source)],
            capture_output=True, text=True)
        if result.returncode:
            raise SystemExit(result.stdout + result.stderr)
        instructions, _ = cm.parse_listing(listing)
        return binary.read_bytes(), instructions


class Counter:
    """Exact MC68000 cycles, a conditional branch costed by what it did."""

    def __init__(self, emulator, listing, base, size):
        self.listing, self.base = listing, base
        self.cycles, self.pending = 0, None
        emulator.hook_add(UC_HOOK_CODE, self._hook, begin=base,
                          end=base + size - 1)

    def _hook(self, _emulator, address, _size, _data):
        if self.pending is not None:
            where, instruction = self.pending
            taken = address != where + instruction.size
            self.cycles += 10 if taken else (8 if instruction.size == 2 else 12)
            self.pending = None
        instruction = self.listing.get(address - self.base)
        if instruction is None:
            raise AssertionError('unlisted opcode at %#x' % (address - self.base))
        if instruction.mnemonic.split('.')[0] in cm.CONDITIONALS:
            self.pending = (address, instruction)
        else:
            self.cycles += cm.cycles_of(instruction)


def load(uc, code, control, literal, byte_offsets, word_offsets):
    uc.mem_map(st4.LITERAL, 0x20000)
    uc.mem_map(st4.BYTE_OFFSETS, 0x20000)
    uc.mem_map(st4.WORD_OFFSETS, 0x20000)
    uc.mem_write(t.CODE, code)
    uc.mem_write(st4.LITERAL, literal)
    uc.mem_write(st4.BYTE_OFFSETS, byte_offsets or b'\0')
    uc.mem_write(st4.WORD_OFFSETS, word_offsets or b'\0\0')
    uc.reg_write(UC_M68K_REG_A0, t.SRC)
    uc.reg_write(UC_M68K_REG_A2, st4.LITERAL)
    uc.reg_write(UC_M68K_REG_A4, st4.BYTE_OFFSETS)
    uc.reg_write(UC_M68K_REG_A5, st4.WORD_OFFSETS)


def linear(packed, expected, unit, window_bytes, code, listing):
    """ST4.S into one buffer. Returns the cycles it spent.

    ST4_init and then ST4_resume with the budget ST4_decompress uses, rather
    than ST4_decompress itself: a window build patches two instructions in
    ST4_init, and the emulator runs the translation it made before the write
    where the patch and the patched code run inside one emu_start. A 68000
    has no instruction cache and reads the patched bytes, so the split is the
    emulator's rather than the decoder's.
    """
    control, literal, byte_offsets, word_offsets, _, _, _ = st4.streams(packed, unit)
    uc = t.make_emu(control)
    load(uc, code, control, literal, byte_offsets, word_offsets)
    uc.reg_write(UC_M68K_REG_A1, t.DST)
    uc.reg_write(UC_M68K_REG_D3, window_bytes)
    counter = Counter(uc, listing, t.CODE, len(code))
    t.call(uc, t.CODE)                                  # ST4_init at +0
    while True:
        uc.reg_write(UC_M68K_REG_D3, 0xFFFF)            # the drain loop's budget
        t.call(uc, t.CODE + 8)                          # ST4_resume at +8
        if uc.reg_read(UC_M68K_REG_D1) & 0xFFFF == 0:
            break
    produced = uc.reg_read(UC_M68K_REG_A1) - t.DST
    if produced != len(expected):
        raise SystemExit(f'produced {produced} bytes, expected {len(expected)}')
    if bytes(uc.mem_read(t.DST, len(expected))) != expected:
        raise SystemExit('output differs')
    return counter.cycles


def wrapped(packed, expected, unit, code, listing, ring_bytes, chunk_units):
    """ST4_wrap through an N-byte ring, C units a call, as its caller drives it."""
    control, literal, byte_offsets, word_offsets, _, _, _ = st4.streams(packed, unit)
    units_total = len(expected) // unit
    calls = -(-units_total // chunk_units)
    per_fill = ring_bytes // (chunk_units * unit)
    uc = t.make_emu(control)
    load(uc, code, control, literal, byte_offsets, word_offsets)
    ring = t.DST + 16
    uc.reg_write(UC_M68K_REG_A1, ring)
    uc.reg_write(UC_M68K_REG_D3, ring_bytes)
    counter = Counter(uc, listing, t.CODE, len(code))
    t.call(uc, t.CODE)                                  # ST4_init at +0

    output = bytearray()
    previous, slot = ring, 0
    for call in range(calls):
        budget = min(chunk_units, units_total - call * chunk_units)
        uc.reg_write(UC_M68K_REG_D3, budget)
        t.call(uc, t.CODE + 4)                          # ST4_resume at +4
        current = uc.reg_read(UC_M68K_REG_A1)
        output.extend(uc.mem_read(previous, current - previous))
        slot += 1
        if call + 1 < calls and slot == per_fill:
            uc.reg_write(UC_M68K_REG_A1, ring)
            previous, slot = ring, 0
        else:
            previous = current
    if bytes(output) != expected:
        raise SystemExit('output differs')
    return counter.cycles


def main() -> int:
    unit = 1
    window = None
    copies = False
    wrap = None
    one = None
    arguments = ARGUMENTS
    for at, argument in enumerate(arguments):
        if argument.startswith('-k'):
            unit = int(argument[2:])
        elif argument.startswith('-m'):
            window = int(argument[2:])
        elif argument == '-c':
            copies = True
        elif argument == '--wrap':
            wrap = tuple(int(n) for n in arguments[at + 1].split(','))
        elif argument == '--one':
            one = arguments[at + 1]
    if window is None:
        window = 32512 // unit

    decoder = 'ST4_wrap' if wrap else 'ST4'
    builds = {}
    for shape in ((True,) if copies else (False, True)):
        builds[shape] = assemble(decoder, unit, shape)
    print(f'{decoder}.S, k = {unit}, window {window} units'
          + (', packed with copies' if copies else '')
          + (f', ring {wrap[0]} bytes, {wrap[1]} units a call' if wrap else ''))
    head = 'plain' if not copies else 'window'
    print(f'{"corpus":<14}{"bytes":>8}{"units":>8}{head + " c/u":>12}'
          + ('' if copies else f'{"window c/u":>12}{"window":>9}'))

    totals = {False: 0, True: 0}
    units_all = 0
    for name, data, forced in t.testcases():
        if one and name != one:
            continue
        if forced is not None:
            continue                         # a corpus with a window of its own
        padded = data + bytes(-len(data) % unit)
        packed = st4.pack_file(data, unit, window, copies=copies)
        units = len(padded) // unit
        units_all += units
        spent = {}
        for shape, (code, listing) in builds.items():
            if wrap:
                spent[shape] = wrapped(packed, padded, unit, code, listing,
                                       wrap[0], wrap[1])
            else:
                spent[shape] = linear(packed, padded, unit, window * unit,
                                      code, listing)
            totals[shape] += spent[shape]
        row = (f'{name:<14}{len(padded):>8}{units:>8}'
               f'{spent[copies] / units:>12.1f}')
        if not copies:
            row += (f'{spent[True] / units:>12.1f}'
                    f'{100.0 * (spent[True] - spent[False]) / spent[False]:>8.1f}%')
        print(row)

    row = (f'{"ALL":<14}{"":>8}{units_all:>8}'
           f'{totals[copies] / units_all:>12.1f}')
    if not copies:
        row += (f'{totals[True] / units_all:>12.1f}'
                f'{100.0 * (totals[True] - totals[False]) / totals[False]:>8.1f}%')
    print(row)
    return 0


if __name__ == '__main__':
    sys.exit(main())
