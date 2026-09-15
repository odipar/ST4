# House style

Rules for prose: documents, code comments, commit messages. Each appears once.

## Plain words

Standard terms, not coinages. A compound that says one thing twice is a term
the reader has to decode, and the plain description is shorter than the
compound it replaces.

A noun pressed into service as a verb is the same fault. A repository does
not *vendor* a library: it carries a copy of one, and the copy is what the
sentence is about. Write what happened - copied here, carried here, kept
here - and the reader needs no glossary.

## One vocabulary

Where a project defines its terms, those are the names. A term that changes in
the glossary changes in the code the same day, or there are two vocabularies
to keep in step.

## Programs do not intend

No file, program or algorithm wants, knows, decides, expects or refuses. The
plain verb is there: a source *needs*, a header *declares*, a stage
*resolves*, a reader *does not validate*.

Roles and abstractions follow the rule. A writer does not *promise*, a
document section does not *keep* bits, a verb does not *consume* its
operand, bits do not *stand as they were*. The writer emits, the section
lists, the verb reads, the bits keep their value.

Established technical vocabulary is not this. A resource has an *owner*, a
caller *claims* it, a register *survives* a call.

A thing does no human act either. It does not *say*, *ask*, *tell*, *want*,
*lean on*, *keep to*, *hand over*, *work out* or *would rather*. Formal
equivalents: a field *is* a value, a check *requires* a whole number, a
player *assumes* a rule, a document *defines* an operation, a reader
*verifies* a column.

## No possessive decoration

`a table of its own` is `a table`, and `the two chips' own figures` are `the
figures of the two chips`. Drop *own* wherever the sentence stands without
it, and prefer *each*, *separate* or *a* to a possessive: `each timer is a
separate object`, `each source opens a table`.

## Formal and short

State the fact in as few words as carry it. `Each one says which version of
this format it reads` is `A player pins a version of this format`. Cut a
clause that adds a person's viewpoint: `for a reader who would rather open
one in a spreadsheet` is `for reading in a spreadsheet`.

## Say it once

Four habits that say an idea twice:

- **three of a kind.** `no stale value, no zero, no bus cycle` - say what
  happens and stop.
- **the cleft.** `X is what makes Y` is `X makes Y`.
- **the restatement.** `- which is a compile-time edit` is `- a compile-time
  edit`. Drop `which is` where a comma already carries the appositive; keep it
  before a predicate (`which is true whether…`) or an explanation (`which is
  why…`).
- **filler.** `simply`, `actually`, `precisely`, `entirely`, `at all` - cut
  unless the word carries the meaning: `exactly` for an equality, `entirely in
  memory` for the absence of a file on disk.

Keep a list only where each item carries something the others do not.

## No flourish

Technical prose says what happens and ends. Three habits that decorate
instead:

- **the sweep.** `whatever value is written`, `wherever it sits` - a
  trailing clause that generalises what the sentence already said. Name the
  condition or end the sentence.
- **the metaphor.** `leaves a tail no reader ever touches`, `the pressure
  point`, `the structure written down` - an image in place of the
  operation. Write the operation: a form encodes the structure.
- **the verdict.** `this is deliberate`, `asked properly`, `worth reading` -
  the sentence grading itself or its subject. Delete it.

## The verb that says the action

Something *uses* a resource, a bit *marks* a case, a code *selects* an option,
a field *is* the value it stands for. Reserve *names* for what a thing is
called.

Five stand-ins for the action are struck, and a test reads every document for
them:

- **holds.** `what a tune holds` is `the tune data structure`. A table *has*
  columns, a register *keeps* a value, a file *has* tunes in it.
- **states.** `the rate the tune states` is `the tune's rate`. A row *sets* a
  register, a document *defines* a rule.
- **gives.** `what the two chips give` is `the figures of the two chips`. A
  chip does not give: a clock *counts*, a timer *counts* a period, and a
  column *is* one value a row.
- **takes.** `a value the register takes` is `a value that fits the register`,
  and `a reader takes any JSON of this shape` is `a reader reads any JSON of
  this shape`. A tick *reads* a row, a tool's flags *are* what they are, and
  the row that stops an effect *sets* its register back.
- **nothing.** `it states nothing about X` and `and no form is the format` are
  a negation standing where the sentence that says what is there belongs.

## One negative at a time

`a timer no row uses opens none` is a negation twice over, and the reader
has to undo both to learn what happens. Say what is there: `a timer any row
uses opens a table of its own`. Where a rule is about what is left alone, one
negative carries it: `a register the row does not set is absent`.

`no X` where the operation has a name is the same fault in one negative:
`a blank line belongs to no block and is not read` is `a reader skips a
blank line`; `writes no register` is `leaves every register as it is`;
`is not defined` is `is left to a later version`. A quoted message text
keeps its words.

## Plain names for sections

A heading says what the section is about in the words the reader would use.
`What is turned away` is `What is an error`. Where the project has a name for
the thing, that name goes in the heading: `JSON`, not `The text form`.

## Struck in review

Every remark on a sentence lands here on the day it is made, and in the ban
list of `HouseStyleTest` where a phrase can be matched. The log is what has
been struck so far, the rows above the rule shared with the other
repositories of the family and the rows below it struck here.

| struck | now |
|---|---|
| `what a tune holds` | `the tune data structure` |
| `the rate the tune states` | `the tune's rate` |
| `what the two chips give` | `the figures of the two chips` |
| `a value the register takes` | `a value that fits the register` |
| `a reader takes any JSON of this shape` | `a reader reads any JSON of this shape` |
| `it states nothing about how a tune is written down` | dropped; the paragraph says what is there |
| `A form is that structure written down, and no form is the format.` | `A form is an encoding of that structure.` |
| `What is turned away` | `What is an error` |
| `the text form`, `the table form` | `JSON`, `CSV` |
| `a timer no row uses opens none` | `each timer a row uses opens a table` |
| `Writing a tune down is a separate job, and a form does it.` | dropped; the paragraph below it defines a form |
| `A form is the structure written down` | `A form is an encoding of the structure`; `written down` is a metaphor for encoded, struck wherever it stood |
| `a blank line belongs to no block and is not read` | `a reader skips a blank line`; a `no X` where the operation has a name, struck wherever it stood |
| `Each player names the version of this it reads.` | `A player is a separate program, and pins a version of this format.` |
| `a table of its own` | `a table` |
| `for a reader who would rather open one in a spreadsheet` | `for reading in a spreadsheet` |
| `frames says how long every column is` | `frames is the length of every column` |
| `tool.say(...)`, `tool.says()` | `tool.report(...)`, `tool.reports()` |
| `each stream holding one kind of value` | `each stream has one kind of value` |
| `the ring holds only what the literals cannot give` | `the ring keeps only what the literals leave`; `holds` struck throughout both documents |
| `| stream | holds |` | `| stream | contents |` |
| `The header holds what the streams cannot give` | `The header records what the streams leave out` |
| `the header gives the rewind point` | `the header records the rewind point`; `gives` struck throughout |
| `four of every five take a counter-free ladder` | `four of every five run a counter-free ladder` |
| `ST4_init takes it in d3` | `ST4_init reads it in d3`; `takes` struck throughout |
| `The state is held in registers` | `The state is in registers` |
| `the two ring decoders hold d1 and d2` | `the two ring decoders keep d1 and d2` |
| `Each file states its contract` | `Each file defines its contract` |
| `the decoder holds no state for it: the window sits in its code` | `the decoder keeps no state for it: the window is in its code` |
| `exact for a given set of forced literals` | `exact for a fixed set of forced literals` |
| `no longer gives the best parse` | `no longer finds the best parse` |
| `its own literal stream`, `the pass's own rate`, `its own ring`, `ST1's own timing` | `the literal stream`, `the rate of the pass`, `a separate ring`, `ST1's timing`; the possessive struck throughout |
| `the loop is packed on its own` | `the loop is packed separately` |
| `complete on their own` | `complete in themselves` |
| `the ring can shrink to almost nothing` | `the ring can shrink far` |
| `the whole window and nothing to gain` | `the whole window already` |
| `nothing depends on where the ring or the literal stream is` | `the position of the ring and the literal stream is free` |
| `The tune data needs nothing for this` | `The tune data needs no change for this` |
| `what it measured` | `what the measurements read` |
| `whatever chains of matches the packer chooses` | `the chains of matches the packer chooses` |
| `all held to each other by tests` | `and tests check the three against each other` |
| `a 30-unit window can hold` | `a 30-unit window fits` |
| `those states do not record it` | `those figures do not record it` |
| `reaching 512 &minus; M literals` | `reaching 512 - M literals`; the minus sign is a dash |
| `Nt4.Nt4.Container carries both names` | `Nt4.Nt4.Container repeats the name` |
| the em dash in research.md's sources | a single `-`, as the house style sets |
| `consume n from the operation` | `subtract n from the operation` |
| `the parse exit takes one branch` | `the parse exit costs one branch` |
| `segments take the counted ladder` | `segments run the counted ladder` |
| `the calling convention promises to preserve` | `the calling convention preserves` |
| `repeated once guarantees one far match` | `repeated once forces one far match` |
| `the reference must refuse it there` | `the reference rejects it there` |
| `and still hold to its rewind point` | `and still stop at its rewind point`; `keep to` is struck with it |
| `256 distinct bytes hold no match at all` | `256 distinct bytes have no match` |
| `nothing has happened at this offset yet` | `a fresh offset: no state and no literal run yet` |
| `run whatever the cost` | `run at any cost`; `whatever` struck throughout |
| `writes no memory` | `leaves memory alone` |
| `a stream of nothing but flag bits` | `a stream of flag bits alone` |
| `0 for none` | `0 for a plain parse` |
| `each decoder carries its own copy` | `each decoder has a separate copy` |
| `-r asks for more of it` | `-r extends it` |
| `a stream decodes with any window at least its own` | `with any window at least as wide` |

## A specification defines operations

Describe what happens, in terms an implementer can check: what is written, in
what order, and what is left alone. Name no product, routine or source file -
an implementation follows the specification, not the other way round. A rule
that needs a cross-reference to be understood is not yet defined
operationally.

## True beats accurate

A sentence that is literally correct but implies something false is wrong. A
figure without the comparison that makes it meaningful misleads as much as a
wrong figure.

## Measure, do not recall

Check a claim against the thing it describes before writing it. Where a number
has to appear in prose, have a test read it back out and fail when the
sentence carrying it is reworded away.

## One idea per row

Two things in one table cell get a row each, and a heading that covers two
subjects gets split. A column means one thing from top to bottom; where a row
needs a different convention, the cell says so.

## Shape

Wrap at one width and keep it. Rewrap the paragraph you changed and no other:
a blanket reflow buries the words that moved.

No em dash construct anywhere: a dash that must stay is a single `-`.
