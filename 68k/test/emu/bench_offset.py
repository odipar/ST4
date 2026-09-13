#!/usr/bin/env python3
"""Two ways of decoding a reference, cycle-counted on a real stream.

ST4 spends 1 flag bit, 2 class bits and a byte of stream C on a reference
that reaches within 512 units, or a word of stream D beyond that. Where the
window M is small the offset byte has spare values, so the byte can say what
it is and the class bits go:

  NOW     two class bits from stream A select byte or word and, for a byte,
          the 256-unit bank.
  FOLDED  the byte of stream C is the class: 1..M a match, M+1..255 a copy,
          0 an escape to a word of stream D.

The paths are the ones in ST4_wrap.S, driven over the references a real tune
packs to, and counted instruction by instruction on an MC68000 model.

Each input is packed with the packer at the -k and -m given, its references
are read back out of the container, and the two paths decode them.

    python3 68k/test/emu/bench_offset.py [-kK] [-mN] input [...]
"""
import importlib.util
import re
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent

_spec = importlib.util.spec_from_file_location('cm', HERE / 'm68k_cycles.py')
cm = importlib.util.module_from_spec(_spec)
sys.modules['cm'] = cm
_spec.loader.exec_module(cm)

from unicorn import Uc, UC_ARCH_M68K, UC_MODE_BIG_ENDIAN, UC_HOOK_CODE   # noqa: E402
from unicorn.m68k_const import (                                         # noqa: E402
    UC_CPU_M68K_M68000, UC_M68K_REG_A0, UC_M68K_REG_A4, UC_M68K_REG_A5,
    UC_M68K_REG_A7, UC_M68K_REG_D0, UC_M68K_REG_D3, UC_M68K_REG_PC,
)

CODE, STREAM_A, STREAM_C, STREAM_D, STACK = 0x1000, 0x40000, 0x60000, 0x80000, 0x200000
UNIT = 2


def cycles_of(instruction):
    root = instruction.mnemonic.split('.')[0]
    long = instruction.mnemonic.endswith('.l')
    operands = instruction.operands
    if root in {'move', 'movea'}:
        source, destination = operands.rsplit(',', 1)
        if source.startswith('#') and re.fullmatch(r'[ad]\d', destination):
            return 12 if long else 8
    if root == 'lea':
        if re.fullmatch(r'\w+\(pc\),a\d', operands):
            return 8
        if re.fullmatch(r'\$[0-9a-f]+,a\d', operands):
            return 12 if instruction.size == 6 else 8
    return cm.fixed_cycles(instruction)


class Counter:
    """Exact MC68000 cycles, a conditional branch costed by what it did."""

    def __init__(self, emulator, listing, base, size):
        self.listing, self.base, self.cycles, self.pending = listing, base, 0, None
        self.at = {}
        emulator.hook_add(UC_HOOK_CODE, self._hook, begin=base, end=base + size - 1)

    def _hook(self, _emulator, address, _size, _data):
        if self.pending is not None:
            where, instruction = self.pending
            taken = address != where + instruction.size
            spent = 10 if taken else (8 if instruction.size == 2 else 12)
            self.cycles += spent
            self.at[where - self.base] = self.at.get(where - self.base, 0) + spent
            self.pending = None
        instruction = self.listing.get(address - self.base)
        if instruction is None:
            raise AssertionError('unlisted opcode at %#x' % (address - self.base))
        if instruction.mnemonic.split('.')[0] in cm.CONDITIONALS:
            self.pending = (address, instruction)
        else:
            spent = cycles_of(instruction)
            self.cycles += spent
            self.at[address - self.base] = self.at.get(address - self.base, 0) + spent


def assemble(text):
    with tempfile.TemporaryDirectory() as directory:
        source = Path(directory) / 'b.S'
        source.write_text(text)
        binary, listing = Path(directory) / 'b.bin', Path(directory) / 'b.lst'
        result = subprocess.run(
            ['rmac', '-m68000', '-fr', '-l*%s' % listing, '-o', str(binary), str(source)],
            capture_output=True, text=True)
        if result.returncode or 'Error' in result.stdout + result.stderr:
            raise SystemExit(result.stdout + result.stderr)
        instructions, symbols = cm.parse_listing(listing)
        return binary.read_bytes(), instructions, symbols


# ----------------------------------------------------------------- the paths

# The path ST4_wrap.S runs today, entered with the flag bit already read:
# two class bits out of the queue, then a byte of stream C or a word of D.
NOW = """
        add.w   d0,d0
        beq.s   class_refill
class_read:
        bcs.s   byte_offset
        add.w   d0,d0
        bcs.s   ref_next
        move.w  (a5)+,d2
        moveq   #1,d4
        bra.s   ref_next
class_refill:
        move.w  (a0)+,d0
        addx.w  d0,d0
        bra.s   class_read
byte_offset:
        move.b  (a4)+,d4
        add.w   d0,d0
        bcc.s   scale
        sub.w   #256,d4
scale:
        add.w   d4,d4
        move.w  d4,d2
        moveq   #1,d4
"""

# The folded path: the byte of stream C is the class. Zero escapes to a word,
# so no class bit is read and the queue is untouched.
FOLDED = """
        move.b  (a4)+,d4
        beq.s   escape
        add.w   d4,d4
        move.w  d4,d2
        moveq   #1,d4
        bra.s   ref_next
escape:
        move.w  (a5)+,d2
        moveq   #1,d4
"""

PROGRAM = """
        .text
        .org    $%x
start:
        move.w  #$8000,d0
        lea     $%x,a0
        lea     $%x,a4
        lea     $%x,a5
        move.w  #%d,d3
loop:
        moveq   #-1,d4
%s
ref_next:
        subq.w  #1,d3
        bne.s   loop
stop:
        nop
"""


def bits_to_bytes(bits):
    out = bytearray((len(bits) + 7) // 8)
    for i, b in enumerate(bits):
        if b:
            out[i // 8] |= 0x80 >> (i % 8)
    return bytes(out)


def streams(refs, folded, window):
    """Streams A, C and D as each design would write them."""
    a, c, d = [], bytearray(), bytearray()
    for kind, offset in refs:
        if folded:
            # 1..M a match, M+1..255 a copy, 0 the escape to a word
            if offset <= 255:
                c.append((256 - offset) & 0xFF)
            else:
                c.append(0)
                d += (-offset * UNIT & 0xFFFF).to_bytes(2, 'big')
        elif kind == 0:
            a += [1, 0]
            c.append((256 - offset) & 0xFF)
        elif kind == 1:
            a += [1, 1]
            c.append((512 - offset) & 0xFF)
        else:
            a += [0, 0]
            d += (-offset * UNIT & 0xFFFF).to_bytes(2, 'big')
    # the queue reads a word at a time, so stream A is padded to one
    while len(a) % 16:
        a.append(0)
    return bits_to_bytes(a), bytes(c) or b'\0', bytes(d) or b'\0\0'


def run(refs, folded, window):
    text = PROGRAM % (CODE, STREAM_A, STREAM_C, STREAM_D, len(refs),
                      FOLDED if folded else NOW)
    code, listing, symbols = assemble(text)
    a, c, d = streams(refs, folded, window)
    emulator = Uc(UC_ARCH_M68K, UC_MODE_BIG_ENDIAN)
    emulator.ctl_set_cpu_model(UC_CPU_M68K_M68000)
    for base, size in ((CODE, 0x10000), (STREAM_A, 0x20000), (STREAM_C, 0x20000),
                       (STREAM_D, 0x20000), (STACK - 0x10000, 0x20000)):
        emulator.mem_map(base, size)
    emulator.mem_write(CODE, code)
    emulator.mem_write(STREAM_A, a)
    emulator.mem_write(STREAM_C, c)
    emulator.mem_write(STREAM_D, d)
    emulator.reg_write(UC_M68K_REG_A7, STACK)
    counter = Counter(emulator, listing, CODE, len(code))
    stop = CODE + len(code) - 2                      # the trailing nop
    emulator.emu_start(CODE, stop, 0, 0)
    return counter, symbols, len(a)


def container_refs(blob):
    """The new-offset references of an ST4 container: (class, offset) each,
    class 0 the near byte bank, 1 the far bank, 2 a word."""
    assert blob[0:2] == b'S4' and blob[2] == 7, 'not an ST4 container'
    k = blob[3]
    size = int.from_bytes(blob[4:8], 'big')
    c0 = int.from_bytes(blob[12:16], 'big')
    d0 = int.from_bytes(blob[16:20], 'big')
    window = int.from_bytes(blob[24:28], 'big')
    units, out = size // k, []
    at_bit = 28 * 8

    def one():
        nonlocal at_bit
        bit = (blob[at_bit // 8] >> (7 - at_bit % 8)) & 1
        at_bit += 1
        return bit

    def gamma():
        value = 1
        while one():
            value = value * 2 + one()
        return value

    unit = gamma()
    after = 'literals'
    ci, di = c0, d0
    while unit < units:
        if one() == 0:
            unit += gamma()
            after = 'match' if after == 'literals' else 'literals'
            continue
        klass = (one() << 1) | one()
        if klass == 1:
            one()
            break
        if klass == 2:
            offset = 256 - blob[ci]
            ci += 1
            if offset <= 0:
                offset += 256
            kind = 0
        elif klass == 3:
            offset = 512 - blob[ci]
            ci += 1
            kind = 1
        else:
            offset = (65536 - int.from_bytes(blob[di:di + 2], 'big')) // k
            di += 2
            kind = 2
        unit += gamma() + 1
        after = 'match'
        out.append((kind, offset))
    return out, window


def pack(path, k, window):
    """The packer's container for one file, with copies at that window."""
    with tempfile.TemporaryDirectory() as directory:
        out = Path(directory) / 'x.st4'
        result = subprocess.run(
            ['java', '-cp', str(HERE.parents[2] / 'target' / 'classes'), 'org.st4.St4',
             '-f', '-c', '-k%d' % k, '-m%d' % window, str(path), str(out)],
            capture_output=True, text=True)
        if result.returncode:
            raise SystemExit(result.stdout + result.stderr)
        return out.read_bytes()


def refill_cycles(counter, symbols):
    """Cycles spent in the class-bit refill, which the real decoder reaches at
    its own rate rather than this benchmark's."""
    start = symbols.get('class_refill')
    if start is None:
        return 0
    start -= CODE
    end = symbols['byte_offset'] - CODE
    return sum(v for k, v in counter.at.items() if start <= k < end)


def main():
    k = 2
    window = 60
    files = []
    for argument in sys.argv[1:]:
        if argument.startswith('-k'):
            k = int(argument[2:])
        elif argument.startswith('-m'):
            window = int(argument[2:])
        else:
            files.append(argument)
    print('unit %d, window %d units' % (k, window))
    print('%-16s %7s %9s %9s %9s %8s' % ('input', 'refs', 'now', 'folded',
                                         'saved', 'a ref'))
    for named in files:
        refs, found = container_refs(pack(named, k, window))
        if not refs:
            continue
        now, symbols, _ = run(refs, False, found)
        folded, _, _ = run(refs, True, found)
        straight = now.cycles - refill_cycles(now, symbols)
        saved = straight - folded.cycles
        gone = 2 * len(refs) // 16               # refills stream A no longer needs
        print('%-16s %7d %9d %9d %9d %8.1f'
              % (Path(named).name, len(refs), straight, folded.cycles, saved,
                 (saved + 22 * gone) / len(refs)))


if __name__ == '__main__':
    main()
