import { ScoreMode } from "./types";

/**
 * A client-side mirror of LeaderboardScoring, for the target slider only.
 *
 * The server is authoritative and GET /api/leaderboard/preview is what actually decides the mode
 * and ceiling a target selects. This exists because a slider that only updates after a round trip
 * feels broken: the numbers here paint on every frame of a drag, and the debounced preview call
 * reconciles them a moment later. If the two ever disagree, the preview wins on screen.
 *
 * Every constant below is copied from LeaderboardScoring.java. They are stated rather than fetched
 * because they are frozen per season on the server anyway, and a slider that cannot draw until a
 * request returns is the problem this file is solving.
 */

export const SCALE_MAX = 12.0;
export const MAX_REACH = 4.0;
export const MIN_GROWTH_GAP = 1.0;
export const RECOVERY_BAND = 0.1;
export const AMBITION_BONUS = 0.25;
export const FULL_SCORE = 10_000;
export const MAINTENANCE_CEILING = 0.7 * FULL_SCORE;

/** The top of the scale, which is also the top a GROWTH ceiling can reach: 12,250. */
export const MAX_POSSIBLE_SCORE = 12_250;

export const clamp = (v: number, min: number, max: number) =>
  Math.max(min, Math.min(max, v));

export const headroom = (baseline: number) => SCALE_MAX - baseline;

/**
 * Which mode a baseline and target select.
 *
 * Above 11.0 there is no room to declare a full point of climb, so the student is routed to CEILING
 * rather than barred. Below the one-point gap is MAINTENANCE, which is a choice and not a rejection.
 */
export function modeFor(baseline: number, target: number): ScoreMode {
  if (headroom(baseline) < MIN_GROWTH_GAP) return "CEILING";
  return target - baseline >= MIN_GROWTH_GAP ? "GROWTH" : "MAINTENANCE";
}

/**
 * How much of their available reach the target claims, 0 to 1.
 *
 * A baseline of exactly zero is forced to 0: a first-year's transcript parses to a real 0 because
 * nothing is graded yet, and without this every one of them would claim a full ambition bonus for
 * an ordinary goal.
 */
export function ambition(baseline: number, target: number): number {
  if (baseline <= 0) return 0;
  const reach = Math.min(MAX_REACH, headroom(baseline));
  if (reach <= 0) return 0;
  return clamp((target - baseline) / reach, 0, 1);
}

/**
 * The best score this baseline and target can produce, before behaviour damping.
 *
 * Best case saturates every branch, so GROWTH is evaluated at the target and the holding modes at
 * the baseline.
 */
export function ceilingFor(baseline: number, target: number, mode: ScoreMode): number {
  if (mode === "MAINTENANCE") return MAINTENANCE_CEILING;
  if (mode === "CEILING") return FULL_SCORE;
  // GROWTH, delivered in full: the whole recovery band plus everything earned above it, bonused.
  const earned = 1 - RECOVERY_BAND;
  const bonus = 1 + AMBITION_BONUS * ambition(baseline, target);
  return round2(FULL_SCORE * (RECOVERY_BAND + earned * bonus));
}

export const round2 = (v: number) => Math.round(v * 100) / 100;

/**
 * Whole points with thousands separators ("12,250"), which is what every board row and podium
 * shows. The server keeps two decimals so near-ties still order correctly; at this scale a
 * fraction of a point is noise on screen.
 *
 * Grouped by hand rather than with toLocaleString, so the output does not depend on which Intl
 * data the JS engine ships with.
 */
export const fmtScore = (v: number | null | undefined) =>
  Math.round(v ?? 0)
    .toString()
    .replace(/\B(?=(\d{3})+(?!\d))/g, ",");

/** Two decimals, for GPAs. */
export const fmtGpa = (v: number | null | undefined) =>
  v === null || v === undefined ? "0.00" : v.toFixed(2);

/**
 * The range the target slider spans.
 *
 * The floor is the baseline, not zero: a target below where you already are is not a goal, and
 * MAINTENANCE already covers holding. The ceiling is the top of the scale.
 */
export function targetRange(baseline: number): { min: number; max: number } {
  return { min: round2(clamp(baseline, 0, SCALE_MAX)), max: SCALE_MAX };
}
