// ZX1 by Einar Saukas; ST4 and this C# port by Claude (Anthropic's Claude
// Code) under Robbert van Dalen's direction. See LICENSE for the terms.

using System.Globalization;

namespace Nt4;

/// <summary>Shared command-line parsing and error-reporting helpers.</summary>
internal static class Cli
{
    /// <summary>Writes a Java-compatible error message and returns the failure exit code.</summary>
    internal static int Error(string message)
    {
        Console.Error.WriteLine($"Error: {message}");
        return 1;
    }

    /// <summary>Writes usage text and returns the failure exit code.</summary>
    internal static int Usage(string text)
    {
        Console.Error.WriteLine(text);
        return 1;
    }

    /// <summary>
    /// Parses a signed decimal integer. Invalid or overflowing values become
    /// zero so callers reject them the way the Java tools reject a parse failure.
    /// </summary>
    internal static int ParseNumber(string value) =>
        int.TryParse(value, NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture,
            out int number) ? number : 0;

    /// <summary>Identifies filesystem exceptions that the CLIs report without a stack trace.</summary>
    internal static bool IsFileException(Exception exception) =>
        exception is IOException or UnauthorizedAccessException or ArgumentException
            or NotSupportedException;
}
