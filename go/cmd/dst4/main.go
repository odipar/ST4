// Command dst4 unpacks an ST4 container.
//
// It is the Go tool beside the Java org.st4.Dst4, and writes the same bytes
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
	return "DST4: aligned split-stream unpacker " + version + " by Robbert van Dalen," +
		" based on ZX1 v1.5 by Einar Saukas\n" +
		"Usage: dst4 [-rN] [-silent] < input.st4 > output\n" +
		"  -rN     Play a looping stream's loop N times: the whole pass, then\n" +
		"          N-1 repeats of its loop section (default 1, the pass)\n" +
		"  -silent Leave the report off standard error\n" +
		"The output is padded to a whole number of units, as the format stores it."
}

// count is a count and its noun: one for a count of 1 and many for any
// other (tools.md, the report).
func count(n int, one, many string) string {
	if n == 1 {
		return fmt.Sprintf("%d %s", n, one)
	}
	return fmt.Sprintf("%d %s", n, many)
}

func fail(message string) {
	fmt.Fprintln(os.Stderr, "Error: "+message)
	os.Exit(1)
}

func main() {
	silent := false
	passes := 1

	args := os.Args[1:]
	i := 0
	for ; i < len(args) && strings.HasPrefix(args[i], "-"); i++ {
		a := args[i]
		switch {
		case a == "-silent":
			silent = true
		case strings.HasPrefix(a, "-r"):
			v, err := strconv.Atoi(a[2:])
			if err != nil || v < 1 {
				fail("Invalid parameter " + a)
			}
			passes = v
		default:
			fail("Invalid parameter " + a)
		}
	}
	if i != len(args) {
		fmt.Fprintln(os.Stderr, usage())
		os.Exit(1)
	}

	file, err := io.ReadAll(os.Stdin)
	if err != nil {
		fail("Cannot read standard input")
	}

	container, err := st4.Read(file)
	if err != nil {
		fail(err.Error())
	}
	size := container.Size
	decoded, err := st4.Decode(container.Control, container.Literal,
		container.ByteOffsets, container.WordOffsets, container.Unit, size,
		container.Window, container.Rewind)
	if err != nil {
		fail(err.Error())
	}
	output := decoded.Output
	switch {
	case passes == 1:
	case decoded.RepeatIndex >= 0:
		// A repeating stream decodes to any size from one pass up: the pass,
		// then the loop again for every repeat asked for.
		loop := size - decoded.RepeatIndex*container.Unit
		want := size + (passes-1)*loop
		output, err = st4.DecompressWindow(container.Control, container.Literal,
			container.ByteOffsets, container.WordOffsets, container.Unit, want,
			container.Window)
		if err != nil {
			fail(err.Error())
		}
	case container.Rewind != st4.NoRewind:
		// A stream that loops by rewind is replayed by the caller, which
		// restores the decoder's state at the end of each pass (SPEC.md
		// 6.3), so every pass after the first is the output from the rewind
		// point, in bytes, to the end.
		pass := output
		loop := len(pass) - container.Rewind
		output = make([]byte, len(pass)+(passes-1)*loop)
		copy(output, pass)
		for at := len(pass); at < len(output); at += loop {
			copy(output[at:], pass[container.Rewind:])
		}
	default:
		fail(fmt.Sprintf("The stream does not loop, and -r%d repeats a loop", passes))
	}

	if _, err := os.Stdout.Write(output); err != nil {
		fail("Cannot write standard output")
	}
	if !silent {
		// The line of the Java and C# trees, word for word.
		whole := ""
		if container.Unit != 1 {
			whole = " (a whole number of units)"
		}
		loop := ""
		if decoded.RepeatIndex >= 0 {
			loop = fmt.Sprintf(", looping from unit %d", decoded.RepeatIndex)
		} else if container.Rewind != st4.NoRewind {
			loop = fmt.Sprintf(", looping from unit %d by rewind", container.Rewind/container.Unit)
		}
		played := ""
		if passes != 1 {
			played = fmt.Sprintf(", played %d times", passes)
		}
		fmt.Fprintf(os.Stderr, "File decompressed from %d to %s, k=%d%s%s%s!\n", len(file),
			count(len(output), "byte", "bytes"), container.Unit, whole, loop, played)
	}
}
