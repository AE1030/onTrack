import React, { useEffect } from "react";
import { Platform, StyleSheet, View } from "react-native";
import Animated, {
  Easing,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withDelay,
  withSpring,
  withTiming,
} from "react-native-reanimated";
import { Text } from "../../../src/ui/Text";
import { Feather } from "@expo/vector-icons";
import { useTheme } from "../../../src/theme/ThemeContext";
import { LeaderboardRow } from "../../../src/leaderboard/types";
import { normalizeAvatar } from "../../../src/leaderboard/avatar";
import { POINTS_SHORT, fmtScore } from "../../../src/leaderboard/scoring";
import PlayerAvatar from "./PlayerAvatar";
import { radius } from "../../../src/theme/spacing";

/**
 * The top three, as 2 · 1 · 3 with the centre raised.
 *
 * Scores are whole points and the columns are sized for "12,250", not "10,000": GROWTH tops out
 * above the full score, so a layout built around five digits clips its own leaders the first time
 * someone delivers an ambitious target.
 *
 * Blurring is the caller's job. An opted-out student sees the top three at full legibility and the
 * rest of the list gated, so this component never knows about the gate.
 *
 * The plinths rise on open in the order a result is read out: third, second, then first. It runs on
 * every visit because it is short and it is the board arriving, not a celebration.
 */
export default function Podium({ rows }: { rows: LeaderboardRow[] }) {
  const c = useTheme();

  const first = rows.find((r) => r.rank === 1) ?? rows[0];
  const second = rows.find((r) => r.rank === 2) ?? rows[1];
  const third = rows.find((r) => r.rank === 3) ?? rows[2];

  if (!first) {
    return (
      <View style={styles.empty}>
        <Text style={[styles.emptyText, { color: c.onFieldMuted }]}>
          Nobody has been ranked yet. Track Points update three times a day.
        </Text>
      </View>
    );
  }

  return (
    <View style={styles.podium}>
      <Place row={second} height={64} avatar={48} order={1} />
      <Place row={first} height={88} avatar={58} order={2} crown />
      <Place row={third} height={52} avatar={48} order={0} />
    </View>
  );
}

function Place({
  row,
  height,
  avatar,
  order,
  crown = false,
}: {
  row?: LeaderboardRow;
  height: number;
  avatar: number;
  order: number;
  crown?: boolean;
}) {
  const c = useTheme();
  const reduceMotion = useReducedMotion();
  const rise = useSharedValue(reduceMotion ? 1 : 0);
  const pop = useSharedValue(reduceMotion ? 1 : 0);

  useEffect(() => {
    if (reduceMotion) return;
    const delay = 120 + order * 170;
    rise.value = withDelay(delay, withSpring(1, { damping: 13, stiffness: 140, mass: 0.8 }));
    pop.value = withDelay(
      delay + 160,
      withTiming(1, { duration: 360, easing: Easing.out(Easing.back(2)) })
    );
  }, [reduceMotion, order, rise, pop]);

  const plinthStyle = useAnimatedStyle(() => ({
    height: Math.max(0, height * rise.value),
  }));
  const topStyle = useAnimatedStyle(() => ({
    opacity: Math.min(1, pop.value * 1.4),
    transform: [{ translateY: (1 - pop.value) * 14 }, { scale: 0.85 + 0.15 * pop.value }],
  }));

  if (!row) {
    // The plinth still renders. A two-person board that reflows into a different shape on the day
    // a third student joins reads as a bug.
    return (
      <View style={styles.place}>
        <Animated.View style={[styles.plinth, styles.plinthEmpty, plinthStyle]} />
      </View>
    );
  }

  const isFirst = row.rank === 1;

  return (
    <View style={styles.place}>
      <Animated.View style={[styles.top, topStyle]}>
        {crown && <Feather name="award" size={16} color={c.accent} style={styles.crown} />}

        <PlayerAvatar
          config={normalizeAvatar(row.avatar)}
          handle={row.handle}
          size={avatar}
          ring={isFirst ? "gold" : "none"}
        />

        <Text
          style={[styles.handle, { color: c.onField }, row.you && { color: c.accent }]}
          numberOfLines={1}
        >
          {row.handle}
        </Text>

        <Text style={[styles.score, { color: isFirst ? c.accent : c.onField }]}>
          {fmtScore(row.score)}
          <Text style={[styles.unit, { color: c.onFieldFaint }]}> {POINTS_SHORT}</Text>
        </Text>
      </Animated.View>

      <Animated.View style={[styles.plinth, plinthStyle]}>
        <Text style={[styles.plinthRank, { color: c.onField }]}>{row.rank}</Text>
      </Animated.View>
    </View>
  );
}

const styles = StyleSheet.create({
  podium: {
    flexDirection: "row",
    alignItems: "flex-end",
    justifyContent: "center",
    gap: 10,
    paddingHorizontal: 12,
  },
  place: {
    flex: 1,
    maxWidth: 124,
    alignItems: "center",
  },
  top: {
    alignItems: "center",
    maxWidth: "100%",
  },
  unit: {
    fontSize: 11,
    fontWeight: "500",
  },
  crown: {
    marginBottom: 2,
  },
  handle: {
    marginTop: 6,
    fontSize: 13,
    fontWeight: "500",
    maxWidth: "100%",
  },
  // Sized for "12,250", which is six characters, because GROWTH does not stop at 10,000.
  score: {
    marginTop: 1,
    marginBottom: 6,
    fontSize: 18,
    fontWeight: "700",
    fontVariant: ["tabular-nums"],
  },
  plinth: {
    width: "100%",
    borderTopLeftRadius: radius.md,
    borderTopRightRadius: radius.md,
    backgroundColor: "rgba(255,255,255,0.14)",
    alignItems: "center",
    paddingTop: 8,
    overflow: "hidden",
  },
  plinthEmpty: {
    backgroundColor: "rgba(255,255,255,0.06)",
  },
  plinthRank: {
    fontSize: 20,
    fontWeight: "700",
    opacity: 0.85,
  },
  empty: {
    paddingHorizontal: 24,
    paddingVertical: 28,
  },
  emptyText: {
    textAlign: "center",
    fontSize: 14,
    lineHeight: 21,
    ...Platform.select({ web: { maxWidth: 380, marginHorizontal: "auto" } }),
  },
});
