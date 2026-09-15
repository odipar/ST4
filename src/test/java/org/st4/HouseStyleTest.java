package org.st4;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The documents against the phrases struck in review.
 *
 * <p>AGENTS.md defines the rules and logs what has been struck; this reads
 * every Markdown file in the tree for the phrases among them a match can
 * find. AGENTS.md and CLAUDE.md are left out: they quote the struck phrases
 * to define them.
 *
 * <p>Code comments are outside this test at present. The sweep that would
 * bring the three source trees under it is a separate round.
 */
final class HouseStyleTest {

    /** The two files that define the rules, which quote what they strike. */
    private static final List<String> DEFINES_THE_RULES =
            List.of("AGENTS.md", "CLAUDE.md");

    /** Struck in review, AGENTS.md. A phrase here is matched anywhere in a
     *  line, so a stem stands for its forms. */
    private static final List<String> STRUCK = List.of(
            "promise",
            "guarantee",
            "implies",
            "imply ",
            "can be told",
            "roles stand",
            "it ruled",
            "it measured",
            "answered",
            "carries",
            "sits in",
            "stand apart",
            "keeps its place",
            "keeps the place",
            "stands where it",
            " stands on ",
            " stand on ",
            "is the base",
            "in flight",
            "spells out",
            "spell out",
            "because it says",
            "says it",
            "says so",
            "says what to take",
            "asks of",
            "asks for",
            "set-ness",
            "takes the machine with it",
            "consume ",
            "consumes",
            "consumed",
            "consuming",
            "stand as they were",
            "understand",
            "refuse",
            "whatever",
            "whichever way",
            "where it sits",
            "stood still",
            " a tail ",
            "sliver",
            "literally",
            "smear",
            "bears it out",
            "pressure point",
            "door left open",
            "cover version",
            "smuggl",
            "catastroph",
            "—",
            "–",
            "−",
            "vendor",
            "is deliberate",
            "by design",
            "on purpose",
            "asked properly",
            "not a shrug",
            "most of the point",
            "the answer to that",
            "worth reading",
            "the ones that matter",
            "the whole point",
            "actually",
            " hold ",
            "holds",
            "holding",
            "held",
            "states",
            "stated",
            "stating",
            "gives",
            "giving",
            "given",
            " take ",
            " takes ",
            " taking ",
            " taken ",
            "nothing",
            " own ",
            " own.",
            "written down",
            "write down",
            "writes down",
            "writing down",
            "a tune down",
            "the structure down",
            "puts down",
            "belongs to no",
            "performs no ",
            "writes no ",
            "reaches no ",
            "and no other",
            "for none",
            "on no chip",
            "with no error",
            "is not defined",
            "not defined by",
            "no other step",
            "would rather");

    private static List<Path> documents() throws IOException {
        try (Stream<Path> tree = Files.walk(Path.of("."))) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".md"))
                    .filter(path -> !path.toString().contains("/target/"))
                    .filter(path -> !DEFINES_THE_RULES
                            .contains(path.getFileName().toString()))
                    .sorted()
                    .toList();
        }
    }

    @Test
    void noDocumentUsesAPhraseStruckInReview() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path document : documents()) {
            List<String> lines = Files.readAllLines(document);
            for (int i = 0; i < lines.size(); i++) {
                String lower = lines.get(i).toLowerCase();
                for (String phrase : STRUCK) {
                    if (lower.contains(phrase.toLowerCase())) {
                        found.add(document + ":" + (i + 1) + " reads \""
                                + phrase + "\": " + lines.get(i).strip());
                    }
                }
            }
        }
        assertTrue(found.isEmpty(), () -> String.join("\n", found)
                + "\nAGENTS.md, Struck in review, has what to write instead.");
    }
}
