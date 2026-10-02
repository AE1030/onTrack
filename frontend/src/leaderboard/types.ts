/**
 * The wire shapes, mirroring org.tracker.gpatracker.leaderboard.dto exactly.
 *
 * Java BigDecimal serialises as a JSON number, so every GPA and score arrives as `number` here.
 * The one thing worth remembering while rendering them: `score` tops out at 12,250, not 10,000,
 * because a delivered ambitious target pays a bonus. Anything that treats 10,000 as full scale will
 * clip the leaders.
 */

import type { AvatarConfig } from "./avatar";

/** Which game a student is playing. Chosen at onboarding from baseline and target, then frozen. */
export type ScoreMode = "GROWTH" | "MAINTENANCE" | "CEILING";

export type EntryStatus = "ACTIVE" | "WITHDRAWN" | "SETTLED" | "UNVERIFIED" | "VOID";

/** GET /api/leaderboard — one row of the public board. */
export type LeaderboardRow = {
  rank: number;
  handle: string;
  score: number;
  mode: ScoreMode;
  /** True on the caller's own row, so the client never has to match on handle. */
  you: boolean;
  /** The face the student picked. Null when they never picked one: derive it from the handle. */
  avatar: AvatarConfig | null;
  /**
   * Places moved since the previous scoring run, positive meaning climbed. Null until an entry has
   * been through two runs, which is why a new joiner gets no arrow rather than a flat one.
   */
  rankDelta: number | null;
};

/** One point on the rank sparkline, oldest first as the server returns them. */
export type RankSample = {
  rank: number;
  computedAt: string;
};

/** GET /api/leaderboard */
export type LeaderboardResponse = {
  season: string;
  rows: LeaderboardRow[];
  /** Returned even when the caller is nowhere near the top. Null before they have joined. */
  me: LeaderboardRow | null;
  entrants: number;
  /** Null until the ranking job has run at least once. */
  computedAt: string | null;
};

/** GET /api/leaderboard/status */
export type LeaderboardStatus = {
  season: string;
  joined: boolean;
  status: EntryStatus | null;
  handle: string | null;
  mode: ScoreMode | null;
  score: number | null;
  ceiling: number | null;
  canChangeHandle: boolean;
  transcriptSubmitted: boolean;
  computedAt: string | null;
  /** The caller's chosen face. Survives a withdrawal, so a rejoin can start from it. */
  avatar: AvatarConfig | null;
  /** Position after the most recent run. Null until the job has ranked them once. */
  rank: number | null;
  /** Places moved since the run before. Null when there is no earlier run to compare against. */
  rankDelta: number | null;
  /** Up to seven points, oldest first. Empty for a student the job has not ranked twice yet. */
  history: RankSample[];
};

/** GET /api/leaderboard/handle/check?handle= */
export type HandleCheck = {
  available: boolean;
  /** Null when available. Written to be shown to the student as-is. */
  reason: string | null;
};

/** POST /api/leaderboard/transcript */
export type TranscriptBaseline = {
  baselineGpa12: number;
  headroom: number;
  growthAvailable: boolean;
  /** The lowest target that still counts as growth, or null when growth is out of reach. */
  minGrowthTarget: number | null;
  /** True when this season's baseline was already frozen and the upload changed nothing. */
  frozen: boolean;
};

/** GET /api/leaderboard/preview?target= */
export type TargetPreview = {
  baselineGpa12: number;
  targetGpa12: number;
  mode: ScoreMode;
  ceiling: number;
  ambition: number;
};

/** Which slice of the field is showing. Only `global` is served today. */
export type BoardScope = "global" | "program" | "friends";

export const MODE_LABEL: Record<ScoreMode, string> = {
  GROWTH: "Growth",
  MAINTENANCE: "Maintenance",
  CEILING: "Ceiling",
};

/** One line, shown next to the mode pill wherever there is room to explain it. */
export const MODE_BLURB: Record<ScoreMode, string> = {
  GROWTH: "Climbing toward a target at least a full point above your baseline.",
  MAINTENANCE: "Holding steady. Scores top out at 7,000.",
  CEILING: "Already above 11.0, so holding the line is the whole game.",
};
