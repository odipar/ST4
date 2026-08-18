# nt4 for C#

`nt4` is the .NET 10 workbench for the ST4 project and a port of the Java
`st4` tools. It packs and unpacks ST4 containers and independently mirrors the
readable compressor, the two fast optimizers and the reference decoder the
68000 code is verified against.

## Build and test

Run these commands from the repository root with the .NET 10 SDK:

```sh
dotnet build csharp/Nt4.slnx -c Release
dotnet test csharp/Nt4.slnx -c Release
```

The tests are the Java suite, corpus for corpus - `java.util.Random` is
replicated so the fixtures are byte-identical - covering round trips at every
unit size, the container format's promises, offset windows and the limit-
checking decoder, operation splitting, the ZX1 golden sizes, and both fast
optimizers held to the reference parse.

## Command-line tools

```sh
dotnet run --project csharp/src/Nt4.Cli -- [-f] [-kK] [-mN] [-lN] input [output.st4]
dotnet run --project csharp/src/Dnt4.Cli -- [-f] input.st4 [output]
```

The arguments have the same meaning as the [Java tools](../README.md), and the
containers are interchangeable: what `st4` packs, `dnt4` unpacks, and the other
way round. To produce app-host executables named `nt4` and `dnt4`:

```sh
dotnet publish csharp/src/Nt4.Cli -c Release
dotnet publish csharp/src/Dnt4.Cli -c Release
```

## API

The `Nt4` library exposes the same core types as the Java implementation:

```csharp
using Nt4;

int[] units = Units.Split(input, 2);                        // k = 2
Block parse = EventOptimizer.Optimize(units, 2, Format.MaxOffsetUnits(2), false);
Compressor.Result result = Compressor.Compress(parse, units, 2, Format.MaxOp);
byte[] container = Nt4.Container(result);

Format.Container read = Format.Read(container);
byte[] restored = Decompressor.Decompress(read.Control, read.Literal,
    read.ByteOffsets, read.WordOffsets, read.Unit, read.Size);
```

`EventOptimizer` is the CLI's default engine and falls back to
`FastOptimizer` on run-churny data; `Optimizer` is the readable reference both
are checked against. Malformed containers and streams throw
`InvalidDataException`; argument errors use the standard argument exceptions.

## Projects

| Project | Purpose |
|---|---|
| `src/Nt4` | Reusable packer and unpacker library |
| `src/Nt4.Cli` | `nt4` packer executable |
| `src/Dnt4.Cli` | `dnt4` unpacker executable |
| `tests/Nt4.Tests` | Compatibility and behavior tests |

## Origin and attribution

The ZX1 format and original C implementation were designed and implemented by
Einar Saukas (Copyright © 2021), with thanks to introspec/spke. The ST4 format
and project additions are Copyright © 2026 Robbert van Dalen. The Java
implementation and this C# port were written by Claude (Anthropic's Claude
Code) under Robbert's direction. The port uses the repository's
[ST4/ZX1 dual license](../LICENSE).
