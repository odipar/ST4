# nt4 for C#

`nt4` is the .NET 10 workbench for the ST4 project and a port of the Java
`st4` tools. It packs and unpacks ST4 containers and independently mirrors the
compressor, the readable reference optimizer, the two fast ones and the
reference decoder the 68000 code is verified against.

## Build and test

Run these commands from the repository root with the .NET 10 SDK:

```sh
dotnet build csharp/Nt4.slnx -c Release
dotnet test csharp/Nt4.slnx -c Release
```

The tests are the Java suite, corpus for corpus - `java.util.Random` is
replicated so the fixtures are byte-identical - covering round trips at every
unit size, the container format's guarantees, offset windows and the limit-
checking decoder, operation splitting, the ZX1 golden sizes, and both fast
optimizers held to the reference parse.

## Command-line tools

```sh
dotnet run --project csharp/src/Nt4.Cli -- [-f] [-c[S]] [-kK] [-mN] [-lN] [-rR] input [output.st4]
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
byte[] container = Nt4.Nt4.Container(result);

Format.Container read = Format.Read(container);
byte[] restored = Decompressor.Decompress(read.Control, read.Literal,
    read.ByteOffsets, read.WordOffsets, read.Unit, read.Size);
```

`Nt4.Nt4.Container` needs both names because the class and the namespace share
one: inside a `using Nt4;` file the bare `Nt4` binds to the namespace, and the
compiler goes looking for a type that is not there.

`EventOptimizer` is the CLI's default engine and falls back to
`FastOptimizer` on run-churny data; `Optimizer` is the readable reference both
are checked against. Malformed containers and streams throw
`InvalidDataException`; argument errors use the standard argument exceptions.

## Projects

| Project | Purpose |
|---|---|
| `csharp/src/Nt4` | Reusable packer and unpacker library |
| `csharp/src/Nt4.Cli` | `nt4` packer executable |
| `csharp/src/Dnt4.Cli` | `dnt4` unpacker executable |
| `csharp/tests/Nt4.Tests` | Compatibility and behavior tests |

## Origin and attribution

The ZX1 format and original C implementation were designed and implemented by
Einar Saukas (Copyright © 2021), with thanks to introspec/spke. The ST4 format
and project additions are Copyright © 2026 Robbert van Dalen. The Java
implementation and this C# port were written by Claude (Anthropic's Claude
Code) under Robbert's direction. The port uses the repository's
[ST4/ZX1 dual license](../LICENSE).
