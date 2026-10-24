package org.tracker.gpatracker.leaderboard.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
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
 * <p>The screen runs over normalised forms of the handle, because the interesting input is never
 * the plain word. Digits and symbols standing in for letters are undone ({@code 1} is tried as both
 * {@code i} and {@code l}), separators are dropped, and a second spelling swaps the common
 * sound-alikes ({@code ph}, {@code v}, {@code zz}). Each listed word is matched as a pattern in which
 * every letter may be repeated but never shortened, so {@code fuuuck} and {@code asssshole} are
 * caught while {@code bobsled} is not mistaken for {@code boobs}.
 *
 * <p>Words are screened in two ways, and which list a word sits on is the whole design:
 *
 * <ul>
 *   <li>{@code substring.txt}, {@code hate.txt} and {@code reserved.txt} match anywhere. Their
 *       terms appear inside no common English word, so matching loosely is nearly free.</li>
 *   <li>{@code whole-word.txt} matches only a complete token. These terms <em>do</em> occur inside
 *       ordinary words — {@code ass} inside {@code password}, {@code mong} inside {@code among},
 *       {@code semen} inside {@code basement} — so matching them loosely would reject handles that
 *       are entirely innocent.</li>
 * </ul>
 *
 * <p>Two smaller lists cover what letters cannot. {@code numeric.txt} holds number codes, which
 * normalising would otherwise turn into letters or drop. {@code allow.txt} holds real names and
 * ordinary words that contain a listed term ({@code Nazir}, {@code Yamashita}, {@code supporter});
 * a word on it is taken out of the handle before the screen runs, so {@code Nazir_99} passes while
 * {@code Nazir_<slur>} is still judged on the rest.
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

    /** Where a word ends: a separator or a lower-to-upper case change. Digits stay in the word. */
    private static final Pattern WORD_SPLIT = Pattern.compile("[_\\-]+|(?<=[a-z])(?=[A-Z])");

    /** Where a token ends: a separator, a digit, or a lower-to-upper case change. */
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[_\\-0-9]+|(?<=[a-z])(?=[A-Z])");

    private static final Pattern DIGIT_RUN = Pattern.compile("[0-9]+");

    /** Past this many 1s, trying every i/l combination stops being worth it; both uniform readings are still tried. */
    private static final int MAX_AMBIGUOUS_ONES = 6;

    private static final Pattern ANYWHERE = compile(
            load("/profanity/substring.txt", "/profanity/hate.txt", "/profanity/reserved.txt"));
    private static final Pattern TOKEN_ONLY = compile(load("/profanity/whole-word.txt"));
    private static final Set<String> NUMERIC = loadRaw("/profanity/numeric.txt");
    private static final Set<String> ALLOW = load("/profanity/allow.txt");

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

    /** Whether any list matches, once the usual disguises have been undone. */
    static boolean isScreened(String handle) {
        if (handle == null) {
            return false;
        }
        if (hasNumericCode(handle)) {
            return true;
        }

        // Allowed words leave the handle entirely, so they can neither match nor lend letters to a
        // match that spans into them.
        List<String> words = new ArrayList<>();
        for (String word : WORD_SPLIT.split(handle)) {
            if (!word.isEmpty() && !ALLOW.contains(lettersOnly(word))) {
                words.add(word);
            }
        }
        if (words.isEmpty()) {
            return false;
        }

        // Anywhere: the remaining words run together, so f_u_c_k reads as one word.
        for (String spelling : spellings(String.join("", words))) {
            if (ANYWHERE.matcher(spelling).find()) {
                return true;
            }
        }

        // Whole tokens, cut two ways: with digits read as letters (a55, t1t), and with digits as
        // separators (tit4tat), because a digit is sometimes a disguise and sometimes a space.
        for (String word : words) {
            if (tokenMatches(word)) {
                return true;
            }
            for (String token : TOKEN_SPLIT.split(word)) {
                if (!token.isEmpty() && !ALLOW.contains(lettersOnly(token)) && tokenMatches(token)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean tokenMatches(String token) {
        for (String spelling : spellings(token)) {
            if (!spelling.isEmpty() && TOKEN_ONLY.matcher(spelling).matches()) {
                return true;
            }
        }
        return false;
    }

    /** A number code standing alone between letters or separators: Sam1488, 14_88. */
    private static boolean hasNumericCode(String handle) {
        Matcher runs = DIGIT_RUN.matcher(handle.replaceAll("[_\\-]", ""));
        while (runs.find()) {
            if (NUMERIC.contains(runs.group())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every reading of a raw string worth screening: each way of taking its 1s as i or l, each with
     * and without the sound-alike swaps.
     */
    private static Set<String> spellings(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        List<String> readings = new ArrayList<>();
        long ones = lower.chars().filter(ch -> ch == '1').count();
        if (ones == 0) {
            readings.add(lower);
        } else if (ones > MAX_AMBIGUOUS_ONES) {
            readings.add(lower.replace('1', 'i'));
            readings.add(lower.replace('1', 'l'));
        } else {
            readings.addAll(onesAsIorL(lower));
        }

        Set<String> out = new LinkedHashSet<>();
        for (String reading : readings) {
            String normalised = normalise(reading);
            out.add(normalised);
            out.add(soundAlike(normalised));
        }
        return out;
    }

    private static List<String> onesAsIorL(String value) {
        List<String> out = new ArrayList<>(List.of(""));
        for (char ch : value.toCharArray()) {
            List<String> next = new ArrayList<>();
            for (String prefix : out) {
                if (ch == '1') {
                    next.add(prefix + 'i');
                    next.add(prefix + 'l');
                } else {
                    next.add(prefix + ch);
                }
            }
            out = next;
        }
        return out;
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
     * The spellings that sound the same: phuck, fvck, azzhole.
     *
     * <p>Only a doubled z stands in for s. A single one is too common at the start of real names
     * (Zhitomirsky, Zucker) that would then read as something else.
     */
    private static String soundAlike(String normalised) {
        return normalised.replace("ph", "f").replace('v', 'u').replace("zz", "ss");
    }

    private static String lettersOnly(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }

    /**
     * One alternation over every term, each letter run widened to "at least this many".
     *
     * <p>{@code ass} becomes {@code a+s{2,}}, never {@code a+s+}: collapsing a run is lossy, and
     * {@code as} would match most of the dictionary.
     */
    private static Pattern compile(Set<String> terms) {
        String alternation = terms.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(HandlePolicy::stretchable)
                .collect(Collectors.joining("|"));
        return Pattern.compile("(?:" + alternation + ")");
    }

    private static String stretchable(String term) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < term.length()) {
            char ch = term.charAt(i);
            int run = 1;
            while (i + run < term.length() && term.charAt(i + run) == ch) {
                run++;
            }
            out.append(ch).append(run == 1 ? "+" : "{" + run + ",}");
            i += run;
        }
        return out.toString();
    }

    /** Letter terms: lower case, letters only, at least {@link #MIN_LENGTH} long. */
    private static Set<String> load(String... resources) {
        return Arrays.stream(resources)
                .flatMap(resource -> readLines(resource).stream())
                .map(line -> line.replaceAll("[^a-z]", ""))
                .filter(line -> line.length() >= MIN_LENGTH)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Number codes, kept as written. */
    private static Set<String> loadRaw(String resource) {
        return readLines(resource).stream()
                .filter(line -> line.matches("[0-9]{2,}"))
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
                        .filter(line -> !line.isEmpty())
                        .toList();
            }
        } catch (IOException e) {
            // A handle screen that silently loaded nothing would pass every word on the list.
            throw new UncheckedIOException("Could not read word list " + resource, e);
        }
    }
}
