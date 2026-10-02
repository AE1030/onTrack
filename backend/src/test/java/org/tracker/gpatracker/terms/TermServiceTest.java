package org.tracker.gpatracker.terms;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.tracker.gpatracker.courses.model.CourseEnrollement;
import org.tracker.gpatracker.courses.model.CourseEnrollementKey;
import org.tracker.gpatracker.courses.repository.CourseEnrollementRepository;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TermServiceTest {

    @Mock
    private CourseEnrollementRepository enrollementRepo;

    private TermService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new TermService(enrollementRepo);
        // @Value does not apply in a unit test, so the configured term is set directly.
        Field currentTerm = TermService.class.getDeclaredField("currentTerm");
        currentTerm.setAccessible(true);
        currentTerm.set(service, "Winter 2026");
    }

    private static CourseEnrollement enrollment(String term) {
        CourseEnrollement e = new CourseEnrollement();
        e.setId(new CourseEnrollementKey(1L, 10L, term));
        return e;
    }

    @Test
    void requireEditable_acceptsTheCurrentTerm() {
        service.requireEditable("Winter 2026");
    }

    @Test
    void requireEditable_refusesAPastTermWith409() {
        assertThatThrownBy(() -> service.requireEditable("Fall 2025"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .as("403 would be mapped to an auth failure by the client and sign the user out")
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void requireEditable_refusesATermThatDoesNotExist() {
        // The same equality check covers invented terms, so there is no second validator.
        assertThatThrownBy(() -> service.requireEditable("Winter 3025"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void requireEditable_refusesNull() {
        assertThatThrownBy(() -> service.requireEditable(null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void resolveForRead_treatsAMissingTermAsTheCurrentOne() {
        assertThat(service.resolveForRead(null)).isEqualTo("Winter 2026");
        assertThat(service.resolveForRead("")).isEqualTo("Winter 2026");
        assertThat(service.resolveForRead("Fall 2025")).isEqualTo("Fall 2025");
    }

    @Test
    void listTerms_includesTheCurrentTermEvenWithNoCourses() {
        when(enrollementRepo.findByStudentsId(1L)).thenReturn(List.of());

        List<TermDTO> terms = service.listTerms(1L);

        assertThat(terms).hasSize(1);
        assertThat(terms.get(0).getTerm()).isEqualTo("Winter 2026");
        assertThat(terms.get(0).isCurrent()).isTrue();
        assertThat(terms.get(0).isEditable()).isTrue();
        assertThat(terms.get(0).getCourseCount()).isZero();
    }

    @Test
    void listTerms_ordersNewestFirstAndMarksOnlyTheCurrentOneEditable() {
        when(enrollementRepo.findByStudentsId(1L)).thenReturn(List.of(
                enrollment("Fall 2025"),
                enrollment("Winter 2025"),
                enrollment("Winter 2026"),
                enrollment("Spring/Summer 2025"),
                enrollment("Fall 2025")));

        List<TermDTO> terms = service.listTerms(1L);

        // Year descending, then season descending within the year: Fall closes a calendar year
        // and Winter opens it, so Winter 2025 is the oldest of the 2025 terms.
        assertThat(terms).extracting(TermDTO::getTerm).containsExactly(
                "Winter 2026", "Fall 2025", "Spring/Summer 2025", "Winter 2025");

        assertThat(terms).filteredOn(TermDTO::isEditable)
                .extracting(TermDTO::getTerm)
                .containsExactly("Winter 2026");

        assertThat(terms.get(1).getCourseCount())
                .as("two enrollments in Fall 2025")
                .isEqualTo(2);
    }

    @Test
    void listTerms_doesNotThrowOnATermItCannotParse() {
        // One malformed row should not take out the whole picker.
        when(enrollementRepo.findByStudentsId(1L)).thenReturn(List.of(enrollment("nonsense")));

        List<TermDTO> terms = service.listTerms(1L);

        assertThat(terms).extracting(TermDTO::getTerm)
                .containsExactly("Winter 2026", "nonsense");
    }
}
