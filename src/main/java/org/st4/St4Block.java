package org.st4;

import org.jspecify.annotations.Nullable;

/**
 * One block of a parse, chained to the block before it: the last block of a
 * parse is the parse.
 *
 * <p>{@code bits} is the cost of the chain up to and including this block,
 * {@code index} the last unit it covers, and {@code offset} its kind: zero is
 * a literal run, a positive offset a match that copies output from that many
 * units back, and a negative offset a copy from the literal stream, whose
 * source starts that many units back in the output and must be literal there.
 * The compressor turns the latter into an offset beyond the window. The one
 * value no copy can have, {@link #RUN}, marks a run block: the block's first
 * unit is a literal, and the rest repeat it.
 */
public record St4Block(int bits, int index, int offset, @Nullable St4Block chain) {

    /** The offset of a run block, which has none: one literal unit, repeated. */
    public static final int RUN = Integer.MIN_VALUE;
}
