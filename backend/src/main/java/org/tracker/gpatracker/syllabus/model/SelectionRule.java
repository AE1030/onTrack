package org.tracker.gpatracker.syllabus.model;

/**
 * How a course combines its grading schemes when it publishes more than one.
 *
 * <p>Promoted out of {@code GradingScheme.java}, where it sat as a package-private enum in a file
 * named after another type. It is part of the stored jsonb payload and therefore part of a contract
 * shared with {@code tools/syllabus-pipeline}, so it should be findable and referenceable from a
 * test rather than visible only inside this package.
 *
 * <p>{@code MAX} is the only rule the extractor emits: where a syllabus offers alternative weighting
 * schemes, the student's grade is the best of them.
 */
public enum SelectionRule {
    MAX
}
