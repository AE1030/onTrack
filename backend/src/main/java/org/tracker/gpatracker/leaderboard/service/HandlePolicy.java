package org.tracker.gpatracker.leaderboard.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * What a handle is allowed to be.
 *
 * <p>The board publishes this string next to a score, which makes it the one piece of free text in
 * the feature that every other student reads. {@code Users.username} could not take this job: it
 * predates the leaderboard, has no uniqueness or content contract, and adding one to it would
 * change what already-registered accounts are allowed to be called.
 *
 * <p>The screen runs over a normalised form of the handle, because the interesting input is never
 * the plain word. Digits and symbols standing in for letters are undone, separators are dropped,
 * and a second pass collapses runs of a repeated letter, so {@code f_u_c_k}, {@code fuuuck} and
 * {@code f4ck} all reduce to the same thing.
 *
 * <p>Words are screened in two ways, and which list a word sits on is the whole design:
 *
 * <ul>
 *   <li>{@code substring.txt} matches anywhere. Every term on it was checked against the 20,000
 *       most common English words and appears inside none of them, so matching loosely is free.</li>
 *   <li>{@code whole-word.txt} matches only a complete token. These terms <em>do</em> occur inside
 *       ordinary words — {@code ass} inside {@code password}, {@code mong} inside {@code among},
 *       {@code semen} inside {@code basement} — so matching them loosely would reject handles that
 *       are entirely innocent.</li>
 * </ul>
 *
 * <p>What this cannot do is judge a phrase it has never seen. It catches known words, including
 * obfuscated ones, which is the shape abuse of a 24-character field actually takes. Anything
 * novel is left to the fact that a handle is attached to a real, verified account.
 */
public final class HandlePolicy {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 24;

    /** Letters, digits, underscore and hyphen. No spaces: a handle is one token on a board row. */
    private static final Pattern ALLOWED = Pattern.compile("^[A-Za-z0-9_-]+$");

    /** Where a token ends: a separator, a digit, or a lower-to-upper case change. */
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[_\\-0-9]+|(?<=[a-z])(?=[A-Z])");

    private static final Set<String> ANYWHERE =
            load("/profanity/substring.txt", "/profanity/reserved.txt");
    private static final Set<String> TOKEN_ONLY = load("/profanity/whole-word.txt");

    private HandlePolicy() {
    }

    /**
     * @return null if the handle is acceptable, otherwise a message safe to show the student
     */
    public static String rejectionReason(String handle) {
        if (handle == null || handle.isBlank()) {
            return "Pick a handle for the board.";
        }
        String trimmed = handle.trim();
        if (trimmed.length() < MIN_LENGTH || trimmed.length() > MAX_LENGTH) {
            return "Handles are between " + MIN_LENGTH + " and " + MAX_LENGTH + " characters.";
        }
        if (!ALLOWED.matcher(trimmed).matches()) {
            return "Handles can use letters, numbers, underscores and hyphens.";
        }
        if (isScreened(trimmed)) {
            return "Pick a different handle.";
        }
        return null;
    }

    /** Whether either list matches, once the usual disguises have been undone. */
    static boolean isScreened(String handle) {
        if (handle == null) {
            return false;
        }
        String whole = normalise(handle);
        if (containsAny(whole) || containsAny(collapseRuns(whole))) {
            return true;
        }
        for (String token : TOKEN_SPLIT.split(handle)) {
            String normalised = normalise(token);
            if (normalised.isEmpty()) {
                continue;
            }
            if (TOKEN_ONLY.contains(normalised) || TOKEN_ONLY.contains(collapseRuns(normalised))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String normalised) {
        for (String term : ANYWHERE) {
            if (normalised.contains(term)) {
                return true;
            }
        }
        return false;
    }

    /** Lower case, the usual substitutions undone, everything that is not a letter dropped. */
    private static String normalise(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replace('0', 'o')
                .replace('1', 'i')
                .replace('3', 'e')
                .replace('4', 'a')
                .replace('5', 's')
                .replace('7', 't')
                .replace('@', 'a')
                .replace('$', 's')
                .replaceAll("[^a-z]", "");
    }

    /**
     * Runs of one letter reduced to a single letter, so padding out a word does not hide it.
     *
     * <p>Applied as a second pass rather than folded into {@link #normalise}, because it is lossy
     * in a way that matters: it would turn {@code ass} into {@code as}, which is two characters of
     * nothing and would match most of the dictionary.
     */
    private static String collapseRuns(String normalised) {
        return normalised.replaceAll("(.)\\1+", "$1");
    }

    private static Set<String> load(String... resources) {
        return Arrays.stream(resources)
                .flatMap(resource -> readLines(resource).stream())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static List<String> readLines(String resource) {
        try (InputStream in = HandlePolicy.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing word list on the classpath: " + resource);
            }
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines()
                        .map(line -> line.split("#", 2)[0].trim().toLowerCase(Locale.ROOT))
                        .filter(line -> line.length() >= MIN_LENGTH)
                        .toList();
            }
        } catch (IOException e) {
            // A handle screen that silently loaded nothing would pass every word on the list.
            throw new UncheckedIOException("Could not read word list " + resource, e);
        }
    }
}
