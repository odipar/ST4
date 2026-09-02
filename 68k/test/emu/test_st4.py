#!/usr/bin/env python3
"""Differential test for ST4.S: the 68000 decoder against the Java packer.

Packs each corpus with the real ST4 packer at unit sizes 1, 2 and 4, assembles
ST4.S once per unit size, and decodes under Unicorn as a plain 68000 - one shot
and resumed in chunks - checking every output byte, that all four streams are
consumed exactly, and that the caller's registers survive.

    python3 68k/test/emu/test_st4.py [--quick]
"""
import hashlib
import importlib.util
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
CLASSES = REPO / 'target' / 'classes'
CACHE = HERE / '.st4'

spec = importlib.util.spec_from_file_location('t', HERE / 'test68k.py')
sys.argv = ['x']
t = importlib.util.module_from_spec(spec)
sys.modules['t'] = t
spec.loader.exec_module(t)

from unicorn.m68k_const import (                                    # noqa: E402
    UC_M68K_REG_A0, UC_M68K_REG_A1, UC_M68K_REG_A2, UC_M68K_REG_A3,
    UC_M68K_REG_A4, UC_M68K_REG_A5, UC_M68K_REG_A6, UC_M68K_REG_D0,
    UC_M68K_REG_D1, UC_M68K_REG_D2, UC_M68K_REG_D3, UC_M68K_REG_D4,
    UC_M68K_REG_D5, UC_M68K_REG_D6, UC_M68K_REG_D7,
)

QUICK = '--quick' in sys.argv
LITERAL = 0x100000                  # a region of its own for stream D
BYTE_OFFSETS = 0x140000             # and one each for streams B and C
WORD_OFFSETS = 0x180000
PRESERVED = {UC_M68K_REG_D6: 0xD6D6D6D6, UC_M68K_REG_D7: 0xD7D7D7D7,
             UC_M68K_REG_A6: 0x00A60000}
SCRATCH = (UC_M68K_REG_D3, UC_M68K_REG_D4, UC_M68K_REG_D5, UC_M68K_REG_A3)


def assemble(unit: int) -> bytes:
    """ST4.S built for one unit size: the width is decided here, not at run time."""
    with tempfile.TemporaryDirectory() as directory:
        source = Path(directory) / 'build.S'
        binary = Path(directory) / 'build.bin'
        source.write_text(f'ST4_UNIT    equ     {unit}\n'
                          f'        include "{REPO / "68k" / "ST4.S"}"\n')
        result = subprocess.run(
            ['rmac', '-m68000', '-fr', '+o3', '-o', str(binary), str(source)],
            capture_output=True, text=True)
        if result.returncode:
            raise SystemExit(result.stdout + result.stderr)
        return binary.read_bytes()


def pack_file(data: bytes, unit: int, window: int, repeat: int | None = None,
              copies: bool = False) -> bytes:
    """Runs the real packer; repeat is the loop point as a unit index, 0 valid,
    and copies lets a match past the window copy from the literal stream.
    Returns the whole container, cached by its inputs and the format version."""
    if not CLASSES.exists():
        raise SystemExit('target/classes is missing; run `mvn compile` first')
    CACHE.mkdir(exist_ok=True)
    key = CACHE / (f'{hashlib.sha1(data).hexdigest()[:16]}-v6-k{unit}-m{window}'
                   + (f'-at{repeat}' if repeat is not None else '')
                   + ('-c' if copies else '') + '.st4')
    if not key.exists():
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'in'
            source.write_bytes(data)
            subprocess.run(['java', '-ea', '-cp', str(CLASSES), 'org.st4.St4', '-f',
                            f'-k{unit}', f'-m{window}', '-l65535']
                           + ([f'-r{repeat}'] if repeat is not None else [])
                           + (['-c'] if copies else [])
                           + [str(source), str(key)],
                           check=True, capture_output=True)
    return key.read_bytes()



def unpack_file(file: bytes, times: int) -> bytes:
    """Runs the real unpacker on a container: dst4 -rN, the pass and then N-1
    repeats of its loop section. Cached by the container and the count."""
    CACHE.mkdir(exist_ok=True)
    key = CACHE / f'{hashlib.sha1(file).hexdigest()[:16]}-r{times}.bin'
    if not key.exists():
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'in.st4'
            source.write_bytes(file)
            subprocess.run(['java', '-ea', '-cp', str(CLASSES), 'org.st4.Dst4', '-f',
                            f'-r{times}', str(source), str(key)],
                           check=True, capture_output=True)
    return key.read_bytes()

def streams(file: bytes, unit: int) -> tuple:
    """The four streams, the padded size, the rewind point and the window."""
    def long(at):
        return int.from_bytes(file[at:at + 4], 'big')

    # Twenty-eight bytes of header: signature, O, where B, C and D begin, the
    # rewind point and the window. A begins where the header ends, and no
    # length is stored - each stream runs to the next in file order A, B, C,
    # D, so a slice can carry up to three bytes of padding; D, last, runs to
    # the end of the file exactly.
    assert long(0) == 0x53340000 | (6 << 8) | unit, 'not an ST4 v6 file for this unit'
    size = long(4)
    bytes_at, words_at, literal_at = long(8), long(12), long(16)
    assert 28 <= bytes_at <= words_at <= literal_at <= len(file), 'streams out of order'
    rewind = long(20)
    rewind = -1 if rewind == 0xFFFFFFFF else rewind
    return (file[28:bytes_at], file[literal_at:],
            file[bytes_at:words_at], file[words_at:literal_at], size, rewind, long(24))


def pack(data: bytes, unit: int, window: int, repeat: int | None = None) -> tuple:
    """Runs the real packer; returns the four streams and the padded size."""
    return streams(pack_file(data, unit, window, repeat), unit)[:5]


def consumed(name: str, count: int, stream: bytes) -> str:
    """A stream must be read to its end, give or take its alignment padding.

    No length is stored, so a slice runs to where the next stream begins and can
    carry up to three bytes the packer never wrote. A decoder that stopped short
    by more than that, or read past the end, has lost its place.
    """
    if not 0 <= len(stream) - count < 4:
        return f'stream {name}: consumed {count} of {len(stream)}'
    return ''


def run(control: bytes, literal: bytes, byte_offsets: bytes, word_offsets: bytes,
        expected: bytes, unit: int, code: bytes, chunk: int | None) -> str:
    uc = t.make_emu(control)
    uc.mem_map(LITERAL, 0x20000)        # stream D is as large as the literals
    uc.mem_map(BYTE_OFFSETS, 0x20000)
    uc.mem_map(WORD_OFFSETS, 0x20000)
    uc.mem_write(t.CODE, code)
    uc.mem_write(LITERAL, literal)
    uc.mem_write(BYTE_OFFSETS, byte_offsets or b'\0')
    uc.mem_write(WORD_OFFSETS, word_offsets or b'\0\0')
    for register, canary in PRESERVED.items():
        uc.reg_write(register, canary)
    for register in SCRATCH:
        uc.reg_write(register, 0xBAD0BAD0)
    uc.reg_write(UC_M68K_REG_A0, t.SRC)
    uc.reg_write(UC_M68K_REG_A1, t.DST)
    uc.reg_write(UC_M68K_REG_A2, LITERAL)
    uc.reg_write(UC_M68K_REG_A4, BYTE_OFFSETS)
    uc.reg_write(UC_M68K_REG_A5, WORD_OFFSETS)
    t.call(uc, t.CODE)                                  # ST4_init at +0

    if chunk is None:
        t.call(uc, t.CODE + 4)                          # ST4_decompress at +4
    else:
        calls = 0
        while True:
            calls += 1
            if calls > len(expected) // unit + 16:
                return 'resume did not terminate'
            uc.reg_write(UC_M68K_REG_D3, 0xBEEF0000 | chunk)
            t.call(uc, t.CODE + 8)                      # ST4_resume at +8
            if uc.reg_read(UC_M68K_REG_D1) & 0xFFFF == 0:
                break

    end = uc.reg_read(UC_M68K_REG_A1)
    produced = end - t.DST
    if produced != len(expected):
        return f'produced {produced} bytes, expected {len(expected)}'
    if bytes(uc.mem_read(t.DST, len(expected))) != expected:
        return 'output differs'
    for name, register, base, stream in (
            ('A', UC_M68K_REG_A0, t.SRC, control),
            ('D', UC_M68K_REG_A2, LITERAL, literal),
            ('B', UC_M68K_REG_A4, BYTE_OFFSETS, byte_offsets),
            ('C', UC_M68K_REG_A5, WORD_OFFSETS, word_offsets)):
        problem = consumed(name, uc.reg_read(register) - base, stream)
        if problem:
            return problem
    for register, canary in PRESERVED.items():
        if uc.reg_read(register) != canary:
            return 'a preserved register was clobbered'
    return ''


def main() -> int:
    chunks = [None, 1, 7, 16, 255] if not QUICK else [None, 16]
    failures = 0
    for unit in (1, 2, 4):
        code = assemble(unit)
        window = 32512 // unit                          # the byte window ST4 keeps
        for name, data, _ in t.testcases():
            padded = data + bytes(-len(data) % unit)
            control, literal, byte_offsets, word_offsets, size = pack(data, unit, window)
            if size != len(padded):
                print(f'FAIL k={unit} {name}: header says {size}, padded is {len(padded)}')
                failures += 1
                continue
            for chunk in chunks:
                problem = run(control, literal, byte_offsets, word_offsets,
                              padded, unit, code, chunk)
                if problem:
                    shape = 'one shot' if chunk is None else f'chunks of {chunk}'
                    print(f'FAIL k={unit} {name} ({shape}): {problem}')
                    failures += 1
        print(f'{"OK  " if not failures else "    "}k={unit}: {len(code)} bytes of decoder, '
              f'{len(t.testcases())} corpora x {len(chunks)} call shapes')

    print('ALL ST4 TESTS PASS' if not failures else f'{failures} FAILURES')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
