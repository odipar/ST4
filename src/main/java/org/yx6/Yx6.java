package org.yx6;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Command-line YM to yx6 packer: reads a YM5!/YM6! register dump and writes a
 * {@code .yx6} file that the 68000 {@code YX6.S} player streams through ST4.
 *
 * <p>The player covers the fourteen standard YM2149 registers and the YM
 * special effects - digidrums, SID voices, the sync-buzzer - extracted into
 * their own streams; only the never-implemented sinus-SID is dropped.
 */
public final class Yx6 {

    private Yx6() {}

    public static void main(String[] args) {
        // -meta: the YM header's strings and rate, one per line, for the
        // build scripts to carry into SNDH tags - no banner, no packing.
        if (args.length == 2 && args[0].equals("-meta")) {
            Ym6Reader.Song song;
            try {
                song = Ym6Reader.read(Files.readAllBytes(Path.of(args[1])));
            } catch (IOException | Ym6Reader.FormatException e) {
                throw error(args[1] + ": " + e.getMessage());
            }
            System.out.println(song.name().strip());
            System.out.println(song.author().strip());
            System.out.println(song.playerHz());
            return;
        }
        // -script: the compiled effect script, one line per acting frame -
        // the debugging window into what the v2 streams will carry.
        if (args.length == 2 && args[0].equals("-script")) {
            Ym6Reader.Song song;
            try {
                song = Ym6Reader.read(Files.readAllBytes(Path.of(args[1])));
            } catch (IOException | Ym6Reader.FormatException e) {
                throw error(args[1] + ": " + e.getMessage());
            }
            int loop = (int) Math.min(song.loopFrame(), Integer.MAX_VALUE);
            EffectScript.Result script = EffectScript.compile(song,
                    YmEffects.extract(song), loop < song.frames() ? loop : 0, 1);
            System.out.printf("%d frames, split %d%n", script.frames(), script.split());
            for (int f = 0; f < script.frames(); f++) {
                if (script.m()[f] == 0 && script.r7force()[f] == 0) {
                    continue;
                }
                StringBuilder line = new StringBuilder(
                        String.format("%6d  M=%02X", f, script.m()[f] & 0xFF));
                for (int c = 0; c < script.actions().length; c++) {
                    line.append(String.format(" A%d=%02X P%d=%3d", c + 1,
                            script.actions()[c][f] & 0xFF, c + 1,
                            script.counts()[c][f] & 0xFF));
                }
                System.out.printf("%s R7|=%02X%n", line,
                        script.r7force()[f] & 0xFF);
            }
            script.notes().forEach(n -> System.out.println("note: " + n));
            return;
        }
        System.out.println("YX6: YM chiptune packer v3.0 by Robbert van Dalen, "
                + "streaming ST4");

        int ringSize = Yx6Format.DEFAULT_RING_SIZE;
        int chunk = Yx6Format.DEFAULT_CHUNK;
        int unit = 0;                           // 0 until chosen: -kK, or the
        int loopFrame = -1;                     // tune's shape; -1 likewise
        boolean playOnce = false;
        boolean forcedMode = false;
        boolean sidResume = false;
        int startMin = 0;                       // the trim window, for zooming
        int startSec = 0;                       // in on a moment of a tune
        int startFrame = -1;
        int endFrame = -1;
        int frameCount = -1;
        int drumHz = YmEffects.MAX_TIMER_HZ;
        int i = 0;
        for (; i < args.length && args[i].startsWith("-"); i++) {
            switch (args[i]) {
                case "-f" -> forcedMode = true;
                case "-o" -> playOnce = true;
                case "-sidresume" -> sidResume = true;
                default -> {
                    if (args[i].startsWith("-drumhz")) {
                        drumHz = parseNumber(args[i].substring(7));
                    } else if (args[i].startsWith("-startframe")) {
                        startFrame = parseNumber(args[i].substring(11));
                    } else if (args[i].startsWith("-endframe")) {
                        endFrame = parseNumber(args[i].substring(9));
                    } else if (args[i].startsWith("-frames")) {
                        frameCount = parseNumber(args[i].substring(7));
                    } else if (args[i].startsWith("-min")) {
                        startMin = parseNumber(args[i].substring(4));
                    } else if (args[i].startsWith("-sec")) {
                        startSec = parseNumber(args[i].substring(4));
                    } else if (args[i].startsWith("-n")) {
                        ringSize = parseNumber(args[i].substring(2));
                    } else if (args[i].startsWith("-c")) {
                        chunk = parseNumber(args[i].substring(2));
                    } else if (args[i].startsWith("-k")) {
                        unit = parseNumber(args[i].substring(2));
                    } else if (args[i].startsWith("-l")) {
                        loopFrame = parseNumber(args[i].substring(2), true);
                    } else {
                        throw error("Invalid parameter " + args[i]);
                    }
                }
            }
        }

        // A trailing DIRECTORY collects a whole set: every argument before it
        // is an input, each packed with the identical configuration into
        // <dir>/<stem>.yx6 - the shape a multi-tune player needs, since one
        // player build serves one unit size and one workspace.
        if (args.length - i >= 2 && Files.isDirectory(Path.of(args[args.length - 1]))) {
            if (startMin != 0 || startSec != 0 || startFrame >= 0
                    || endFrame >= 0 || frameCount >= 0) {
                throw error("the trim options take one tune, not a set");
            }
            if (unit == 0) {
                unit = 2;               // uniform by construction: padding
            }                           // makes any shape fit, or fails loudly
            Path dir = Path.of(args[args.length - 1]);
            for (int input = i; input < args.length - 1; input++) {
                String stem = Path.of(args[input]).getFileName().toString()
                        .replaceAll("(?i)\\.ym$", "");
                packOne(args[input], dir.resolve(stem + ".yx6").toString(),
                        ringSize, chunk, unit, loopFrame, playOnce, forcedMode,
                        drumHz, sidResume, 0, 0, -1, -1, -1);
            }
            return;
        }

        String outputName;
        if (args.length == i + 1) {
            outputName = args[i] + ".yx6";
        } else if (args.length == i + 2) {
            outputName = args[i + 1];
        } else {
            usage("""
                    Usage: yx6 [-f] [-o] [-nN] [-cC] [-kK] [-lF] input.ym [output.yx6]
                           yx6 [options] one.ym two.ym more.ym output-dir/
                      -f      Force overwrite of output file
                      -o      Play once: pack no loop section
                      -nN     Ring size per stream, in bytes (default 960)
                      -cC     Values decoded per call, and the round-robin group
                              size (default 24; N mod C = 0, and C at
                              least the streams the tune decodes: 17 with
                              no timer channel, 21 for a YM tune, 25 for
                              one that uses all four)
                      -kK     ST4 unit size: 1, 2 or 4 (default 2). An odd
                              tune length or loop frame is padded with safe
                              duplicate frames - inaudible - to fit the unit.
                              The player must be built with the same ST4_UNIT
                      -lF     Loop from frame F, overriding the YM header
                      -minM -secS   Trim: drop everything before M:S, so a
                              moment deep in a long tune plays immediately
                      -drumhzH   The drum rate ceiling (default 25600): a drum
                              asking for a faster timer is downsampled to fit,
                              with a warning
                      -sidresume   The maxYMiser SID gap model: a released
                              SID's timer keeps counting and a re-arrival
                              resumes its phase. Default: the ym2149-rs
                              model, phase-zero restarts
                      -startframeF -endframeF -framesN   The same window in
                              frames: start, end, or a length cap

                    The input is a YM5!/YM6! dump, LHA-archived or already
                    unpacked - the reader tells them apart by itself. With a
                    trailing DIRECTORY, every argument before it is an input,
                    packed with the same configuration - the set one player
                    build can hold as subtunes.""");
            return;
        }
        packOne(args[i], outputName, ringSize, chunk, unit, loopFrame, playOnce,
                forcedMode, drumHz, sidResume, startMin, startSec, startFrame,
                endFrame, frameCount);
    }

    /** The whole pipeline for one tune: read, trim, pad, pack, write, report. */
    private static void packOne(String inputName, String outputName, int ringSize,
                                int chunk, int unit, int loopFrame, boolean playOnce,
                                boolean forcedMode, int drumHz, boolean sidResume,
                                int startMin, int startSec, int startFrame,
                                int endFrame, int frameCount) {
        // The floor only: how many streams a tune decodes depends on the
        // channels it names, which the encoder learns when it compiles the
        // script and checks again there.
        String problem = Yx6Format.checkShape(ringSize, chunk, Math.max(unit, 1),
                Yx6Format.STREAM_A0);
        if (!problem.isEmpty()) {
            throw error(problem);
        }

        byte[] input;
        try {
            input = Files.readAllBytes(Path.of(inputName));
        } catch (IOException e) {
            throw error("Cannot access input file " + inputName);
        }

        Path outputPath = Path.of(outputName);
        if (!forcedMode && Files.exists(outputPath)) {
            throw error("Already existing output file " + outputName);
        }

        Ym6Reader.Song song;
        try {
            song = Ym6Reader.read(input);
        } catch (Ym6Reader.FormatException e) {
            throw error(inputName + ": " + e.getMessage());
        }

        // The trim window: -minM -secS (or -startframeF) picks where to start,
        // -framesN (or -endframeF) how much to keep - everything before and
        // after is dropped, so a moment deep in a long tune plays immediately.
        // A loop frame inside the kept window is kept, adjusted; one outside
        // it makes the excerpt loop from its own start.
        int start = startFrame >= 0 ? startFrame
                : (startMin * 60 + startSec) * song.playerHz();
        int end = song.frames();
        if (endFrame >= 0) {
            end = Math.min(end, endFrame);
        }
        if (frameCount >= 0) {
            end = Math.min(end, start + frameCount);
        }
        if (start > 0 || end < song.frames()) {
            if (start < 0 || start >= end) {
                throw error("Empty trim window: frames " + start + ".." + end
                        + " of " + song.frames());
            }
            byte[][] cut = new byte[song.registers().length][];
            for (int r = 0; r < cut.length; r++) {
                cut[r] = java.util.Arrays.copyOfRange(song.registers()[r], start, end);
            }
            long loop = song.loopFrame() >= start && song.loopFrame() < end
                    ? song.loopFrame() - start : 0;
            song = new Ym6Reader.Song(song.format(), end - start, song.playerHz(),
                    song.masterClock(), loop, song.interleaved(), song.attributes(),
                    song.drums(), song.name(), song.author(), song.comment(), cut);
            System.out.printf("Trimmed to frames %d-%d: %d frames%n",
                    start, end - 1, end - start);
        }

        // The YM header's loop frame is the default; -lF overrides it and -o
        // drops the loop altogether.
        if (loopFrame < 0 && !playOnce) {
            loopFrame = (int) Math.min(song.loopFrame(), Integer.MAX_VALUE);
            if (loopFrame >= song.frames()) {
                System.out.printf("Warning: the YM loop frame is %d in a %d-frame tune; "
                        + "looping from the start instead%n", loopFrame, song.frames());
                loopFrame = 0;
            }
        }
        if (playOnce) {
            loopFrame = -1;
        }

        // The default unit is 2, measured a few percent cheaper per frame for
        // little ratio. A tune whose length or loop frame is odd is PADDED to
        // the shape: a duplicated frame holds the chip state one tick longer,
        // which is inaudible as long as the duplicate neither writes R13 (an
        // envelope restart) nor triggers a drum - the packer scans for a safe
        // frame and says what it did. An explicit -kK pads the same way and
        // fails loudly only when no safe frame exists.
        if (unit == 0 && chunk % 2 == 0) {
            Ym6Reader.Song padded = padToUnit(song, loopFrame, 2);
            if (padded != null) {
                if (padded != song) {
                    song = padded;
                    loopFrame = loopFrame > 0 ? (int) song.loopFrame() : loopFrame;
                }
                unit = 2;
            } else {
                unit = 1;
                System.out.println("Packing at -k1: this tune's shape is not "
                        + "a whole number of 2-byte units, and no frame near "
                        + "the boundary is safe to duplicate");
            }
        } else if (unit == 0) {
            unit = 1;
        } else if (unit > 1) {
            Ym6Reader.Song padded = padToUnit(song, loopFrame, unit);
            if (padded != null && padded != song) {
                song = padded;
                loopFrame = loopFrame > 0 ? (int) song.loopFrame() : loopFrame;
            }
        }

        Yx6Encoder.Result result;
        try {
            result = Yx6Encoder.encode(song, ringSize, chunk, loopFrame, true, unit,
                    drumHz, sidResume);
        } catch (IllegalArgumentException e) {
            // The encoder always says what it rejected, but getMessage() is
            // @Nullable, so give it something to fall back on.
            String reason = e.getMessage();
            throw error(reason != null ? reason : "cannot pack this tune with these options");
        }
        try {
            Files.write(outputPath, result.file());
        } catch (IOException e) {
            throw error("Cannot write output file " + outputName);
        }

        report(song, result);
    }

    /**
     * Pads the tune so its length and loop split are whole {@code unit}s, by
     * duplicating safe frames: one that neither writes R13 nor triggers a
     * drum can hold the chip state one tick longer without being heard. The
     * split is evened by duplicating a safe frame inside the intro, the
     * length by duplicating one at the tail. Returns the song itself when
     * the shape already fits, the padded song otherwise - or null when no
     * safe frame exists within 64 frames of a boundary that needs one.
     */
    static Ym6Reader.@Nullable Song padToUnit(Ym6Reader.Song song, int loopFrame, int unit) {
        int split = loopFrame > 0 ? loopFrame : 0;
        int splitPad = split > 0 ? (unit - split % unit) % unit : 0;
        int frames = song.frames() + splitPad;
        int endPad = (unit - frames % unit) % unit;
        if (splitPad == 0 && endPad == 0) {
            return song;
        }
        int atSplit = splitPad > 0 ? safeFrame(song, split - 1, 0) : -1;
        if (splitPad > 0 && atSplit < 0) {
            return null;
        }
        int atEnd = endPad > 0 ? safeFrame(song, song.frames() - 1,
                Math.max(split, song.frames() - 64)) : -1;
        if (endPad > 0 && atEnd < 0) {
            return null;
        }
        int total = song.frames() + splitPad + endPad;
        byte[][] out = new byte[song.registers().length][];
        for (int r = 0; r < out.length; r++) {
            byte[] v = song.registers()[r];
            byte[] cut = new byte[total];
            int at = 0;
            for (int f = 0; f < song.frames(); f++) {
                cut[at++] = v[f];
                if (f == atSplit) {
                    for (int d = 0; d < splitPad; d++) {
                        cut[at++] = v[f];
                    }
                }
                if (f == atEnd) {
                    for (int d = 0; d < endPad; d++) {
                        cut[at++] = v[f];
                    }
                }
            }
            out[r] = cut;
        }
        long loop = split > 0 ? split + splitPad : song.loopFrame();
        System.out.printf("Padded %d frame%s (duplicates of safe frames) so the "
                + "shape is whole %d-byte units%n", splitPad + endPad,
                splitPad + endPad == 1 ? "" : "s", unit);
        return new Ym6Reader.Song(song.format(), total, song.playerHz(),
                song.masterClock(), loop, song.interleaved(), song.attributes(),
                song.drums(), song.name(), song.author(), song.comment(), out);
    }

    /**
     * The nearest frame at or before {@code from} (not before {@code floor})
     * that is safe to duplicate: R13 quiet, no drum code in either slot's
     * effect field - a duplicated drum code would trigger the drum again.
     */
    private static int safeFrame(Ym6Reader.Song song, int from, int floor) {
        byte[][] r = song.registers();
        boolean ym6 = song.format().startsWith("YM6");
        for (int f = from; f >= Math.max(floor, from - 63); f--) {
            if ((r[13][f] & 0xFF) != 0xFF) {
                continue;                       // this frame restarts the
            }                                   // envelope: not twice
            int c1 = r[1][f] & 0xF0;
            int c3 = r[3][f] & 0xF0;
            boolean drum = ym6 ? (c1 & 0xC0) == 0x40 && (c1 & 0x30) != 0
                    || (c3 & 0xC0) == 0x40 && (c3 & 0x30) != 0
                    : (c3 & 0x30) != 0;
            if (!drum) {
                return f;
            }
        }
        return -1;
    }

    private static void report(Ym6Reader.Song song, Yx6Encoder.Result result) {
        System.out.printf("%s: %s%s%s%n", song.format(),
                song.name().isBlank() ? "(untitled)" : song.name(),
                song.author().isBlank() ? "" : " by " + song.author(),
                song.interleaved() ? "" : " (de-interleaved)");
        YmEffects.Extraction effects = result.effects();
        if (effects.samples().length > 0) {
            int bytes = 0;
            for (byte[] sample : effects.samples()) {
                bytes += sample.length + 1;
            }
            System.out.printf("%d digidrum%s, %d bytes%n", effects.samples().length,
                    effects.samples().length == 1 ? "" : "s", bytes);
        }
        if (effects.sinus() > 0) {
            System.out.printf("Warning: %d Sinus-SID frame%s dropped (unimplemented "
                    + "everywhere, the reference player included)%n",
                    effects.sinus(), effects.sinus() == 1 ? "" : "s");
        }
        if (effects.tooFast() > 0) {
            System.out.printf("Warning: %d effect frame%s dropped: timer above %d Hz%n",
                    effects.tooFast(), effects.tooFast() == 1 ? "" : "s",
                    YmEffects.MAX_TIMER_HZ);
        }
        if (effects.missingDrum() > 0) {
            System.out.printf("Warning: %d drum trigger%s dropped: no such sample%n",
                    effects.missingDrum(), effects.missingDrum() == 1 ? "" : "s");
        }
        for (String note : effects.notes()) {
            System.out.println("Warning: " + note);
        }

        int raw = song.frames() * Yx6Format.STREAMS;    // registers and effects alike
        System.out.printf("%d frames at %d Hz (%d:%02d), %d rings of %d bytes, %d per call%n",
                song.frames(), song.playerHz(),
                song.frames() / song.playerHz() / 60, song.frames() / song.playerHz() % 60,
                Yx6Format.STREAMS, result.ringSize(), result.chunk());
        System.out.println(result.loops()
                ? result.loopFrame() == 0
                        ? "Loops from the start"
                        : "Plays frames 0-" + (result.loopFrame() - 1)
                                + ", then loops from frame " + result.loopFrame()
                : "Plays once, then stops");
        String[] effectNames = {"M ", "X ", "T ", "A0", "P0", "A1", "P1",
                                "A2", "P2", "A3", "P3"};
        for (Yx6Encoder.Stream stream : result.streams()) {
            String name = stream.register() < Yx6Format.REGISTER_STREAMS
                    ? String.format("R%-2d", stream.register())
                    : effectNames[stream.register() - Yx6Format.REGISTER_STREAMS] + " ";
            System.out.printf("  %s %-5s %6d -> %6d bytes (%5.1f%%)%n", name,
                    stream.loop() ? "loop" : "intro", stream.frames(), stream.packedSize(),
                    100.0 * stream.packedSize() / stream.frames());
        }
        System.out.printf("Packed %d register bytes into %d (%.1f%%), file %d bytes%n",
                raw, result.packedSize(), 100.0 * result.packedSize() / raw, result.file().length);
        int flags = ((result.file()[Yx6Format.OFFSET_FLAGS] & 0xFF) << 8)
                | (result.file()[Yx6Format.OFFSET_FLAGS + 1] & 0xFF);
        System.out.printf("Player needs %d bytes of ring plus its state,"
                        + " and decodes %d of the %d streams%n",
                Yx6Format.STREAMS * result.ringSize(),
                Yx6Format.liveStreams(flags), Yx6Format.STREAMS);
        for (String note : result.script().notes()) {
            System.out.println(note);
        }

        if (result.longestOp() > 65535) {
            // A literal run, the one operation ZX1 cannot split. Only a tune
            // longer than 65535 frames with a register that never repeats can
            // reach this, and the 68000 decoder would mis-decode it.
            System.out.printf("Warning: longest operation is %d bytes, over the 65535 the "
                    + "68000 decoder can represent: do not play this file%n",
                    result.longestOp());
        }
    }

    private static RuntimeException error(String message) {
        System.err.println("Error: " + message);
        System.exit(1);
        throw new AssertionError("unreachable");
    }

    private static void usage(String text) {
        System.err.println(text);
        System.exit(1);
    }

    private static int parseNumber(String argument) {
        return parseNumber(argument, false);
    }

    /** Parses a numeric argument; {@code zeroAllowed} for a loop frame of 0. */
    private static int parseNumber(String argument, boolean zeroAllowed) {
        try {
            int value = Integer.parseInt(argument);
            if (value < 0 || (value == 0 && !zeroAllowed)) {
                throw error("Invalid parameter value " + argument);
            }
            return value;
        } catch (NumberFormatException e) {
            throw error("Invalid parameter value " + argument);
        }
    }
}
