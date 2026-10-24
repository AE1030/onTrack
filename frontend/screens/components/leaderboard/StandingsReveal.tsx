import React, { useEffect, useMemo, useState } from "react";
import { Pressable, StyleSheet, View, useWindowDimensions } from "react-native";
import Animated, {
  Easing,
  FadeIn,
  ZoomIn,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withDelay,
  withTiming,
} from "react-native-reanimated";
import { Feather } from "@expo/vector-icons";
import { Text } from "../../../src/ui/Text";
import { noHover } from "../../../src/ui/hover";
import { useTheme } from "../../../src/theme/ThemeContext";
import { LeaderboardRow } from "../../../src/leaderboard/types";
import { POINTS_NAME, fmtScore, pointsToPass } from "../../../src/leaderboard/scoring";
import { radius } from "../../../src/theme/spacing";
import { fonts, typo } from "../../../src/theme/typography";
import BrandField from "./BrandField";
import { TrendChip } from "./Indicators";

/**
 * The first look at the board after a sign-in: confetti, where you landed, and what the next rung
 * costs.
 *
 * It is a scoreboard moment, not a reward for opening a screen, which is why it runs once per sign-in
 * (see src/leaderboard/celebration.ts) and never on an ordinary visit. The useful part is the gap to
 * the next place; the confetti is only there to make reading it feel like a result.
 */
export default function StandingsReveal({
  me,
  rows,
  entrants,
  delta,
  onDone,
}: {
  me: LeaderboardRow;
  rows: LeaderboardRow[];
  entrants: number;
  delta: number | null;
  onDone: () => void;
}) {
  const c = useTheme();
  const reduceMotion = useReducedMotion();

  // The row just above, by rank rather than rank - 1: ties share a rank and skip the next number.
  const above = useMemo(
    () =>
      rows
        .filter((r) => r.rank < me.rank)
        .sort((a, b) => b.rank - a.rank)[0] ?? null,
    [rows, me.rank]
  );
  const below = useMemo(
    () => rows.filter((r) => r.rank > me.rank).sort((a, b) => a.rank - b.rank)[0] ?? null,
    [rows, me.rank]
  );

  // The rank ticks from where they were to where they are, so a climb is something you watch.
  const from = delta !== null && delta !== 0 ? Math.max(1, me.rank + delta) : me.rank;
  const shownRank = useTicker(from, me.rank, reduceMotion ? 0 : 900);

  const headline =
    delta === null
      ? "Your first standing is in."
      : delta > 0
        ? `You climbed ${delta} ${delta === 1 ? "place" : "places"}.`
        : delta < 0
          ? `You slipped ${-delta} ${delta === -1 ? "place" : "places"}. Plenty of season left.`
          : "You held your place.";

  return (
    <Animated.View
      // Stock presets only: on web, Reanimated's cleanup throws for a customised (springified or
      // re-seeded) layout animation, and an exiting one throws on unmount.
      entering={FadeIn.duration(180)}
      style={StyleSheet.absoluteFill}
      accessibilityViewIsModal
    >
      <Pressable
        {...noHover}
        style={[StyleSheet.absoluteFill, { backgroundColor: c.scrim }]}
        onPress={onDone}
        accessibilityLabel="Close"
      />

      {!reduceMotion && <Confetti />}

      <View style={styles.center} pointerEvents="box-none">
        <Animated.View
          entering={ZoomIn.duration(320).delay(80)}
          style={styles.cardWrap}
        >
          <BrandField style={styles.card}>
            <View style={styles.rankRow}>
              <Text style={[styles.rank, { color: c.onField }]}>#{shownRank}</Text>
              <Text style={[styles.of, { color: c.onFieldFaint }]}>of {entrants}</Text>
              <View style={styles.spacer} />
              <TrendChip delta={delta} onField />
            </View>

            <Text style={[styles.headline, { color: c.onField }]}>{headline}</Text>

            <View style={styles.rung}>
              {above ? (
                <>
                  <Text style={[styles.rungNumber, { color: c.accent }]}>
                    {fmtScore(pointsToPass(me.score, above.score))}
                  </Text>
                  <Text style={[styles.rungText, { color: c.onFieldMuted }]}>
                    {POINTS_NAME} to take #{above.rank} from{" "}
                    <Text style={[styles.rungHandle, { color: c.onField }]}>{above.handle}</Text>.
                  </Text>
                </>
              ) : below ? (
                <>
                  <Text style={[styles.rungNumber, { color: c.accent }]}>
                    {fmtScore(Math.max(0, me.score - below.score))}
                  </Text>
                  <Text style={[styles.rungText, { color: c.onFieldMuted }]}>
                    {POINTS_NAME} clear of{" "}
                    <Text style={[styles.rungHandle, { color: c.onField }]}>{below.handle}</Text>.
                    First place is yours to hold.
                  </Text>
                </>
              ) : (
                <Text style={[styles.rungText, { color: c.onFieldMuted }]}>
                  First place, and nobody behind you yet.
                </Text>
              )}
            </View>

            <View style={styles.footer}>
              <Text style={[styles.yours, { color: c.onFieldFaint }]}>
                {fmtScore(me.score)} {POINTS_NAME}
              </Text>
              <Pressable
                onPress={onDone}
                style={({ pressed }) => [
                  styles.button,
                  { backgroundColor: c.accent },
                  pressed && { transform: [{ scale: 0.97 }] },
                ]}
              >
                <Text style={styles.buttonText}>See the board</Text>
                <Feather name="arrow-right" size={15} color="#3B0A1D" />
              </Pressable>
            </View>
          </BrandField>
        </Animated.View>
      </View>
    </Animated.View>
  );
}

/** Counts an integer from `from` to `to` over `ms`, easing out so it settles on the real number. */
function useTicker(from: number, to: number, ms: number): number {
  const [value, setValue] = useState(ms > 0 ? from : to);
  useEffect(() => {
    if (ms <= 0 || from === to) {
      setValue(to);
      return;
    }
    let frame = 0;
    const start = Date.now() + 260; // let the card land first
    const tick = () => {
      const t = Math.min(1, Math.max(0, (Date.now() - start) / ms));
      const eased = 1 - Math.pow(1 - t, 3);
      setValue(Math.round(from + (to - from) * eased));
      if (t < 1) frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [from, to, ms]);
  return value;
}

// ---------------------------------------------------------------------------- confetti

// School colours plus the climb mint. White and pink carry it over the maroon header; maroon and gold
// carry it over the light sheet.
const CONFETTI = ["#F2B94A", "#FFFFFF", "#7CE8A6", "#EE86AC", "#7A003C", "#F2B94A"];
const PIECES = 46;

/** Seeded, so the burst has the same shape on every render instead of reshuffling mid-fall. */
function seeded(seed: number) {
  let s = seed;
  return () => {
    s = (s * 16807) % 2147483647;
    return (s - 1) / 2147483646;
  };
}

function Confetti() {
  const { width, height } = useWindowDimensions();
  const pieces = useMemo(() => {
    const rand = seeded(20261020);
    return Array.from({ length: PIECES }, (_, i) => ({
      key: i,
      x: rand() * width,
      drift: (rand() - 0.5) * 140,
      delay: rand() * 380,
      duration: 1900 + rand() * 1100,
      spin: (rand() < 0.5 ? -1 : 1) * (360 + rand() * 540),
      w: 6 + rand() * 5,
      h: rand() < 0.3 ? 6 + rand() * 4 : 10 + rand() * 6,
      round: rand() < 0.25,
      color: CONFETTI[i % CONFETTI.length],
    }));
  }, [width]);

  return (
    <View style={StyleSheet.absoluteFill} pointerEvents="none">
      {pieces.map(({ key, ...p }) => (
        <Piece key={key} {...p} fall={height + 40} />
      ))}
    </View>
  );
}

function Piece({
  x,
  drift,
  delay,
  duration,
  spin,
  w,
  h,
  round,
  color,
  fall,
}: {
  x: number;
  drift: number;
  delay: number;
  duration: number;
  spin: number;
  w: number;
  h: number;
  round: boolean;
  color: string;
  fall: number;
}) {
  const t = useSharedValue(0);

  useEffect(() => {
    t.value = withDelay(delay, withTiming(1, { duration, easing: Easing.in(Easing.quad) }));
  }, [t, delay, duration]);

  const style = useAnimatedStyle(() => {
    const p = t.value;
    return {
      opacity: p < 0.02 ? p * 50 : p > 0.82 ? (1 - p) / 0.18 : 1,
      transform: [
        { translateX: x + drift * Math.sin(p * Math.PI * 2.2) },
        { translateY: -30 + p * fall },
        { rotate: `${spin * p}deg` },
        { rotateX: `${p * 720}deg` },
      ],
    };
  });

  return (
    <Animated.View
      style={[
        styles.piece,
        { width: w, height: round ? w : h, borderRadius: round ? w / 2 : 1.5, backgroundColor: color },
        style,
      ]}
    />
  );
}

const styles = StyleSheet.create({
  center: {
    ...StyleSheet.absoluteFillObject,
    alignItems: "center",
    justifyContent: "center",
    padding: 20,
  },
  cardWrap: {
    width: "100%",
    maxWidth: 380,
  },
  card: {
    borderRadius: radius.lg,
    padding: 20,
    gap: 12,
    boxShadow: "0px 10px 30px rgba(0,0,0,0.35)",
    elevation: 10,
  },
  rankRow: {
    flexDirection: "row",
    alignItems: "baseline",
    gap: 8,
  },
  rank: {
    ...typo.display,
    fontSize: 56,
    lineHeight: 60,
    letterSpacing: -1.4,
    fontVariant: ["tabular-nums"],
  },
  of: {
    ...typo.body,
    fontFamily: fonts.bodyMedium,
    fontVariant: ["tabular-nums"],
  },
  spacer: {
    flex: 1,
  },
  headline: {
    ...typo.h2,
  },
  rung: {
    backgroundColor: "rgba(0,0,0,0.20)",
    borderRadius: radius.md,
    paddingHorizontal: 14,
    paddingVertical: 12,
    gap: 2,
  },
  rungNumber: {
    ...typo.display,
    fontSize: 32,
    lineHeight: 36,
    fontVariant: ["tabular-nums"],
  },
  rungText: {
    ...typo.body,
  },
  rungHandle: {
    fontFamily: fonts.bodyMedium,
  },
  footer: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    marginTop: 2,
  },
  yours: {
    ...typo.caption,
    fontFamily: fonts.bodyMedium,
    fontVariant: ["tabular-nums"],
  },
  button: {
    flexDirection: "row",
    alignItems: "center",
    gap: 6,
    paddingHorizontal: 16,
    paddingVertical: 10,
    borderRadius: radius.md,
  },
  buttonText: {
    ...typo.label,
    fontFamily: fonts.display,
    color: "#3B0A1D", // dark ink on gold in both themes
  },
  piece: {
    position: "absolute",
    top: 0,
    left: 0,
  },
});
