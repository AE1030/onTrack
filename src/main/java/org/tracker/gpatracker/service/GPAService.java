package org.tracker.gpatracker.service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.tracker.gpatracker.service.GPAUtils.*;
import java.util.ArrayList;
import java.util.List;

@Service
public class GPAService {
    private static final Logger log = LoggerFactory.getLogger(GPAService.class);
    private final GradeFinder gradeFinder;
    private final CodeFinder codeFinder;
    private final GradeDict gradeDict;
    private final TermFinder termFinder;
    private final GPACalc gpaCalc;
    private String [] line;
    List<GPABuilder> courseList = new ArrayList<>();
    List<String> currentCourses = new ArrayList<>();
    private final UnitFinder unitFinder;
    private final String currentTerm;
    private Boolean termFound;

    public GPAService(){
        gradeFinder = new GradeFinder();
        codeFinder = new CodeFinder();
        gradeDict = new GradeDict();
        gpaCalc = new GPACalc();
        unitFinder = new UnitFinder();
        termFinder = new TermFinder();
        currentTerm = "--- 2026 Winter ---";//varies per term
        termFound = false;
    }

    public Double processFile(MultipartFile file) {
        try{
            line = TextConverter.convertPDFtoTxt(file);
        } catch (Exception e){
            throw new RuntimeException(e);
        }

        //temp logs
        log.info("Uploaded transcript text lines: {}", line.length);
        for (int idx = 0; idx < line.length; idx++) {
            log.info("line[{}]: {}", idx, line[idx]);
        }

        for (int i = 0; i < line.length; i++) {
            String currentLine = line[i];
            if (termFinder.containsTerm(currentLine)) {
                termFound = termFinder.containsCurrentTerm(currentLine, currentTerm);
            }

            if (codeFinder.containsCourse(currentLine)) {
                String code = codeFinder.getCourse(currentLine); //check for multi term course and adjust accordingly :)
                /*For multi term courses I will have it as A/B for both semesters, this shouldnt affect anything with transcript upload
                * For manual uplaod I will have to take the total credits and divide them by 2 for A/B courses:)*/
                String units = null;
                String grade = null;
                if(termFound){
                    currentCourses.add(code);
                }
                // search current line plus next six lines (window of 7) for units/grade
                for (int j = i; j <= i + 7 && j < line.length; j++) {
                    String candidate = line[j];
                    if (units == null && unitFinder.containsUnits(candidate)) {
                        units = unitFinder.getUnits(candidate);
                    }
                    if (grade == null && gradeFinder.containsGrade(candidate)) {
                        grade = gradeFinder.getGrade(candidate);
                        if (!gradeFinder.containsLetterGrade(candidate)) {
                            // Skip this course entirely if the first grade we see is non-letter
                            grade = null;
                            break;
                        }
                    }
                    if (units != null && grade != null) {
                        break;
                    }
                }
                if (units != null && grade != null) {
                    GPABuilder gpaBuilder = new GPABuilder(); // new instance per course/grade pair
                    gpaBuilder.setCode(code);
                    gpaBuilder.setUnits(units);
                    gpaBuilder.setGrade(grade);
                    courseList.add(gpaBuilder);
                }
            }
        }

        System.out.println(courseList);
        System.out.println(currentCourses);

        if (courseList.isEmpty()) {
            throw new RuntimeException("No course/grade/units pairs found in uploaded file.");
        }
        log.info("Parsed course entries: {}", courseList.size());
        return gpaCalc.getGPA(courseList, gradeDict.getMacGradeDict());
    }
}
