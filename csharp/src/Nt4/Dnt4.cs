// ZX1 by Einar Saukas; ST4 and this C# port by Claude (Anthropic's Claude
// Code) under Robbert van Dalen's direction. See LICENSE for the terms.

namespace Nt4;

/// <summary>
/// Command-line ST4 unpacker: the counterpart to <see cref="Nt4"/>, and the C#
/// twin of the Java <c>Dst4</c> reference.
/// </summary>
/// <remarks>
/// What comes out is the <em>padded</em> data - a whole number of k-byte units
/// - because that is what the format stores and what the 68000 decoders write.
/// At <c>-k1</c> that is the input exactly; at <c>-k2</c> or <c>-k4</c> it can
/// be up to k-1 bytes longer, and this tool says so. For a stream that loops,
/// <c>-rN</c> plays the loop N times: the whole pass, and then N-1 repeats of
/// its loop section.
/// </remarks>
public static class Dnt4
{
    /// <summary>Runs the unpacker command.</summary>
    /// <param name="args">
    /// Arguments after the executable name. Syntax:
    /// <c>dnt4 [-f] [-rN] input.st4 [output]</c>.
    /// </param>
    /// <returns>Zero on success; one after a user-facing argument, file, or data error.</returns>
    /// <exception cref="ArgumentNullException"><paramref name="args"/> is null.</exception>
    public static int Run(string[] args)
    {
        ArgumentNullException.ThrowIfNull(args);
        Console.WriteLine("DNT4: aligned split-stream unpacker v4.0 by Robbert van Dalen, "
            + "based on ZX1 v1.5 by Einar Saukas");

        bool forcedMode = false;
        int times = 1;
        int index = 0;
        for (; index < args.Length
            && args[index].StartsWith('-'); index++)
        {
            if (args[index] == "-f")
            {
                forcedMode = true;
            }
            else if (args[index].StartsWith("-r", StringComparison.Ordinal))
            {
                times = Cli.ParseNumber(args[index][2..]);
                if (times <= 0)
                {
                    return Cli.Error($"Invalid parameter value {args[index][2..]}");
                }
            }
            else
            {
                return Cli.Error($"Invalid parameter {args[index]}");
            }
        }

        string inputName;
        string outputName;
        if (args.Length == index + 1)
        {
            inputName = args[index];
            if (inputName.Length > 4 && inputName.EndsWith(".st4", StringComparison.Ordinal))
            {
                outputName = inputName[..^4];
            }
            else
            {
                return Cli.Error("Cannot infer output filename");
            }
        }
        else if (args.Length == index + 2)
        {
            inputName = args[index];
            outputName = args[index + 1];
        }
        else
        {
            return Cli.Usage(
                "Usage: dnt4 [-f] [-rN] input.st4 [output]\n"
                + "  -f      Force overwrite of output file\n"
                + "  -rN     Play a looping stream's loop N times: the whole pass, then\n"
                + "          N-1 repeats of its loop section (default 1, the pass)\n"
                + "The output is padded to a whole number of units, as the format stores it.");
        }

        byte[] file;
        try
        {
            file = File.ReadAllBytes(inputName);
        }
        catch (Exception exception) when (Cli.IsFileException(exception))
        {
            return Cli.Error($"Cannot access input file {inputName}");
        }

        if (!forcedMode && Path.Exists(outputName))
        {
            return Cli.Error($"Already existing output file {outputName}");
        }

        Format.Container container;
        Decompressor.Decoded decoded;
        byte[] output;
        try
        {
            container = Format.Read(file);
            // One whole pass: a repeating stream would fill any size, but what
            // the container stores is the pass - and -r asks for more of it.
            decoded = Decompressor.Decode(container.Control, container.Literal,
                container.ByteOffsets, container.WordOffsets, container.Unit,
                container.Size, container.Window, container.Rewind);
            output = Played(container, decoded, times);
        }
        catch (InvalidDataException exception)
        {
            return Cli.Error($"{exception.Message}: {inputName}");
        }
        catch (ArgumentException exception)
        {
            return Cli.Error($"{exception.Message}: {inputName}");
        }

        try
        {
            File.WriteAllBytes(outputName, output);
        }
        catch (Exception exception) when (Cli.IsFileException(exception))
        {
            return Cli.Error($"Cannot write output file {outputName}");
        }

        Console.WriteLine($"File decompressed from {file.Length} to {output.Length} bytes, "
            + $"k={container.Unit}{(container.Unit == 1 ? "" : " (a whole number of units)")}"
            + $"{(decoded.RepeatIndex >= 0 ? $", looping from unit {decoded.RepeatIndex}"
                : container.Rewind < 0 ? ""
                : $", looping from unit {container.Rewind / container.Unit} by rewind")}"
            + $"{(times == 1 ? "" : $", played {times} times")}!");
        return 0;
    }

    /// <summary>
    /// The pass, and then <paramref name="times"/> - 1 repeats of its loop
    /// section: what a decoder driven past the end of a looping stream
    /// produces. A stream that loops by itself is decoded again to that
    /// length, since its repeat fills whatever the pass did not; a stream that
    /// loops by rewind is replayed as its caller would, and every pass sees
    /// the same history, so the replay is the pass's loop section again.
    /// </summary>
    /// <exception cref="ArgumentException">The stream does not loop and more than one pass is asked for.</exception>
    internal static byte[] Played(Format.Container container, Decompressor.Decoded pass, int times)
    {
        byte[] output = pass.Output;
        if (times == 1)
        {
            return output;
        }
        int unit = container.Unit;
        if (pass.RepeatIndex >= 0)
        {
            int loop = output.Length - pass.RepeatIndex * unit;
            return Decompressor.Decode(container.Control, container.Literal,
                container.ByteOffsets, container.WordOffsets, unit,
                output.Length + (times - 1) * loop, container.Window, container.Rewind).Output;
        }
        if (container.Rewind < 0)
        {
            throw new ArgumentException($"The stream does not loop, so -r{times} has nothing to repeat");
        }
        int section = output.Length - container.Rewind;
        byte[] result = new byte[output.Length + (times - 1) * section];
        output.CopyTo(result, 0);
        for (int at = output.Length; at < result.Length; at += section)
        {
            Array.Copy(output, container.Rewind, result, at, section);
        }
        return result;
    }
}
