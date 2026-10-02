package org.tracker.gpatracker.terms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.tracker.gpatracker.courses.model.CourseEnrollement;
import org.tracker.gpatracker.courses.repository.CourseEnrollementRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Everything the app knows about terms: which one is current, which ones a student has, and
 * which ones may be written to.
 *
 * <p>Only the current term is editable. Past terms are a reading surface, so every write path
 * calls {@link #requireEditable(String)} before it touches either store. The term on a write
 * request is not there to choose where to write -- it is there so the server can confirm the
 * client is writing where it thinks it is.
 */
@Service
public class TermService {

    /**
     * Season order <em>within</em> a calendar year, earliest first. McMaster's Winter term runs
     * January to April, so it opens the year rather than closing it.
     */
    private static final Map<String, Integer> SEASON_ORDER = Map.of(
            "winter", 1,
            "spring", 2,
            "summer", 2,
            "spring/summer", 2,
            "fall", 3
    );

    private final CourseEnrollementRepository enrollementRepo;

    @Value("${app.current-term}")
    private String currentTerm;

    public TermService(CourseEnrollementRepository enrollementRepo) {
        this.enrollementRepo = enrollementRepo;
    }

    public String getCurrentTerm() {
        return currentTerm;
    }

    /** Whether writes are allowed against this term. Today that means "is it the current one". */
    public boolean isEditable(String term) {
        return currentTerm.equals(term);
    }

    /**
     * Refuses anything but the current term.
     *
     * <p>409 rather than 403 on purpose: {@code authedFetch} on the client maps 401 <em>and</em>
     * 403 to an auth failure and sends the user back to the login screen, so a 403 here would
     * look like a session expiry. 409 is also what this codebase already returns for a refused
     * but authenticated action, and it reaches the client with its message intact.
     *
     * <p>A term that is not a real term at all (a typo, or something a caller invented) fails the
     * same equality check, so there is no second validator to write.
     */
    public void requireEditable(String term) {
        if (!isEditable(term)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Past terms are view only");
        }
    }

    /**
     * The term a read should use. Requests may omit it, which means "the current one" -- that is
     * what keeps an older app build working against a server that has already shipped this.
     */
    public String resolveForRead(String term) {
        return term == null || term.isBlank() ? currentTerm : term;
    }

    /**
     * Every term this student has, newest first, with the current term always present even when
     * nothing is enrolled in it yet.
     */
    public List<TermDTO> listTerms(Long studentId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put(currentTerm, 0);

        for (CourseEnrollement enrollement : enrollementRepo.findByStudentsId(studentId)) {
            String term = enrollement.getId() == null ? null : enrollement.getId().getTerm();
            if (term != null && !term.isBlank()) {
                counts.merge(term, 1, Integer::sum);
            }
        }

        List<TermDTO> terms = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            String term = entry.getKey();
            boolean current = currentTerm.equals(term);
            terms.add(new TermDTO(term, current, current, entry.getValue()));
        }
        terms.sort(newestFirst());
        return terms;
    }

    /**
     * Newest first: year descending, then season descending within the year.
     *
     * <p>Lives here so the client never has to reimplement it. A term whose shape is not
     * "&lt;season&gt; &lt;year&gt;" sorts to the end rather than throwing, because a bad value in
     * one row should not take out the whole picker.
     */
    static Comparator<TermDTO> newestFirst() {
        return Comparator.comparingInt((TermDTO t) -> yearOf(t.getTerm())).reversed()
                .thenComparing(Comparator.comparingInt((TermDTO t) -> seasonRank(t.getTerm())).reversed())
                .thenComparing(TermDTO::getTerm);
    }

    private static int yearOf(String term) {
        int split = term == null ? -1 : term.lastIndexOf(' ');
        if (split < 0) {
            return Integer.MIN_VALUE;
        }
        try {
            return Integer.parseInt(term.substring(split + 1).trim());
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    private static int seasonRank(String term) {
        int split = term == null ? -1 : term.lastIndexOf(' ');
        if (split < 0) {
            return Integer.MIN_VALUE;
        }
        String season = term.substring(0, split).trim().toLowerCase(Locale.ROOT);
        return SEASON_ORDER.getOrDefault(season, Integer.MIN_VALUE);
    }
}
