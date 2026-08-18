package org.yx6;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.st4.St4;
import org.st4.St4Compressor;
import org.st4.St4EventOptimizer;
import org.st4.St4Format;
import org.st4.Units;

/**
 * Turns a parsed YM tune into a {@code .yx6} file: fourteen register vectors,
 * masked down to what a plain YM2149 sees, each packed as its own embedded ST4
 * container at unit size 1.
 *
 * <p>Packing the registers separately is the whole point. A register's value
 * usually repeats from frame to frame, and a vector holds one register's values
 * back to back, so the matches are short-range and dense. It also gives the
 * player fourteen independent decoders it can advance one at a time, which is
 * what keeps the per-VBL cost flat.
 *
 * <p>A looping tune is packed as two sets of sections, split at the loop
 * frame. Looping means restarting a decoder, and a stream can only be
 * restarted from its beginning - so the frames from the loop point on become
 * sections of their own, which the player re-inits every time round. The split
 * costs a little ratio, since the loop half cannot reference the intro half,
 * and costs nothing at all for the common case of a tune that loops from
 * frame 0.
 *
 * <p>Each section is packed the way {@code st4} would with
 * {@code -k1 -mN -l65535}: offsets never reach further back than the ring the
 * player decodes through, and no single operation is longer than the word
 * counters in the 68000 decoder. Register vectors are exactly the event
 * engine's kind of data - long runs of an unchanging value - so packing is
 * effectively instant.
 */
public final class Yx6Encoder {

    /** What packing one register vector produced. */
    public record Stream(int register, boolean loop, int frames, int packedSize, int longestOp) {}

    /** The finished file plus the per-stream numbers the CLI reports. */
    public record Result(byte[] file, List<Stream> streams, int ringSize, int chunk,
                         int loopFrame, boolean loops, int unit) {

        public int packedSize() {
            return streams.stream().mapToInt(Stream::packedSize).sum();
        }

        /** The longest operation in any stream; over 65535 the file is unsafe for ST1. */
        public int longestOp() {
            return streams.stream().mapToInt(Stream::longestOp).max().orElse(0);
        }
    }

    private Yx6Encoder() {}

    /** Packs a tune that plays once and stops. */
    public static Result encode(Ym6Reader.Song song, int ringSize, int chunk) {
        return encode(song, ringSize, chunk, -1, true);
    }

    /**
     * Packs a tune, looping at {@code loopFrame} - or playing once and stopping
     * when {@code loopFrame} is negative. A loop frame of 0 means the whole
     * tune is the loop.
     */
    public static Result encode(Ym6Reader.Song song, int ringSize, int chunk,
                                int loopFrame) {
        return encode(song, ringSize, chunk, loopFrame, true);
    }

    /** A tune that plays once and stops, with the progress report turned off. */
    public static Result encode(Ym6Reader.Song song, int ringSize, int chunk,
                                boolean progress) {
        return encode(song, ringSize, chunk, -1, progress);
    }

    /**
     * As above, with the parser's progress report turned off. The fourteen
     * register streams are packed one after another, so what it reports is
     * progress through a stream rather than through the tune - worth watching
     * at a terminal, noise anywhere else.
     */
    public static Result encode(Ym6Reader.Song song, int ringSize, int chunk,
                                int loopFrame, boolean progress) {
        return encode(song, ringSize, chunk, loopFrame, progress, 1);
    }

    /**
     * As above, packing the sections at {@code unit} bytes per ST4 unit - the
     * player must then be built with the same {@code ST4_UNIT}, which it
     * verifies against each container's signature. Lengths and offsets are
     * whole units, so the tune length and the loop frame must be multiples of
     * {@code unit}: a padded section would decode one extra value into the
     * ring, and it would be played.
     */
    public static Result encode(Ym6Reader.Song song, int ringSize, int chunk,
                                int loopFrame, boolean progress, int unit) {
        String problem = Yx6Format.checkShape(ringSize, chunk, unit);
        if (!problem.isEmpty()) {
            throw new IllegalArgumentException(problem);
        }
        boolean loops = loopFrame >= 0;
        if (loops && loopFrame >= song.frames()) {
            throw new IllegalArgumentException("loop frame " + loopFrame
                    + " is not inside a tune of " + song.frames() + " frames");
        }
        // Without a loop the intro covers everything, which is the same thing
        // as looping at the end - so the player needs only one rule.
        int split = loops ? loopFrame : song.frames();
        if (song.frames() % unit != 0 || split % unit != 0) {
            throw new IllegalArgumentException("a tune of " + song.frames()
                    + " frames splitting at " + split + " cannot be packed in "
                    + unit + "-byte units: both must be multiples of " + unit);
        }

        // A back-reference may never reach out of the ring the player decodes
        // through - N bytes is N/unit units - and the format's own ceiling
        // still applies above that.
        int offsetLimit = Math.min(ringSize / unit, St4Format.maxOffsetUnits(unit));

        var streams = new ArrayList<Stream>(2 * Yx6Format.STREAMS);
        var intro = new byte[Yx6Format.STREAMS][];
        var loop = new byte[Yx6Format.STREAMS][];
        for (int register = 0; register < Yx6Format.STREAMS; register++) {
            byte[] values = Ym2149.mask(register, song.registers()[register]);
            intro[register] = pack(streams, register, false, progress,
                    Arrays.copyOfRange(values, 0, split), offsetLimit, unit);
            loop[register] = pack(streams, register, true, progress,
                    loops ? Arrays.copyOfRange(values, split, values.length) : new byte[0],
                    offsetLimit, unit);
        }

        byte[] file = build(song, ringSize, chunk, split, loops, intro, loop);
        return new Result(file, List.copyOf(streams), ringSize, chunk, split, loops, unit);
    }

    /**
     * Packs one section of one register as a complete ST4 container; an empty
     * section produces no container at all.
     */
    private static byte[] pack(List<Stream> streams, int register, boolean loop,
                               boolean progress, byte[] values, int offsetLimit,
                               int unit) {
        if (values.length == 0) {
            return new byte[0];
        }
        int[] units = Units.split(values, unit);
        St4Compressor.Result result = St4Compressor.compress(
                St4EventOptimizer.optimize(units, unit, offsetLimit, progress),
                units, unit, St4Format.MAX_OP);
        byte[] container = St4.container(result);
        streams.add(new Stream(register, loop, values.length, container.length,
                result.longestOp()));
        return container;
    }

    private static byte[] build(Ym6Reader.Song song, int ringSize, int chunk, int split,
                                boolean loops, byte[][] intro, byte[][] loop) {
        // Containers carry alignment promises of their own - stream A and D
        // are read a word at a time - so each is placed on a long boundary.
        int total = Yx6Format.HEADER_SIZE;
        for (byte[] container : intro) {
            total = align(total) + container.length;
        }
        for (byte[] container : loop) {
            total = align(total) + container.length;
        }

        byte[] file = new byte[align(total)];
        putLong(file, Yx6Format.OFFSET_MAGIC, Yx6Format.MAGIC);
        putWord(file, Yx6Format.OFFSET_VERSION, Yx6Format.VERSION);
        putWord(file, Yx6Format.OFFSET_FLAGS, loops ? Yx6Format.FLAG_LOOPS : 0);
        putLong(file, Yx6Format.OFFSET_FRAMES, song.frames());
        putWord(file, Yx6Format.OFFSET_PLAYER_HZ, song.playerHz());
        putWord(file, Yx6Format.OFFSET_STREAM_COUNT, Yx6Format.STREAMS);
        putWord(file, Yx6Format.OFFSET_RING_SIZE, ringSize);
        putWord(file, Yx6Format.OFFSET_CHUNK, chunk);
        putLong(file, Yx6Format.OFFSET_LOOP_FRAME, split);
        putLong(file, Yx6Format.OFFSET_MASTER_CLOCK, song.masterClock());

        int at = Yx6Format.HEADER_SIZE;
        at = place(file, Yx6Format.OFFSET_INTRO_TABLE, intro, at);
        place(file, Yx6Format.OFFSET_LOOP_TABLE, loop, at);
        return file;
    }

    /** Copies one table's containers into the file and fills in its offsets. */
    private static int place(byte[] file, int table, byte[][] containers, int at) {
        for (int register = 0; register < Yx6Format.STREAMS; register++) {
            if (containers[register].length == 0) {
                continue;                       // no such section: the offset stays 0
            }
            at = align(at);
            putLong(file, table + 4 * register, at);
            System.arraycopy(containers[register], 0, file, at, containers[register].length);
            at += containers[register].length;
        }
        return at;
    }

    private static int align(int at) {
        return at + ((-at) & 3);
    }

    private static void putWord(byte[] file, int at, int value) {
        file[at] = (byte) (value >>> 8);
        file[at + 1] = (byte) value;
    }

    private static void putLong(byte[] file, int at, long value) {
        putWord(file, at, (int) (value >>> 16));
        putWord(file, at + 2, (int) value);
    }
}
