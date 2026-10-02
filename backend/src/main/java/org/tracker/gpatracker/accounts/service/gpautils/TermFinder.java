package org.tracker.gpatracker.accounts.service.gpautils;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TermFinder {

    /**
     * A transcript term header: {@code --- 2026 Winter ---}.
     *
     * <p>The season is a run of letters that may contain "/" or spaces, because
     * "Spring/Summer" is a real McMaster term. The previous {@code [A-Za-z]+} could not
     * cross the slash, so that header was not recognised as a header at all -- which left
     * the current-term window open and enrolled every course that followed it.
     */
    private static final Pattern TERM_PATTERN =
            Pattern.compile("^-{2,}\\s*(\\d{4})\\s+([A-Za-z][A-Za-z/ ]*?)\\s*-{2,}$");

    /** A parsed term header. */
    public record Term(int year, String season) {}

    public boolean containsTerm(String line) {
        return match(line) != null;
    }

    /** The header's year and season, or {@code null} when the line is not a term header. */
    public Term parseTerm(String line) {
        Matcher m = match(line);
        if (m == null) {
            return null;
        }
        return new Term(Integer.parseInt(m.group(1)), normalize(m.group(2)));
    }

    /**
     * Whether this header opens the configured current term. Compared on year and season
     * separately rather than against a rendered string, so the config format and the
     * transcript's header format no longer have to agree character for character.
     */
    public boolean containsCurrentTerm(String line, int year, String season) {
        Term term = parseTerm(line);
        return term != null && term.year() == year && term.season().equals(normalize(season));
    }

    private Matcher match(String line) {
        if (line == null) {
            return null;
        }
        Matcher m = TERM_PATTERN.matcher(line.trim());
        return m.matches() ? m : null;
    }

    private static String normalize(String season) {
        return season == null ? "" : season.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
