/**
 * Every query key in the app. Nothing may spell a key inline — an eviction
 * that misspells a key fails silently and shows stale data.
 */
export const qk = {
  dashboard: ["dashboard"] as const,
  upcomingEvents: ["upcoming-events"] as const,
  pastCourses: ["past-courses"] as const,
  calendarEvents: ["calendar-events"] as const,

  /** Which terms this student has. Changes only when a course is added or deleted. */
  terms: ["terms"] as const,

  /**
   * Courses for one term.
   *
   * The term is the second element, so ["current-courses"] is still a valid prefix: passing it
   * to invalidateQueries evicts every term at once, which is what a transcript upload needs
   * because it rewrites more than one term's worth of rows. Everything else uses the full key
   * so the other terms in the cache stay warm.
   */
  currentCourses: (term: string) => ["current-courses", term] as const,

  assessmentTable: (courseCode: string, term: string) =>
    ["assessment-table", courseCode, term] as const,

  // Leaderboard. The board and the caller's standing are separate keys because they invalidate on
  // different events: joining or renaming changes the status immediately, while the rows only
  // change when the ranking job has run (three times a day).
  leaderboard: ["leaderboard"] as const,
  leaderboardStatus: ["leaderboard-status"] as const,
};
