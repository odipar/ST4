package org.st4;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
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
        return HouseStyleTest.documents();
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
        List<String> broken = new ArrayList<>();
        for (Path p : documents()) {
            Matcher m = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)")
                    .matcher(read(p));
            while (m.find()) {
                String target = m.group(2);
                if (target.startsWith("http") || target.startsWith("#")) {
                    continue;
                }
                Path base = p.getParent() == null ? Path.of(".") : p.getParent();
                Path at = base.resolve(target.split("#")[0]).normalize();
                if (!Files.exists(at)) {
                    broken.add(p + ": [" + m.group(1) + "](" + target + ')');
                }
            }
        }
        assertTrue(broken.isEmpty(), () -> String.join("\n", broken));
    }

    /** The clauses SPEC.md numbers, as {@code 2}, {@code 2.1} and so on. */
    private static Set<String> clauses(String spec) {
        Set<String> out = new TreeSet<>();
        Matcher h = Pattern.compile("^## (\\d+)\\. ", Pattern.MULTILINE)
                .matcher(spec);
        while (h.find()) {
            out.add(h.group(1));
        }
        Matcher c = Pattern.compile("^\\*\\*(\\d+\\.\\d+)\\*\\* ", Pattern.MULTILINE)
                .matcher(spec);
        while (c.find()) {
            out.add(c.group(1));
        }
        return out;
    }

    @Test
    void everyClauseCitedIsInTheSpecification() throws IOException {
        Set<String> clauses = clauses(read(SPEC));
        assertTrue(clauses.size() > 20,
                () -> "SPEC.md read as " + clauses.size() + " clauses");
        List<String> dangling = new ArrayList<>();
        for (Path p : documents()) {
            Matcher m = Pattern.compile("SPEC\\.md\\)? (\\d+(?:\\.\\d+)?)"
                    + "(?:, (\\d+\\.\\d+))?").matcher(read(p));
            while (m.find()) {
                for (int g = 1; g <= m.groupCount(); g++) {
                    String cited = m.group(g);
                    if (cited != null && !clauses.contains(cited)) {
                        dangling.add(p + " cites SPEC.md " + cited);
                    }
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
    private static List<String[]> glossaryRows(String glo) {
        List<String[]> out = new ArrayList<>();
        for (String line : glo.split("\n")) {
            if (!line.startsWith("| ") || line.startsWith("| term")
                    || line.startsWith("| ---")) {
                continue;
            }
            String[] cells = line.split("\\|");
            if (cells.length >= 4) {
                out.add(new String[] {cells[1].trim(), cells[2].trim(),
                                      cells[3].trim()});
            }
        }
        return out;
    }

    @Test
    void theGlossaryIsInOrder() throws IOException {
        List<String[]> rows = glossaryRows(read(GLO));
        assertTrue(rows.size() > 20, () -> "the glossary read as " + rows.size()
                + " rows");
        List<String> wrong = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            String before = rows.get(i - 1)[0].replace("`", "")
                    .toLowerCase(Locale.ROOT);
            String after = rows.get(i)[0].replace("`", "")
                    .toLowerCase(Locale.ROOT);
            if (before.compareTo(after) > 0) {
                wrong.add('"' + before + "\" stands before \"" + after + '"');
            }
        }
        assertTrue(wrong.isEmpty(), () -> String.join("\n", wrong));
    }

    @Test
    void everyGlossaryRowPointsSomewhereReal() throws IOException {
        Set<String> clauses = clauses(read(SPEC));
        List<String> bad = new ArrayList<>();
        for (String[] row : glossaryRows(read(GLO))) {
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
        List<String> wide = new ArrayList<>();
        for (Path p : documents()) {
            List<String> lines = Files.readAllLines(p);
            boolean fenced = false;
            for (int at = 0; at < lines.size(); at++) {
                String line = lines.get(at);
                if (line.startsWith("```")) {
                    fenced = !fenced;
                    continue;
                }
                if (fenced || line.startsWith("|") || line.startsWith("    ")
                        || line.contains("](") || line.contains("<https://")) {
                    continue;
                }
                if (line.length() > 78) {
                    wide.add(p + ":" + (at + 1) + " runs to " + line.length());
                }
            }
        }
        assertTrue(wide.isEmpty(), () -> String.join("\n", wide)
                + "\nAGENTS.md defines one wrap width, and a document keeps it.");
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
}
