import type { QueryClient } from "@tanstack/react-query";
import { qk } from "./keys";

/**
 * Marks every cached view of due dates stale: the dashboard's upcoming list and the calendar.
 *
 * Both are read from the server's calendar projection, which the server rebuilds before it answers
 * any write that changes an assessment (a table save, a syllabus import, a course delete). The
 * screens that make those writes only refreshed the course list and the GPA, so a new due date
 * stayed off the dashboard until a page reload. Call this after any such write.
 *
 * Invalidating does not fetch for a screen that is not showing; it refetches when the student
 * goes back to it.
 */
export function invalidateDueDates(queryClient: QueryClient) {
  queryClient.invalidateQueries({ queryKey: qk.upcomingEvents });
  queryClient.invalidateQueries({ queryKey: qk.calendarEvents });
}
