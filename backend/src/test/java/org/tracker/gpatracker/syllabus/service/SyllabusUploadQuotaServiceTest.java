package org.tracker.gpatracker.syllabus.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.tracker.gpatracker.accounts.service.StudentService;
import org.tracker.gpatracker.syllabus.model.SyllabusUploadQuota;
import org.tracker.gpatracker.syllabus.repository.SyllabusUploadQuotaRepository;
import org.tracker.gpatracker.terms.TermService;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SyllabusUploadQuotaServiceTest {

    private static final Long STUDENT = 42L;

    private SyllabusUploadQuotaRepository repository;
    private TermService termService;
    private SyllabusUploadQuotaService service;

    /** Stands in for the table: whatever was saved last is what the next lookup finds. */
    private SyllabusUploadQuota stored;

    @BeforeEach
    void setUp() {
        repository = mock(SyllabusUploadQuotaRepository.class);
        termService = mock(TermService.class);
        when(termService.getCurrentTerm()).thenReturn("Fall 2026");
        when(repository.findByOwnerId(STUDENT)).thenAnswer(inv -> Optional.ofNullable(stored));
        when(repository.save(any(SyllabusUploadQuota.class))).thenAnswer(inv -> {
            stored = inv.getArgument(0);
            return stored;
        });
        service = new SyllabusUploadQuotaService(repository, mock(StudentService.class), termService);
    }

    @Test
    @DisplayName("a new student gets three uploads and the fourth is refused")
    void threePerTerm() {
        assertThat(service.getRemainingUploads(STUDENT)).isEqualTo(3);

        assertThat(consume()).isEqualTo(2);
        assertThat(consume()).isEqualTo(1);
        assertThat(consume()).isEqualTo(0);

        assertThatThrownBy(this::consume)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(stored.getUploadsUsed()).isEqualTo(3);
    }

    @Test
    @DisplayName("a new term resets the count")
    void newTermResets() {
        consume();
        consume();
        consume();

        when(termService.getCurrentTerm()).thenReturn("Winter 2027");

        assertThat(service.getRemainingUploads(STUDENT)).isEqualTo(3);
        assertThat(consume()).isEqualTo(2);
        assertThat(stored.getTerm()).isEqualTo("Winter 2027");
    }

    @Test
    @DisplayName("a row carried over from the lifetime quota (no term) starts fresh")
    void nullTermStartsFresh() {
        stored = new SyllabusUploadQuota();
        stored.setOwnerId(STUDENT);
        stored.setUploadsUsed(3);

        assertThat(service.getRemainingUploads(STUDENT)).isEqualTo(3);
        assertThat(consume()).isEqualTo(2);
    }

    @Test
    @DisplayName("a refund gives back one upload and never goes below zero used")
    void restore() {
        consume();
        assertThat(service.restoreQuota(STUDENT)).isEqualTo(3);
        assertThat(service.restoreQuota(STUDENT)).isEqualTo(3);
        assertThat(stored.getUploadsUsed()).isZero();
    }

    @Test
    @DisplayName("uploads closer than 20 seconds apart are rate limited")
    void rateLimited() {
        consume();
        assertThatThrownBy(() -> service.consumeUploadQuota(STUDENT))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    /** Consumes one upload, backdating the last one so the 20 second rate limit stays out of the way. */
    private int consume() {
        if (stored != null) {
            stored.setLastUploadAt(Instant.now().minusSeconds(60));
        }
        return service.consumeUploadQuota(STUDENT);
    }
}
