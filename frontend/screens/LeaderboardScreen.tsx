import React, { useEffect, useMemo, useState } from "react";
import {
  ActivityIndicator,
  Modal,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from "react-native";
import { Feather } from "@expo/vector-icons";
import { useQuery, useQueryClient } from "@tanstack/react-query";

import { useTheme } from "../src/theme/ThemeContext";
import { qk } from "../src/cache/keys";
import { isAuthError, isServerRejection, queryRetry } from "../src/cache/authedGet";
import {
  SCOPE_SERVED,
  changeHandle,
  fetchBoard,
  fetchStatus,
  withdraw,
} from "../src/leaderboard/api";
import { BoardScope, LeaderboardRow } from "../src/leaderboard/types";
import { HANDLE_MAX, localHandleRejection } from "../src/leaderboard/handle";
import { useHandleAvailability } from "../src/leaderboard/useHandleAvailability";
import { AvatarConfig, normalizeAvatar } from "../src/leaderboard/avatar";
import { useBackfillLocalAvatar } from "../src/leaderboard/useBackfillLocalAvatar";
import BrandField from "./components/leaderboard/BrandField";
import Podium from "./components/leaderboard/Podium";
import RankRow from "./components/leaderboard/RankRow";
import PlayerAvatar from "./components/leaderboard/PlayerAvatar";

/**
 * The board.
 *
 * The header is the app's one full-bleed brand field, and the list sheet slides over it. The
 * gradient stops where the sheet starts on purpose: a long list of ranked rows on a saturated
 * ground is exhausting to read, and the rows are what people came for.
 *
 * Your own row is pinned to the bottom in gold and never scrolls away. It is the single most
 * useful thing on the screen, and it is the reason gold is reserved: using it anywhere else costs
 * the find-yourself-instantly property the pinned row exists for.
 */

const TABS: { key: BoardScope; label: string; tag?: string }[] = [
  { key: "global", label: "Global" },
  { key: "program", label: "My Program" },
  { key: "friends", label: "Friends", tag: "v3" },
];

type Props = {
  onBack: () => void;
  onJoin: () => void;
  onAuthError: () => void;
};

export default function LeaderboardScreen({ onBack, onJoin, onAuthError }: Props) {
  const c = useTheme();
  const queryClient = useQueryClient();

  const [scope, setScope] = useState<BoardScope>("global");
  const [settingsOpen, setSettingsOpen] = useState(false);

  const statusQuery = useQuery({
    queryKey: qk.leaderboardStatus,
    queryFn: fetchStatus,
    retry: queryRetry,
  });

  const boardQuery = useQuery({
    queryKey: qk.leaderboard,
    queryFn: () => fetchBoard(100),
    retry: queryRetry,
  });

  useEffect(() => {
    const err = statusQuery.error ?? boardQuery.error;
    if (err && isAuthError(err)) onAuthError();
  }, [statusQuery.error, boardQuery.error, onAuthError]);

  useBackfillLocalAvatar(statusQuery.data);
  const myAvatar = normalizeAvatar(statusQuery.data?.avatar);

  const board = boardQuery.data;
  const me = board?.me ?? null;

  const onBoard = statusQuery.data?.joined === true && statusQuery.data.status === "ACTIVE";

  const rows = board?.rows ?? [];
  const podiumRows = useMemo(() => rows.filter((r) => r.rank <= 3), [rows]);
  const listRows = useMemo(() => rows.filter((r) => r.rank > 3), [rows]);
  const delta = me?.rankDelta ?? statusQuery.data?.rankDelta ?? null;

  // Your row is already in the list when you are near the top. Pinning it as well would show the
  // same rank twice, so the pin only appears once it has actually scrolled out of the top.
  const meInList = me !== null && listRows.some((r) => r.you);
  const pinMe = onBoard && me !== null && !podiumRows.some((r) => r.you);

  const loading = statusQuery.isLoading || boardQuery.isLoading;

  return (
    <View style={[styles.screen, { backgroundColor: c.background }]}>
      <ScrollView
        contentContainerStyle={[styles.scroll, pinMe && styles.scrollWithPin]}
        showsVerticalScrollIndicator={false}
      >
        <BrandField style={styles.header}>
          <View style={styles.headerTop}>
            <Pressable onPress={onBack} hitSlop={10} style={styles.headerButton}>
              <Feather name="arrow-left" size={20} color={c.onField} />
            </Pressable>

            <Text style={[styles.headerTitle, { color: c.onField }]}>Leaderboard</Text>

            <Pressable
              onPress={() => setSettingsOpen(true)}
              hitSlop={10}
              style={[styles.headerButton, !onBoard && styles.hidden]}
              disabled={!onBoard}
            >
              <Feather name="settings" size={18} color={c.onFieldMuted} />
            </Pressable>
          </View>

          <View style={styles.seasonRow}>
            <Text style={[styles.season, { color: c.onField }]}>
              {board?.season ?? statusQuery.data?.season ?? ""}
            </Text>
            <Text style={[styles.seasonMeta, { color: c.onFieldFaint }]}>
              {board ? `${board.entrants} ranked` : ""}
              {board?.computedAt ? `  ·  updated ${relativeTime(board.computedAt)}` : ""}
            </Text>
          </View>

          <View style={styles.tabs}>
            {TABS.map((tab) => {
              const active = scope === tab.key;
              const disabled = tab.key === "friends";
              return (
                <Pressable
                  key={tab.key}
                  onPress={() => !disabled && setScope(tab.key)}
                  disabled={disabled}
                  style={[
                    styles.tab,
                    active && { backgroundColor: "rgba(255,255,255,0.20)" },
                    disabled && styles.tabDisabled,
                  ]}
                >
                  <Text
                    style={[
                      styles.tabText,
                      { color: active ? c.onField : c.onFieldFaint },
                    ]}
                  >
                    {tab.label}
                  </Text>
                  {/* Drawn rather than omitted, so the strip does not reflow when it lands. */}
                  {tab.tag && (
                    <Text style={[styles.tabTag, { color: c.onFieldFaint }]}>{tab.tag}</Text>
                  )}
                </Pressable>
              );
            })}
          </View>

          <View style={styles.podiumWrap}>
            {loading ? (
              <ActivityIndicator color={c.onField} style={styles.podiumLoading} />
            ) : (
              <Podium rows={podiumRows} />
            )}
          </View>
        </BrandField>

        <View style={[styles.sheet, { backgroundColor: c.background }]}>
          {!SCOPE_SERVED[scope] && (
            // The server serves one scope today. Saying so beats showing the global field under a
            // label that claims it has been filtered.
            <View style={[styles.scopeNotice, { backgroundColor: c.surfaceSunken }]}>
              <Feather name="info" size={14} color={c.bodyText} />
              <Text style={[styles.scopeNoticeText, { color: c.bodyText }]}>
                Program filtering is not live yet. These are the same global standings.
              </Text>
            </View>
          )}

          {loading ? (
            <ActivityIndicator color={c.primary} style={styles.listLoading} />
          ) : boardQuery.isError ? (
            <EmptyState
              icon="wifi-off"
              title="The board did not load"
              body="Check your connection and pull the screen open again."
            />
          ) : rows.length === 0 ? (
            <EmptyState
              icon="award"
              title="Nobody is ranked yet"
              body="The first standings for this season land at the next update. Scores update three times a day."
            />
          ) : onBoard ? (
            <View style={styles.list}>
              {listRows.map((row) => (
                <RankRow
                  key={row.rank + row.handle}
                  row={row}
                  delta={row.you ? delta : null}
                />
              ))}
            </View>
          ) : (
            <GatedList rows={listRows} onJoin={onJoin} />
          )}
        </View>
      </ScrollView>

      {/* Pinned, always visible, and outside the scroll view so it cannot be scrolled past. */}
      {pinMe && me && (
        <View style={[styles.pin, { backgroundColor: c.background, borderTopColor: c.border }]}>
          {meInList && (
            <Text style={[styles.pinLabel, { color: c.faintText }]}>Your standing</Text>
          )}
          <RankRow row={me} delta={delta} pinned />
        </View>
      )}

      <SettingsSheet
        visible={settingsOpen}
        onClose={() => setSettingsOpen(false)}
        handle={statusQuery.data?.handle ?? ""}
        canChangeHandle={statusQuery.data?.canChangeHandle ?? false}
        avatar={myAvatar}
        onChanged={async () => {
          await queryClient.invalidateQueries({ queryKey: qk.leaderboardStatus });
          await queryClient.invalidateQueries({ queryKey: qk.leaderboard });
        }}
        onLeft={async () => {
          await queryClient.invalidateQueries({ queryKey: qk.leaderboardStatus });
          await queryClient.invalidateQueries({ queryKey: qk.leaderboard });
          // Leaving unlocks the dashboard target and hands it back to the personal one.
          await queryClient.invalidateQueries({ queryKey: qk.dashboard });
          setSettingsOpen(false);
        }}
        onAuthError={onAuthError}
      />
    </View>
  );
}

// ------------------------------------------------------------------------------ the gate

/**
 * What an opted-out student sees below the podium.
 *
 * The top three stay fully legible and the rest is obscured behind a join card: the premium-gate
 * pattern, reused for a gate that costs nothing to pass. The card lists what joining actually
 * requires before the flow starts, so the first step is never a surprise.
 */
function GatedList({ rows, onJoin }: { rows: LeaderboardRow[]; onJoin: () => void }) {
  const c = useTheme();
  const teaser = rows.slice(0, 6);

  return (
    <View style={styles.gate}>
      <View
        style={[
          styles.gateRows,
          // react-native-web can actually blur. Native has no blur without another dependency, so
          // it leans on opacity, which obscures the numbers without pretending to be frosted glass.
          Platform.OS === "web" ? ({ filter: "blur(4px)" } as any) : { opacity: 0.35 },
        ]}
        pointerEvents="none"
      >
        {teaser.map((row) => (
          <RankRow key={row.rank + row.handle} row={{ ...row, you: false }} />
        ))}
      </View>

      <View style={styles.gateCardWrap} pointerEvents="box-none">
        <BrandField style={styles.gateCard}>
          <Feather name="award" size={22} color={c.accent} />
          <Text style={[styles.gateTitle, { color: c.onField }]}>See the whole board</Text>
          <Text style={[styles.gateBody, { color: c.onFieldMuted }]}>
            Joining takes three things: your transcript, a target to aim at, and a handle to be
            known by. It is free, it takes a minute, and you can leave whenever you like.
          </Text>
          <Pressable onPress={onJoin} style={[styles.gateButton, { backgroundColor: c.accent }]}>
            <Text style={styles.gateButtonText}>Join the game</Text>
          </Pressable>
        </BrandField>
      </View>
    </View>
  );
}

function EmptyState({
  icon,
  title,
  body,
}: {
  icon: keyof typeof Feather.glyphMap;
  title: string;
  body: string;
}) {
  const c = useTheme();
  return (
    <View style={styles.empty}>
      <Feather name={icon} size={28} color={c.muted} />
      <Text style={[styles.emptyTitle, { color: c.boldText }]}>{title}</Text>
      <Text style={[styles.emptyBody, { color: c.bodyText }]}>{body}</Text>
    </View>
  );
}

// -------------------------------------------------------------------------- §06 settings

/**
 * The two things the plan requires to be reversible, in one card.
 *
 * Neither is buried. The handle can change once a season and the budget is stated before the edit
 * rather than discovered on a failed save; leaving is a plain text button with no colour bait, and
 * its confirmation says exactly what happens.
 */
function SettingsSheet({
  visible,
  onClose,
  handle,
  canChangeHandle,
  avatar,
  onChanged,
  onLeft,
  onAuthError,
}: {
  visible: boolean;
  onClose: () => void;
  handle: string;
  canChangeHandle: boolean;
  avatar: AvatarConfig | null;
  onChanged: () => Promise<void>;
  onLeft: () => Promise<void>;
  onAuthError: () => void;
}) {
  const c = useTheme();
  const [draft, setDraft] = useState(handle);
  const [editing, setEditing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirmingLeave, setConfirmingLeave] = useState(false);
  const [leaving, setLeaving] = useState(false);

  useEffect(() => {
    if (visible) {
      setDraft(handle);
      setEditing(false);
      setError(null);
      setConfirmingLeave(false);
    }
  }, [visible, handle]);

  // Skipped while the sheet is shut, and while the draft is still the handle they already hold:
  // their own name is not taken from where they are standing, and asking would say so.
  const availability = useHandleAvailability(draft, {
    skip: !visible || !editing || draft.trim().toLowerCase() === handle.trim().toLowerCase(),
  });
  const shownHandleError =
    error ?? (availability.state === "taken" ? availability.reason : null);
  const handleFree = availability.state === "available";

  const save = async () => {
    const rejection = localHandleRejection(draft);
    if (rejection) {
      setError(rejection);
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await changeHandle(draft.trim());
      await onChanged();
      setEditing(false);
    } catch (err) {
      if (isAuthError(err)) {
        onAuthError();
        return;
      }
      setError(
        isServerRejection(err) && err.serverMessage
          ? err.serverMessage
          : "That did not save. Try again in a moment."
      );
    } finally {
      setSaving(false);
    }
  };

  const leave = async () => {
    setLeaving(true);
    try {
      await withdraw();
      await onLeft();
    } catch (err) {
      if (isAuthError(err)) {
        onAuthError();
        return;
      }
      setError("Leaving did not go through. Try again in a moment.");
    } finally {
      setLeaving(false);
    }
  };

  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={onClose}>
      <Pressable style={[styles.modalScrim, { backgroundColor: c.scrim }]} onPress={onClose} />

      <View style={[styles.modalSheet, { backgroundColor: c.surface }]}>
        <View style={styles.modalHead}>
          <Text style={[styles.modalTitle, { color: c.boldText }]}>Leaderboard settings</Text>
          <Pressable onPress={onClose} hitSlop={10}>
            <Feather name="x" size={20} color={c.bodyText} />
          </Pressable>
        </View>

        <ScrollView contentContainerStyle={styles.modalBody} keyboardShouldPersistTaps="handled">
          <View style={styles.identityRow}>
            <PlayerAvatar config={avatar} handle={handle} size={56} />
            <View style={styles.identityText}>
              <Text style={[styles.identityHandle, { color: c.boldText }]} numberOfLines={1}>
                {handle}
              </Text>
              <Text style={[styles.identityNote, { color: c.bodyText }]}>
                Your face is not rate limited. It carries no standing, so there is nothing to
                launder.
              </Text>
            </View>
          </View>

          <View style={styles.section}>
            <Text style={[styles.sectionTitle, { color: c.boldText }]}>Handle</Text>

            {canChangeHandle ? (
              editing ? (
                <>
                  <TextInput
                    value={draft}
                    onChangeText={(v) => {
                      setDraft(v);
                      setError(null);
                    }}
                    autoCapitalize="none"
                    autoCorrect={false}
                    maxLength={HANDLE_MAX}
                    style={[
                      styles.input,
                      {
                        backgroundColor: c.surface,
                        borderColor: shownHandleError ? c.danger : handleFree ? c.success : c.border,
                        color: c.boldText,
                      },
                    ]}
                  />
                  <Text
                    style={[
                      styles.sectionBody,
                      { color: shownHandleError ? c.danger : handleFree ? c.success : c.bodyText },
                    ]}
                  >
                    {shownHandleError ??
                      (availability.state === "checking"
                        ? "Checking that one."
                        : handleFree
                          ? "That one is free. Saving spends your one change for the season."
                          : "This spends your one change for the season.")}
                  </Text>
                  <View style={styles.actionRow}>
                    <Pressable onPress={() => setEditing(false)} hitSlop={8}>
                      <Text style={[styles.textButton, { color: c.bodyText }]}>Cancel</Text>
                    </Pressable>
                    <Pressable
                      onPress={save}
                      disabled={saving || availability.state === "taken"}
                      style={[
                        styles.primaryButton,
                        { backgroundColor: c.primary },
                        (saving || availability.state === "taken") && { opacity: 0.5 },
                      ]}
                    >
                      {saving ? (
                        <ActivityIndicator color="#FFFFFF" size="small" />
                      ) : (
                        <Text style={styles.primaryButtonText}>Save handle</Text>
                      )}
                    </Pressable>
                  </View>
                </>
              ) : (
                <>
                  {/* The budget is stated before the edit, not after a rejected save. */}
                  <Text style={[styles.sectionBody, { color: c.bodyText }]}>
                    You have one change left this season. A bad season should not be sheddable by
                    shedding recognition, so it is the only one.
                  </Text>
                  <Pressable onPress={() => setEditing(true)} hitSlop={8}>
                    <Text style={[styles.textButton, { color: c.primary }]}>Change handle</Text>
                  </Pressable>
                </>
              )
            ) : (
              <>
                <View
                  style={[
                    styles.input,
                    styles.inputReadOnly,
                    { backgroundColor: c.surfaceSunken, borderColor: c.border },
                  ]}
                >
                  <Text style={[styles.inputReadOnlyText, { color: c.bodyText }]}>{handle}</Text>
                </View>
                <Text style={[styles.sectionBody, { color: c.bodyText }]}>
                  Changes again next season. Leaving and rejoining does not reset this.
                </Text>
              </>
            )}
          </View>

          <View style={[styles.divider, { backgroundColor: c.borderSubtle }]} />

          <View style={styles.section}>
            <Text style={[styles.sectionTitle, { color: c.boldText }]}>Leaving</Text>

            {confirmingLeave ? (
              <>
                <Text style={[styles.sectionBody, { color: c.bodyText }]}>
                  Your entry stops being displayed and stops being scored. Nothing is recorded
                  against you, and nothing follows you to next season. If you come back this
                  season, your starting line and target are restored rather than reset.
                </Text>
                <View style={styles.actionRow}>
                  <Pressable onPress={() => setConfirmingLeave(false)} hitSlop={8}>
                    <Text style={[styles.textButton, { color: c.bodyText }]}>Stay</Text>
                  </Pressable>
                  <Pressable
                    onPress={leave}
                    disabled={leaving}
                    style={[styles.primaryButton, { backgroundColor: c.danger }, leaving && { opacity: 0.5 }]}
                  >
                    {leaving ? (
                      <ActivityIndicator color="#FFFFFF" size="small" />
                    ) : (
                      <Text style={styles.primaryButtonText}>Leave the board</Text>
                    )}
                  </Pressable>
                </View>
              </>
            ) : (
              // Plain text, no colour bait. Leaving is allowed, so it does not need to be scary.
              <Pressable onPress={() => setConfirmingLeave(true)} hitSlop={8}>
                <Text style={[styles.textButton, { color: c.bodyText }]}>Leave the leaderboard</Text>
              </Pressable>
            )}
          </View>
        </ScrollView>
      </View>
    </Modal>
  );
}

// ------------------------------------------------------------------------------- helpers

/** "3 hours ago", for a job that runs three times a day. Deliberately coarse: the exact minute is never the point. */
function relativeTime(iso: string): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return "";
  const minutes = Math.max(0, Math.round((Date.now() - then) / 60000));
  if (minutes < 60) return minutes <= 1 ? "just now" : `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return hours === 1 ? "1 hour ago" : `${hours} hours ago`;
  const days = Math.round(hours / 24);
  return days === 1 ? "yesterday" : `${days} days ago`;
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
  },
  scroll: {
    paddingBottom: 28,
  },
  scrollWithPin: {
    paddingBottom: 96,
  },
  header: {
    paddingTop: Platform.OS === "ios" ? 16 : 12,
    paddingBottom: 30,
    gap: 12,
    borderBottomLeftRadius: 0,
    borderBottomRightRadius: 0,
  },
  headerTop: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    paddingHorizontal: 16,
  },
  headerButton: {
    width: 32,
    height: 32,
    alignItems: "center",
    justifyContent: "center",
  },
  hidden: {
    opacity: 0,
  },
  headerTitle: {
    fontSize: 17,
    fontWeight: "700",
  },
  seasonRow: {
    alignItems: "center",
    gap: 2,
  },
  season: {
    fontSize: 13,
    fontWeight: "700",
    letterSpacing: 0.4,
    textTransform: "uppercase",
  },
  seasonMeta: {
    fontSize: 12,
  },
  tabs: {
    flexDirection: "row",
    alignSelf: "center",
    gap: 4,
    padding: 4,
    borderRadius: 999,
    backgroundColor: "rgba(0,0,0,0.18)",
  },
  tab: {
    flexDirection: "row",
    alignItems: "center",
    gap: 5,
    paddingHorizontal: 14,
    paddingVertical: 7,
    borderRadius: 999,
  },
  tabDisabled: {
    opacity: 0.55,
  },
  tabText: {
    fontSize: 13,
    fontWeight: "600",
  },
  tabTag: {
    fontSize: 10,
    fontWeight: "700",
    textTransform: "uppercase",
  },
  podiumWrap: {
    marginTop: 6,
  },
  podiumLoading: {
    paddingVertical: 40,
  },
  sheet: {
    borderTopLeftRadius: 22,
    borderTopRightRadius: 22,
    marginTop: -18,
    paddingTop: 14,
    minHeight: 300,
  },
  scopeNotice: {
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
    marginHorizontal: 14,
    marginBottom: 10,
    paddingHorizontal: 12,
    paddingVertical: 9,
    borderRadius: 10,
  },
  scopeNoticeText: {
    flex: 1,
    fontSize: 12,
    lineHeight: 17,
  },
  list: {
    paddingBottom: 8,
  },
  listLoading: {
    paddingVertical: 40,
  },
  empty: {
    alignItems: "center",
    gap: 8,
    paddingHorizontal: 32,
    paddingVertical: 48,
  },
  emptyTitle: {
    fontSize: 16,
    fontWeight: "700",
  },
  emptyBody: {
    fontSize: 13,
    lineHeight: 19,
    textAlign: "center",
  },
  gate: {
    position: "relative",
    minHeight: 320,
  },
  gateRows: {
    paddingBottom: 8,
  },
  gateCardWrap: {
    ...StyleSheet.absoluteFillObject,
    alignItems: "center",
    justifyContent: "center",
    padding: 20,
  },
  gateCard: {
    width: "100%",
    maxWidth: 380,
    borderRadius: 18,
    padding: 20,
    gap: 10,
    alignItems: "flex-start",
    boxShadow: "0px 6px 20px rgba(0,0,0,0.22)",
    elevation: 8,
  },
  gateTitle: {
    fontSize: 18,
    fontWeight: "800",
  },
  gateBody: {
    fontSize: 13,
    lineHeight: 19,
  },
  gateButton: {
    marginTop: 4,
    paddingHorizontal: 16,
    paddingVertical: 10,
    borderRadius: 10,
  },
  gateButtonText: {
    color: "#3B0A1D",
    fontSize: 14,
    fontWeight: "700",
  },
  pin: {
    position: "absolute",
    left: 0,
    right: 0,
    bottom: 0,
    paddingTop: 8,
    paddingBottom: Platform.OS === "ios" ? 26 : 12,
    borderTopWidth: StyleSheet.hairlineWidth,
  },
  pinLabel: {
    fontSize: 11,
    fontWeight: "700",
    letterSpacing: 0.5,
    textTransform: "uppercase",
    marginLeft: 26,
    marginBottom: 4,
  },
  modalScrim: {
    ...StyleSheet.absoluteFillObject,
  },
  modalSheet: {
    position: "absolute",
    left: 0,
    right: 0,
    bottom: 0,
    maxHeight: "86%",
    borderTopLeftRadius: 22,
    borderTopRightRadius: 22,
    width: "100%",
    maxWidth: 560,
    alignSelf: "center",
  },
  modalHead: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    paddingHorizontal: 20,
    paddingTop: 18,
    paddingBottom: 12,
  },
  modalTitle: {
    fontSize: 17,
    fontWeight: "800",
  },
  modalBody: {
    paddingHorizontal: 20,
    paddingBottom: Platform.OS === "ios" ? 34 : 24,
    gap: 18,
  },
  identityRow: {
    flexDirection: "row",
    gap: 14,
    alignItems: "center",
  },
  identityText: {
    flex: 1,
    gap: 3,
  },
  identityHandle: {
    fontSize: 18,
    fontWeight: "800",
  },
  identityNote: {
    fontSize: 12,
    lineHeight: 17,
  },
  section: {
    gap: 8,
  },
  sectionTitle: {
    fontSize: 13,
    fontWeight: "700",
    letterSpacing: 0.4,
    textTransform: "uppercase",
  },
  sectionBody: {
    fontSize: 13,
    lineHeight: 19,
  },
  input: {
    borderWidth: 1.5,
    borderRadius: 12,
    paddingHorizontal: 14,
    paddingVertical: 12,
    fontSize: 16,
    fontWeight: "600",
  },
  inputReadOnly: {
    justifyContent: "center",
  },
  inputReadOnlyText: {
    fontSize: 16,
    fontWeight: "600",
  },
  actionRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "flex-end",
    gap: 18,
    marginTop: 4,
  },
  textButton: {
    fontSize: 14,
    fontWeight: "600",
  },
  primaryButton: {
    minWidth: 130,
    alignItems: "center",
    justifyContent: "center",
    paddingVertical: 11,
    paddingHorizontal: 18,
    borderRadius: 10,
  },
  primaryButtonText: {
    color: "#FFFFFF",
    fontSize: 14,
    fontWeight: "700",
  },
  divider: {
    height: StyleSheet.hairlineWidth,
  },
});
