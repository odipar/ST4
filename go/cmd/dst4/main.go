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
	note := ""
	if decoded.RepeatIndex < 0 && passes > 1 {
		fail(fmt.Sprintf("The stream does not loop, so -r%d has nothing to repeat", passes))
	}
	if decoded.RepeatIndex >= 0 && passes > 1 {
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
		note = fmt.Sprintf(", %d passes of a loop from unit %d", passes, decoded.RepeatIndex)
	} else if decoded.RepeatIndex >= 0 {
		note = fmt.Sprintf(", which loops from unit %d", decoded.RepeatIndex)
	}

	if _, err := os.Stdout.Write(output); err != nil {
		fail("Cannot write standard output")
	}
	if !silent {
		fmt.Fprintf(os.Stderr, "Unpacked %d bytes into %d%s\n",
			len(file), len(output), note)
	}
}
