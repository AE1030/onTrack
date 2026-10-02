import React from "react";
import { StyleSheet, Text, View } from "react-native";
import { useTheme } from "../../../src/theme/ThemeContext";
import { LeaderboardRow } from "../../../src/leaderboard/types";
import { normalizeAvatar } from "../../../src/leaderboard/avatar";
import { fmtScore } from "../../../src/leaderboard/scoring";
import PlayerAvatar from "./PlayerAvatar";
import { ModePill, MovementArrow } from "./Indicators";

/**
 * One ranked row, from fourth place down.
 *
 * Handle only. leaderboard_entry denormalises the handle precisely so a public read exposes nothing
 * else, and the client must not reach for a real name it could technically join to — the board
 * having the student id available is not permission to render who they are.
 *
 * `pinned` is the copy of your own row that sits at the bottom of the screen. It is the same row,
 * so both are rendered by this component and the pinned one simply never scrolls away.
 */
export default function RankRow({
  row,
  delta = null,
  pinned = false,
}: {
  row: LeaderboardRow;
  /** Ranks moved since the last run. Only ever known for your own row today. */
  delta?: number | null;
  pinned?: boolean;
}) {
  const c = useTheme();
  const mine = row.you;

  return (
    <View
      style={[
        styles.row,
        { borderBottomColor: c.borderSubtle },
        mine && { backgroundColor: c.accentSoft, borderColor: c.accent },
        mine && styles.rowMine,
        pinned && styles.rowPinned,
        pinned && { backgroundColor: c.accentSoft, borderColor: c.accent },
      ]}
    >
      <View style={styles.rankCell}>
        <Text
          style={[
            styles.rank,
            { color: mine ? c.boldText : c.bodyText },
          ]}
        >
          {row.rank}
        </Text>
        <MovementArrow delta={mine ? delta : null} />
      </View>

      <PlayerAvatar
        config={normalizeAvatar(row.avatar)}
        handle={row.handle}
        size={30}
        showBackdrop
      />

      <View style={styles.identity}>
        <Text
          style={[styles.handle, { color: c.boldText }]}
          numberOfLines={1}
        >
          {row.handle}
          {mine ? "  ·  you" : ""}
        </Text>
        <ModePill mode={row.mode} compact />
      </View>

      <Text style={[styles.score, { color: mine ? c.boldText : c.boldText }]}>
        {fmtScore(row.score)}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: "row",
    alignItems: "center",
    gap: 10,
    paddingVertical: 10,
    paddingHorizontal: 14,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  rowMine: {
    borderWidth: 1.5,
    borderRadius: 12,
    marginVertical: 2,
  },
  rowPinned: {
    borderWidth: 1.5,
    borderRadius: 14,
    borderBottomWidth: 1.5,
    marginHorizontal: 12,
  },
  rankCell: {
    width: 34,
    flexDirection: "row",
    alignItems: "center",
    gap: 3,
  },
  rank: {
    fontSize: 14,
    fontWeight: "700",
    fontVariant: ["tabular-nums"],
    minWidth: 17,
    textAlign: "right",
  },
  identity: {
    flex: 1,
    gap: 3,
  },
  handle: {
    fontSize: 14,
    fontWeight: "600",
  },
  // Right-aligned and tabular so a column of scores lines up, and wide enough for the "12,250" a
  // delivered GROWTH target can reach.
  score: {
    fontSize: 15,
    fontWeight: "800",
    fontVariant: ["tabular-nums"],
    minWidth: 60,
    textAlign: "right",
  },
});
