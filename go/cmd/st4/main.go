// Command st4 packs a file into an ST4 container.
//
// It is the Go tool beside the Java org.st4.St4, and writes the same bytes
// for the same arguments.
package main

import (
	"fmt"
	"io"
	"os"
	"strconv"
	"strings"

	"github.com/odipar/st4/go/st4"
)

const version = "v7.0"

func usage() string {
	return "ST4: aligned split-stream packer " + version + " by Robbert van Dalen," +
		" based on ZX1 v1.5 by Einar Saukas\n" +
		"Usage: st4 [-c[S]] [-kK] [-mN] [-lN] [-pN] [-rR] [-silent]" +
		" < input > output.st4\n" +
		"  -c      Let a match beyond the -m window copy from the\n" +
		"          literal stream; needs a decoder built with copies\n" +
		"  -cS     The same, searching for S seconds for a better parse\n" +
		"  -kK     Unit size: 1, 2 or 4 bytes (default 1). Lengths and\n" +
		"          offsets count units, so the output is padded to a\n" +
		"          whole number of them\n" +
		"  -pN     Charge N bits on every block besides what it\n" +
		"          writes, so the parse prefers fewer, longer ones:\n" +
		"          a decoder parses a block at a time\n" +
		"  -mN     Limit back-references to N units\n" +
		"  -lN     Split matches so no operation exceeds N units\n" +
		"  -rR     Loop: after the last unit, the output continues\n" +
		"          from unit R, forever\n" +
		"  -silent Leave the report off standard error"
}

func fail(message string) {
	fmt.Fprintln(os.Stderr, "Error: "+message)
	os.Exit(1)
}

func number(text string) float64 {
	v, err := strconv.ParseFloat(text, 64)
	if err != nil || v < 0 {
		fail("Invalid parameter -" + text)
	}
	return v
}

func index(text string) int {
	v, err := strconv.Atoi(text)
	if err != nil || v < 0 {
		fail("Invalid parameter " + text)
	}
	return v
}

func main() {
	silent, copies := false, false
	unit, maxOpLength := 1, 65535
	offsetLimit, repeatIndex, penalty := 0, -1, 0
	search := 0.0
	limitNamed := false

	args := os.Args[1:]
	i := 0
	for ; i < len(args) && strings.HasPrefix(args[i], "-"); i++ {
		a := args[i]
		switch {
		case a == "-silent":
			silent = true
		case strings.HasPrefix(a, "-c"):
			copies = true
			if len(a) > 2 {
				search = number(a[2:])
			}
		case strings.HasPrefix(a, "-p"):
			penalty = index(a[2:])
		case strings.HasPrefix(a, "-k"):
			unit = index(a[2:])
		case strings.HasPrefix(a, "-m"):
			offsetLimit = index(a[2:])
			limitNamed = true
		case strings.HasPrefix(a, "-l"):
			maxOpLength = index(a[2:])
		case strings.HasPrefix(a, "-r"):
			repeatIndex = index(a[2:])
		default:
			fail("Invalid parameter " + a)
		}
	}
	if i != len(args) {
		fmt.Fprintln(os.Stderr, usage())
		os.Exit(1)
	}
	if message := st4.CheckUnit(unit); message != "" {
		fail(message)
	}
	if !limitNamed || offsetLimit == 0 {
		offsetLimit = st4.MaxOffsetUnits(unit)
	}

	input, err := io.ReadAll(os.Stdin)
	if err != nil {
		fail("Cannot read standard input")
	}
	if len(input) == 0 {
		fail("Empty input on standard input")
	}

	units := st4.Split(input, unit)
	if repeatIndex >= len(units) {
		fail(fmt.Sprintf("-r%d is not a unit of the input, which is %d units",
			repeatIndex, len(units)))
	}

	parse := func(part []uint32) *st4.Block {
		if copies {
			return st4.OptimizeCopies(part, unit, offsetLimit, maxOpLength, search, !silent)
		}
		if penalty != 0 {
			return st4.OptimizePenalty(part, unit, offsetLimit, !silent, penalty)
		}
		return st4.OptimizeEvents(part, unit, offsetLimit, !silent)
	}

	var result st4.Result
	if repeatIndex >= 0 && len(units)-repeatIndex > offsetLimit {
		// The loop is longer than the window, so no match reaches across it
		// and the caller replays the stream from the state it saved at the
		// loop point. The loop is parsed alone, so every pass sees the same
		// history.
		var intro *st4.Block
		if repeatIndex != 0 {
			intro = parse(units[:repeatIndex])
		}
		result = st4.CompressRewinding(intro, parse(units[repeatIndex:]), units,
			unit, maxOpLength, repeatIndex, offsetLimit)
	} else {
		result = st4.CompressRepeating(parse(units), units, unit, maxOpLength,
			repeatIndex, offsetLimit)
	}

	if _, err := os.Stdout.Write(result.Container()); err != nil {
		fail("Cannot write standard output")
	}
	if silent {
		return
	}

	padded := st4.PaddedLength(len(input), unit)
	note := ""
	if padded != len(input) {
		note = fmt.Sprintf(" padded to %d", padded)
	}
	tail := ""
	if result.Copies != 0 {
		tail = fmt.Sprintf(", %d copies from the literal stream", result.Copies)
	}
	if repeatIndex >= 0 {
		tail += fmt.Sprintf(", loops from unit %d", repeatIndex)
		if result.RewindIndex >= 0 {
			tail += " by rewind"
		}
	}
	fmt.Fprintf(os.Stderr, "Packed %d bytes%s into %d (%.1f%%): A %d, B %d, C %d, D %d, %d operations%s\n",
		len(input), note, result.PackedSize(),
		100.0*float64(result.PackedSize())/float64(len(input)),
		len(result.Control), len(result.Literal), len(result.ByteOffsets),
		len(result.WordOffsets), result.Operations, tail)
	if result.RewindIndex >= 0 {
		fmt.Printf("The loop is longer than the -m%d window, so the decoder cannot loop it"+
			" alone: save its state at unit %d and restore it at unit %d, every pass\n",
			offsetLimit, repeatIndex, len(units))
	}
	if result.LongestOp > maxOpLength {
		fmt.Printf("Warning: longest operation is %d units, over the -l%d limit:"+
			" a literal run, which the format cannot split\n",
			result.LongestOp, maxOpLength)
	}
}
