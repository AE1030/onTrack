/**
 * A client-side mirror of HandlePolicy, for immediate feedback while typing.
 *
 * The server screens every handle again on join and on rename, and its answer is final. This is
 * here so a student does not discover a 25th character was too many only after a round trip.
 *
 * The blocklist is deliberately not copied. Reproducing it would ship a slur list in a JS bundle
 * that anyone can read, for the sake of moving one error message a few hundred milliseconds
 * earlier. Screened handles come back from the server as "Pick a different handle." and the form
 * shows that.
 */

export const HANDLE_MIN = 3;
export const HANDLE_MAX = 24;

/** Letters, digits, underscore and hyphen. No spaces: a handle is one token on a board row. */
const ALLOWED = /^[A-Za-z0-9_-]+$/;

/** @returns null when the handle looks acceptable, otherwise a message safe to show the student. */
export function localHandleRejection(handle: string): string | null {
  const trimmed = handle.trim();
  if (!trimmed) return "Pick a handle for the board.";
  if (trimmed.length < HANDLE_MIN || trimmed.length > HANDLE_MAX) {
    return `Handles are between ${HANDLE_MIN} and ${HANDLE_MAX} characters.`;
  }
  if (!ALLOWED.test(trimmed)) {
    return "Handles can use letters, numbers, underscores and hyphens.";
  }
  return null;
}
