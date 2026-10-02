package org.tracker.gpatracker.leaderboard.service;

import org.springframework.stereotype.Service;
import org.tracker.gpatracker.accounts.service.gpautils.GPABuilder;
import org.tracker.gpatracker.accounts.service.gpautils.GPACalc;
import org.tracker.gpatracker.accounts.service.gpautils.GradeDict;
import org.tracker.gpatracker.accounts.service.gpautils.NumericGradeConverter;
import org.tracker.gpatracker.assessmenttable.model.AssessmentTableDocument;
import org.tracker.gpatracker.assessmenttable.repository.AssessmentTableDocumentRepository;
import org.tracker.gpatracker.courses.model.Course;
import org.tracker.gpatracker.courses.model.PastCourse;
import org.tracker.gpatracker.courses.repository.CourseRepository;
import org.tracker.gpatracker.courses.repository.PastCourseRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Where a student's cumulative GPA stands right now, on the evidence they have reported.
 *
 * <p>Cumulative rather than term-only, because the baseline it is compared against is the
 * cumulative GPA from their transcript. A term-only figure would make {@code projected − baseline}
 * measure two different things at once, and a student with one strong course would appear to have
 * leapt several points.
 *
 * <p>Deliberately recomputed from the assessment tables rather than read from
 * {@code Student.gpa12}. That field is maintained from the grade the client sends alongside each
 * table save, which is a number the client computed — fine for a dashboard, useless as the input to
 * a ranked score. Everything here goes back to the stored table and its server-set timestamps.
 */
@Service
public class ProjectedGpaService {

    private final AssessmentTableDocumentRepository tables;
    private final PastCourseRepository pastCourses;
    private final CourseRepository courses;
    private final GradeDict gradeDict = new GradeDict();
    private final GPACalc gpaCalc = new GPACalc();

    public ProjectedGpaService(AssessmentTableDocumentRepository tables,
                               PastCourseRepository pastCourses,
                               CourseRepository courses) {
        this.tables = tables;
        this.pastCourses = pastCourses;
        this.courses = courses;
    }

    /**
     * The student's projected 12-point GPA for a term.
     *
     * <p>Past courses come from the transcript and are taken as read; current courses are included
     * only if {@link CourseScoring} accepts their table. A term where nothing qualifies yet simply
     * returns the transcript GPA, which scores as sitting exactly on the baseline — correct, since
     * they have not yet reported anything that moves them.
     *
     * @param now the clock the entry window is checked against, passed in so the caller controls it
     */
    public BigDecimal projectedGpa12(Long studentId, String term, Instant now) {
        List<GPABuilder> all = new ArrayList<>();

        for (PastCourse past : pastCourses.findByOwnerId(studentId)) {
            GPABuilder builder = new GPABuilder();
            builder.setCode(past.getName());
            builder.setUnits(past.getUnits());
            builder.setGrade(past.getGrade());
            all.add(builder);
        }

        for (AssessmentTableDocument table : currentTerm(studentId, term)) {
            projectedCourse(table, now).ifPresent(all::add);
        }

        return gpaCalc.getGPA(all, gradeDict.getMacGradeDict());
    }

    /** This student's stored tables for the term. Package-private so the ranking job can reuse it. */
    List<AssessmentTableDocument> currentTerm(Long studentId, String term) {
        List<AssessmentTableDocument> forTerm = new ArrayList<>();
        for (AssessmentTableDocument table : tables.findByOwnerId(studentId)) {
            if (table != null && term.equals(table.getTerm())) {
                forTerm.add(table);
            }
        }
        return forTerm;
    }

    private Optional<GPABuilder> projectedCourse(AssessmentTableDocument table, Instant now) {
        Optional<BigDecimal> percentage = CourseScoring.percentage(table.getSchemes(), now);
        if (percentage.isEmpty()) {
            return Optional.empty();
        }
        Optional<Course> course = courses.findBycourseCode(table.getCourseCode());
        if (course.isEmpty() || course.get().getCourseCredits() == null) {
            // A course the catalog does not know has no credit weight, so it cannot be averaged in.
            return Optional.empty();
        }

        GPABuilder builder = new GPABuilder();
        builder.setCode(table.getCourseCode());
        builder.setUnits(course.get().getCourseCredits().toString());
        // Through the same percentage-to-letter table the dashboard uses, so a course contributes
        // the same points to a leaderboard score as it does to the GPA the student already sees.
        builder.setGrade(NumericGradeConverter.toLetter(percentage.get()));
        return Optional.of(builder);
    }
}
