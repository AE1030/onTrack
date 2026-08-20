package org.tracker.gpatracker.avenue.controller;

import org.tracker.gpatracker.avenue.service.D2LAPIService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class D2LController {

    private final D2LAPIService d2lApiService;

    public D2LController(D2LAPIService d2lApiService) {
        this.d2lApiService = d2lApiService;
    }

    // ==========================================
    // DROPBOX / ASSIGNMENT ENDPOINTS
    // ==========================================

    @GetMapping("/api/courses/{id}/dropbox")
    public String getDropboxFolders(@PathVariable String id) {
        return d2lApiService.getDropboxFolders(id);
    }

    @GetMapping("/api/courses/{id}/dropbox/{folderId}")
    public String getDropboxFolder(@PathVariable String id, @PathVariable String folderId) {
        return d2lApiService.getDropboxFolder(id, folderId);
    }

    @GetMapping("/api/courses/{id}/dropbox/{folderId}/submissions")
    public String getDropboxSubmissions(@PathVariable String id, @PathVariable String folderId) {
        return d2lApiService.getDropboxSubmissions(id, folderId);
    }

    // ==========================================
    // CONTENT ENDPOINTS
    // ==========================================

    @GetMapping("/api/courses/{id}/content/toc")
    public String getContentToc(@PathVariable String id) {
        return d2lApiService.getContentToc(id);
    }

    @GetMapping("/api/courses/{id}/content/topics/{topicId}")
    public String getContentTopic(@PathVariable String id, @PathVariable String topicId) {
        return d2lApiService.getContentTopic(id, topicId);
    }

    @GetMapping("/api/courses/{id}/content/modules")
    public String getContentModules(@PathVariable String id) {
        return d2lApiService.getContentModules(id);
    }

    @GetMapping("/api/courses/{id}/content/modules/{moduleId}")
    public String getContentModule(@PathVariable String id, @PathVariable String moduleId) {
        return d2lApiService.getContentModule(id, moduleId);
    }

    // ==========================================
    // GRADES ENDPOINTS
    // ==========================================

    @GetMapping("/api/courses/{id}/grades")
    public String getGrades(@PathVariable String id) {
        return d2lApiService.getMyGradeValues(id);
    }

    @GetMapping("/api/courses/{id}/grades/objects")
    public String getGradeObjects(@PathVariable String id) {
        return d2lApiService.getGradeObjects(id);
    }

    // ==========================================
    // CALENDAR ENDPOINTS
    // ==========================================

    @GetMapping("/api/courses/{id}/calendar")
    public String getCalendar(
            @PathVariable String id,
            @RequestParam String startDateTime,
            @RequestParam String endDateTime) {
        return d2lApiService.getMyCalendarEvents(id, startDateTime, endDateTime);
    }

    // ==========================================
    // NEWS / ANNOUNCEMENTS ENDPOINTS
    // ==========================================

    @GetMapping("/api/courses/{id}/news")
    public String getNews(@PathVariable String id) {
        return d2lApiService.getNews(id);
    }

    // ==========================================
    // USER INFO ENDPOINTS
    // ==========================================

    @GetMapping("/api/user/whoami")
    public String getWhoAmI() {
        return d2lApiService.whoami();
    }

    @GetMapping("/api/user/enrollments")
    public String getEnrollments() {
        return d2lApiService.getMyEnrollments();
    }
}
