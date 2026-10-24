import { authedFetch } from "./authedGet";

/** One assessment row as the assessment-table and syllabus endpoints return it. */
export type AssessmentRow = {
  assessmentName: string;
  weights: number[];
  dueDate: string | null;
  startTime: string | null;
  endTime: string | null;
  location: string | null;
  grade: number | null;
};

/** Marking scheme name to its rows. */
export type AssessmentTable = Record<string, AssessmentRow[]>;

/**
 * The course's saved assessment table, falling back to the syllabus-extracted
 * assessments when the student has not saved one yet. Null when neither exists.
 *
 * Shared by My Courses and the Calendar so both read and write the same
 * qk.assessmentTable cache entry with the same shape.
 */
export async function fetchAssessmentTable(
  courseCode: string,
  term: string
): Promise<AssessmentTable | null> {
  const encodedCourse = encodeURIComponent(courseCode);
  const encodedTerm = encodeURIComponent(term);

  const assessmentRes = await authedFetch(
    `/api/assessment-table/get-assessment-table?courseCode=${encodedCourse}&term=${encodedTerm}`
  );

  if (assessmentRes.status !== 404) {
    if (!assessmentRes.ok) throw new Error("Failed to load assessment table");
    const raw = await assessmentRes.text();
    return raw ? JSON.parse(raw) : null;
  }

  const syllabusRes = await authedFetch(
    `/api/syllabus/assessments?courseCode=${encodedCourse}&term=${encodedTerm}`
  );

  // Nothing uploaded for this course yet: a normal empty state.
  if (syllabusRes.status === 404) return null;
  if (!syllabusRes.ok) throw new Error("Failed to load syllabus assessments");

  const raw = await syllabusRes.text();
  return raw ? JSON.parse(raw) : null;
}
