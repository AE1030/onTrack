package org.tracker.gpatracker.avenue.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class D2LAPIService {

    private final D2LTokenService tokenService;
    private final RestClient restClient;

    private static final String LE_VER = "1.57";
    private static final String LP_VER = "1.43";

    public D2LAPIService(D2LTokenService tokenService, @Value("${d2l.api.base-url}") String d2lBaseUrl) {
        this.tokenService = tokenService;
        this.restClient = RestClient.builder()
                .baseUrl(d2lBaseUrl)
                .build();
    }

    // Low-level helper method to inject the bearer token and execute the GET call
    private String get(String path) {
        String token = tokenService.getActiveToken();
        return restClient.get()
                .uri(path)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(String.class);
    }

    // ==========================================
    // DROPBOX / ASSIGNMENT ENDPOINTS
    // ==========================================

    public String getDropboxFolders(String orgUnitId) {
        return get("/d2l/api/le/" + LE_VER + "/" + orgUnitId + "/dropbox/folders/");
    }

    public String getDropboxFolder(String orgUnitId, String folderId) {
        return get("/d2l/api/le/" + LE_VER + "/" + orgUnitId + "/dropbox/folders/" + folderId);
    }

    public String getDropboxSubmissions(String orgUnitId, String folderId) {
        return get("/d2l/api/le/" + LE_VER + "/" + orgUnitId + "/dropbox/folders/" + folderId + "/submissions/");
    }

    // ==========================================
    // CONTENT ENDPOINTS
    // ==========================================

    public String getContentToc(String orgUnitId) {
        return get("/d2l/api/le/" + LE_VER + "/" + orgUnitId + "/content/toc");
    }

    public String getContentTopic(String orgUnitId, String topicId) {
        return get("/d2l/api/le/" + LE_VER + "/" + orgUnitId + "/content/topics/" + topicId);
    }

    public String getContentModules(String orgUnitId) {
        return get("/d2l/api/le/" + LE_VER + "/" + orgUnitId + "/content/root/");
    }

    public String getContentModule(String orgUnitId, String moduleId) {
        return get("/d2l/api/le/" + LE_VER + "/" + orgUnitId + "/content/modules/" + moduleId + "/structure/");
    }

    // ==========================================
    // USER INFO ENDPOINTS
    // ==========================================

    public String whoami() {
        return get("/d2l/api/lp/" + LP_VER + "/users/whoami");
    }

    // ==========================================
    // GRADES ENDPOINTS
    // ==========================================

    public String getMyGradeValues(String orgUnitId) {
        return get("/d2l/api/le/" + LE_VER + "/" + orgUnitId + "/grades/values/myGradeValues/");
    }

    public String getGradeObjects(String orgUnitId) {
        return get("/d2l/api/le/" + LE_VER + "/" + orgUnitId + "/grades/");
    }

    // ==========================================
    // CALENDAR ENDPOINTS
    // ==========================================

    public String getMyCalendarEvents(String orgUnitId, String startDateTime, String endDateTime) {
        String uriString = UriComponentsBuilder.fromPath("/d2l/api/le/" + LE_VER + "/" + orgUnitId + "/calendar/events/myEvents/")
                .queryParam("startDateTime", startDateTime)
                .queryParam("endDateTime", endDateTime)
                .toUriString();
        return get(uriString);
    }

    // ==========================================
    // NEWS / ANNOUNCEMENTS ENDPOINTS
    // ==========================================

    public String getNews(String orgUnitId) {
        return get("/d2l/api/le/" + LE_VER + "/" + orgUnitId + "/news/");
    }

    // ==========================================
    // ENROLLMENTS ENDPOINTS
    // ==========================================

    public String getMyEnrollments() {
        return get("/d2l/api/lp/" + LP_VER + "/enrollments/myenrollments/");
    }
}
