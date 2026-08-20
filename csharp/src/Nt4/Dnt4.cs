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
/// be up to k-1 bytes longer, and this tool says so.
/// </remarks>
public static class Dnt4
{
    /// <summary>Runs the unpacker command.</summary>
    /// <param name="args">
    /// Arguments after the executable name. Syntax:
    /// <c>dnt4 [-f] input.st4 [output]</c>.
    /// </param>
    /// <returns>Zero on success; one after a user-facing argument, file, or data error.</returns>
    /// <exception cref="ArgumentNullException"><paramref name="args"/> is null.</exception>
    public static int Run(string[] args)
    {
        ArgumentNullException.ThrowIfNull(args);
        Console.WriteLine("DNT4: aligned split-stream unpacker v1.1 by Robbert van Dalen, "
            + "based on ZX1 v1.5 by Einar Saukas");

        bool forcedMode = false;
        int index = 0;
        for (; index < args.Length
            && args[index].StartsWith('-'); index++)
        {
            if (args[index] == "-f")
            {
                forcedMode = true;
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
                "Usage: dnt4 [-f] input.st4 [output]\n"
                + "  -f      Force overwrite of output file\n"
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
        byte[] output;
        try
        {
            container = Format.Read(file);
            output = Decompressor.Decompress(container.Control, container.Literal,
                container.ByteOffsets, container.WordOffsets, container.Unit,
                container.Size);
        }
        catch (InvalidDataException exception)
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
            + $"k={container.Unit}{(container.Unit == 1 ? "" : " (a whole number of units)")}!");
        return 0;
    }
}
