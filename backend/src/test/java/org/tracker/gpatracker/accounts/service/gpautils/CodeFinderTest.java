package org.tracker.gpatracker.accounts.service.gpautils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Course codes as transcript lines carry them, and the catalog names they normalize to.
 *
 * <p>The A/B rows mirror the layout of a real multi-term pair (first half "0.00/0.00 MT", second
 * half carrying the units and the grade). The A/B S spellings with a trailing S are not confirmed
 * against a real transcript; they are accepted so that either rendering parses.
 */
class CodeFinderTest {

    private final CodeFinder finder = new CodeFinder();

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "COMPSCI 2C03 Data Structures and Algorithms 3.00/3.00 A  | COMPSCI 2C03      | COMPSCI 2C03",
            "ENGINEER 1P13A Integrated Dsgn Projcts in Eng 0.00/0.00 MT | ENGINEER 1P13A | ENGINEER 1P13 A/B",
            "ENGINEER 1P13B Integrated Dsgn Projcts in Eng 13.00/13.00 A+ | ENGINEER 1P13B | ENGINEER 1P13 A/B",
            "BIOLOGY 4C12A Senior Thesis 0.00/0.00 MT                  | BIOLOGY 4C12A     | BIOLOGY 4C12 A/B",
            "BIOLOGY 4F06AS Senior Project 0.00/0.00 MT                | BIOLOGY 4F06AS    | BIOLOGY 4F06 A/B S",
            "BIOLOGY 4F06BS Senior Project 6.00/6.00 A                 | BIOLOGY 4F06BS    | BIOLOGY 4F06 A/B S",
            "BIOLOGY 4F06S Senior Project 6.00/6.00 A                  | BIOLOGY 4F06S     | BIOLOGY 4F06 A/B S",
    })
    @DisplayName("finds the code on a transcript line and normalizes it to the catalog's name")
    void findsAndNormalizes(String line, String found, String normalized) {
        assertThat(finder.containsCourse(line)).isTrue();
        assertThat(finder.getCourse(line)).isEqualTo(found);
        assertThat(finder.normalizeMultiYearCourse(found)).isEqualTo(normalized);
    }

    @Test
    @DisplayName("a following word starting with S is not mistaken for the S suffix")
    void sMustTouchTheCode() {
        assertThat(finder.getCourse("MATH 1ZA3 Section C01 3.00/3.00 B+")).isEqualTo("MATH 1ZA3");
    }

    @ParameterizedTest
    @ValueSource(strings = {"--- 2026 Spring/Summer ---", "Attm./Earned Units", "COMPSCI 2C03X"})
    @DisplayName("lines that are not a course code are not read as one")
    void rejectsNonCourses(String line) {
        assertThat(finder.containsCourse(line)).isFalse();
    }

    @Test
    @DisplayName("isMultiTerm recognizes both multi-term catalog suffixes and nothing else")
    void multiTermSuffixes() {
        assertThat(CodeFinder.isMultiTerm("ARTSSCI 1D06 A/B")).isTrue();
        assertThat(CodeFinder.isMultiTerm("BIOLOGY 4F06 A/B S")).isTrue();
        assertThat(CodeFinder.isMultiTerm("COMPSCI 2C03")).isFalse();
        assertThat(CodeFinder.isMultiTerm(null)).isFalse();
    }
}
