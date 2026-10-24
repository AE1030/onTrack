import React from "react";
import { StyleSheet, View } from "react-native";
import { Text } from "../../../src/ui/Text";
import { useTheme } from "../../../src/theme/ThemeContext";
import { LeaderboardRow } from "../../../src/leaderboard/types";
import { normalizeAvatar } from "../../../src/leaderboard/avatar";
import { fmtScore } from "../../../src/leaderboard/scoring";
import PlayerAvatar from "./PlayerAvatar";
import { ModePill, MovementArrow } from "./Indicators";
import { radius } from "../../../src/theme/spacing";

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

      <Text style={[styles.score, { color: c.boldText }]}>
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
    borderRadius: radius.md,
    marginVertical: 2,
  },
  rowPinned: {
    borderWidth: 1.5,
    borderRadius: radius.lg,
    borderBottomWidth: 1.5,
    marginHorizontal: 12,
  },
  rankCell: {
    width: 40,
    flexDirection: "row",
    alignItems: "center",
    gap: 3,
  },
  rank: {
    fontFamily: "SpaceGrotesk_700Bold",
    fontSize: 16,
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
    fontWeight: "500",
  },
  // Right-aligned and tabular so a column of scores lines up, and wide enough for the "12,250" a
  // delivered GROWTH target can reach.
  // Plex, not display type: on the board the rank is the shout and the score is the detail.
  score: {
    fontFamily: "IBMPlexSans_500Medium",
    fontSize: 16,
    letterSpacing: 0.32,
    fontVariant: ["tabular-nums"],
    minWidth: 64,
    textAlign: "right",
  },
});
