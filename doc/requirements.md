# What ST4 has to do

Numbered so a change can name what it answers to. The format itself is
[SPEC.md](SPEC.md), which these requirements are about rather than repeat.

## R0. The house style and the terms

The format and its decoders are what this repository produces. How it is
written comes before what it describes, and what things are called comes
before both.

- **R0.1** `AGENTS.md` defines the rules, for every document, code comment
  and commit message.
- **R0.2** A test reads every document, and every code comment this
  repository writes, against a list of phrases struck in review, and names
  the file and line of each hit.
- **R0.3** The test walks the tree for documents. A document is checked
  because it is there, not because someone listed it.
- **R0.4** Striking a phrase adds it to the list, in the same change.
- **R0.5** Using a struck phrase again removes it from the list, in the
  same change.
- **R0.6** [glossary.md](glossary.md) lists every term and names the
  document that explains it.
- **R0.7** Every document, comment and name in this repository uses those
  terms, and no second word for a thing that has one.
- **R0.8** A term that changes in the glossary changes everywhere in the
  same change.
- **R0.9** A figure in a document is measured, and what measured it stands
  beside it or in [research.md](research.md).

## R1. The format

- **R1.1** One container stands for one output, a whole number of units
  long (SPEC.md 1.2).
- **R1.2** A container names the version, the unit, the output size, the
  window and where each stream begins, and a reader needs no other input
  (SPEC.md 2.1).
- **R1.3** A reader of one version reads a container of another as an
  error (SPEC.md 8.1).
- **R1.4** The bits of a block are a whole number of pairs with the flag,
  so a decoder tests for a refill on three reads rather than on every one
  (SPEC.md 3.8).
- **R1.5** An offset reaches at most 32512 bytes back, at every unit size
  (SPEC.md 4.3).
- **R1.6** A stream packed for a window of `N` units is read by a decoder
  keeping `N` units, and a stream packed without copies reads no further
  back (SPEC.md 4.5).
- **R1.7** A looping container stands for an infinite output, by a match
  that never ends or by a rewind the caller drives (SPEC.md 6).

## R2. What a packer does

- **R2.1** A packer writes a container a decoder of the same unit size
  reads, and every operation in it is within a 16-bit counter.
- **R2.2** A packer names the unit, the window, the operation limit and the
  loop point; every one of them is a flag (tools.md).
- **R2.3** What a packer writes for one input and one set of flags is one
  sequence of bytes, whichever tree runs it.
- **R2.4** A parse without copies is the cheapest the format allows for its
  flags, which an oracle over every parse checks on small inputs.
- **R2.5** A parse with copies is a search, since the optimum is NP-hard
  (tools.md, the search). What it reaches is measured rather than claimed.
- **R2.6** A packer reports what it wrote on standard error and leaves
  standard output for the container alone.

## R3. What a decoder does

- **R3.1** A decoder reads a container into the output it stands for, byte
  for byte, which an emulated 68000 checks against the reference
  decompressor over every corpus.
- **R3.2** A decoder is one build a unit size, and a build is the same
  bytes wherever it is assembled.
- **R3.3** A decoder does not check its input (SPEC.md 7.1).
- **R3.4** A decoder keeps its state in registers, and only `a6`, `d6` and
  `d7` survive a call (decoders.md, the state).
- **R3.5** A wide move never lands on an odd address, at any unit size
  (decoders.md, the state).
- **R3.6** A decoder built for copies reads the window out of the header
  and writes it into the instructions that read it, so one build a unit size serves
  every window (decoders.md, copies from the literal stream).

## R4. The three trees

- **R4.1** The Java tree is the reference; the Go and C# trees are ports of
  it.
- **R4.2** The three write the same bytes for the same input and flags,
  which a test reads back over inputs at every flag setting that changes
  the output.
- **R4.3** A change to the packer reaches all three in the same change.
- **R4.4** A release is built from one tree, since the three agree.

## R5. The releases

- **R5.1** A release names its version in the pom, and the zips and the Go
  module tag are named by it.
- **R5.2** A release ships an executable a tool a platform, with no runtime
  to install beside it.
- **R5.3** A release lists every file's size and sha256, and the commit it
  was built from.
- **R5.4** A release records whether a packer writes other bytes than the
  release before it, either way.
