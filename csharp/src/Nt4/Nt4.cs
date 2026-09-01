// ZX1 by Einar Saukas; ST4 and this C# port by Claude (Anthropic's Claude
// Code) under Robbert van Dalen's direction. See LICENSE for the terms.

using System.Globalization;

namespace Nt4;

/// <summary>
/// Command-line ST4 packer: ZX1's blocks at a chosen unit granularity, with
/// the literal payload in a stream of its own.
/// </summary>
/// <remarks>
/// <c>Nt4</c> is the C# counterpart of the Java <c>St4</c> entry point.
/// <c>-k1</c> is ZX1's granularity and should pack to within a byte or two of
/// jx1; <c>-k2</c> and <c>-k4</c> trade ratio for a decoder that runs half or
/// a quarter as many operations and copies two or four bytes at a time.
/// </remarks>
public static class Nt4
{
    /// <summary>Runs the packer command.</summary>
    /// <param name="args">
    /// Arguments after the executable name. Syntax:
    /// <c>nt4 [-f] [-kK] [-mN] [-lN] input [output.st4]</c>.
    /// </param>
    /// <returns>Zero on success; one after a user-facing argument or file error.</returns>
    /// <exception cref="ArgumentNullException"><paramref name="args"/> is null.</exception>
    public static int Run(string[] args)
    {
        ArgumentNullException.ThrowIfNull(args);
        Console.WriteLine("NT4: aligned split-stream packer v4.0 by Robbert van Dalen, "
            + "based on ZX1 v1.5 by Einar Saukas");

        int unit = 1;
        int offsetLimit = Format.MaxOffset;
        int maxOpLength = Format.MaxOp;
        int repeatIndex = -1;
        bool copies = false;
        bool forcedMode = false;
        int index = 0;
        for (; index < args.Length
            && args[index].StartsWith('-'); index++)
        {
            switch (args[index])
            {
                case "-f":
                    forcedMode = true;
                    break;
                case "-c":
                    copies = true;
                    break;
                default:
                    if (args[index].StartsWith("-r", StringComparison.Ordinal))
                    {
                        // An index, not a count: -r0 is valid and loops it all.
                        repeatIndex = Cli.ParseIndex(args[index][2..]);
                        if (repeatIndex < 0)
                        {
                            return Cli.Error($"Invalid parameter value {args[index][2..]}");
                        }
                        break;
                    }
                    int value = Cli.ParseNumber(args[index][2..]);
                    if (args[index].StartsWith("-k", StringComparison.Ordinal))
                    {
                        unit = value;
                    }
                    else if (args[index].StartsWith("-m", StringComparison.Ordinal))
                    {
                        offsetLimit = value;
                    }
                    else if (args[index].StartsWith("-l", StringComparison.Ordinal))
                    {
                        maxOpLength = value;
                    }
                    else
                    {
                        return Cli.Error($"Invalid parameter {args[index]}");
                    }
                    if (value <= 0)
                    {
                        return Cli.Error($"Invalid parameter value {args[index][2..]}");
                    }
                    break;
            }
        }

        string outputName;
        if (args.Length == index + 1)
        {
            outputName = args[index] + ".st4";
        }
        else if (args.Length == index + 2)
        {
            outputName = args[index + 1];
        }
        else
        {
            return Cli.Usage(
                "Usage: nt4 [-f] [-c] [-kK] [-mN] [-lN] [-rR] input [output.st4]\n"
                + "  -f      Force overwrite of output file\n"
                + "  -c      Let a match beyond the -m window copy from the\n"
                + "          literal stream; needs a decoder built with copies\n"
                + "  -kK     Unit size: 1, 2 or 4 bytes (default 1). Lengths and\n"
                + "          offsets count units, so the output is padded to a\n"
                + "          whole number of them\n"
                + "  -mN     Limit back-references to N units\n"
                + "  -lN     Split matches so no operation exceeds N units\n"
                + "  -rR     Loop: after the last unit, the output continues\n"
                + "          from unit R, forever");
        }
        string inputName = args[index];

        string problem = Format.CheckUnit(unit);
        if (problem.Length != 0)
        {
            return Cli.Error(problem);
        }
        // A word offset is stored pre-scaled, so the window is a byte figure:
        // reaching 32512 units at k=4 would not fit the word it is kept in.
        if (offsetLimit > Format.MaxOffsetUnits(unit))
        {
            offsetLimit = Format.MaxOffsetUnits(unit);
        }

        byte[] input;
        try
        {
            input = File.ReadAllBytes(inputName);
        }
        catch (Exception exception) when (Cli.IsFileException(exception))
        {
            return Cli.Error($"Cannot access input file {inputName}");
        }
        if (input.Length == 0)
        {
            return Cli.Error($"Empty input file {inputName}");
        }
        if (!forcedMode && Path.Exists(outputName))
        {
            return Cli.Error($"Already existing output file {outputName}");
        }

        int[] units = Units.Split(input, unit);
        if (repeatIndex >= units.Length)
        {
            return Cli.Error($"-r{repeatIndex} is not a unit of the input, which is "
                + $"{units.Length} units");
        }
        Compressor.Result result;
        int window = offsetLimit;
        if (repeatIndex >= 0 && units.Length - repeatIndex > offsetLimit)
        {
            // The loop is longer than the window, so no match can reach across
            // it and the decoder cannot loop it alone: the caller will replay
            // the stream from the state it saved at the loop point. For every
            // pass to see the same history, the loop is parsed on its own.
            int[] intro = units[..repeatIndex];
            int[] loop = units[repeatIndex..];
            result = Compressor.CompressRewinding(
                intro.Length == 0 ? null : Parse(intro, unit, offsetLimit, copies),
                Parse(loop, unit, offsetLimit, copies),
                units, unit, maxOpLength, repeatIndex, window);
        }
        else
        {
            // The loop fits the window, so the stream loops by itself: its end
            // becomes an endless match back to the loop point.
            result = Compressor.Compress(Parse(units, unit, offsetLimit, copies), units, unit,
                maxOpLength, repeatIndex, window);
        }

        try
        {
            File.WriteAllBytes(outputName, Container(result));
        }
        catch (Exception exception) when (Cli.IsFileException(exception))
        {
            return Cli.Error($"Cannot write output file {outputName}");
        }

        int padded = Units.PaddedLength(input.Length, unit);
        Console.WriteLine(string.Create(CultureInfo.InvariantCulture,
            $"Packed {input.Length} bytes{(padded == input.Length ? "" : $" padded to {padded}")} "
            + $"into {result.PackedSize} ({100.0 * result.PackedSize / input.Length:F1}%): "
            + $"A {result.Control.Length}, B {result.ByteOffsets.Length}, "
            + $"C {result.WordOffsets.Length}, D {result.Literal.Length}, "
            + $"{result.Operations} operations"
            + $"{(result.Copies == 0 ? "" : $", {result.Copies} copies from the literal stream")}"
            + $"{(repeatIndex < 0 ? "" : $", loops from unit {repeatIndex}")}"
            + $"{(result.RewindIndex < 0 ? "" : " by rewind")}"));
        if (result.RewindIndex >= 0)
        {
            Console.WriteLine($"The loop is longer than the -m{offsetLimit} window, so the "
                + $"decoder cannot loop it alone: save its state at unit {repeatIndex} and "
                + $"restore it at unit {units.Length}, every pass");
        }
        if (result.LongestOp > maxOpLength)
        {
            Console.WriteLine(
                $"Warning: longest operation is {result.LongestOp} units, over the "
                + $"-l{maxOpLength} limit: a literal run, which the format cannot split");
        }
        return 0;
    }

    /// <summary>
    /// The parse: the event-driven optimizer, or with <c>-c</c> the one that
    /// lets a match beyond the window copy from the literal stream, which is
    /// the readable reference and slow on large inputs.
    /// </summary>
    private static Block Parse(int[] units, int unit, int window, bool copies) =>
        copies ? LiteralCopyOptimizer.Optimize(units, unit, window, true)
            : EventOptimizer.Optimize(units, unit, window);

    /// <summary>
    /// Twenty-eight bytes of header, then A, B, C and D in order, each
    /// starting on a long boundary. Nothing says how long a stream is: it runs
    /// to the next - and D, the literal payload, runs to the end of the file,
    /// so it borders whatever the caller loads after the container.
    /// </summary>
    /// <remarks>
    /// Public because a container is also how other formats embed an ST4
    /// stream: a yx6 file holds up to fifty of them.
    /// </remarks>
    /// <param name="result">The four streams to lay out.</param>
    /// <returns>The complete container, header first.</returns>
    /// <exception cref="ArgumentNullException"><paramref name="result"/> is null.</exception>
    public static byte[] Container(Compressor.Result result)
    {
        ArgumentNullException.ThrowIfNull(result);
        int controlAt = Format.HeaderSize;                  // already a multiple of 4
        int byteAt = Align(controlAt + result.Control.Length);
        int wordAt = Align(byteAt + result.ByteOffsets.Length);
        int literalAt = Align(wordAt + result.WordOffsets.Length);
        byte[] file = new byte[literalAt + result.Literal.Length];

        PutLong(file, Format.OffsetSignature, Format.Signature(result.Unit));
        PutLong(file, Format.OffsetSize, result.PaddedSize);
        PutLong(file, Format.OffsetLiteral, literalAt);
        PutLong(file, Format.OffsetByteOffsets, byteAt);
        PutLong(file, Format.OffsetWordOffsets, wordAt);
        PutLong(file, Format.OffsetRewind, result.RewindIndex < 0
            ? Format.NoRewind : result.RewindIndex * result.Unit);
        PutLong(file, Format.OffsetWindow, result.Window);
        result.Control.CopyTo(file, controlAt);
        result.Literal.CopyTo(file, literalAt);
        result.ByteOffsets.CopyTo(file, byteAt);
        result.WordOffsets.CopyTo(file, wordAt);
        return file;
    }

    private static int Align(int at) => at + ((-at) & 3);

    private static void PutWord(byte[] file, int at, int value)
    {
        file[at] = unchecked((byte)(value >>> 8));
        file[at + 1] = unchecked((byte)value);
    }

    private static void PutLong(byte[] file, int at, int value)
    {
        PutWord(file, at, value >>> 16);
        PutWord(file, at + 2, value);
    }
}
