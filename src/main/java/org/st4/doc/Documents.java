package org.st4.doc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a check reads of the documents of a tree, apart from their figures.
 *
 * <p>Each repository checks its figures itself: a clause against the code
 * that implements it, a table against what a tool measures. The four
 * here read what every repository of this family requires of a document - a
 * link that resolves, one wrap width, a glossary in order - and each was
 * written four times before it stood here.
 *
 * <p>Each returns the lines to report, empty where the documents are true,
 * so a caller names the rule in the words it uses and says how many
 * documents it expected to read. The documents to read are
 * {@link org.st4.style.HouseStyle#documents}, which finds them rather than
 * listing them.
 *
 * <p>This package is carried between the repositories of the family, as
 * {@code org.st4.style} is: a copy follows this one line for line.
 */
public final class Documents {

    /** A Markdown link: its text, then its target. */
    private static final Pattern LINK =
            Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");

    private Documents() {
    }

    /**
     * Every link whose target the tree lacks.
     *
     * <p>A link to a URL and one to an anchor of the document it stands in
     * are left, and a target with an anchor after it is read up to the
     * anchor. A line names the link, since a document names one target
     * twice as often as not.
     */
    public static List<String> links(List<Path> documents) throws IOException {
        List<String> broken = new ArrayList<>();
        for (Path p : documents) {
            List<String> lines = Files.readAllLines(p);
            Path base = p.getParent() == null ? Path.of(".") : p.getParent();
            for (int at = 0; at < lines.size(); at++) {
                Matcher m = LINK.matcher(lines.get(at));
                while (m.find()) {
                    String target = m.group(2);
                    if (target.startsWith("http") || target.startsWith("#")) {
                        continue;
                    }
                    Path to = base.resolve(target.split("#")[0]).normalize();
                    if (!Files.exists(to)) {
                        broken.add(p + ":" + (at + 1) + " links to " + target);
                    }
                }
            }
        }
        return broken;
    }

    /**
     * Every line of prose past {@code width} characters.
     *
     * <p>What a writer cannot wrap is left: a table row, an indented block,
     * a fenced block, a line with a link in it, and a line with a bare URL.
     */
    public static List<String> wide(List<Path> documents, int width)
            throws IOException {
        List<String> out = new ArrayList<>();
        for (Path p : documents) {
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
                if (line.length() > width) {
                    out.add(p + ":" + (at + 1) + " runs to " + line.length());
                }
            }
        }
        return out;
    }

    /**
     * The rows of a glossary table, each its cells with the spaces off. The
     * heading row is left, and so is every line the table lacks a cell for.
     */
    public static List<String[]> glossaryRows(String glossary) {
        List<String[]> out = new ArrayList<>();
        for (String line : glossary.split("\n")) {
            if (!line.startsWith("| ") || line.startsWith("| term")) {
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

    /** Every row of a glossary whose term stands before the one above it. */
    public static List<String> outOfOrder(List<String[]> rows) {
        List<String> wrong = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            String before = rows.get(i - 1)[0].replace("`", "").toLowerCase();
            String after = rows.get(i)[0].replace("`", "").toLowerCase();
            if (before.compareTo(after) > 0) {
                wrong.add('"' + before + "\" stands before \"" + after + '"');
            }
        }
        return wrong;
    }
}
