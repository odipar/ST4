# Struck

What the style check strikes, under the AGENTS.md rule that strikes it.
The check, `org.st4.style.HouseStyle`, reads this file and then every
document and code comment in the tree against it. `mvn test` runs it, and
so does

    java -cp target/classes org.st4.style.HouseStyle

A section is one rule, and its heading is the rule's heading in AGENTS.md.
An entry is a name on a line of its own, then an indented block: the
pattern, a regular expression over lowered prose, on as many lines as it
takes; then `in:` lines, each a sample the pattern is in, and `not:` lines,
each a sample near it that the pattern is not in. HouseStyleTest reads
every sample back, so a pattern that drifts fails there rather than in
review. Add an entry to strike a construct, and take one off in the same
change that uses the construct.

Before the first rule, three entries are read differently. `names` lists
names a construct is spelled inside which are not that construct, each
blanked before a line is lowered; this tree has none, and the entry stands
empty. `carried` lists what the tree carries from another repository, by a
fragment of the path, and `own` what is read despite standing there, by the
end of the path. A carried copy follows its own tree's style.

names

carried
    /org/st4/style/
    /org/st4/doc/

own

## Plain words

A noun pressed into service as a verb: a repository carries a copy of a
library rather than vendoring one.

a noun as a verb
    vendor
    in: the repository vendors the library

## Programs do not intend

Roles and abstractions doing what a person does: a writer promising, a
source implying, a player being told, a role standing.

promising
    promise
    in: the writer promises a value

guaranteeing
    guarantee
    in: the header guarantees a count

implying
    implies
    in: the source implies a width

implying, the bare verb
    \bimply\b
    in: the rows imply a rate

being told
    can be told
    in: the reader can be told the width

a role standing
    roles stand
    in: the two roles stand apart

a format ruling
    it ruled
    in: the format read the value, and it ruled the rest out

a format measuring
    it measured
    in: the cost stands where it measured it

a format answering
    answered
    in: the format answered the constraint

a thing carrying
    carries
    in: the tune carries its rows

a thing sitting
    sits in
    in: the value sits in the register

a thing standing apart
    stand apart
    in: the two fields stand apart

a place keeping itself
    keeps its place
    in: the effect keeps its place

a place kept
    keeps the place
    in: the row keeps the place

standing where it was
    stands where it
    in: the place stands where it was

spelling out
    spells out
    in: the clause spells out the rule

spelling out, the bare verb
    spell out
    in: the tables spell out the map

a thing saying, quoted as a reason
    because it says
    in: the value is read because it says so

a thing saying it
    says it
    in: the header says it is packed

a thing saying so
    says so
    in: the clause says so

a thing saying what to do
    says what to take
    in: the flag says what to take

asking of
    asks of
    in: the call asks of the host

asking for
    asks for
    in: the reader asks for a row

set-ness
    set-ness
    in: the column's set-ness

taking the machine
    takes the machine with it
    in: the call takes the machine with it

consuming
    \bconsume\b
    in: the reader will consume a byte

consuming, the third person
    consumes
    in: the caller consumes the stream

consumed
    consumed
    in: the byte is consumed

consuming, the participle
    consuming
    in: consuming the row

standing as they were
    stand as they were
    in: the bits stand as they were

understanding
    understand
    in: the reader understands the stream

refusing
    refuse
    in: the writer refuses the value

## A specification defines operations

A document defines and cites; it rests on no other and props none up.

standing on another document
    \bstands on\b
    in: this clause stands on the one before

standing on another document, the plural
    \bstand on\b
    in: the two clauses stand on the first

the base
    is the base
    in: that document is the base of this

## Formal and short

A clause that adds a person's viewpoint.

a person's viewpoint
    would rather
    in: a reader would rather open it

## Say it once

The cleft and the filler: `X is what makes Y` is `X makes Y`, and a filler
is cut unless the word carries the meaning.

the cleft
    is what
    in: the count is what the timer reads

the cleft, the plural
    are what
    in: the rows are what the player reads

filler
    actually
    in: the value is actually read

## No flourish

The sweep, the metaphor and the verdict: a trailing clause that
generalises, an image in place of the operation, and the sentence grading
itself.

the sweep
    whatever
    in: whatever the value is

the sweep, either way
    whichever way
    in: whichever way it is read

the sweep, wherever it sits
    where it sits
    in: the byte is read where it sits

standing still
    stood still
    in: the counter stood still

a period in flight
    in flight
    in: the period in flight

a tail
    \ba tail\b
    in: the rows leave a tail of zeroes

a sliver
    sliver
    in: a sliver of the frame

literally
    literally
    in: the value is literally the row

a smear
    smear
    in: a smear across the frame

bearing it out
    bears it out
    in: the measurement bears it out

a pressure point
    pressure point
    in: the pressure point of the format

a door left open
    door left open
    in: a door left open for a later version

a cover version
    cover version
    in: a cover version of the tune

smuggling
    smuggl
    in: the flag smuggles a value through

a catastrophe
    catastroph
    in: a catastrophic read

the verdict, deliberate
    is deliberate
    in: the gap is deliberate

the verdict, by design
    by design
    in: the value is 0 by design

the verdict, on purpose
    on purpose
    in: the row is left unset on purpose

the verdict, asked properly
    asked properly
    in: the question asked properly

the verdict, not a shrug
    not a shrug
    in: this is not a shrug

the verdict, most of the point
    most of the point
    in: that is most of the point

the verdict, the answer to that
    the answer to that
    in: the answer to that is the table

the verdict, worth reading
    worth reading
    in: the clause is worth reading

the verdict, the ones that matter
    the ones that matter
    in: the ones that matter are the rows

the verdict, the whole point
    the whole point
    in: the whole point of the form

## The verb that says the action

The five stand-ins: `what a tune holds` is `the tune data structure`, and
`the rate the tune states` is `the tune's rate`. The verb forms stand here
rather than the stem, since a stem would match a word the rule leaves
alone.

holding
    \bhold\b
    in: the table will hold the rows

holding, the third person
    holds
    in: the table holds the rows

holding, the participle
    holding
    in: holding the rows

held
    held
    in: the value is held in the register

stating, the third person
    states
    in: the tune states its rate

stated
    stated
    in: the rate stated by the tune

stating, the participle
    stating
    in: stating the rate

giving
    gives
    in: the chip gives the figures

giving, the participle
    giving
    in: giving the figures

given
    given
    in: the figures given by the chip

taking
    \btake\b
    in: the register will take a value

taking, the third person
    \btakes\b
    in: the register takes a value

taking, the participle
    \btaking\b
    in: taking a value

taken
    \btaken\b
    in: the value taken by the register

nothing
    nothing
    in: it states nothing about the rate

writing down
    written down
    in: the structure written down

writing down, the infinitive
    write down
    in: a writer may write down the tune

writing down, the third person
    writes down
    in: the tool writes down the rows

writing down, the participle
    writing down
    in: writing down a tune

a tune down
    a tune down
    in: a writer puts a tune down

the structure down
    the structure down
    in: the form puts the structure down

putting down
    puts down
    in: the writer puts down the rows

## No possessive decoration

`a table of its own` is `a table`: drop *own* wherever the sentence stands
without it.

a possessive own
    \bown\b
    in: a table of its own row

a possessive own, ending the sentence
    \bown\b\.
    in: the figures are the chip's own.

## One negative at a time

`no X` where the operation has a name: `a blank line belongs to no block`
is `a reader skips a blank line`.

belonging to no
    belongs to no
    in: the line belongs to no block

performing no
    performs no\b
    in: the tick performs no write

writing no
    writes no\b
    in: the row writes no register

reaching no
    reaches no\b
    in: the value reaches no register

and no other
    and no other
    in: the first row and no other

for none
    for none
    in: the rule holds for none of them

on no chip
    on no chip
    in: the value stands on no chip

with no error
    with no error
    in: the file reads with no error

is not defined
    is not defined
    in: the field is not defined

not defined by
    not defined by
    in: the order is not defined by this

no other step
    no other step
    in: no other step writes it

## Shape

No em dash construct anywhere: a dash that must stay is a single `-`.

an em dash
    —
    in: the value — the row — is read

an en dash
    –
    in: rows 1 – 4

a minus sign
    −
    in: the count is − 1
