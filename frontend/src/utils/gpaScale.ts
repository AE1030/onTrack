/**
 * Converts between a McMaster 12-point GPA and the 4.0 scale.
 *
 * Mirrors backend GpaScale (accounts/service/gpautils) so the target popup can show the other
 * scale while the student types, and agrees with what the server would fill in. Keep the table and
 * the rounding in step with that file.
 *
 * The scales are not proportional (an 11 is a 3.9, not a 3.67): each whole 12-point value is a
 * letter with fixed 4.0 points, and values between letters interpolate linearly.
 */

/** Index is the 12-point value, from F (0) to A+ (12); the entry is the matching 4.0 value. */
const FOUR_POINT = [0.0, 0.7, 1.0, 1.3, 1.7, 2.0, 2.3, 2.7, 3.0, 3.3, 3.7, 3.9, 4.0];

const round2 = (n: number) => Math.round(n * 100) / 100;

export function toFourPoint(gpa12: number): number {
  const value = Math.min(Math.max(gpa12, 0), 12);
  const lower = Math.floor(value);
  if (lower >= 12) return FOUR_POINT[12];
  const fraction = value - lower;
  return round2(FOUR_POINT[lower] + fraction * (FOUR_POINT[lower + 1] - FOUR_POINT[lower]));
}

export function toTwelvePoint(gpa4: number): number {
  const value = Math.min(Math.max(gpa4, 0), FOUR_POINT[12]);
  for (let lower = 0; lower < 12; lower++) {
    if (value <= FOUR_POINT[lower + 1]) {
      const fraction = (value - FOUR_POINT[lower]) / (FOUR_POINT[lower + 1] - FOUR_POINT[lower]);
      return round2(lower + fraction);
    }
  }
  return 12;
}
