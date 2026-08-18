#!/usr/bin/env python3
"""Three ways of reading ST4's control stream, cycle-counted on real streams.

ST4 spends a large part of its time turning stream A into flags and lengths, so
it is worth knowing what that costs and what the alternatives would cost. This
packs the test corpora with the real packer, recovers the exact sequence of
operations from stream A, and makes three decoders reproduce that sequence:

  V1  byte queue    ZX1's own reader: a $80-sentinel byte, refilled a byte at
                    a time. What ST4 did before the offsets moved out.
  V2  word queue    the same reader with a $8000 sentinel, refilled a word at
                    a time. What ST4 does now. Same bits, half the refills,
                    and only possible because stream A is nothing but bits.
  V3  peek table    lengths in a stream of their own, written LSB first, read
                    through a 512-entry table indexed by the next 9 bits.
                    Flags keep a word queue. The table is the classic fast
                    Huffman-style reader.

V3 exists to be measured, not to be shipped: on this data it loses, and this is
the file that says by how much. The reason is in the length distribution - more
than half of all lengths are 1, which interlaced Elias gamma spends ONE bit and
about 30 cycles on, while any table needs a variable shift to consume a
bit-granular code and a running count of valid bits to know when to refill.

    python3 68k/test/emu/bench_bits.py [--quick]
"""
import importlib.util
import re
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]

_spec = importlib.util.spec_from_file_location('cm', HERE / 'm68k_cycles.py')
cm = importlib.util.module_from_spec(_spec)
sys.modules['cm'] = cm
_spec.loader.exec_module(cm)

_spec = importlib.util.spec_from_file_location('st4', HERE / 'test_st4.py')
_saved, sys.argv = sys.argv, ['x'] + [a for a in sys.argv[1:] if a.startswith('-')]
st4 = importlib.util.module_from_spec(_spec)
sys.modules['st4'] = st4
_spec.loader.exec_module(st4)
sys.argv = _saved
t = st4.t

from unicorn import Uc, UC_ARCH_M68K, UC_MODE_BIG_ENDIAN, UC_HOOK_CODE  # noqa: E402
from unicorn.m68k_const import (                                        # noqa: E402
    UC_CPU_M68K_M68000, UC_M68K_REG_A0, UC_M68K_REG_A1, UC_M68K_REG_A2,
    UC_M68K_REG_A7, UC_M68K_REG_D0, UC_M68K_REG_D1, UC_M68K_REG_D2,
    UC_M68K_REG_D3, UC_M68K_REG_D4, UC_M68K_REG_D5, UC_M68K_REG_D6,
    UC_M68K_REG_D7,
)

QUICK = '--quick' in sys.argv
CODE, SRC, LENGTHS, DST, STACK = 0x1000, 0x40000, 0x80000, 0xC0000, 0x200000
PEEK = 9                                # table index width, so 512 entries
DATA = {f'd{i}': r for i, r in enumerate([
    UC_M68K_REG_D0, UC_M68K_REG_D1, UC_M68K_REG_D2, UC_M68K_REG_D3,
    UC_M68K_REG_D4, UC_M68K_REG_D5, UC_M68K_REG_D6, UC_M68K_REG_D7])}


# ---------------------------------------------------------------- cycle model

def cycles_of(instruction, emulator):
    """MC68000 cycles; cycle_model's table plus the forms only this file uses."""
    root = instruction.mnemonic.split('.')[0]
    long = instruction.mnemonic.endswith('.l')
    operands = instruction.operands
    if root in {'lsl', 'lsr'}:
        match = re.fullmatch(r'#(\d+),d\d', operands)
        if match:
            return (8 if long else 6) + 2 * int(match.group(1))
        match = re.fullmatch(r'(d\d),d\d', operands)
        if match:                       # a register count is only known at run time
            return (8 if long else 6) + 2 * (emulator.reg_read(DATA[match.group(1)]) & 63)
        raise KeyError(instruction)
    if root in {'move', 'movea'}:
        source, destination = operands.rsplit(',', 1)
        if re.fullmatch(r'\w*\(a\d,d\d\.w\)', source):
            return 18 if long else 14
        if source.startswith('#') and re.fullmatch(r'[ad]\d', destination):
            return 12 if long else 8
        if re.fullmatch(r'd\d', source) and re.fullmatch(r'\(a\d\)\+', destination):
            return 12 if long else 8
    if root == 'or' and re.fullmatch(r'[ad]\d,d\d', operands):
        return 8 if long else 4
    if root == 'lea' and re.fullmatch(r'\w+\(pc\),a\d', operands):
        return 8
    return cm.fixed_cycles(instruction)


class Counter:
    def __init__(self, emulator, listing, base, size):
        self.listing, self.base, self.cycles, self.pending = listing, base, 0, None
        self.emulator = emulator
        emulator.hook_add(UC_HOOK_CODE, self._hook, begin=base, end=base + size - 1)

    def _hook(self, _emulator, address, _size, _data):
        if self.pending is not None:
            where, instruction = self.pending
            taken = address != where + instruction.size
            self.cycles += 10 if taken else (8 if instruction.size == 2 else 12)
            self.pending = None
        instruction = self.listing.get(address - self.base)
        if instruction is None:
            raise AssertionError(f'unlisted opcode at {address - self.base:#x}')
        if instruction.mnemonic.split('.')[0] in cm.CONDITIONALS:
            self.pending = (address, instruction)
        else:
            self.cycles += cycles_of(instruction, self.emulator)


def assemble(source_text):
    with tempfile.TemporaryDirectory() as directory:
        source = Path(directory) / 'b.S'
        source.write_text(source_text)
        binary, listing = Path(directory) / 'b.bin', Path(directory) / 'b.lst'
        result = subprocess.run(
            ['rmac', '-m68000', '-fr', f'-l*{listing}', '-o', str(binary), str(source)],
            capture_output=True, text=True)
        if result.returncode or 'Error' in result.stdout + result.stderr:
            raise SystemExit(result.stdout + result.stderr)
        instructions, _ = cm.parse_listing(listing)
        return binary.read_bytes(), instructions


# --------------------------------------------------------------- the streams

def operations(control: bytes):
    """The lengths and the control bits between them, as stream A really holds.

    Returns the lengths and, for each, the bits that follow it: one flag bit,
    and two more when that flag opens a new-offset match. Timing them means
    reproducing them, since it is their count that sets where refills fall.
    """
    position = [0]

    def bit():
        index, shift = divmod(position[0], 8)
        position[0] += 1
        return (control[index] >> (7 - shift)) & 1

    def gamma():
        value = 1
        while bit():
            value = value * 2 + bit()
        return value

    lengths, between = [gamma()], []
    while True:
        flag = bit()
        if not flag:
            between.append([0])
            lengths.append(gamma())
            continue
        first, second = bit(), bit()
        between.append([1, first, second])
        if not first and second:
            return lengths, between             # the end marker ends stream A
        lengths.append(gamma())


class MsbBits:
    """ZX1's order: bits fill each byte from the top."""

    def __init__(self):
        self.out, self.mask, self.at = bytearray(), 0, 0

    def bit(self, value):
        if self.mask == 0:
            self.mask, self.at = 128, len(self.out)
            self.out.append(0)
        if value:
            self.out[self.at] |= self.mask
        self.mask >>= 1

    def gamma(self, value):
        for shift in range(value.bit_length() - 2, -1, -1):
            self.bit(1)
            self.bit((value >> shift) & 1)
        self.bit(0)

    def done(self, align):
        out = bytearray(self.out)
        while len(out) % align or not out:
            out.append(0)
        return bytes(out)


class LsbBits:
    """Reversed order, so the table index is a mask rather than a shift."""

    def __init__(self):
        self.bits = []

    def bit(self, value):
        self.bits.append(value)

    def gamma(self, value):
        for shift in range(value.bit_length() - 2, -1, -1):
            self.bit(1)
            self.bit((value >> shift) & 1)
        self.bit(0)

    def done(self):
        bits = self.bits + [0] * (-len(self.bits) % 16)
        out = bytearray()
        for start in range(0, len(bits), 16):
            word = sum(b << i for i, b in enumerate(bits[start:start + 16]))
            out += word.to_bytes(2, 'big')
        return bytes(out) or b'\0\0'


def build_table():
    """VAL and LEN for every PEEK-bit window; LEN 0 means the code is longer."""
    values, lengths = [0] * (1 << PEEK), [0] * (1 << PEEK)
    for index in range(1 << PEEK):
        window = [(index >> i) & 1 for i in range(PEEK)]
        value, at = 1, 0
        while at < PEEK and window[at]:
            at += 1
            if at >= PEEK:
                break
            value = value * 2 + window[at]
            at += 1
        if at < PEEK and not window[at]:
            values[index], lengths[index] = value, at + 1
    return values, lengths


# -------------------------------------------------------------- the decoders
# Each reads one length and then the next operation's flag bit - the order the
# format uses, and the one that makes gamma+flag an even number of bits.

V1 = """
        moveq   #-128,d0
v1:
        moveq   #0,d1
        addq.w  #1,d1
        add.b   d0,d0
        beq.s   .refill
        bcc.s   .done
.inner:
        add.b   d0,d0
        addx.w  d1,d1
        add.b   d0,d0
        beq.s   .refill
        bcs.s   .inner
.done:
        move.w  d1,(a1)+
        add.b   d0,d0                   ; the flag bit: bare, it sits at an odd position
        bcc.s   .next
        add.b   d0,d0                   ; class bit 1: the one bit that can refill
        beq.s   .classfill
.class2:
        add.b   d0,d0                   ; class bit 2: bare again
.next:
        subq.w  #1,d6
        bne.s   v1
        rts
.classfill:
        move.b  (a0)+,d0
        addx.b  d0,d0
        bra.s   .class2
.refill:
        move.b  (a0)+,d0
        addx.b  d0,d0
        bcs.s   .inner
        bra.s   .done
"""

V2 = """
        move.w  #$8000,d0
v2:
        moveq   #0,d1
        addq.w  #1,d1
        add.w   d0,d0
        beq.s   .refill
        bcc.s   .done
.inner:
        add.w   d0,d0
        addx.w  d1,d1
        add.w   d0,d0
        beq.s   .refill
        bcs.s   .inner
.done:
        move.w  d1,(a1)+
        add.w   d0,d0                   ; the flag bit: bare, it sits at an odd position
        bcc.s   .next
        add.w   d0,d0                   ; class bit 1: the one bit that can refill
        beq.s   .classfill
.class2:
        add.w   d0,d0                   ; class bit 2: bare again
.next:
        subq.w  #1,d6
        bne.s   v2
        rts
.classfill:
        move.w  (a0)+,d0
        addx.w  d0,d0
        bra.s   .class2
.refill:
        move.w  (a0)+,d0
        addx.w  d0,d0
        bcs.s   .inner
        bra.s   .done
"""

# Two tables so an entry needs no unpacking, which costs two address registers
# on top of the length stream's own pointer.  Every flag read needs a refill
# check now: a stream of nothing but flag bits has no parity to argue from.
V3 = """
        move.w  #$8000,d0
        moveq   #0,d7
        moveq   #0,d5
        lea     v3len(pc),a3
        lea     v3val(pc),a4
v3:
        cmp.w   #9,d5
        bge.s   .peek
        moveq   #0,d4
        move.w  (a2)+,d4
        lsl.l   d5,d4
        or.l    d4,d7
        add.w   #16,d5
.peek:
        move.w  d7,d4
        and.w   #$1ff,d4
        move.b  (a3,d4.w),d3
        beq.s   .escape
        add.w   d4,d4
        move.w  (a4,d4.w),d1
        lsr.l   d3,d7
        sub.w   d3,d5
.done:
        move.w  d1,(a1)+
        add.w   d0,d0
        beq.s   .flagfill
.flag:
        bcc.s   .next
        add.w   d0,d0
        beq.s   .c1fill
.c2:
        add.w   d0,d0
        beq.s   .c2fill
.next:
        subq.w  #1,d6
        bne.s   v3
        rts
.flagfill:
        move.w  (a0)+,d0
        addx.w  d0,d0
        bra.s   .flag
.c1fill:
        move.w  (a0)+,d0
        addx.w  d0,d0
        bra.s   .c2
.c2fill:
        move.w  (a0)+,d0
        addx.w  d0,d0
        bra.s   .next
.escape:
        moveq   #1,d1
.econt:
        lsr.l   #1,d7
        bcc.s   .eout
        lsr.l   #1,d7
        addx.w  d1,d1
        subq.w  #2,d5
        cmp.w   #2,d5
        bge.s   .econt
        moveq   #0,d4
        move.w  (a2)+,d4
        lsl.l   d5,d4
        or.l    d4,d7
        add.w   #16,d5
        bra.s   .econt
.eout:
        subq.w  #1,d5
        bra.s   .done
"""


def v3_tables():
    values, lengths = build_table()
    lines = ['v3len:']
    for start in range(0, len(lengths), 16):
        lines.append('        dc.b    ' + ','.join(str(v) for v in lengths[start:start + 16]))
    lines.append('v3val:')
    for start in range(0, len(values), 16):
        lines.append('        dc.w    ' + ','.join(str(v) for v in values[start:start + 16]))
    return '\n'.join(lines) + '\n'


def run(source, control, lengths, expected, extra=''):
    code, listing = assemble(source + extra)
    emulator = Uc(UC_ARCH_M68K, UC_MODE_BIG_ENDIAN)
    emulator.ctl_set_cpu_model(UC_CPU_M68K_M68000)
    for base, span in ((CODE, 0x10000), (SRC, 0x40000), (LENGTHS, 0x40000),
                       (DST, 0x40000), (STACK - 0x1000, 0x2000)):
        emulator.mem_map(base, span)
    emulator.mem_write(CODE, code)
    emulator.mem_write(SRC, control)
    emulator.mem_write(LENGTHS, lengths)
    stop = CODE + len(code) + 0x100
    emulator.mem_write(STACK - 4, stop.to_bytes(4, 'big'))
    emulator.reg_write(UC_M68K_REG_A7, STACK - 4)
    emulator.reg_write(UC_M68K_REG_A0, SRC)
    emulator.reg_write(UC_M68K_REG_A1, DST)
    emulator.reg_write(UC_M68K_REG_A2, LENGTHS)
    emulator.reg_write(UC_M68K_REG_D6, len(expected))
    counter = Counter(emulator, listing, CODE, len(code))
    emulator.emu_start(CODE, stop, count=200_000_000)
    produced = emulator.mem_read(DST, 2 * len(expected))
    got = [int.from_bytes(produced[i * 2:i * 2 + 2], 'big') for i in range(len(expected))]
    if got != expected:
        bad = next(i for i in range(len(expected)) if got[i] != expected[i])
        raise SystemExit(f'decoder disagreed at length {bad}: '
                         f'{got[bad]} instead of {expected[bad]}')
    return counter.cycles


def main():
    tables = v3_tables()
    print(f'{"corpus":<22} {"lengths":>8} {"V1 byte":>9} {"V2 word":>9} {"V3 table":>9}'
          f' {"V2":>7} {"V3":>7}')
    totals, grand = [0, 0, 0], 0
    units = (1, 4) if QUICK else (1, 2, 4)
    for name, data, _ in t.testcases():
        for unit in units:
            control, literal, _, _, _ = st4.pack(data, unit, 32512 // unit)
            lengths, between = operations(control)
            if len(lengths) < 4:
                continue                        # nothing to time

            joint = MsbBits()
            split_flags, split_lengths = MsbBits(), LsbBits()
            for index, value in enumerate(lengths):
                joint.gamma(value)
                split_lengths.gamma(value)
                for control_bit in between[index]:
                    joint.bit(control_bit)
                    split_flags.bit(control_bit)

            cycles = (run(V1, joint.done(1), b'\0\0', lengths),
                      run(V2, joint.done(2), b'\0\0', lengths),
                      run(V3, split_flags.done(2), split_lengths.done(),
                          lengths, tables))
            count = len(lengths)
            totals = [a + b for a, b in zip(totals, cycles)]
            grand += count
            print(f'{name + " k=" + str(unit):<22} {count:>8} {cycles[0] / count:>9.1f}'
                  f' {cycles[1] / count:>9.1f} {cycles[2] / count:>9.1f}'
                  f' {100 * (cycles[1] / cycles[0] - 1):>+6.1f}%'
                  f' {100 * (cycles[2] / cycles[0] - 1):>+6.1f}%')
    print(f'{"ALL":<22} {grand:>8} {totals[0] / grand:>9.1f} {totals[1] / grand:>9.1f}'
          f' {totals[2] / grand:>9.1f} {100 * (totals[1] / totals[0] - 1):>+6.1f}%'
          f' {100 * (totals[2] / totals[0] - 1):>+6.1f}%')
    print('\ncycles per operation - one length plus the control bits that follow'
          ' it,\nincluding the harness that stores each result (move.w, subq, bne = 22).')
    return 0


if __name__ == '__main__':
    sys.exit(main())
