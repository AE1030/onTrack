package org.tracker.gpatracker.calendar.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.tracker.gpatracker.accounts.model.Student;
import org.tracker.gpatracker.accounts.repository.StudentRepo;
import org.tracker.gpatracker.calendar.dto.GoogleCalendarDateTime;
import org.tracker.gpatracker.calendar.dto.GoogleCalendarEventRequest;
import org.tracker.gpatracker.calendar.dto.GoogleTokens;
import org.tracker.gpatracker.calendar.model.CalendarEvents;
import org.tracker.gpatracker.calendar.model.CalendarEvents.InternalCalendarEvent;
import org.tracker.gpatracker.calendar.model.GoogleCalendarAccount;
import org.tracker.gpatracker.calendar.model.CalendarProvider;
import org.tracker.gpatracker.calendar.model.GoogleCalendarExport;
import org.tracker.gpatracker.calendar.model.GoogleCalendarExport.GoogleCalendarEvent;
import org.tracker.gpatracker.calendar.repository.GoogleCalendarAccountRepository;
import org.tracker.gpatracker.calendar.repository.CalendarEventsRepository;
import org.tracker.gpatracker.calendar.repository.GoogleCalendarExportRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class GoogleCalendarSyncService {

    private static final Logger logger = LoggerFactory.getLogger(GoogleCalendarSyncService.class);
    private static final String PRIMARY_CALENDAR = "primary";

    private final ConcurrentHashMap<Long, Boolean> activeSyncs = new ConcurrentHashMap<>();

    private final CalendarEventsRepository calendarEventsRepository;
    private final GoogleCalendarExportRepository exportRepository;
    private final GoogleCalendarAccountRepository googleCalendarAccountRepository;
    private final StudentRepo studentRepo;
    private final GoogleCalendarService googleCalendarService;
    private final GoogleOAuthService googleOAuthService;

    public GoogleCalendarSyncService(
            CalendarEventsRepository calendarEventsRepository,
            GoogleCalendarExportRepository exportRepository,
            GoogleCalendarAccountRepository googleCalendarAccountRepository,
            StudentRepo studentRepo,
            GoogleCalendarService googleCalendarService,
            GoogleOAuthService googleOAuthService
    ) {
        this.calendarEventsRepository = calendarEventsRepository;
        this.exportRepository = exportRepository;
        this.googleCalendarAccountRepository = googleCalendarAccountRepository;
        this.studentRepo = studentRepo;
        this.googleCalendarService = googleCalendarService;
        this.googleOAuthService = googleOAuthService;
    }

    public void sync(Long studentId) {
        // Prevent concurrent syncs for the same student
        if (activeSyncs.putIfAbsent(studentId, Boolean.TRUE) != null) {
            logger.info("Sync already in progress for student {}, skipping", studentId);
            return;
        }
        try {
            doSync(studentId);
        } finally {
            activeSyncs.remove(studentId);
        }
    }

    private void doSync(Long studentId) {
        // Step 1: Get current projection
        CalendarEvents calendarEvents = calendarEventsRepository.findByStudentId(studentId)
                .orElseThrow(() -> new IllegalStateException("No calendar events found for student " + studentId));

        List<InternalCalendarEvent> currentEvents = calendarEvents.getEvents() != null
                ? calendarEvents.getEvents()
                : Collections.emptyList();

        // Step 2: Get latest export snapshot
        GoogleCalendarExport lastExport = exportRepository
                .findTopByStudentIdOrderByExportedAtDesc(studentId)
                .orElse(null);

        List<GoogleCalendarEvent> previousEvents = lastExport != null && lastExport.getEvents() != null
                ? lastExport.getEvents()
                : Collections.emptyList();

        // Step 3: Diff by eventKey
        Map<String, InternalCalendarEvent> currentMap = new LinkedHashMap<>();
        for (InternalCalendarEvent e : currentEvents) {
            currentMap.put(e.getEventKey(), e);
        }

        Map<String, GoogleCalendarEvent> previousMap = new LinkedHashMap<>();
        for (GoogleCalendarEvent e : previousEvents) {
            previousMap.put(e.getEventKey(), e);
        }

        Set<String> toDelete = previousMap.keySet().stream()
                .filter(key -> !currentMap.containsKey(key))
                .collect(Collectors.toSet());

        Set<String> toAdd = currentMap.keySet().stream()
                .filter(key -> !previousMap.containsKey(key))
                .collect(Collectors.toSet());

        // Step 4: Get Google credentials
        String accessToken = resolveAccessToken(studentId);

        // Step 5: Apply Google operations
        deleteRemovedEvents(accessToken, toDelete, previousMap);

        List<GoogleCalendarEvent> newExportEvents = new ArrayList<>();

        // Keep existing events that are still current
        for (GoogleCalendarEvent prev : previousEvents) {
            if (!toDelete.contains(prev.getEventKey())) {
                newExportEvents.add(prev);
            }
        }

        // Add new events
        createNewEvents(accessToken, toAdd, currentMap, newExportEvents);

        // Step 6: Save new export snapshot
        GoogleCalendarExport newExport = new GoogleCalendarExport();
        newExport.setStudentId(studentId);
        newExport.setExportedAt(Instant.now());
        newExport.setEvents(newExportEvents);
        exportRepository.save(newExport);

        logger.info("Google Calendar sync completed for student {}: {} added, {} deleted, {} kept",
                studentId, toAdd.size(), toDelete.size(), newExportEvents.size() - toAdd.size());
    }

    private void deleteRemovedEvents(String accessToken, Set<String> toDelete, Map<String, GoogleCalendarEvent> previousMap) {
        for (String key : toDelete) {
            GoogleCalendarEvent prev = previousMap.get(key);
            if (prev.getGoogleEventId() == null) continue;
            try {
                googleCalendarService.deleteEvent(accessToken, prev.getGoogleCalendarId(), prev.getGoogleEventId());
                logger.info("Deleted Google Calendar event: {}", key);
            } catch (Exception e) {
                logger.error("Failed to delete Google Calendar event {}: {}", key, e.getMessage());
            }
        }
    }

    private void createNewEvents(String accessToken, Set<String> toAdd,
                                  Map<String, InternalCalendarEvent> currentMap,
                                  List<GoogleCalendarEvent> newExportEvents) {
        for (String key : toAdd) {
            InternalCalendarEvent current = currentMap.get(key);
            GoogleCalendarEventRequest request = toGoogleRequest(current);
            try {
                String googleEventId = googleCalendarService.createEvent(accessToken, PRIMARY_CALENDAR, request);
                GoogleCalendarEvent exported = new GoogleCalendarEvent();
                exported.setCourseCode(current.getCourseCode());
                exported.setAssessmentName(current.getAssessmentName());
                exported.setDueDate(current.getDueDate());
                exported.setStartTime(current.getStartTime());
                exported.setEndTime(current.getEndTime());
                exported.setAllDay(current.isAllDay());
                exported.setEventKey(current.getEventKey());
                exported.setGoogleEventId(googleEventId);
                exported.setGoogleCalendarId(PRIMARY_CALENDAR);
                newExportEvents.add(exported);
                logger.info("Created Google Calendar event: {}", key);
            } catch (Exception e) {
                logger.error("Failed to create Google Calendar event {}: {}", key, e.getMessage());
            }
        }
    }

    public void upsertCalendarAccount(Long userId, GoogleTokens tokens) {
        googleCalendarAccountRepository
                .findByUserIdAndProvider(userId, CalendarProvider.GOOGLE)
                .ifPresent(googleCalendarAccountRepository::delete);

        GoogleCalendarAccount account = new GoogleCalendarAccount();
        account.setUserId(userId);
        account.setProvider(CalendarProvider.GOOGLE);
        account.setAccessToken(tokens.accessToken());
        account.setRefreshToken(tokens.refreshToken());
        account.setExpiresAt(tokens.expiresAt());
        account.setEmail(tokens.email());
        googleCalendarAccountRepository.save(account);
    }

    public boolean isAccountConnectedForEmail(Long userId, String email) {
        return googleCalendarAccountRepository
                .findByUserIdAndProvider(userId, CalendarProvider.GOOGLE)
                .map(account -> email.equalsIgnoreCase(account.getEmail()))
                .orElse(false);
    }

    private String resolveAccessToken(Long studentId) {
        Student student = studentRepo.findById(studentId)
                .orElseThrow(() -> new IllegalStateException("Student not found: " + studentId));
        Long userId = student.getUser().getId();

        GoogleCalendarAccount account = googleCalendarAccountRepository
                .findByUserIdAndProvider(userId, CalendarProvider.GOOGLE)
                .orElseThrow(() -> new IllegalStateException("Google Calendar not connected for student " + studentId));

        if (account.getExpiresAt().isBefore(Instant.now())) {
            try {
                GoogleTokens refreshed = googleOAuthService.refreshAccessToken(account.getRefreshToken());
                account.setAccessToken(refreshed.accessToken());
                if (refreshed.refreshToken() != null) {
                    account.setRefreshToken(refreshed.refreshToken());
                }
                account.setExpiresAt(refreshed.expiresAt());
                googleCalendarAccountRepository.save(account);
            } catch (Exception e) {
                logger.warn("Refresh token revoked/expired for student {}, removing stale account", studentId);
                googleCalendarAccountRepository.delete(account);
                throw new IllegalStateException("Google Calendar authorization expired. Please reconnect your account.");
            }
        }

        return account.getAccessToken();
    }

    private GoogleCalendarEventRequest toGoogleRequest(InternalCalendarEvent event) {
        String summary = event.getCourseCode() + " - " + event.getAssessmentName();
        String description = event.getDescription();

        GoogleCalendarDateTime start;
        GoogleCalendarDateTime end;

        if (event.isAllDay()) {
            start = new GoogleCalendarDateTime(event.getDueDate(), null, null);
            end = new GoogleCalendarDateTime(event.getDueDate(), null, null);
        } else {
            String startDateTime = event.getDueDate() + "T" + event.getStartTime() + ":00";
            String endDateTime;
            if (event.getEndTime() != null && !event.getEndTime().isBlank()) {
                endDateTime = event.getDueDate() + "T" + event.getEndTime() + ":00";
            } else {
                endDateTime = startDateTime;
            }
            start = new GoogleCalendarDateTime(null, startDateTime, null);
            end = new GoogleCalendarDateTime(null, endDateTime, null);
        }

        return new GoogleCalendarEventRequest(summary, description, start, end);
    }
}
