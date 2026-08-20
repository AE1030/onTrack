package org.tracker.gpatracker.assessmenttable.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.tracker.gpatracker.assessmenttable.service.AssessmentTableService;
import org.tracker.gpatracker.support.ContainerIntegrationBase;
import org.tracker.gpatracker.syllabus.dto.AssessmentTableDTO;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssessmentTableControllerTest extends ContainerIntegrationBase {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AssessmentTableService assessmentTableService;

    @Test
    void getAssessmentTable_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/assessment-table/get-assessment-table?courseCode=COMP101&term=Winter 2026"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "testuser")
    void getAssessmentTable_notFound_returns404() throws Exception {
        when(assessmentTableService.getByStudentIdAndCourseCodeAndTerm("COMP101", "Winter 2026"))
                .thenReturn(Map.of());

        mockMvc.perform(get("/api/assessment-table/get-assessment-table?courseCode=COMP101&term=Winter 2026"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "testuser")
    void getAssessmentTable_found_returns200WithBody() throws Exception {
        AssessmentTableDTO dto = new AssessmentTableDTO();
        dto.setAssessmentName("Final Exam");
        dto.setWeights(new BigDecimal[]{new BigDecimal("40")});
        dto.setDueDate("2026-04-15");
        dto.setStartTime("09:00");
        dto.setEndTime("12:00");
        dto.setLocation("Gym A");

        Map<String, List<AssessmentTableDTO>> result = new LinkedHashMap<>();
        result.put("Standard", List.of(dto));

        when(assessmentTableService.getByStudentIdAndCourseCodeAndTerm("COMP101", "Winter 2026"))
                .thenReturn(result);

        mockMvc.perform(get("/api/assessment-table/get-assessment-table?courseCode=COMP101&term=Winter 2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.Standard").isArray())
                .andExpect(jsonPath("$.Standard", hasSize(1)))
                .andExpect(jsonPath("$.Standard[0].assessmentName").value("Final Exam"))
                .andExpect(jsonPath("$.Standard[0].weights[0]").value(40))
                .andExpect(jsonPath("$.Standard[0].dueDate").value("2026-04-15"))
                .andExpect(jsonPath("$.Standard[0].location").value("Gym A"));
    }

    @Test
    @WithMockUser(username = "testuser")
    void getAssessmentTable_nullResult_returns404() throws Exception {
        when(assessmentTableService.getByStudentIdAndCourseCodeAndTerm("COMP101", "Winter 2026"))
                .thenReturn(null);

        mockMvc.perform(get("/api/assessment-table/get-assessment-table?courseCode=COMP101&term=Winter 2026"))
                .andExpect(status().isNotFound());
    }
}
