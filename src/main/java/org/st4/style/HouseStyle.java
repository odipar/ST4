package org.st4.style;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.st4.style.Comments.Comment;
import org.st4.style.Construct.Match;

/**
 * The house style, as a check over a tree.
 *
 * <p>AGENTS.md defines the rules and STRUCK.md lists the constructs struck
 * under them; this class reads the list, then every document and every code
 * comment the tree writes against it. A hit names the file, the line, the
 * text matched and the rule. {@code main} runs the check over a tree and
 * exits with 1 where it found a hit; HouseStyleTest runs it under Maven.
 *
 * <p>The documents and sources are found rather than listed: a list is a
 * place a new file is not. What is carried from another repository is not
 * read, since a copy follows its tree's style, and the documents that
 * define the rules are not read either, since they quote what they strike.
 *
 * @param constructs every construct struck, in the order STRUCK.md lists
 *     them
 * @param names names a construct is spelled inside, blanked before a line is
 *     lowered
 * @param carried fragments of a path that mark a file as carried from
 *     another repository
 * @param own ends of a path that mark a file as the tree's despite
 *     standing among carried ones
 */
public record HouseStyle(List<Construct> constructs, List<String> names,
        List<String> carried, List<String> own) {

    /** The file that lists the constructs, at the root of the tree. */
    public static final String STRUCK = "STRUCK.md";

    /** The documents that define the rules, and so quote what they strike. */
    public static final List<String> DEFINES_THE_RULES =
            List.of("AGENTS.md", "CLAUDE.md", STRUCK);

    /** One hit: the file, the line, the construct and the text matched. */
    public record Hit(Path file, int line, Construct construct, String text) {
        @Override
        public String toString() {
            return file + ":" + line + " has \"" + text + "\" - "
                    + construct.rule() + ", " + construct.name();
        }
    }

    /** The style STRUCK.md lists at the root {@code root}. */
    public static HouseStyle read(Path root) throws IOException {
        return parse(Files.readAllLines(root.resolve(STRUCK)));
    }

    /**
     * The style the lines of a STRUCK.md list.
     *
     * <p>A heading {@code ## Rule} opens a rule. An entry is an unindented
     * line followed by an indented block: before the first rule the entries
     * {@code names}, {@code carried} and {@code own} list their items, one a
     * line; under a rule the block is the pattern, over as many lines as it
     * needs, then {@code in:} and {@code not:} samples. Every other line is
     * prose and is not read.
     */
    public static HouseStyle parse(List<String> lines) {
        List<Construct> constructs = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> carried = new ArrayList<>();
        List<String> own = new ArrayList<>();
        String rule = "";
        for (int at = 0; at < lines.size(); at++) {
            String line = lines.get(at);
            if (line.startsWith("## ")) {
                rule = line.substring(3).strip();
                continue;
            }
            boolean names_ = !line.isBlank() && !indented(line)
                    && at + 1 < lines.size() && indented(lines.get(at + 1));
            if (!names_) {
                continue;
            }
            List<String> block = new ArrayList<>();
            while (at + 1 < lines.size() && indented(lines.get(at + 1))) {
                block.add(lines.get(++at).strip());
            }
            String name = line.strip();
            if (rule.isEmpty()) {
                switch (name) {
                    case "names" -> names.addAll(block);
                    case "carried" -> carried.addAll(block);
                    case "own" -> own.addAll(block);
                    default -> throw new IllegalArgumentException(STRUCK
                            + ": \"" + name + "\" stands before the first"
                            + " rule, where names, carried and own are read");
                }
                continue;
            }
            StringBuilder regex = new StringBuilder();
            List<String> in = new ArrayList<>();
            List<String> not = new ArrayList<>();
            for (String one : block) {
                if (one.startsWith("in:")) {
                    in.add(one.substring(3).strip());
                } else if (one.startsWith("not:")) {
                    not.add(one.substring(4).strip());
                } else {
                    regex.append(one);
                }
            }
            if (regex.isEmpty() || in.isEmpty()) {
                throw new IllegalArgumentException(STRUCK + ": \"" + name
                        + "\" needs a pattern and an in: sample");
            }
            constructs.add(new Construct(rule, name,
                    Pattern.compile(regex.toString()), List.copyOf(in),
                    List.copyOf(not)));
        }
        return new HouseStyle(List.copyOf(constructs), List.copyOf(names),
                List.copyOf(carried), List.copyOf(own));
    }

    private static boolean indented(String line) {
        return line.startsWith("    ") || line.startsWith("\t");
    }

    /**
     * {@code line} lowered, with the names blanked to their length, so
     * an offset into the result is an offset into the line.
     */
    public String lower(String line) {
        String out = line;
        for (String name : names) {
            out = out.replace(name, " ".repeat(name.length()));
        }
        return out.toLowerCase();
    }

    /**
     * The hits in one run of lines that begins at line {@code first} of
     * {@code file}. The lines are read joined, since a phrase broken by a
     * line wrap stands in neither of its lines, and a hit is reported at the
     * line the matched text begins on. A line joins without its indent and
     * without the marks a comment writes in front of it, or those would
     * stand inside the phrase a wrap broke. The code spans are blanked once
     * the lines are joined, so a span a wrap breaks is blanked whole.
     */
    public List<Hit> hits(Path file, int first, List<String> lines) {
        StringBuilder joined = new StringBuilder();
        int[] begins = new int[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            begins[i] = joined.length();
            joined.append(lower(unmarked(lines.get(i)))).append(' ');
        }
        String prose = quoted(joined.toString());
        List<Hit> out = new ArrayList<>();
        for (Construct construct : constructs) {
            for (Match match : construct.matches(prose)) {
                int line = 0;
                while (line + 1 < begins.length
                        && begins[line + 1] <= match.at()) {
                    line++;
                }
                out.add(new Hit(file, first + line, construct, match.text()));
            }
        }
        return out;
    }

    /** {@code line} without its indent and the marks a comment writes. */
    private static String unmarked(String line) {
        int at = 0;
        while (at < line.length() && (line.charAt(at) == ' '
                || line.charAt(at) == '\t' || line.charAt(at) == '*'
                || line.charAt(at) == '/')) {
            at++;
        }
        return line.substring(at).strip();
    }

    /**
     * The joined lines with every quoted span blanked, a space a character
     * so the rest keeps its offsets.
     *
     * <p>A quoted span is a message a tool writes, a name in a tree, a
     * construct a document strikes and must spell to strike it. A quoted
     * message keeps its words (AGENTS.md), so the words inside one are not
     * this tree's prose and are not read. A document quotes between
     * backticks; a comment quotes in the marks Javadoc reads, {@code
     * &#123;@code x&#125;} and {@code &#123;@link X&#125;} for a span and
     * {@code <pre>} for a block.
     *
     * <p>A span opens and closes over the run rather than the line: a
     * message as wide as the document wraps, and the half on each line is
     * quoted as much as a message that fits one line. Blanking needs the
     * pair, so where one mark stands alone in the run the words after it
     * are read.
     */
    private static String quoted(String line) {
        StringBuilder out = new StringBuilder(line);
        blank(out, "`", "`");
        blank(out, "{@", "}");
        blank(out, "<pre>", "</pre>");
        return out.toString();
    }

    /** Every span of {@code out} from an opening mark to the closing one
     *  blanked, the marks included. */
    private static void blank(StringBuilder out, String opens, String closes) {
        int at = out.indexOf(opens);
        while (at >= 0) {
            int to = out.indexOf(closes, at + opens.length());
            if (to < 0) {
                return;
            }
            int end = to + closes.length();
            for (int i = at; i < end; i++) {
                out.setCharAt(i, ' ');
            }
            at = out.indexOf(opens, end);
        }
    }

    /**
     * The hits in a document. A paragraph runs to a blank line, a table row
     * or a fence, and a table row is read alone: joining them would put
     * words side by side that no sentence puts there.
     *
     * <p>A fenced block is quoted material, as a code span is: a command, a
     * file, a run of output. Its words are those of what it quotes rather
     * than this tree's, so the check reads past the block and the two
     * fences around it. A script a document quotes stands in the tree as
     * well, and is read there, under {@code sources}.
     *
     * <p>An indent reads two ways, and where it falls decides which. An
     * indented line that opens a block, after a blank line, is an indented
     * code block, quoted as a fenced one is, and it runs to the first
     * line that is neither blank nor indented. An indented line under an
     * open paragraph continues a list item, or opens a list under one, and
     * is this tree's prose: it joins the paragraph, so a construct the wrap
     * of a list item breaks is read.
     */
    public List<Hit> document(Path file, List<String> lines) {
        List<Hit> out = new ArrayList<>();
        List<String> paragraph = new ArrayList<>();
        int began = 0;
        boolean fenced = false;
        boolean block = false;
        for (int at = 0; at <= lines.size(); at++) {
            String line = at < lines.size() ? lines.get(at) : "";
            if (block && at < lines.size() && (line.isBlank() || indented(line))) {
                continue;
            }
            block = false;
            boolean fence = line.startsWith("```");
            boolean opens = !fenced && paragraph.isEmpty() && indented(line);
            boolean breaks = at == lines.size() || fenced || fence || opens
                    || line.isBlank() || line.startsWith("|");
            if (!breaks) {
                if (paragraph.isEmpty()) {
                    began = at;
                }
                paragraph.add(line);
                continue;
            }
            if (!paragraph.isEmpty()) {
                out.addAll(hits(file, began + 1, paragraph));
                paragraph.clear();
            }
            if (fence) {
                // A fence the document leaves open runs to its end, which
                // is where the lines run out.
                fenced = !fenced;
            } else if (opens) {
                block = true;
            } else if (!fenced && !line.isBlank()) {
                out.addAll(hits(file, at + 1, List.of(line)));
            }
        }
        return out;
    }

    /** The hits in the comments of a source file. */
    public List<Hit> source(Path file, String text) {
        List<Hit> out = new ArrayList<>();
        for (Comment comment : Comments.of(file, text)) {
            List<String> lines = List.of(comment.text().split("\n", -1));
            List<String> paragraph = new ArrayList<>();
            int began = 0;
            for (int at = 0; at <= lines.size(); at++) {
                String line = at < lines.size() ? unmarked(lines.get(at)) : "";
                if (!line.isEmpty()) {
                    if (paragraph.isEmpty()) {
                        began = at;
                    }
                    paragraph.add(line);
                } else if (!paragraph.isEmpty()) {
                    out.addAll(hits(file, comment.line() + began, paragraph));
                    paragraph.clear();
                }
            }
        }
        return out;
    }

    /** Whether {@code path} is carried from another repository. */
    public boolean isCarried(Path path) {
        String at = path.toString();
        for (String end : own) {
            if (at.endsWith(end)) {
                return false;
            }
        }
        for (String fragment : carried) {
            if (at.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /** What a build or an editor writes beside the tree, which is not read. */
    private static boolean built(Path path) {
        String at = path.toString();
        return at.contains("/target/") || at.contains("/obj/")
                || at.contains("/dotnet/bin/") || at.contains("/dist/")
                || at.contains("/.git/") || at.contains("/.idea/");
    }

    /** Every Markdown file under {@code root} but those that define the rules. */
    public static List<Path> documents(Path root) throws IOException {
        try (Stream<Path> tree = Files.walk(root)) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".md"))
                    .filter(path -> !built(path))
                    .filter(path -> !DEFINES_THE_RULES.contains(
                            path.toFile().getName()))
                    .sorted()
                    .toList();
        }
    }

    /**
     * Every source file under {@code root} whose comments the tree writes.
     *
     * <p>A name is read by its extension, and two kinds do not carry one:
     * the scripts under {@code bin/}, which a shell reads, and a
     * {@code .gitignore}. Both write their comments behind a {@code #}, as
     * a shell script does.
     */
    public List<Path> sources(Path root) throws IOException {
        try (Stream<Path> tree = Files.walk(root)) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> {
                        String at = path.toString();
                        return at.endsWith(".java") || at.endsWith(".go")
                                || at.endsWith(".cs") || at.endsWith(".S")
                                || at.endsWith(".py") || at.endsWith(".sh")
                                || at.endsWith(".gitignore") || inBin(path);
                    })
                    .filter(path -> !built(path))
                    .filter(path -> !isCarried(path))
                    .sorted()
                    .toList();
        }
    }

    /** Whether {@code path} is one of the scripts under the repository's
     *  {@code bin/}. A directory of that name deeper in the tree is a
     *  build's, and a build writes executables there, not scripts. */
    private static boolean inBin(Path path) {
        Path up = path.getParent();
        if (up == null || up.getFileName() == null
                || !up.getFileName().toString().equals("bin")) {
            return false;
        }
        Path over = up.getParent();
        return over == null || over.getFileName() == null
                || over.getFileName().toString().isEmpty()
                || over.equals(Path.of(".")) || over.getNameCount() == 0;
    }

    /** Every hit in the documents and source comments under {@code root}. */
    public List<Hit> check(Path root) throws IOException {
        List<Hit> out = new ArrayList<>();
        for (Path document : documents(root)) {
            out.addAll(document(document, Files.readAllLines(document)));
        }
        for (Path source : sources(root)) {
            out.addAll(source(source, Files.readString(source)));
        }
        return out;
    }

    /**
     * Runs the check over a tree, the working directory unless one is
     * named, prints a line a hit, and exits with 1 where there was one.
     */
    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        List<Hit> hits = read(root).check(root);
        for (Hit hit : hits) {
            System.out.println(hit);
        }
        if (!hits.isEmpty()) {
            System.out.println(hits.size() + " hits. AGENTS.md defines the rule"
                    + " each was struck under; reword the line, or take the"
                    + " entry off " + STRUCK + " in the same change.");
            System.exit(1);
        }
    }
}
