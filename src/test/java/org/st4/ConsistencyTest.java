package org.st4;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.st4.doc.Documents;
import org.st4.style.HouseStyle;
import org.junit.jupiter.api.Test;

/**
 * The documents against the tree: every pointer that can be followed, every
 * figure that can be measured again.
 *
 * <p>{@code HouseStyleTest} reads the prose against AGENTS.md. This reads the
 * numbers and the pointers, which drift as a document is edited: a clause
 * renumbered, a flag renamed, a figure left over from the measurement before
 * it. The checks came from DTX and YMXR, where each reads a document of that
 * repository.
 *
 * <p>Where a figure needs a tool the machine may lack, the check is skipped
 * rather than failed, and says which tool.
 */
final class ConsistencyTest {

    /**
     * Every line tools.md reports reads the same in the three trees: a
     * line reworded in one tree and the document, or in the document
     * alone, fails here. The letters a table writes for a figure, V or N
     * or K, and the figures a tool builds a line from are outside the
     * comparison, and this reads the words around them.
     *
     * <p>The check came from YMXR, where a release took a descriptor's
     * version to 2 in one clause and left another reading 1. Writing the
     * table for it found the Go tree unpacking a stream that does not
     * loop under -rN where the other two report it.
     */
    @Test
    void everyLineTheDocumentReportsReadsTheSameInTheTrees() throws IOException {
        String java = tree(Path.of("src/main/java/org/st4"), ".java");
        String go = tree(Path.of("go"), ".go");
        String sharp = tree(Path.of("csharp/src"), ".cs");
        int read = 0;
        for (String said : reported(Files.readString(TOOLS))) {
            String part = longest(said);
            if (part.isEmpty() || !java.contains(part)) {
                continue;
            }
            read++;
            assertTrue(go.contains(part),
                    "tools.md reports \"" + said + "\" and the Go tree lacks \"" + part + "\"");
            assertTrue(sharp.contains(part),
                    "tools.md reports \"" + said + "\" and the C# tree lacks \"" + part + "\"");
        }
        assertTrue(read >= 12, "tools.md reports " + read + " lines of the tools");
    }

    /** The longest run of words of a line between the figures a tool
     *  writes into it, and the empty text where the line is figures and
     *  short runs. */
    private static String longest(String said) {
        String longest = "";
        // a letter a table writes for a figure is a lone capital, one
        // with no letter after it and no capital before it, as V or N or
        // the N of -rN, or a run of digits between word boundaries
        for (String part : said.split("(?<![A-Z])[A-Z](?![A-Za-z])|\\bi\\b|\\b[0-9]+\\b")) {
            String one = part.strip();
            if (one.length() >= 12 && one.length() > longest.length()) {
                longest = one;
            }
        }
        return longest;
    }

    /** The lines the tables of a document report: the last cell of a row,
     *  each code span in it of three words or more. */
    private static List<String> reported(String document) {
        List<String> out = new ArrayList<>();
        Matcher row = Pattern.compile("^\\|(.*)\\|\\s*$", Pattern.MULTILINE)
                .matcher(document);
        while (row.find()) {
            String[] cells = row.group(1).split("\\|");
            if (cells.length < 2) {
                continue;
            }
            Matcher said = Pattern.compile("`([^`]+)`").matcher(cells[cells.length - 1]);
            while (said.find()) {
                String one = said.group(1);
                if (one.split("\\s+").length >= 3) {
                    out.add(one);
                }
            }
        }
        return out;
    }

    /** Every source of a tree, read as one text, a line built from two
     *  strings read as one. */
    private static String tree(Path at, String ending) throws IOException {
        StringBuilder out = new StringBuilder();
        try (java.util.stream.Stream<Path> found = Files.walk(at)) {
            for (Path one : found.filter(p -> p.toString().endsWith(ending)).toList()) {
                out.append(Files.readString(one)).append('\n');
            }
        }
        return out.toString().replaceAll("\"\\s*\\+\\s*\"", "")
                .replaceAll("\\$\"", "\"");
    }

    private static final Path README = Path.of("README.md");
    private static final Path SPEC = Path.of("doc/SPEC.md");
    private static final Path REQ = Path.of("doc/requirements.md");
    private static final Path GLO = Path.of("doc/glossary.md");
    private static final Path TOOLS = Path.of("doc/tools.md");
    private static final Path DECODERS = Path.of("doc/decoders.md");
    private static final Path RELEASES = Path.of("doc/RELEASES.md");

    /** The number words a clause counts a small figure in. */
    private static final List<String> WORD = List.of("zero", "one", "two",
            "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve");

    private static String read(Path p) throws IOException {
        return Files.readString(p);
    }

    /**
     * The documents these checks read: every one the tree writes, found the
     * way the style check finds them, so a document written under doc/ is read
     * here without a list to add it to.
     */
    private static List<Path> documents() throws IOException {
        return HouseStyle.documents(Path.of("."));
    }

    /** Prose as one line, so a figure is found however its sentence wraps. */
    private static String flat(String text) {
        return text.replaceAll("\\s+", " ");
    }

    /** Adds to {@code wrong} where {@code text} is missing {@code figure}. */
    private static void defines(List<String> wrong, String text, String figure,
            String what) {
        if (!flat(text).contains(figure)) {
            wrong.add(what + " should read \"" + figure + '"');
        }
    }

    // ------------------------------------------------------ the pointers

    @Test
    void everyLinkResolves() throws IOException {
        List<String> broken = Documents.links(documents());
        assertTrue(broken.isEmpty(), () -> String.join("\n", broken));
    }

    /** The clauses a document numbers, as {@code 2}, {@code 2.1} and so
     *  on: its numbered headings and the bold number that opens a clause. */
    private static Set<String> clauses(String document) {
        Set<String> out = new TreeSet<>();
        Matcher h = Pattern.compile("^#{1,4} (\\d+(?:\\.\\d+)*)\\.?\\s",
                Pattern.MULTILINE).matcher(document);
        while (h.find()) {
            out.add(h.group(1));
        }
        Matcher c = Pattern.compile("^\\*\\*(\\d+(?:\\.\\d+)*)\\b",
                Pattern.MULTILINE).matcher(document);
        while (c.find()) {
            out.add(c.group(1));
        }
        return out;
    }

    @Test
    void everyClauseCitedIsDefined() throws IOException {
        Set<String> clauses = clauses(read(SPEC));
        assertTrue(clauses.size() > 20,
                () -> "SPEC.md read as " + clauses.size() + " clauses");
        java.util.Map<Path, Set<String>> defined = new java.util.LinkedHashMap<>();
        List<String> dangling = new ArrayList<>();
        int found = 0;
        for (Path p : documents()) {
            if (p.getFileName().toString().equals("RELEASES.md")) {
                continue;   // what was true at a release keeps its words
            }
            String said = read(p);
            Matcher m = Pattern.compile("([A-Za-z_]+)\\.md\\)? (\\d+(?:\\.\\d+)*)"
                    + "(?:, (\\d+\\.\\d+))?").matcher(said);
            while (m.find()) {
                // A citation qualified with DTX or YMXR names that
                // repository's document, and so does one this repository
                // does not have; both are left alone.
                int open = said.lastIndexOf('(', Math.max(0, m.start() - 1));
                String before = open >= 0 && m.start() - open <= 120
                        ? said.substring(open, m.start())
                        : said.substring(Math.max(0, m.start() - 20), m.start());
                if (before.contains("DTX") || before.contains("YMXR")
                        || before.contains("YMXS")) {
                    continue;
                }
                Path in = Path.of("doc", m.group(1) + ".md");
                if (!Files.exists(in)) {
                    in = Path.of(m.group(1) + ".md");
                }
                if (!Files.exists(in)) {
                    continue;
                }
                if (!defined.containsKey(in)) {
                    defined.put(in, clauses(read(in)));
                }
                for (int g = 2; g <= m.groupCount(); g++) {
                    String at = m.group(g);
                    if (at == null) {
                        continue;
                    }
                    found++;
                    if (!defined.get(in).contains(at)) {
                        dangling.add(p + " cites " + m.group(1) + ".md " + at);
                    }
                }
            }
        }
        final int read = found;
        assertTrue(read > 30, () -> "only " + read
                + " citations read; the check is asleep");
        // SPEC.md points at the clauses of this document by number alone, in brackets
        Matcher inside = Pattern.compile("\\((\\d+\\.\\d+)(?:, (\\d+\\.\\d+))?"
                + "(?:, (\\d+\\.\\d+))?\\)").matcher(read(SPEC));
        while (inside.find()) {
            for (int g = 1; g <= inside.groupCount(); g++) {
                String cited = inside.group(g);
                if (cited != null && !clauses.contains(cited)) {
                    dangling.add("SPEC.md points at " + cited);
                }
            }
        }
        assertTrue(dangling.isEmpty(), () -> String.join("\n", dangling)
                + "\nSPEC.md numbers " + clauses);
    }

    @Test
    void everyRequirementCitedIsDefined() throws IOException {
        Set<String> defined = new TreeSet<>();
        Matcher d = Pattern.compile("^- \\*\\*(R\\d+\\.\\d+)\\*\\*",
                Pattern.MULTILINE).matcher(read(REQ));
        while (d.find()) {
            defined.add(d.group(1));
        }
        assertTrue(defined.size() > 20,
                () -> "requirements.md read as " + defined.size() + " requirements");
        List<String> dangling = new ArrayList<>();
        for (Path p : documents()) {
            String text = read(p);
            Matcher c = Pattern.compile("\\bR\\d+\\.\\d+\\b").matcher(text);
            while (c.find()) {
                // "YMXR's R4.5" is that repository's requirement, not one of these
                boolean foreign = c.start() > 3
                        && text.substring(0, c.start()).endsWith("'s ");
                if (!foreign && !defined.contains(c.group())) {
                    dangling.add(p + " cites " + c.group());
                }
            }
        }
        assertTrue(dangling.isEmpty(), () -> String.join("\n", dangling)
                + "\nrequirements.md defines " + defined);
    }

    /** Every row of the glossary's table: the term, what it is, and where. */
    @Test
    void theGlossaryIsInOrder() throws IOException {
        List<String[]> rows = Documents.glossaryRows(read(GLO));
        assertTrue(rows.size() > 5, () -> "the glossary read as " + rows.size()
                + " rows");
        List<String> wrong = Documents.outOfOrder(rows);
        assertTrue(wrong.isEmpty(), () -> String.join("\n", wrong));
    }

    @Test
    void everyGlossaryRowPointsSomewhereReal() throws IOException {
        Set<String> clauses = clauses(read(SPEC));
        List<String> bad = new ArrayList<>();
        for (String[] row : Documents.glossaryRows(read(GLO))) {
            String where = row[2];
            String file = where.split("[ ,;]")[0];
            if (!Files.exists(Path.of("doc", file))) {
                bad.add(row[0] + " points at " + file);
                continue;
            }
            Matcher m = Pattern.compile("^SPEC\\.md (\\d+(?:\\.\\d+)?)$")
                    .matcher(where);
            if (m.matches() && !clauses.contains(m.group(1))) {
                bad.add(row[0] + " points at SPEC.md " + m.group(1));
            }
        }
        assertTrue(bad.isEmpty(), () -> String.join("\n", bad)
                + "\nSPEC.md numbers " + clauses);
    }

    /** Every document under doc/ is a row of the README's table. */
    @Test
    void theReadmeNamesEveryDocument() throws IOException {
        String readme = read(README);
        List<String> missing = new ArrayList<>();
        try (var tree = Files.list(Path.of("doc"))) {
            for (Path p : tree.sorted().toList()) {
                String name = p.getFileName().toString();
                if (!name.endsWith(".md")) {
                    continue;
                }
                if (!readme.contains("(doc/" + name + ")")) {
                    missing.add("README.md does not link doc/" + name);
                }
            }
        }
        assertTrue(missing.isEmpty(), () -> String.join("\n", missing));
    }

    @Test
    void everyDocumentKeepsOneWrapWidth() throws IOException {
        List<Path> read = documents();
        assertTrue(read.size() > 5, () -> "only " + read.size()
                + " documents read; the check is asleep");
        List<String> wide = Documents.wide(read, 78);
        assertTrue(wide.isEmpty(), () -> String.join("\n", wide)
                + "\nAGENTS.md gives one wrap width, and a document keeps it.");
    }

    // -------------------------------------------------------- the format

    /**
     * SPEC.md 2.1's picture against {@link St4Format}. Every field the picture
     * draws is a constant, so a field moved in the code and left in the
     * picture fails here.
     */
    @Test
    void theHeaderPictureIsTheFormat() throws IOException {
        String spec = read(SPEC);
        Matcher version = Pattern.compile(
                "signature: 'S', '4', format version \\((\\d+)\\), k")
                .matcher(spec);
        assertTrue(version.find(), "SPEC.md 2.1 does not draw a signature");
        List<String> wrong = new ArrayList<>();
        if (Integer.parseInt(version.group(1)) != St4Format.VERSION) {
            wrong.add("the picture draws version " + version.group(1)
                    + ", and the format is " + St4Format.VERSION);
        }
        int[] fields = {St4Format.OFFSET_SIGNATURE, St4Format.OFFSET_SIZE,
                        St4Format.OFFSET_LITERAL, St4Format.OFFSET_BYTE_OFFSETS,
                        St4Format.OFFSET_WORD_OFFSETS, St4Format.OFFSET_REWIND,
                        St4Format.OFFSET_WINDOW};
        List<Integer> drawn = new ArrayList<>();
        Matcher row = Pattern.compile("^\\s*(\\d+)\\s+(?:4|\\.\\.)\\s{2}",
                Pattern.MULTILINE).matcher(spec);
        while (row.find()) {
            drawn.add(Integer.parseInt(row.group(1)));
        }
        List<Integer> want = new ArrayList<>();
        for (int at : fields) {
            want.add(at);
        }
        want.add(St4Format.HEADER_SIZE);
        if (!drawn.equals(want)) {
            wrong.add("the picture draws " + drawn + ", and the format is " + want);
        }
        defines(wrong, spec, "twenty-eight bytes of header", "2.1's header size");
        assertTrue(St4Format.HEADER_SIZE == 28,
                "the header is no longer twenty-eight bytes: 2.1 says the number in words");
        assertTrue(wrong.isEmpty(), () -> String.join("\n", wrong));
    }

    /** The bits of stream A, most significant first, as a string of 0 and 1. */
    private static String bitsOf(byte[] control, int count) {
        StringBuilder out = new StringBuilder();
        for (int at = 0; at < count; at++) {
            out.append((control[at / 8] >> (7 - at % 8)) & 1);
        }
        return out.toString();
    }

    /** What the packer writes for an input of {@code n} unmatchable units. */
    private static String openingGamma(int n) {
        int[] units = new int[n];
        for (int at = 0; at < n; at++) {
            units[at] = at;                       // every unit new: all literal
        }
        St4Block parse = St4EventOptimizer.optimize(units, 1, 512, false);
        St4Compressor.Result packed = St4Compressor.compress(parse, units, 1,
                St4Format.MAX_OP, -1, 512);
        // the run opens the stream, so its gamma opens stream A; the flag,
        // the end code and the repeat bit follow it
        return bitsOf(packed.control(), packed.controlBits() - 4);
    }

    /**
     * SPEC.md 3.3's four examples against the packer. A literal run opens the
     * stream, so the bits before its flag are the gamma of its length.
     */
    @Test
    void theGammaExamplesAreWhatThePackerWrites() throws IOException {
        String spec = read(SPEC);
        Matcher m = Pattern.compile("So 1 is `(\\d+)`, 2 is `(\\d+)`, 3 is "
                + "`(\\d+)`, 4 is `(\\d+)`\\.").matcher(flat(spec));
        assertTrue(m.find(), "SPEC.md 3.3 does not give the four examples");
        List<String> wrong = new ArrayList<>();
        for (int n = 1; n <= 4; n++) {
            String written = openingGamma(n);
            if (!written.equals(m.group(n))) {
                wrong.add("3.3 writes " + n + " as `" + m.group(n)
                        + "`, and the packer writes `" + written + '`');
            }
        }
        assertTrue(wrong.isEmpty(), () -> String.join("\n", wrong));
    }

    /** SPEC.md 4 and requirements.md R1.5 against the format's limits. */
    @Test
    void theOffsetLimitsAreTheFormats() throws IOException {
        String spec = read(SPEC);
        List<String> wrong = new ArrayList<>();
        defines(wrong, spec, "further back than " + St4Format.MAX_OFFSET
                + " bytes", "4.3's furthest offset");
        defines(wrong, read(REQ), "at most " + St4Format.MAX_OFFSET + " bytes back",
                "R1.5's furthest offset");
        int bank = St4Format.BYTE_OFFSET_LIMIT / 2;
        defines(wrong, spec, "1 to " + bank + " units back", "3.5's first bank");
        defines(wrong, spec, (bank + 1) + " to " + St4Format.BYTE_OFFSET_LIMIT
                + " units back", "3.5's second bank");
        defines(wrong, spec, "stored as the byte " + bank + "(b + 1) - n",
                "4.1's byte offset");
        defines(wrong, spec, "stored as 65536 - nk", "4.2's word offset");
        defines(wrong, read(GLO), "bank 0 reaches 1 to " + bank
                + " units back, bank 1 reaches " + (bank + 1) + " to "
                + St4Format.BYTE_OFFSET_LIMIT, "the glossary's bank");
        assertTrue(wrong.isEmpty(), () -> String.join("\n", wrong));
    }

    /**
     * SPEC.md 5.4's worked example against the packer. The clause draws one
     * input, one parse and one offset on the wire; the packer is the
     * authority for all three.
     */
    @Test
    void theCopyExampleIsWhatThePackerWrites() throws IOException {
        String spec = read(SPEC);
        Matcher shape = Pattern.compile("At `k` of 1 and `M` of (\\d+), an input"
                + " that repeats its first (\\w+) units (\\w+) units back")
                .matcher(flat(spec));
        assertTrue(shape.find(), "SPEC.md 5.4 does not set up an example");
        int window = Integer.parseInt(shape.group(1));
        int copied = WORD.indexOf(shape.group(2));
        int back = WORD.indexOf(shape.group(3));
        assertTrue(copied > 0 && back > 0,
                () -> "5.4 counts in words this does not read: " + shape.group(2)
                        + ", " + shape.group(3));

        byte[] input = "abcdefghabc".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int[] units = Units.split(input, 1);
        St4Block parse = St4LiteralCopySearch.optimize(units, 1, window,
                St4Format.MAX_OP, 0, false);
        St4Compressor.Result packed = St4Compressor.compress(parse, units, 1,
                St4Format.MAX_OP, -1, window);

        List<String> wrong = new ArrayList<>();
        if (packed.operations() != 2 || packed.copies() != 1) {
            wrong.add("the packer writes " + packed.operations() + " operations and "
                    + packed.copies() + " copies, and 5.4 draws two and one");
        }
        if (packed.literal().length != back) {
            wrong.add("the packer writes " + packed.literal().length
                    + " literals, and 5.4 draws " + shape.group(3));
        }
        St4Block last = parse;
        if (last.offset() >= 0 || last.index() + 1 != units.length) {
            wrong.add("the parse ends at " + last.index() + " with offset "
                    + last.offset() + ", and 5.4 draws a copy that ends the input");
        } else {
            St4Block before = last.chain();
            int length = before == null ? last.index() + 1
                    : last.index() - before.index();
            if (length != copied) {
                wrong.add("the packer copies " + length + " units, and 5.4 draws "
                        + shape.group(2));
            }
        }
        if (packed.byteOffsets().length != 1) {
            wrong.add("the packer writes " + packed.byteOffsets().length
                    + " byte offsets, and 5.4 draws one");
        } else {
            int stored = packed.byteOffsets()[0] & 0xFF;
            int offset = St4Format.BYTE_OFFSET_LIMIT / 2 - stored;
            if (offset != window + back) {
                wrong.add("the packer writes an offset of " + offset
                        + ", and 5.4 draws " + (window + back));
            }
            defines(wrong, spec, "offset = M + " + back + " = " + (window + back),
                    "5.4's offset on the wire");
            defines(wrong, spec, "After the copy the offset is " + (window + back)
                    + " - " + copied + " = " + (window + back - copied),
                    "5.4's offset after the copy");
        }
        assertTrue(wrong.isEmpty(), () -> String.join("\n", wrong));
    }

    @Test
    void theFormatVersionIsTheOneEveryDocumentNames() throws IOException {
        String version = String.valueOf(St4Format.VERSION);
        List<String> wrong = new ArrayList<>();
        defines(wrong, read(SPEC), "The version byte (2.1) is " + version + ".",
                "8.1's version");
        defines(wrong, read(TOOLS), "the format is " + version + " and the module",
                "tools.md's version");
        defines(wrong, read(RELEASES), "`v" + version + ".0` is format " + version,
                "RELEASES.md's format tag");
        assertTrue(wrong.isEmpty(), () -> String.join("\n", wrong));
    }

    // --------------------------------------------------------- the tools

    /** The usage text a tool prints, from "Usage:" to the end of the block. */
    private static String usageOf(String source) {
        Matcher m = Pattern.compile("Usage: .*?\"\"\"\\);", Pattern.DOTALL)
                .matcher(source);
        assertTrue(m.find(), "no usage text in the source");
        return m.group();
    }

    /** The flags a usage text names, as {@code -kK}, {@code -silent}. */
    private static Set<String> flagsOf(String source) {
        Set<String> out = new TreeSet<>();
        Matcher m = Pattern.compile("^ +(-[A-Za-z]+) +[A-Z]", Pattern.MULTILINE)
                .matcher(usageOf(source));
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /**
     * The flags the two tools print against the flags tools.md tabulates. A
     * reader copies a flag out of the table, so one renamed in the tool and
     * left in the table fails here.
     */
    @Test
    void everyFlagIsInBothTheToolAndTheTable() throws IOException {
        Set<String> printed = new TreeSet<>();
        printed.addAll(flagsOf(read(Path.of("src/main/java/org/st4/St4.java"))));
        printed.addAll(flagsOf(read(Path.of("src/main/java/org/st4/Dst4.java"))));
        assertTrue(printed.size() > 5,
                () -> "the two usage texts read as " + printed);

        Set<String> tabulated = new TreeSet<>();
        for (String line : read(TOOLS).split("\n")) {
            Matcher m = Pattern.compile("^\\| `(-[A-Za-z]+)[A-Z]?` ").matcher(line);
            if (m.find()) {
                tabulated.add(m.group(1));
            }
        }
        List<String> wrong = new ArrayList<>();
        for (String flag : printed) {
            if (!tabulated.contains(flag)) {
                wrong.add("the tools print " + flag + ", and tools.md leaves it out");
            }
        }
        for (String flag : tabulated) {
            if (!printed.contains(flag)) {
                wrong.add("tools.md tabulates " + flag + ", and no tool prints it");
            }
        }
        assertTrue(wrong.isEmpty(), () -> String.join("\n", wrong)
                + "\nthe tools print " + printed + " and tools.md " + tabulated);
    }

    /** One usage line, in the tool that prints it and the documents that copy it. */
    @Test
    void theUsageLineIsTheOneTheToolPrints() throws IOException {
        List<String> wrong = new ArrayList<>();
        for (String tool : List.of("St4", "Dst4")) {
            String source = read(Path.of("src/main/java/org/st4/" + tool + ".java"));
            Matcher m = Pattern.compile("Usage: (\\S+) ([^\\n]*?)\\s*\\\\?\\n?\\s*"
                    + "< input").matcher(source);
            assertTrue(m.find(), tool + " does not print a usage line");
            String line = m.group(1) + " " + m.group(2).trim();
            for (Path p : List.of(README, TOOLS)) {
                String text = read(p).replaceAll("[ \\t]+", " ");
                if (!text.contains(line + " < input")) {
                    wrong.add(p + " does not copy \"" + line + " < input\"");
                }
            }
        }
        assertTrue(wrong.isEmpty(), () -> String.join("\n", wrong));
    }

    /** The scripts and the rigs tools.md names, against the tree. */
    @Test
    void everyScriptAndRigNamedIsThereAndRuns() throws IOException {
        String tools = read(TOOLS);
        List<String> wrong = new ArrayList<>();
        List<String> named = new ArrayList<>();
        Matcher m = Pattern.compile("(bin/[A-Za-z0-9._-]+|68k/test/emu/[A-Za-z0-9._-]+"
                + "|release/[A-Za-z0-9._-]+)").matcher(tools);
        while (m.find()) {
            Path script = Path.of(m.group(1));
            named.add(m.group(1));
            if (!Files.isRegularFile(script)) {
                wrong.add("tools.md names " + script + ", which is not there");
            } else if (!Files.isExecutable(script) && !script.toString().endsWith(".py")) {
                wrong.add(script + " is not executable");
            }
        }
        assertTrue(!named.isEmpty(), "tools.md names no script");
        try (var tree = Files.list(Path.of("68k/test/emu"))) {
            for (Path p : tree.sorted().toList()) {
                String name = p.getFileName().toString();
                boolean rig = name.startsWith("test_st4") || name.startsWith("bench_");
                if (rig && !named.contains("68k/test/emu/" + name)) {
                    wrong.add("68k/test/emu/" + name + " is a rig tools.md leaves out");
                }
            }
        }
        assertTrue(wrong.isEmpty(), () -> String.join("\n", wrong)
                + "\ntools.md names " + named);
    }

    /** The parity corpus tools.md reports, against the corpus the test reads. */
    @Test
    void theParityCorpusIsTheOneTheDocumentReports() throws IOException {
        List<String> wrong = new ArrayList<>();
        defines(wrong, read(TOOLS), "reads back over "
                + WORD.get(GoParityTest.INPUTS.size()) + " inputs at "
                + WORD.get(GoParityTest.FLAGS.size()) + " flag settings",
                "tools.md's parity corpus");
        List<String> missing = new ArrayList<>();
        for (String input : GoParityTest.INPUTS) {
            if (!Files.isRegularFile(Path.of(input))) {
                missing.add("GoParityTest packs " + input + ", which is not there");
            }
        }
        wrong.addAll(missing);
        assertTrue(wrong.isEmpty(), () -> String.join("\n", wrong));
    }

    /**
     * The three toolchains the README names, against the three build files
     * that require them.
     */
    @Test
    void everyToolchainIsTheOneItsBuildRequires() throws IOException {
        Matcher java = Pattern.compile("<maven\\.compiler\\.release>(\\d+)<")
                .matcher(read(Path.of("pom.xml")));
        assertTrue(java.find(), "pom.xml requires no Java release");
        Matcher go = Pattern.compile("^go (\\d+\\.\\d+)", Pattern.MULTILINE)
                .matcher(read(Path.of("go/go.mod")));
        assertTrue(go.find(), "go/go.mod requires no Go version");
        Matcher net = Pattern.compile("<TargetFramework>net(\\d+)\\.")
                .matcher(read(Path.of("csharp/Directory.Build.props")));
        assertTrue(net.find(), "the C# build names no framework");

        List<String> wrong = new ArrayList<>();
        defines(wrong, read(README), "Java " + java.group(1)
                + " and Maven for the reference tree, Go " + go.group(1)
                + " for the port, .NET " + net.group(1) + " for",
                "the README's toolchains");
        assertTrue(wrong.isEmpty(), () -> String.join("\n", wrong));
    }

    @Test
    void theGoModulePathIsTheOneTheDocumentsName() throws IOException {
        Matcher m = Pattern.compile("^module (\\S+)", Pattern.MULTILINE)
                .matcher(read(Path.of("go/go.mod")));
        assertTrue(m.find(), "go/go.mod names no module");
        String module = m.group(1);
        List<String> wrong = new ArrayList<>();
        defines(wrong, read(TOOLS), "The Go module is `" + module + '`',
                "tools.md's module path");
        defines(wrong, read(RELEASES), "the Go module `" + module + '`',
                "RELEASES.md's module path");
        assertTrue(wrong.isEmpty(), () -> String.join("\n", wrong));
    }

    /**
     * The newest release listed against the version the release script reads.
     * The pom names the release being cut, so a release published and left out
     * of RELEASES.md fails here.
     */
    @Test
    void theNewestReleaseListedIsThePomVersion() throws IOException {
        Matcher pom = Pattern.compile("<artifactId>st4</artifactId>\\s*"
                + "<version>([^<]+)</version>").matcher(read(Path.of("pom.xml")));
        assertTrue(pom.find(), "pom.xml names no version for st4");
        String version = pom.group(1).replace("-SNAPSHOT", "");
        Matcher newest = Pattern.compile("^### go/v(\\S+), ", Pattern.MULTILINE)
                .matcher(read(RELEASES));
        assertTrue(newest.find(), "RELEASES.md lists no tools release");
        assertTrue(version.equals(newest.group(1)),
                () -> "the pom names " + version + " and RELEASES.md opens at "
                        + newest.group(1));
    }

    // ------------------------------------------------------ the measured

    /** What the search costs against the optimum, as tools.md reports it. */
    @Test
    void theSearchFiguresAreWhatTheOracleMeasures() throws IOException {
        var random = new Random(29);
        int oracleBits = 0;
        int searchBits = 0;
        int openingBits = 0;
        int optimal = 0;
        for (int trial = 0; trial < 60; trial++) {
            int count = 6 + random.nextInt(6);
            int[] units = new int[count];
            for (int at = 0; at < count; at++) {
                units[at] = random.nextInt(3);
            }
            int window = 2 + random.nextInt(3);
            int oracle = St4LiteralCopyOracle.optimize(units, 1, window).bits();
            int opening = St4Compressor.compress(
                    St4LiteralCopySearch.optimize(units, 1, window, St4Format.MAX_OP,
                            0, trial), units, 1, St4Format.MAX_OP, -1, window).bits();
            int search = St4Compressor.compress(
                    St4LiteralCopySearch.optimize(units, 1, window, St4Format.MAX_OP,
                            200, trial), units, 1, St4Format.MAX_OP, -1, window).bits();
            oracleBits += oracle;
            openingBits += opening;
            searchBits += search;
            optimal += search == oracle ? 1 : 0;
        }
        String opening = String.format(Locale.ROOT, "%.1f",
                100.0 * (openingBits - oracleBits) / oracleBits);
        String search = String.format(Locale.ROOT, "%.1f",
                100.0 * (searchBits - oracleBits) / oracleBits);
        List<String> wrong = new ArrayList<>();
        defines(wrong, read(TOOLS), "the opening passes are " + opening
                + " per cent above the optimum and the search " + search
                + " per cent, reaching it on " + optimal + " of 60",
                "tools.md's figures against the oracle");
        assertTrue(wrong.isEmpty(), () -> String.join("\n", wrong));
    }

    /**
     * The column corpus, in every section that measures on it. It lies
     * outside this repository, so no check can read it back. What a check can
     * do is require the sections that cite it to describe it one way.
     */
    @Test
    void everySectionMeasuringColumnsNamesTheSameCorpus() throws IOException {
        String research = read(Path.of("doc/research.md"));
        // a corpus description ends on "raw" or "in all", where a
        // packed total elsewhere in the document ends on neither
        Matcher m = Pattern.compile("(\\d+) (?:chiptune )?columns[^.]{0,99}?"
                + "(\\d{3},\\d{3}) bytes (?:raw|in all)").matcher(flat(research));
        List<String> each = new ArrayList<>();
        while (m.find()) {
            each.add(m.group(1) + " columns, " + m.group(2) + " bytes");
        }
        Set<String> said = new TreeSet<>(each);
        assertTrue(each.size() >= 2,
                () -> "only " + each.size() + " section describes the column "
                        + "corpus: " + each);
        assertTrue(said.size() == 1,
                () -> "the sections that measure on the columns describe them as "
                        + said + ", and one corpus has one description");
    }

    /**
     * The odds the search proposes a move at, in the two documents that
     * report them. The bounds are read out of the parser, so a move
     * reweighted in the code and left in a document fails here.
     */
    @Test
    void theMoveOddsAreTheOnesTheSearchUses() throws IOException {
        String source = read(Path.of("src/main/java/org/st4/St4LiteralCopySearch.java"));
        Matcher m = Pattern.compile("kind < (\\d+)").matcher(source);
        List<Integer> edge = new ArrayList<>();
        while (m.find()) {
            edge.add(Integer.parseInt(m.group(1)));
        }
        assertTrue(edge.size() == 6,
                () -> "the parser proposes at " + edge.size() + " bounds");
        List<String> odds = new ArrayList<>();
        int before = 0;
        for (int at : edge) {
            odds.add(WORD.get(at - before));
            before = at;
        }
        String named = String.join(", ", odds.subList(0, odds.size() - 1))
                + " and " + odds.get(odds.size() - 1);
        List<String> wrong = new ArrayList<>();
        defines(wrong, read(TOOLS), "odds of " + named + " of twenty",
                "tools.md's move odds");
        defines(wrong, read(Path.of("doc/research.md")), "At odds of " + named
                + " of twenty", "research.md's move odds");
        assertTrue(wrong.isEmpty(), () -> String.join("\n", wrong));
    }

    /** Assembles one decoder and returns its size, or -1 where rmac fails. */
    private static int assembled(String decoder, int unit, boolean window)
            throws IOException, InterruptedException {
        Path out = Files.createTempFile("st4-size", ".bin");
        try {
            List<String> command = new ArrayList<>(List.of("rmac", "-m68000", "-fr",
                    "+o3", "-dST4_UNIT=" + unit));
            if (window) {
                command.add("-dST4_WINDOW=1");
            }
            command.addAll(List.of("-o", out.toString(), "68k/" + decoder + ".S"));
            Process rmac = new ProcessBuilder(command).redirectErrorStream(true).start();
            byte[] said = rmac.getInputStream().readAllBytes();
            if (rmac.waitFor() != 0) {
                throw new AssertionError("rmac " + decoder + ": " + new String(said,
                        java.nio.charset.StandardCharsets.UTF_8));
            }
            return (int) Files.size(out);
        } finally {
            Files.deleteIfExists(out);
        }
    }

    private static boolean onThePath(String tool) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String at : path.split(":")) {
            if (Files.isExecutable(Path.of(at, tool))) {
                return true;
            }
        }
        return false;
    }

    /**
     * decoders.md's size table against rmac. Skipped where rmac is off the
     * path, since a machine without an assembler still runs the rest.
     */
    @Test
    void everyDecoderSizeIsWhatRmacWrites() throws IOException, InterruptedException {
        Assumptions.assumeTrue(onThePath("rmac"), "no rmac on the path");
        String decoders = read(DECODERS);
        List<String> wrong = new ArrayList<>();
        int least = Integer.MAX_VALUE;
        int most = 0;
        for (String decoder : List.of("ST4", "ST4_wrap", "ST4_ring")) {
            Matcher row = Pattern.compile("^\\| \\[" + decoder
                    + "\\.S\\]\\([^)]+\\) \\| (\\d+) B \\| (\\d+) B \\| (\\d+) B \\|",
                    Pattern.MULTILINE).matcher(decoders);
            if (!row.find()) {
                wrong.add("decoders.md has no size row for " + decoder);
                continue;
            }
            int[] units = {1, 2, 4};
            for (int at = 0; at < units.length; at++) {
                int plain = assembled(decoder, units[at], false);
                if (plain != Integer.parseInt(row.group(at + 1))) {
                    wrong.add(decoder + " at k = " + units[at] + " assembles to "
                            + plain + ", and decoders.md reads " + row.group(at + 1));
                }
                int grown = assembled(decoder, units[at], true) - plain;
                least = Math.min(least, grown);
                most = Math.max(most, grown);
            }
        }
        int low = least;
        int high = most;
        defines(wrong, decoders, "A window build is " + low + " to " + high
                + " bytes larger", "decoders.md's window build");
        assertTrue(wrong.isEmpty(), () -> String.join("\n", wrong));
    }

    /** The clauses one document defines: `**N.N**` and `## N.N`, a section
     *  number marking itself and the clauses under it. */
    private static Set<String> clausesOf(String said) {
        Set<String> out = new HashSet<>();
        Matcher m = Pattern.compile("(?m)^(?:\\*\\*|#+ )R?(\\d+(?:\\.\\d+)*)").matcher(said);
        while (m.find()) {
            String clause = m.group(1);
            out.add(clause);
            for (int dot = clause.indexOf('.'); dot > 0; dot = clause.indexOf('.', dot + 1)) {
                out.add(clause.substring(0, dot));
            }
        }
        return out;
    }

    /**
     * Every citation of a specification lands on a clause of it.
     *
     * <p>A citation in these documents is the clause in brackets, `(4.4)`,
     * and a reader follows it. 3.4 pointed at a last offset no clause set
     * until a reader with the document alone found it; this reads every
     * citation at once, so one that lands nowhere is named where it is
     * written.
     */
    @Test
    void everyCitationLandsOnAClause() throws IOException {
        List<String> wrong = new ArrayList<>();
        for (Path at : List.of(SPEC, REQ)) {
            String said = Files.readString(at);
            Set<String> clauses = clausesOf(said);
            Matcher m = Pattern.compile("\\((\\d+(?:\\.\\d+){1,3})\\)").matcher(said);
            while (m.find()) {
                if (!clauses.contains(m.group(1))) {
                    wrong.add(at + " cites (" + m.group(1) + "), which is no clause of it");
                }
            }
        }
        assertTrue(wrong.isEmpty(), () -> String.join("\n", wrong));
    }
}
