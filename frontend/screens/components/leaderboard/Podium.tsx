import React from "react";
import { Platform, StyleSheet, Text, View } from "react-native";
import { Feather } from "@expo/vector-icons";
import { useTheme } from "../../../src/theme/ThemeContext";
import { LeaderboardRow } from "../../../src/leaderboard/types";
import { normalizeAvatar } from "../../../src/leaderboard/avatar";
import { fmtScore } from "../../../src/leaderboard/scoring";
import PlayerAvatar from "./PlayerAvatar";

/**
 * The top three, as 2 · 1 · 3 with the centre raised.
 *
 * Scores are whole points and the columns are sized for "12,250", not "10,000": GROWTH tops out
 * above the full score, so a layout built around five digits clips its own leaders the first time
 * someone delivers an ambitious target.
 *
 * Blurring is the caller's job. An opted-out student sees the top three at full legibility and the
 * rest of the list gated, so this component never knows about the gate.
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
          Nobody has been ranked yet. Scores update three times a day.
        </Text>
      </View>
    );
  }

  return (
    <View style={styles.podium}>
      <Place row={second} height={64} avatar={48} />
      <Place row={first} height={88} avatar={58} crown />
      <Place row={third} height={52} avatar={48} />
    </View>
  );
}

function Place({
  row,
  height,
  avatar,
  crown = false,
}: {
  row?: LeaderboardRow;
  height: number;
  avatar: number;
  crown?: boolean;
}) {
  const c = useTheme();

  if (!row) {
    // The plinth still renders. A two-person board that reflows into a different shape on the day
    // a third student joins reads as a bug.
    return (
      <View style={styles.place}>
        <View style={[styles.plinth, styles.plinthEmpty, { height }]} />
      </View>
    );
  }

  const isFirst = row.rank === 1;

  return (
    <View style={styles.place}>
      {crown && (
        <Feather name="award" size={16} color={c.accent} style={styles.crown} />
      )}

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
      </Text>

      <View style={[styles.plinth, { height }]}>
        <Text style={[styles.plinthRank, { color: c.onField }]}>{row.rank}</Text>
      </View>
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
  crown: {
    marginBottom: 2,
  },
  handle: {
    marginTop: 6,
    fontSize: 13,
    fontWeight: "600",
    maxWidth: "100%",
  },
  // Sized for "12,250", which is six characters, because GROWTH does not stop at 10,000.
  score: {
    marginTop: 1,
    marginBottom: 6,
    fontSize: 17,
    fontWeight: "800",
    fontVariant: ["tabular-nums"],
  },
  plinth: {
    width: "100%",
    borderTopLeftRadius: 10,
    borderTopRightRadius: 10,
    backgroundColor: "rgba(255,255,255,0.14)",
    alignItems: "center",
    paddingTop: 8,
  },
  plinthEmpty: {
    backgroundColor: "rgba(255,255,255,0.06)",
  },
  plinthRank: {
    fontSize: 20,
    fontWeight: "800",
    opacity: 0.85,
  },
  empty: {
    paddingHorizontal: 24,
    paddingVertical: 28,
  },
  emptyText: {
    textAlign: "center",
    fontSize: 13,
    lineHeight: 19,
    ...Platform.select({ web: { maxWidth: 380, marginHorizontal: "auto" } }),
  },
});
