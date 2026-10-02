import React, { useEffect } from "react";
import { Pressable, StyleSheet, Text, View } from "react-native";
import { Feather } from "@expo/vector-icons";
import { useQuery } from "@tanstack/react-query";
import { useTheme } from "../../src/theme/ThemeContext";
import { qk } from "../../src/cache/keys";
import { isAuthError, queryRetry } from "../../src/cache/authedGet";
import { fetchBoard, fetchStatus } from "../../src/leaderboard/api";
import { fmtScore } from "../../src/leaderboard/scoring";
import BrandField from "./leaderboard/BrandField";
import { ModePill, RankSparkline, TrendChip } from "./leaderboard/Indicators";

/**
 * The leaderboard's one card on the dashboard.
 *
 * It sits next to the GPA card deliberately: the two things a student is ranked on should live
 * beside each other. It has two states and no dismissal, because at this size a dismiss control
 * costs more room than the card it would hide.
 *
 * The opted-out state leads with the escape hatch rather than the pitch. Joining is a real
 * disclosure of grade-derived data, and holding that back until step one of onboarding would make
 * the card bait.
 */
export default function LeaderboardCard({
  onJoin,
  onOpen,
  onAuthError,
}: {
  onJoin: () => void;
  onOpen: () => void;
  onAuthError: () => void;
}) {
  const c = useTheme();

  const statusQuery = useQuery({
    queryKey: qk.leaderboardStatus,
    queryFn: fetchStatus,
    retry: queryRetry,
  });

  const joined = statusQuery.data?.joined === true && statusQuery.data.status === "ACTIVE";

  // Only the joined card needs the field around it. "#7 of 248" is what makes a rank mean
  // anything, and entrants only comes back on the board response.
  const boardQuery = useQuery({
    queryKey: qk.leaderboard,
    queryFn: () => fetchBoard(),
    enabled: joined,
    retry: queryRetry,
  });

  useEffect(() => {
    if (statusQuery.error && isAuthError(statusQuery.error)) onAuthError();
  }, [statusQuery.error, onAuthError]);

  const me = boardQuery.data?.me ?? null;

  if (statusQuery.isLoading) {
    return <View style={[styles.card, styles.skeleton, { backgroundColor: c.surfaceSunken }]} />;
  }

  // A failed status read should not remove a dashboard tile and reflow the grid under the
  // student's thumb. The card stays, and says what happened.
  if (statusQuery.isError) {
    return (
      <View style={[styles.card, { backgroundColor: c.surface, borderColor: c.border }]}>
        <Text style={[styles.errorText, { color: c.bodyText }]}>
          Leaderboard is unavailable right now.
        </Text>
      </View>
    );
  }

  if (!joined) {
    return (
      <BrandField style={styles.card}>
        <Pressable onPress={onJoin} style={styles.joinInner}>
          <View style={styles.joinHeader}>
            <Feather name="award" size={16} color={c.accent} />
            <Text style={[styles.joinTitle, { color: c.onField }]}>Leaderboard</Text>
          </View>

          <Text style={[styles.joinBody, { color: c.onFieldMuted }]}>
            Ranked on progress toward your own goal, not on raw GPA. Leave whenever you like:
            nothing is recorded against you.
          </Text>

          <View style={[styles.joinButton, { backgroundColor: c.accent }]}>
            <Text style={styles.joinButtonText}>Join the game</Text>
          </View>
        </Pressable>
      </BrandField>
    );
  }

  // Both come off the server now, so the trend and the line are the same on every device and are
  // there the first time a student opens the app rather than building up locally over days.
  const delta = me?.rankDelta ?? statusQuery.data?.rankDelta ?? null;
  const history = statusQuery.data?.history ?? [];
  const entrants = boardQuery.data?.entrants ?? 0;
  const mode = me?.mode ?? statusQuery.data?.mode ?? null;
  const ranked = me !== null;

  return (
    <BrandField style={styles.card}>
      <Pressable onPress={onOpen} style={styles.rankInner}>
        <View style={styles.joinHeader}>
          <Feather name="award" size={16} color={c.accent} />
          <Text style={[styles.joinTitle, { color: c.onField }]}>Leaderboard</Text>
        </View>

        {ranked ? (
          <>
            {/* Rank is the hero, not the score: 9,438 means nothing without the field around it. */}
            <View style={styles.rankRow}>
              <Text style={[styles.rankNumber, { color: c.onField }]}>#{me!.rank}</Text>
              <Text style={[styles.rankOf, { color: c.onFieldFaint }]}>
                of {entrants}
              </Text>
              <View style={styles.spacer} />
              <TrendChip delta={delta} onField />
            </View>

            <View style={styles.metaRow}>
              <Text style={[styles.score, { color: c.accent }]}>{fmtScore(me!.score)}</Text>
              <View style={styles.spacer} />
              <RankSparkline history={history} onField />
            </View>

            {mode && (
              <View style={styles.modeRow}>
                <ModePill mode={mode} onField compact />
              </View>
            )}
          </>
        ) : (
          // Joined, but the ranking job has not run since. Saying so beats showing a zero.
          <Text style={[styles.joinBody, { color: c.onFieldMuted }]}>
            You are in. Your first rank lands at the next update.
          </Text>
        )}
      </Pressable>
    </BrandField>
  );
}

const styles = StyleSheet.create({
  card: {
    borderRadius: 16,
    minHeight: 150,
    borderWidth: 0,
    boxShadow: "0px 2px 8px rgba(0,0,0,0.12)",
    elevation: 3,
  },
  skeleton: {
    borderRadius: 16,
  },
  errorText: {
    padding: 16,
    fontSize: 13,
  },
  joinInner: {
    padding: 16,
    gap: 10,
    flex: 1,
  },
  rankInner: {
    padding: 16,
    gap: 8,
    flex: 1,
  },
  joinHeader: {
    flexDirection: "row",
    alignItems: "center",
    gap: 6,
  },
  joinTitle: {
    fontSize: 13,
    fontWeight: "700",
    letterSpacing: 0.4,
    textTransform: "uppercase",
  },
  joinBody: {
    fontSize: 13,
    lineHeight: 19,
    flex: 1,
  },
  joinButton: {
    alignSelf: "flex-start",
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: 10,
  },
  joinButtonText: {
    color: "#3B0A1D",
    fontSize: 13,
    fontWeight: "700",
  },
  rankRow: {
    flexDirection: "row",
    alignItems: "baseline",
    gap: 6,
    marginTop: 2,
  },
  rankNumber: {
    fontSize: 34,
    fontWeight: "800",
    fontVariant: ["tabular-nums"],
  },
  rankOf: {
    fontSize: 13,
    fontWeight: "600",
  },
  spacer: {
    flex: 1,
  },
  metaRow: {
    flexDirection: "row",
    alignItems: "center",
  },
  score: {
    fontSize: 16,
    fontWeight: "800",
    fontVariant: ["tabular-nums"],
  },
  modeRow: {
    flexDirection: "row",
    marginTop: 2,
  },
});
