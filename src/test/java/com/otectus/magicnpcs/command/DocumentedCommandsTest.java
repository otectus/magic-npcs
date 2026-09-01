package com.otectus.magicnpcs.command;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The documentation contract test the 0.6.1 audit asks for (CMD-001, "Documentation contract tests").
 *
 * <p>The reported failure was not a code bug on its own: the project page advertised
 * {@code /magicnpcs loadout}, {@code /magicnpcs school} and {@code /magicnpcs config} as if they were
 * commands. The first two are headings that need a subcommand; the third was not registered at all. A
 * player copying the page got Brigadier's bare red error from most of it, and had no way to tell a
 * typo from a missing feature.
 *
 * <p>This test extracts every {@code /magicnpcs …} line from the user-facing documentation and checks
 * it against the command tree's known paths. Building the real Brigadier tree needs a
 * {@code CommandSourceStack} and therefore a server, so the check is against a checked-in path list
 * kept beside the builder — the same list a reader of {@code MagicNpcsCommands} would write down. It
 * catches the failure that actually happened: documentation naming a path the tree does not have.
 */
class DocumentedCommandsTest {

    /**
     * Every executable path in the {@code /magicnpcs} tree, as literal words before the first argument.
     *
     * <p>Keep this in step with {@link MagicNpcsCommands}. Adding a subcommand without adding it here
     * makes any documentation of it fail this test, which is the intended direction of pressure: the
     * tree is the source of truth and the docs must match it.
     */
    private static final Set<String> KNOWN_PATHS = Set.of(
            "",                       // bare /magicnpcs — the index
            "help",
            "why",
            "loadout",
            "loadout entity",
            "loadout id",
            "validate",
            "validate resource",
            "validate id",
            "config",
            "reconcile",
            "spells",
            "school",
            "school info",
            "school set",
            "school reroll",
            "school clear",
            "school auto",
            "school pool");

    /**
     * Paths that need a subcommand to do anything. Documentation may still name them — every one of
     * them prints its usage since 0.6.2 — but a line that is offered as something to <em>run</em>
     * should be complete.
     */
    private static final Set<String> HEADING_PATHS = Set.of("loadout", "school");

    private static final List<String> DOCS = List.of(
            "README.md", "CURSEFORGE_DESCRIPTION.md", "CHANGELOG.md",
            "docs/loadouts/README.md", "docs/schools.md", "docs/mob-friendly-spells.md");

    /** {@code /magicnpcs} plus everything up to the end of the line, inside or outside a code fence. */
    private static final Pattern COMMAND = Pattern.compile("/magicnpcs\\b([^`\\n|]*)");

    /** A ``` fenced block tagged as JSON, or an untagged one whose body starts with an object. */
    private static final Pattern JSON_BLOCK =
            Pattern.compile("```(?:json|mcfunction)?\\s*\\n(\\{.*?)```", Pattern.DOTALL);

    private static Path repoRoot() {
        // Gradle runs tests from the project directory; walk up if a runner does otherwise.
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("gradle.properties"))) {
            dir = dir.getParent();
        }
        return dir == null ? Path.of("").toAbsolutePath() : dir;
    }

    private static String read(String relative) throws IOException {
        return Files.readString(repoRoot().resolve(relative), StandardCharsets.UTF_8);
    }

    /**
     * The literal words of a documented command, e.g. {@code "school set"} for
     * {@code /magicnpcs school set <targets> <school>}.
     *
     * <p>Longest-known-prefix, not "every lowercase word": Brigadier arguments can look exactly like
     * literals. {@code /magicnpcs spells fire} is the {@code spells} node with a filter argument, and
     * consuming {@code fire} as a literal would report a path that was never meant to exist. A first
     * token that matches nothing is still returned, so a genuine typo like {@code /magicnpcs loudout}
     * fails rather than silently reading as the bare root.
     */
    private static String pathOf(String tail) {
        String current = "";
        for (String token : tail.trim().split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            // Stop at the first placeholder, selector, or optional-argument marker.
            if (!token.matches("[a-z_]+")) {
                break;
            }
            String candidate = current.isEmpty() ? token : current + " " + token;
            if (!KNOWN_PATHS.contains(candidate)) {
                // Not a deeper node, so it is an argument to the node we already have — unless nothing
                // has matched at all, in which case the very first word is wrong and must be reported.
                return current.isEmpty() ? candidate : current;
            }
            current = candidate;
        }
        return current;
    }

    @Test
    void everyDocumentedCommandPathExistsInTheTree() throws IOException {
        List<String> unknown = new ArrayList<>();
        for (String doc : DOCS) {
            Matcher matcher = COMMAND.matcher(read(doc));
            while (matcher.find()) {
                String path = pathOf(matcher.group(1));
                if (!KNOWN_PATHS.contains(path)) {
                    unknown.add(doc + ": /magicnpcs " + path
                            + "   (from \"" + matcher.group().trim() + "\")");
                }
            }
        }
        if (!unknown.isEmpty()) {
            fail("Documentation names " + unknown.size() + " command path(s) the tree does not have. "
                    + "Either the docs are wrong or MagicNpcsCommands (and KNOWN_PATHS) needs updating:\n  "
                    + String.join("\n  ", unknown));
        }
    }

    /**
     * A line offered inside an {@code mcfunction} block is something the reader is expected to paste,
     * so it must be complete. This is the exact shape of the reported bug.
     */
    @Test
    void everyPasteableExampleIsACompleteCommand() throws IOException {
        List<String> incomplete = new ArrayList<>();
        Pattern fence = Pattern.compile("```mcfunction\\s*\\n(.*?)```", Pattern.DOTALL);
        for (String doc : DOCS) {
            Matcher block = fence.matcher(read(doc));
            while (block.find()) {
                for (String line : block.group(1).split("\\n")) {
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("/magicnpcs")) {
                        continue;
                    }
                    String path = pathOf(trimmed.substring("/magicnpcs".length()));
                    if (HEADING_PATHS.contains(path)) {
                        incomplete.add(doc + ": " + trimmed);
                    }
                }
            }
        }
        if (!incomplete.isEmpty()) {
            fail("These copy-paste examples stop at a heading and need a subcommand:\n  "
                    + String.join("\n  ", incomplete));
        }
    }

    @Test
    void everyJsonExampleInTheDocsParses() throws IOException {
        List<String> broken = new ArrayList<>();
        for (String doc : Stream.concat(DOCS.stream(), Stream.of("docs/loadouts/examples/skeleton.json",
                "docs/loadouts/examples/witch.json")).toList()) {
            String text = read(doc);
            if (doc.endsWith(".json")) {
                try {
                    JsonParser.parseString(text);
                } catch (Exception ex) {
                    broken.add(doc + ": " + ex.getMessage());
                }
                continue;
            }
            Matcher matcher = JSON_BLOCK.matcher(text);
            while (matcher.find()) {
                String body = matcher.group(1).trim();
                try {
                    JsonElement parsed = JsonParser.parseString(body);
                    assertTrue(parsed.isJsonObject() || parsed.isJsonArray(),
                            doc + ": a JSON example should be an object or array");
                } catch (Exception ex) {
                    broken.add(doc + ": " + ex.getMessage() + "\n" + body);
                }
            }
        }
        if (!broken.isEmpty()) {
            fail("Broken JSON example(s) in the documentation:\n  " + String.join("\n  ", broken));
        }
    }

    /**
     * The audit's CMD-002 in test form: {@code /magicnpcs config} was advertised while missing from the
     * binary. Anything the user-facing docs promise has to be in the tree.
     */
    @Test
    void theAdvertisedHeadlineCommandsAreAllInTheTree() {
        Set<String> advertised = new LinkedHashSet<>(List.of(
                "why", "loadout entity", "loadout id", "validate", "config", "reconcile",
                "school info", "school set", "school pool", "spells"));
        advertised.removeAll(KNOWN_PATHS);
        assertTrue(advertised.isEmpty(),
                "advertised but not registered: " + advertised.toString().toLowerCase(Locale.ROOT));
    }
}
