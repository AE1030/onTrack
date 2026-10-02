import React from "react";
import { StyleSheet, Text, View } from "react-native";
import { Feather } from "@expo/vector-icons";
import Svg, { Circle, Polyline } from "react-native-svg";
import { useTheme } from "../../../src/theme/ThemeContext";
import { MODE_LABEL, RankSample, ScoreMode } from "../../../src/leaderboard/types";

/**
 * The three small read-at-a-glance pieces the board and the dashboard card share.
 *
 * All three take an `onField` variant, because each one appears both on the app's normal ground and
 * on the deep maroon brand field, and the maroon field needs its own type colours rather than a
 * darker version of the light ones.
 */

// ------------------------------------------------------------------------- mode pill

/**
 * Which game this student is playing.
 *
 * Worth the space on a dashboard card because a MAINTENANCE student is capped at 7,000 and needs to
 * know that before they wonder why they cannot catch anyone, rather than after.
 */
export function ModePill({
  mode,
  onField = false,
  compact = false,
}: {
  mode: ScoreMode;
  onField?: boolean;
  compact?: boolean;
}) {
  const c = useTheme();
  const bg = onField ? "rgba(255,255,255,0.18)" : c.surfaceSunken;
  const fg = onField ? c.onField : c.bodyText;
  return (
    <View
      style={[
        styles.pill,
        compact && styles.pillCompact,
        { backgroundColor: bg },
      ]}
    >
      <Text style={[styles.pillText, compact && styles.pillTextCompact, { color: fg }]}>
        {MODE_LABEL[mode]}
      </Text>
    </View>
  );
}

// ------------------------------------------------------------------------ trend chip

/**
 * Ranks moved since the last scoring run.
 *
 * Ranks, not points. A student can gain points and still slide, and the honest signal is the one
 * that accounts for everyone else having moved too.
 *
 * `delta` is null until an entry has been through two scoring runs, and the chip says so rather
 * than drawing a flat arrow that claims nothing changed.
 */
export function TrendChip({
  delta,
  onField = false,
}: {
  delta: number | null;
  onField?: boolean;
}) {
  const c = useTheme();

  if (delta === null) {
    return (
      <View style={[styles.chip, { backgroundColor: onField ? "rgba(255,255,255,0.16)" : c.surfaceSunken }]}>
        <Text style={[styles.chipText, { color: onField ? c.onFieldMuted : c.bodyText }]}>
          New
        </Text>
      </View>
    );
  }

  const flat = delta === 0;
  const up = delta > 0;
  const tint = flat ? (onField ? c.onFieldMuted : c.bodyText) : up ? c.success : c.danger;

  return (
    <View style={[styles.chip, { backgroundColor: onField ? "rgba(255,255,255,0.16)" : c.surfaceSunken }]}>
      <Feather
        name={flat ? "minus" : up ? "arrow-up-right" : "arrow-down-right"}
        size={12}
        color={tint}
      />
      <Text style={[styles.chipText, { color: tint, marginLeft: 3 }]}>
        {flat ? "Held" : Math.abs(delta)}
      </Text>
    </View>
  );
}

/** The movement arrow on a board row. Same signal as the chip, with no room for a word. */
export function MovementArrow({ delta }: { delta: number | null }) {
  const c = useTheme();
  if (delta === null) return <View style={{ width: 12 }} />;
  if (delta === 0) return <Feather name="minus" size={12} color={c.faintText} />;
  return (
    <Feather
      name={delta > 0 ? "arrow-up" : "arrow-down"}
      size={12}
      color={delta > 0 ? c.success : c.danger}
    />
  );
}

// -------------------------------------------------------------------------- sparkline

/**
 * The last seven scoring runs, inverted so that up means better.
 *
 * No axis, on purpose. At this size it is a direction, not a chart, and an axis would imply a
 * precision the seven points do not have.
 */
export function RankSparkline({
  history,
  width = 72,
  height = 24,
  onField = false,
}: {
  history: RankSample[];
  width?: number;
  height?: number;
  onField?: boolean;
}) {
  const c = useTheme();
  const stroke = onField ? c.accent : c.primary;

  if (history.length < 2) {
    return (
      <View style={{ width, height, justifyContent: "center" }}>
        <Text style={[styles.sparkEmpty, { color: onField ? c.onFieldFaint : c.faintText }]}>
          Builds over time
        </Text>
      </View>
    );
  }

  const ranks = history.map((s) => s.rank);
  const best = Math.min(...ranks);
  const worst = Math.max(...ranks);
  // A perfectly flat run would divide by zero and also has no shape to draw, so it is pinned to
  // the middle of the box rather than to the top.
  const span = worst - best || 1;
  const pad = 3;
  const stepX = (width - pad * 2) / (ranks.length - 1);

  const points = ranks
    .map((rank, i) => {
      const x = pad + i * stepX;
      // Rank 1 is the best, so a smaller number has to sit higher on the screen.
      const y =
        worst === best
          ? height / 2
          : pad + ((rank - best) / span) * (height - pad * 2);
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(" ");

  const last = points.split(" ").pop()!.split(",");

  return (
    <Svg width={width} height={height}>
      <Polyline
        points={points}
        fill="none"
        stroke={stroke}
        strokeWidth={2}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <Circle cx={Number(last[0])} cy={Number(last[1])} r={2.6} fill={stroke} />
    </Svg>
  );
}

const styles = StyleSheet.create({
  pill: {
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 999,
    alignSelf: "flex-start",
  },
  pillCompact: {
    paddingHorizontal: 6,
    paddingVertical: 2,
  },
  pillText: {
    fontSize: 11,
    fontWeight: "600",
    letterSpacing: 0.2,
  },
  pillTextCompact: {
    fontSize: 10,
  },
  chip: {
    flexDirection: "row",
    alignItems: "center",
    paddingHorizontal: 7,
    paddingVertical: 3,
    borderRadius: 999,
    alignSelf: "flex-start",
  },
  chipText: {
    fontSize: 11,
    fontWeight: "700",
  },
  sparkEmpty: {
    fontSize: 10,
    fontWeight: "500",
  },
});
