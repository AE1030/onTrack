import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  ActivityIndicator,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from "react-native";
import * as DocumentPicker from "expo-document-picker";
import { Feather } from "@expo/vector-icons";
import { useQueryClient } from "@tanstack/react-query";

import { useTheme } from "../src/theme/ThemeContext";
import { qk } from "../src/cache/keys";
import { isAuthError, isServerRejection } from "../src/cache/authedGet";
import {
  fetchPreview,
  fetchStatus,
  joinBoard,
  submitTranscript,
} from "../src/leaderboard/api";
import {
  MODE_BLURB,
  MODE_LABEL,
  ScoreMode,
  TargetPreview,
  TranscriptBaseline,
} from "../src/leaderboard/types";
import {
  MIN_GROWTH_GAP,
  SCALE_MAX,
  ceilingFor,
  fmtGpa,
  fmtScore,
  modeFor,
  targetRange,
} from "../src/leaderboard/scoring";
import { HANDLE_MAX, localHandleRejection } from "../src/leaderboard/handle";
import { useHandleAvailability } from "../src/leaderboard/useHandleAvailability";
import {
  AvatarConfig,
  avatarFor,
  normalizeAvatar,
} from "../src/leaderboard/avatar";
import BrandField from "./components/leaderboard/BrandField";
import TargetSlider from "./components/leaderboard/TargetSlider";
import AvatarBuilder from "./components/leaderboard/AvatarBuilder";
import PlayerAvatar from "./components/leaderboard/PlayerAvatar";

/**
 * Joining the board: seven cards, one decision each.
 *
 * The order is consent, rules, transcript, target, handle, avatar, review, and it is not
 * rearrangeable. Each step's validation depends on the one before it, and the target card in
 * particular cannot show a mode or a ceiling until a baseline exists to measure against.
 *
 * Three entry paths land here:
 *
 *   First time. All seven cards.
 *   Resuming. A transcript was uploaded but the join never completed, so the baseline is already
 *     captured and the transcript card says so instead of asking again. The target is still open,
 *     because nothing froze.
 *   Rejoining after leaving. The frozen baseline and target survived the withdrawal, so the
 *     transcript and target cards are skipped entirely and the flow is consent, handle, avatar,
 *     review.
 */

type CardKey = "consent" | "rules" | "transcript" | "target" | "handle" | "avatar" | "review";

const FULL_FLOW: CardKey[] = [
  "consent",
  "rules",
  "transcript",
  "target",
  "handle",
  "avatar",
  "review",
];

/** Rejoining skips what is already frozen. Asking again would imply it could be changed. */
const REJOIN_FLOW: CardKey[] = ["consent", "handle", "avatar", "review"];

const RULES = [
  "You are ranked on progress toward your own target, not on raw GPA.",
  "Your baseline and target freeze the moment you join. Neither moves for the rest of the season.",
  "Aiming a full point or more above your baseline plays Growth, which can reach 12,250 points. Below that is Maintenance, which tops out at 7,000.",
  "Progress saturates. Beating your target pays exactly what hitting it does.",
  "Moving deadlines after grades land, or bunching a course onto one date, damps your score a little. It never falls below half.",
  "Only your handle and your score are published. No grades, no name, no program.",
];

type Props = {
  onDone: () => void;
  onCancel: () => void;
  onAuthError: () => void;
};

export default function LeaderboardOnboardingScreen({ onDone, onCancel, onAuthError }: Props) {
  const c = useTheme();
  const queryClient = useQueryClient();

  const [loading, setLoading] = useState(true);
  const [flow, setFlow] = useState<CardKey[]>(FULL_FLOW);
  const [index, setIndex] = useState(0);

  const [consented, setConsented] = useState(false);
  const [rulesAccepted, setRulesAccepted] = useState(false);

  const [baseline, setBaseline] = useState<TranscriptBaseline | null>(null);
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [fileName, setFileName] = useState<string | null>(null);

  const [target, setTarget] = useState(0);
  const [preview, setPreview] = useState<TargetPreview | null>(null);

  const [handle, setHandle] = useState("");
  const [handleLocked, setHandleLocked] = useState(false);
  const [handleError, setHandleError] = useState<string | null>(null);

  const [avatar, setAvatar] = useState<AvatarConfig>(() => avatarFor("player"));
  const [avatarTouched, setAvatarTouched] = useState(false);

  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  const card = flow[index];

  // ------------------------------------------------------------------ what flow is this

  useEffect(() => {
    let cancelled = false;

    (async () => {
      try {
        const status = await fetchStatus();
        if (cancelled) return;

        // A rejoin starts from the face they had. The profile keeps it through a withdrawal.
        const saved = normalizeAvatar(status.avatar);
        if (saved) {
          setAvatar(saved);
          setAvatarTouched(true);
        }

        if (status.joined && status.status === "WITHDRAWN") {
          setFlow(REJOIN_FLOW);
          // The profile survived the withdrawal, so the handle is already theirs. It is shown
          // read-only: renaming is one per season and belongs in leaderboard settings, where the
          // budget is stated before it is spent.
          if (status.handle) {
            setHandle(status.handle);
            setHandleLocked(true);
          }
          // join() still validates a target even though the frozen row is what it actually uses.
          // The baseline is a safe value to send: it is never below itself, and it is discarded.
          const p = await fetchPreview(SCALE_MAX);
          if (!cancelled) {
            setBaseline(describeBaseline(p.baselineGpa12, true));
            setTarget(p.baselineGpa12);
          }
        } else if (status.transcriptSubmitted) {
          // Resuming. The upload landed, nothing froze, so the target is still an open choice.
          const p = await fetchPreview(SCALE_MAX);
          if (!cancelled) {
            const b = describeBaseline(p.baselineGpa12, false);
            setBaseline(b);
            setTarget(defaultTarget(b));
          }
        }
      } catch (err) {
        if (isAuthError(err)) {
          onAuthError();
          return;
        }
        // Any other failure just means the full flow runs, which is the correct fallback: the
        // transcript card will find the frozen baseline on its own.
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [onAuthError]);

  // An untouched avatar tracks the handle, so the pre-rolled face belongs to the name they picked.
  useEffect(() => {
    if (!avatarTouched && handle.trim().length >= 3) {
      setAvatar(avatarFor(handle.trim()));
    }
  }, [handle, avatarTouched]);

  // ------------------------------------------------------------------- live target preview

  const previewTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    if (!baseline || card !== "target") return;

    // The local mirror in scoring.ts has already painted this frame. The round trip only
    // reconciles, so it is debounced rather than fired per pixel of a drag.
    if (previewTimer.current) clearTimeout(previewTimer.current);
    previewTimer.current = setTimeout(() => {
      fetchPreview(target)
        .then(setPreview)
        .catch((err) => {
          if (isAuthError(err)) onAuthError();
          // Otherwise the slider keeps showing the local numbers, which is the point of having them.
        });
    }, 220);

    return () => {
      if (previewTimer.current) clearTimeout(previewTimer.current);
    };
  }, [target, baseline, card, onAuthError]);

  // The server's answer wins on screen whenever it has one for the target currently showing.
  const liveMode: ScoreMode = useMemo(() => {
    if (preview && Math.abs(preview.targetGpa12 - target) < 0.05) return preview.mode;
    return baseline ? modeFor(baseline.baselineGpa12, target) : "GROWTH";
  }, [preview, target, baseline]);

  const liveCeiling = useMemo(() => {
    if (preview && Math.abs(preview.targetGpa12 - target) < 0.05) return preview.ceiling;
    return baseline ? ceilingFor(baseline.baselineGpa12, target, liveMode) : 0;
  }, [preview, target, baseline, liveMode]);

  // --------------------------------------------------------------------------- transcript

  const pickTranscript = useCallback(async () => {
    setUploadError(null);
    const picked = await DocumentPicker.getDocumentAsync({
      type: ["application/pdf"],
      copyToCacheDirectory: true,
    });
    if (picked.canceled) return;

    const file = picked.assets[0];
    setFileName(file.name);
    setUploading(true);
    try {
      const result = await submitTranscript(file as any);
      setBaseline(result);
      setTarget(defaultTarget(result));
    } catch (err) {
      if (isAuthError(err)) {
        onAuthError();
        return;
      }
      // A failed parse is a fixable error and never a prompt to type a number in. A wrong baseline
      // is invisible, permanent for the season, and nothing downstream will correct it.
      setUploadError(
        isServerRejection(err) && err.serverMessage
          ? err.serverMessage
          : "That transcript could not be read. Upload the PDF exactly as Mosaic exports it."
      );
    } finally {
      setUploading(false);
    }
  }, [onAuthError]);

  // ------------------------------------------------------------------------------- submit

  const submit = useCallback(async () => {
    if (submitting) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      await joinBoard({
        targetGpa12: target,
        handle: handle.trim(),
        acceptRules: true,
        avatar,
      });
      await queryClient.invalidateQueries({ queryKey: qk.leaderboardStatus });
      await queryClient.invalidateQueries({ queryKey: qk.leaderboard });
      // The dashboard now shows the frozen target and locks editing it.
      await queryClient.invalidateQueries({ queryKey: qk.dashboard });
      onDone();
    } catch (err) {
      if (isAuthError(err)) {
        onAuthError();
        return;
      }
      const message =
        isServerRejection(err) && err.serverMessage
          ? err.serverMessage
          : "That did not go through. Try again in a moment.";
      setSubmitError(message);
      // A handle rejection has to send them back to the card that can fix it, or the message sits
      // on a review screen with no field on it.
      if (isServerRejection(err) && (err.status === 409 || err.status === 400)) {
        const handleStep = flow.indexOf("handle");
        if (handleStep >= 0 && /handle/i.test(message)) {
          setHandleError(message);
          setIndex(handleStep);
        }
      }
    } finally {
      setSubmitting(false);
    }
  }, [submitting, target, handle, avatar, queryClient, onDone, onAuthError, flow]);

  // ------------------------------------------------------------------------ step gating

  const blocked = ((): string | null => {
    switch (card) {
      case "consent":
        return consented ? null : "Tap to agree before continuing.";
      case "rules":
        return rulesAccepted ? null : "Check the box to continue.";
      case "transcript":
        return baseline ? null : "Upload your transcript to continue.";
      case "target":
        return null; // Every target is a legitimate choice. See the card for why.
      case "handle":
        return handleLocked ? null : localHandleRejection(handle);
      case "avatar":
        return null;
      case "review":
        return null;
      default:
        return null;
    }
  })();

  const goNext = () => {
    if (blocked) return;
    if (card === "handle") setHandleError(null);
    if (index < flow.length - 1) setIndex(index + 1);
  };

  const goBack = () => {
    if (index === 0) {
      onCancel();
      return;
    }
    setIndex(index - 1);
  };

  if (loading) {
    return (
      <View style={[styles.loading, { backgroundColor: c.background }]}>
        <ActivityIndicator size="large" color={c.primary} />
      </View>
    );
  }

  return (
    <View style={[styles.screen, { backgroundColor: c.background }]}>
      {/* A floating card rather than a full-height column: on a wide screen a column that runs
          edge to edge vertically but stops short horizontally reads as a layout that failed to
          stretch. The fixed maximum height also keeps the card from jumping between steps. */}
      <View
        style={[
          styles.frame,
          { backgroundColor: c.surface, borderColor: c.border, boxShadow: `0px 12px 40px ${c.shadowStrong}` },
        ]}
      >
        <Header
          step={index + 1}
          total={flow.length}
          title={CARD_TITLES[card]}
          onClose={onCancel}
        />

        <ScrollView
          style={styles.body}
          contentContainerStyle={styles.bodyContent}
          keyboardShouldPersistTaps="handled"
        >
          {card === "consent" && (
            <ConsentCard agreed={consented} onAgree={() => setConsented(true)} />
          )}

          {card === "rules" && (
            <RulesCard accepted={rulesAccepted} onToggle={() => setRulesAccepted((v) => !v)} />
          )}

          {card === "transcript" && (
            <TranscriptCard
              baseline={baseline}
              fileName={fileName}
              uploading={uploading}
              error={uploadError}
              onPick={pickTranscript}
            />
          )}

          {card === "target" && baseline && (
            <TargetCard
              baseline={baseline}
              target={target}
              onTarget={setTarget}
              mode={liveMode}
              ceiling={liveCeiling}
            />
          )}

          {card === "handle" && (
            <HandleCard
              handle={handle}
              locked={handleLocked}
              onChange={(v) => {
                setHandle(v);
                setHandleError(null);
              }}
              error={handleError}
            />
          )}

          {card === "avatar" && (
            <View style={styles.cardBody}>
              <Text style={[styles.lead, { color: c.bodyText }]}>
                Pick a face for the board, or keep the one we rolled for your handle. Avatars are
                drawn, never uploaded, and you can change yours whenever you like.
              </Text>
              <AvatarBuilder
                value={avatar}
                onChange={(next) => {
                  setAvatar(next);
                  setAvatarTouched(true);
                }}
              />
            </View>
          )}

          {card === "review" && (
            <ReviewCard
              flow={flow}
              baseline={baseline}
              target={target}
              mode={liveMode}
              ceiling={liveCeiling}
              handle={handle}
              avatar={avatar}
              error={submitError}
              onJump={(key) => {
                const at = flow.indexOf(key);
                if (at >= 0) setIndex(at);
              }}
            />
          )}
        </ScrollView>

        <Footer
          onBack={goBack}
          backLabel={index === 0 ? "Cancel" : "Back"}
          onNext={card === "review" ? submit : goNext}
          nextLabel={card === "review" ? "Submit" : "Next"}
          disabled={card === "review" ? submitting : blocked !== null}
          busy={submitting}
          hint={blocked}
        />
      </View>
    </View>
  );
}

// ------------------------------------------------------------------------------- the cards

const CARD_TITLES: Record<CardKey, string> = {
  consent: "What this is",
  rules: "The rules",
  transcript: "Your starting line",
  target: "Your target",
  handle: "Your handle",
  avatar: "Your face",
  review: "Check and join",
};

function ConsentCard({ agreed, onAgree }: { agreed: boolean; onAgree: () => void }) {
  const c = useTheme();
  return (
    <View style={styles.cardBody}>
      <Text style={[styles.lead, { color: c.bodyText }]}>
        The leaderboard ranks students on how far they move toward the goal they set for
        themselves. Someone starting at 5.0 and climbing can outrank someone sitting at 11.0.
      </Text>

      <View style={[styles.notice, { backgroundColor: c.primarySoft, borderColor: c.border }]}>
        <Text style={[styles.noticeTitle, { color: c.boldText }]}>What gets published</Text>
        <Text style={[styles.noticeBody, { color: c.bodyText }]}>
          A handle you choose, and a score derived from your grades. Every other student can see
          both. Your GPA, your target, your name and your courses are not published, and this app
          keeps them encrypted.
        </Text>
      </View>

      <Text style={[styles.lead, { color: c.bodyText }]}>
        This is a real disclosure of something derived from your grades, so it takes a deliberate
        yes rather than a default. You can leave at any time.
      </Text>

      <Pressable
        onPress={onAgree}
        style={[
          styles.bigChoice,
          { borderColor: agreed ? c.primary : c.border, backgroundColor: agreed ? c.primarySoft : c.surface },
        ]}
      >
        <Feather
          name={agreed ? "check-circle" : "circle"}
          size={20}
          color={agreed ? c.primary : c.muted}
        />
        <Text style={[styles.bigChoiceText, { color: agreed ? c.primary : c.boldText }]}>
          I understand what is published
        </Text>
      </Pressable>
    </View>
  );
}

function RulesCard({ accepted, onToggle }: { accepted: boolean; onToggle: () => void }) {
  const c = useTheme();
  return (
    <View style={styles.cardBody}>
      <Text style={[styles.lead, { color: c.bodyText }]}>
        Six of them. They are frozen for the season, so they are worth the thirty seconds.
      </Text>

      <View style={styles.rules}>
        {RULES.map((rule, i) => (
          <View key={i} style={styles.rule}>
            <View style={[styles.ruleNumber, { backgroundColor: c.primarySoft }]}>
              <Text style={[styles.ruleNumberText, { color: c.primary }]}>{i + 1}</Text>
            </View>
            <Text style={[styles.ruleText, { color: c.boldText }]}>{rule}</Text>
          </View>
        ))}
      </View>

      <Pressable
        onPress={onToggle}
        style={[
          styles.bigChoice,
          { borderColor: accepted ? c.primary : c.border, backgroundColor: accepted ? c.primarySoft : c.surface },
        ]}
      >
        <Feather
          name={accepted ? "check-square" : "square"}
          size={20}
          color={accepted ? c.primary : c.muted}
        />
        <Text style={[styles.bigChoiceText, { color: accepted ? c.primary : c.boldText }]}>
          I have read the rules
        </Text>
      </Pressable>
    </View>
  );
}

function TranscriptCard({
  baseline,
  fileName,
  uploading,
  error,
  onPick,
}: {
  baseline: TranscriptBaseline | null;
  fileName: string | null;
  uploading: boolean;
  error: string | null;
  onPick: () => void;
}) {
  const c = useTheme();

  if (baseline) {
    return (
      <View style={styles.cardBody}>
        <View style={[styles.result, { backgroundColor: c.successSoft, borderColor: c.success }]}>
          <Feather name="check-circle" size={18} color={c.success} />
          <View style={styles.resultText}>
            <Text style={[styles.resultTitle, { color: c.boldText }]}>
              {baseline.frozen ? "Baseline already set" : "Transcript parsed"}
            </Text>
            <Text style={[styles.resultBody, { color: c.bodyText }]}>
              Starting line {fmtGpa(baseline.baselineGpa12)} on the 12 point scale.
              {baseline.frozen
                ? " This was frozen when you first joined, so it does not change."
                : ""}
            </Text>
          </View>
        </View>

        {baseline.baselineGpa12 === 0 && (
          <Text style={[styles.lead, { color: c.bodyText }]}>
            Nothing is graded yet, so your starting line is zero. That is a real answer for a first
            term and not a problem: everything you earn from here counts as progress.
          </Text>
        )}

        {!baseline.growthAvailable && (
          <View style={[styles.notice, { backgroundColor: c.accentSoft, borderColor: c.accent }]}>
            <Text style={[styles.noticeTitle, { color: c.boldText }]}>You are above 11.0</Text>
            <Text style={[styles.noticeBody, { color: c.bodyText }]}>
              There is no room left to declare a full point of climb, so you play Ceiling: holding
              your level is the whole game, and it scores up to 10,000.
            </Text>
          </View>
        )}

        {!baseline.frozen && (
          <Pressable onPress={onPick} disabled={uploading}>
            <Text style={[styles.link, { color: c.primary }]}>Upload a different transcript</Text>
          </Pressable>
        )}
      </View>
    );
  }

  return (
    <View style={styles.cardBody}>
      <Text style={[styles.lead, { color: c.bodyText }]}>
        Your starting line comes from your transcript, not from a number you type. It is what every
        point of progress this season is measured against, and it cannot be changed once you join.
      </Text>

      <Pressable
        onPress={onPick}
        disabled={uploading}
        style={[styles.dropzone, { borderColor: c.border, backgroundColor: c.surface }]}
      >
        <View style={[styles.dropIcon, { backgroundColor: c.primarySoft }]}>
          {uploading ? (
            <ActivityIndicator color={c.primary} />
          ) : (
            <Feather name="upload" size={22} color={c.primary} />
          )}
        </View>
        <Text style={[styles.dropTitle, { color: c.boldText }]}>
          {uploading ? "Reading your transcript" : "Select your transcript PDF"}
        </Text>
        <Text style={[styles.dropBody, { color: c.bodyText }]}>
          Export it from Mosaic without editing it. A file already used by another account is
          refused.
        </Text>
        {fileName && !uploading && (
          <Text style={[styles.fileName, { color: c.faintText }]}>{fileName}</Text>
        )}
      </Pressable>

      {error && (
        <View style={[styles.errorBox, { backgroundColor: c.dangerSoft, borderColor: c.danger }]}>
          <Feather name="alert-circle" size={16} color={c.danger} />
          <Text style={[styles.errorText, { color: c.danger }]}>{error}</Text>
        </View>
      )}
    </View>
  );
}

function TargetCard({
  baseline,
  target,
  onTarget,
  mode,
  ceiling,
}: {
  baseline: TranscriptBaseline;
  target: number;
  onTarget: (v: number) => void;
  mode: ScoreMode;
  ceiling: number;
}) {
  const c = useTheme();
  const range = targetRange(baseline.baselineGpa12);

  // Above 11.0 the slider cannot change the outcome, and offering a control that does nothing
  // reads as a bug. The card states the mode instead.
  if (!baseline.growthAvailable) {
    return (
      <View style={styles.cardBody}>
        <BrandField style={styles.ceilingField}>
          <Text style={[styles.fieldLabel, { color: c.onFieldFaint }]}>Your mode</Text>
          <Text style={[styles.fieldValue, { color: c.onField }]}>{MODE_LABEL.CEILING}</Text>
          <Text style={[styles.fieldBody, { color: c.onFieldMuted }]}>{MODE_BLURB.CEILING}</Text>
        </BrandField>

        <Text style={[styles.lead, { color: c.bodyText }]}>
          You are starting at {fmtGpa(baseline.baselineGpa12)}, which leaves less than a full point
          of the scale above you. There is no target to choose: you score on holding your level,
          up to 10,000.
        </Text>
      </View>
    );
  }

  const isGrowth = mode === "GROWTH";

  return (
    <View style={styles.cardBody}>
      <BrandField style={styles.targetField}>
        <Text style={[styles.fieldLabel, { color: c.onFieldFaint }]}>Target GPA</Text>
        <Text style={[styles.targetValue, { color: c.onField }]}>{target.toFixed(1)}</Text>

        <View style={styles.fieldSplit}>
          <View>
            <Text style={[styles.fieldLabel, { color: c.onFieldFaint }]}>Mode</Text>
            <Text style={[styles.fieldValue, { color: isGrowth ? c.accent : c.onField }]}>
              {MODE_LABEL[mode]}
            </Text>
          </View>
          <View style={styles.fieldRight}>
            {/* The reward for reaching has to be visible at the moment of choosing, or there is
                no reason to believe the ambition bonus is real. */}
            <Text style={[styles.fieldLabel, { color: c.onFieldFaint }]}>Best possible score</Text>
            <Text style={[styles.fieldValue, { color: c.accent }]}>{fmtScore(ceiling)}</Text>
          </View>
        </View>
      </BrandField>

      <TargetSlider
        min={range.min}
        max={range.max}
        value={target}
        onChange={onTarget}
        growthAt={baseline.minGrowthTarget}
      />

      <Text style={[styles.lead, { color: c.bodyText }]}>{MODE_BLURB[mode]}</Text>

      {!isGrowth && (
        // Not a validation error. Holding steady through a heavy term is a legitimate choice, and
        // the card states the trade rather than refusing to move on.
        <View style={[styles.notice, { backgroundColor: c.accentSoft, borderColor: c.accent }]}>
          <Text style={[styles.noticeTitle, { color: c.boldText }]}>
            This is a Maintenance target
          </Text>
          <Text style={[styles.noticeBody, { color: c.bodyText }]}>
            Anything under {fmtGpa(baseline.baselineGpa12 + MIN_GROWTH_GAP)} is holding rather than
            climbing, so your score tops out at 7,000 instead of 12,250. That is a fair trade for a
            heavy term, and you can continue with it.
          </Text>
        </View>
      )}

      <View style={[styles.notice, { backgroundColor: c.surfaceSunken, borderColor: c.border }]}>
        <Text style={[styles.noticeTitle, { color: c.boldText }]}>This freezes at submit</Text>
        <Text style={[styles.noticeBody, { color: c.bodyText }]}>
          Your target cannot be changed for the rest of the season. Lowering it in week 11 would
          make everything before it count for more, so nobody gets to.
        </Text>
      </View>
    </View>
  );
}

function HandleCard({
  handle,
  locked,
  onChange,
  error,
}: {
  handle: string;
  locked: boolean;
  onChange: (v: string) => void;
  error: string | null;
}) {
  const c = useTheme();
  const localError = handle.length > 0 ? localHandleRejection(handle) : null;
  const availability = useHandleAvailability(handle, { skip: locked });
  const shown = error ?? localError ?? (availability.state === "taken" ? availability.reason : null);
  const free = availability.state === "available";

  return (
    <View style={styles.cardBody}>
      <Text style={[styles.lead, { color: c.bodyText }]}>
        This is the only thing about you the board shows. Your username is not used: it was never
        unique and it was never meant to be read by strangers.
      </Text>

      <TextInput
        value={handle}
        onChangeText={onChange}
        editable={!locked}
        autoCapitalize="none"
        autoCorrect={false}
        maxLength={HANDLE_MAX}
        placeholder="pick_a_handle"
        placeholderTextColor={c.placeholder}
        style={[
          styles.input,
          {
            backgroundColor: locked ? c.surfaceSunken : c.surface,
            borderColor: shown ? c.danger : free ? c.success : c.border,
            color: c.boldText,
          },
        ]}
      />

      <View style={styles.inputMeta}>
        <Text style={[styles.hint, { color: shown ? c.danger : free ? c.success : c.bodyText }]}>
          {shown ??
            (availability.state === "checking"
              ? "Checking that one."
              : free
                ? "That one is free."
                : "3 to 24 characters. Letters, numbers, underscores and hyphens.")}
        </Text>
        {!locked && (
          <Text style={[styles.counter, { color: c.faintText }]}>
            {handle.length}/{HANDLE_MAX}
          </Text>
        )}
      </View>

      {locked && (
        <View style={[styles.notice, { backgroundColor: c.surfaceSunken, borderColor: c.border }]}>
          <Text style={[styles.noticeTitle, { color: c.boldText }]}>You already have a handle</Text>
          <Text style={[styles.noticeBody, { color: c.bodyText }]}>
            It survived you leaving, and so did your one rename for the season. Change it from
            leaderboard settings once you are back on the board.
          </Text>
        </View>
      )}
    </View>
  );
}

function ReviewCard({
  flow,
  baseline,
  target,
  mode,
  ceiling,
  handle,
  avatar,
  error,
  onJump,
}: {
  flow: CardKey[];
  baseline: TranscriptBaseline | null;
  target: number;
  mode: ScoreMode;
  ceiling: number;
  handle: string;
  avatar: AvatarConfig;
  error: string | null;
  onJump: (key: CardKey) => void;
}) {
  const c = useTheme();
  const rejoining = !flow.includes("target");

  const rows: { key: CardKey; label: string; value: string }[] = [
    { key: "handle", label: "Handle", value: handle.trim() || "Not set" },
  ];
  if (!rejoining && baseline) {
    rows.unshift({
      key: "target",
      label: "Target",
      value: `${target.toFixed(1)}  ·  ${MODE_LABEL[mode]}`,
    });
    rows.unshift({
      key: "transcript",
      label: "Starting line",
      value: fmtGpa(baseline.baselineGpa12),
    });
  }

  return (
    <View style={styles.cardBody}>
      <View style={styles.reviewHead}>
        <PlayerAvatar config={avatar} size={72} ring="gold" />
        <View style={styles.reviewHeadText}>
          <Text style={[styles.reviewHandle, { color: c.boldText }]} numberOfLines={1}>
            {handle.trim() || "Not set"}
          </Text>
          {!rejoining && (
            <Text style={[styles.reviewCeiling, { color: c.bodyText }]}>
              Best possible score {fmtScore(ceiling)}
            </Text>
          )}
        </View>
      </View>

      <View style={[styles.reviewRows, { borderColor: c.border, backgroundColor: c.surface }]}>
        {rows.map((row, i) => (
          <Pressable
            key={row.key}
            onPress={() => onJump(row.key)}
            style={[
              styles.reviewRow,
              i > 0 && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: c.borderSubtle },
            ]}
          >
            <Text style={[styles.reviewLabel, { color: c.bodyText }]}>{row.label}</Text>
            <Text style={[styles.reviewValue, { color: c.boldText }]}>{row.value}</Text>
            <Feather name="chevron-right" size={16} color={c.faintText} />
          </Pressable>
        ))}
      </View>

      <Text style={[styles.lead, { color: c.bodyText }]}>
        {rejoining
          ? "Your starting line and target are the ones you froze when you first joined this season. Rejoining restores them rather than starting you over."
          : "Submitting freezes your starting line, your target and your mode for the rest of the season. Your handle and your face can still change."}
      </Text>

      {error && (
        <View style={[styles.errorBox, { backgroundColor: c.dangerSoft, borderColor: c.danger }]}>
          <Feather name="alert-circle" size={16} color={c.danger} />
          <Text style={[styles.errorText, { color: c.danger }]}>{error}</Text>
        </View>
      )}
    </View>
  );
}

// ------------------------------------------------------------------------------- chrome

function Header({
  step,
  total,
  title,
  onClose,
}: {
  step: number;
  total: number;
  title: string;
  onClose: () => void;
}) {
  const c = useTheme();
  return (
    <BrandField style={styles.header}>
      <View style={styles.headerTop}>
        <Text style={[styles.headerStep, { color: c.onFieldFaint }]}>
          Step {step} of {total}
        </Text>
        <Pressable onPress={onClose} hitSlop={10}>
          <Feather name="x" size={20} color={c.onFieldMuted} />
        </Pressable>
      </View>

      <Text style={[styles.headerTitle, { color: c.onField }]}>{title}</Text>

      <View style={styles.progressTrack}>
        {Array.from({ length: total }).map((_, i) => (
          <View
            key={i}
            style={[
              styles.progressSegment,
              { backgroundColor: i < step ? c.accent : "rgba(255,255,255,0.22)" },
            ]}
          />
        ))}
      </View>
    </BrandField>
  );
}

function Footer({
  onBack,
  backLabel,
  onNext,
  nextLabel,
  disabled,
  busy,
  hint,
}: {
  onBack: () => void;
  backLabel: string;
  onNext: () => void;
  nextLabel: string;
  disabled: boolean;
  busy: boolean;
  hint: string | null;
}) {
  const c = useTheme();
  return (
    <View style={[styles.footer, { backgroundColor: c.surface, borderTopColor: c.border }]}>
      {hint && <Text style={[styles.footerHint, { color: c.faintText }]}>{hint}</Text>}
      <View style={styles.footerRow}>
        <Pressable onPress={onBack} style={styles.footerBack} hitSlop={8}>
          <Text style={[styles.footerBackText, { color: c.bodyText }]}>{backLabel}</Text>
        </Pressable>

        <Pressable
          onPress={onNext}
          disabled={disabled}
          style={[
            styles.footerNext,
            { backgroundColor: c.primary },
            disabled && { opacity: 0.45 },
          ]}
        >
          {busy ? (
            <ActivityIndicator color="#FFFFFF" size="small" />
          ) : (
            <Text style={styles.footerNextText}>{nextLabel}</Text>
          )}
        </Pressable>
      </View>
    </View>
  );
}

// ------------------------------------------------------------------------------- helpers

/**
 * Rebuilds a TranscriptBaselineResponse from a baseline the preview endpoint handed back.
 *
 * Every field of it is a pure function of the baseline, and the arithmetic is mirrored in
 * scoring.ts, so this avoids asking the student to re-upload a PDF the server already has just to
 * learn a number it already told us.
 */
function describeBaseline(baselineGpa12: number, frozen: boolean): TranscriptBaseline {
  const headroom = SCALE_MAX - baselineGpa12;
  const growthAvailable = headroom >= MIN_GROWTH_GAP;
  return {
    baselineGpa12,
    headroom: Math.round(headroom * 100) / 100,
    growthAvailable,
    minGrowthTarget: growthAvailable
      ? Math.round((baselineGpa12 + MIN_GROWTH_GAP) * 100) / 100
      : null,
    frozen,
  };
}

/** Opens the slider one full point up, which is the lowest target that still plays Growth. */
function defaultTarget(baseline: TranscriptBaseline): number {
  if (!baseline.growthAvailable) return baseline.baselineGpa12;
  return Math.min(SCALE_MAX, Math.round((baseline.baselineGpa12 + MIN_GROWTH_GAP) * 10) / 10);
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
    paddingHorizontal: 16,
    paddingVertical: Platform.OS === "ios" ? 12 : 24,
  },
  loading: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
  },
  frame: {
    width: "100%",
    maxWidth: 560,
    height: "100%",
    maxHeight: 760,
    borderRadius: 20,
    borderWidth: StyleSheet.hairlineWidth,
    // Clips the maroon header and the footer to the card's rounded corners.
    overflow: "hidden",
    elevation: 10,
  },
  header: {
    paddingHorizontal: 20,
    paddingTop: 18,
    paddingBottom: 16,
    gap: 10,
  },
  headerTop: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
  },
  headerStep: {
    fontSize: 12,
    fontWeight: "700",
    letterSpacing: 0.5,
    textTransform: "uppercase",
  },
  headerTitle: {
    fontSize: 24,
    fontWeight: "800",
  },
  progressTrack: {
    flexDirection: "row",
    gap: 4,
    marginTop: 2,
  },
  progressSegment: {
    flex: 1,
    height: 3,
    borderRadius: 2,
  },
  body: {
    flex: 1,
  },
  bodyContent: {
    padding: 20,
    paddingBottom: 32,
  },
  cardBody: {
    gap: 16,
  },
  lead: {
    fontSize: 14,
    lineHeight: 21,
  },
  notice: {
    borderWidth: 1,
    borderRadius: 12,
    padding: 14,
    gap: 5,
  },
  noticeTitle: {
    fontSize: 13,
    fontWeight: "700",
  },
  noticeBody: {
    fontSize: 13,
    lineHeight: 19,
  },
  bigChoice: {
    flexDirection: "row",
    alignItems: "center",
    gap: 10,
    borderWidth: 1.5,
    borderRadius: 12,
    padding: 14,
  },
  bigChoiceText: {
    fontSize: 14,
    fontWeight: "600",
    flex: 1,
  },
  rules: {
    gap: 12,
  },
  rule: {
    flexDirection: "row",
    gap: 10,
    alignItems: "flex-start",
  },
  ruleNumber: {
    width: 22,
    height: 22,
    borderRadius: 11,
    alignItems: "center",
    justifyContent: "center",
    marginTop: 1,
  },
  ruleNumberText: {
    fontSize: 12,
    fontWeight: "800",
  },
  ruleText: {
    flex: 1,
    fontSize: 13.5,
    lineHeight: 20,
  },
  dropzone: {
    borderWidth: 1,
    borderStyle: "dashed",
    borderRadius: 14,
    padding: 22,
    alignItems: "center",
    gap: 8,
  },
  dropIcon: {
    width: 52,
    height: 52,
    borderRadius: 26,
    alignItems: "center",
    justifyContent: "center",
    marginBottom: 4,
  },
  dropTitle: {
    fontSize: 15,
    fontWeight: "700",
  },
  dropBody: {
    fontSize: 13,
    lineHeight: 19,
    textAlign: "center",
  },
  fileName: {
    fontSize: 12,
    marginTop: 2,
  },
  result: {
    flexDirection: "row",
    gap: 12,
    borderWidth: 1,
    borderRadius: 12,
    padding: 14,
    alignItems: "flex-start",
  },
  resultText: {
    flex: 1,
    gap: 3,
  },
  resultTitle: {
    fontSize: 14,
    fontWeight: "700",
  },
  resultBody: {
    fontSize: 13,
    lineHeight: 19,
  },
  link: {
    fontSize: 13,
    fontWeight: "600",
  },
  errorBox: {
    flexDirection: "row",
    gap: 8,
    borderWidth: 1,
    borderRadius: 12,
    padding: 12,
    alignItems: "flex-start",
  },
  errorText: {
    flex: 1,
    fontSize: 13,
    lineHeight: 19,
  },
  targetField: {
    borderRadius: 16,
    padding: 18,
    gap: 4,
  },
  ceilingField: {
    borderRadius: 16,
    padding: 18,
    gap: 4,
  },
  fieldLabel: {
    fontSize: 11,
    fontWeight: "700",
    letterSpacing: 0.5,
    textTransform: "uppercase",
  },
  fieldValue: {
    fontSize: 18,
    fontWeight: "800",
  },
  fieldBody: {
    fontSize: 13,
    lineHeight: 19,
    marginTop: 4,
  },
  targetValue: {
    fontSize: 46,
    fontWeight: "800",
    fontVariant: ["tabular-nums"],
    marginBottom: 8,
  },
  fieldSplit: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "flex-end",
  },
  fieldRight: {
    alignItems: "flex-end",
  },
  input: {
    borderWidth: 1.5,
    borderRadius: 12,
    paddingHorizontal: 14,
    paddingVertical: 12,
    fontSize: 16,
    fontWeight: "600",
  },
  inputMeta: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    gap: 12,
    marginTop: -8,
  },
  hint: {
    flex: 1,
    fontSize: 12,
    lineHeight: 17,
  },
  counter: {
    fontSize: 12,
    fontVariant: ["tabular-nums"],
  },
  reviewHead: {
    flexDirection: "row",
    alignItems: "center",
    gap: 14,
  },
  reviewHeadText: {
    flex: 1,
    gap: 3,
  },
  reviewHandle: {
    fontSize: 20,
    fontWeight: "800",
  },
  reviewCeiling: {
    fontSize: 13,
  },
  reviewRows: {
    borderWidth: 1,
    borderRadius: 12,
    overflow: "hidden",
  },
  reviewRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: 10,
    paddingHorizontal: 14,
    paddingVertical: 13,
  },
  reviewLabel: {
    fontSize: 13,
    width: 92,
  },
  reviewValue: {
    flex: 1,
    fontSize: 14,
    fontWeight: "700",
  },
  footer: {
    borderTopWidth: StyleSheet.hairlineWidth,
    paddingHorizontal: 20,
    paddingTop: 12,
    paddingBottom: 16,
    gap: 8,
  },
  footerHint: {
    fontSize: 12,
    textAlign: "right",
  },
  footerRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
  },
  footerBack: {
    paddingVertical: 10,
    paddingHorizontal: 6,
  },
  footerBackText: {
    fontSize: 15,
    fontWeight: "600",
  },
  footerNext: {
    minWidth: 132,
    alignItems: "center",
    justifyContent: "center",
    paddingVertical: 13,
    paddingHorizontal: 24,
    borderRadius: 12,
  },
  footerNextText: {
    color: "#FFFFFF",
    fontSize: 15,
    fontWeight: "700",
  },
});
