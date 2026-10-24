import React, { useEffect, useState, useCallback } from "react";
import {
  View,
  StyleSheet,
  Pressable,
  ScrollView,
  Modal,
  ActivityIndicator,
  LayoutChangeEvent,
  useWindowDimensions,
} from "react-native";
import { Text, TextInput } from "../src/ui/Text";
import { noHover } from "../src/ui/hover";
import Animated, {
  useSharedValue,
  useAnimatedStyle,
  withTiming,
  withDelay,
  Easing,
} from "react-native-reanimated";
import { Feather } from "@expo/vector-icons";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { colors } from "../src/theme/colors";
import { spacing, radius } from "../src/theme/spacing";
import { fonts, typo } from "../src/theme/typography";
import { qk } from "../src/cache/keys";
import { authedFetch, authedGet, isAuthError, queryRetry } from "../src/cache/authedGet";
import { useTerm } from "../src/terms/TermContext";
import GpaArc from "./components/GpaArc";
import OnTrackLogo from "./components/OnTrackLogo";
import LeaderboardCard from "./components/LeaderboardCard";

type DashboardData = {
  username: string;
  gpa4: number | null;
  gpa12: number | null;
  targetGpa4: number | null;
  targetGpa12: number | null;
  /** True while on the leaderboard: the target is the one frozen at join and cannot be edited. */
  targetLocked: boolean;
};

type UpcomingEvent = {
  courseCode: string;
  assessmentName: string;
  dueDate: string;
  startTime: string | null;
  endTime: string | null;
  allDay: boolean;
  completed: boolean;
  eventKey: string;
  description: string | null;
  location: string | null;
};

type CurrentCourse = {
  courseName: string;
  credits: string;
  grade: number | null;
  term: string;
  courseCode: string;
  includeInGpa: boolean;
};

const ACCENT_COLORS = colors.courseAccents;

type EventRowProps = {
  event: UpcomingEvent;
  formatDueDate: (dateStr: string) => string;
  onComplete: (eventKey: string) => Promise<boolean>;
};

function EventRow({ event, formatDueDate, onComplete }: EventRowProps) {
  const checkFill = useSharedValue(0);
  const strikethrough = useSharedValue(0);
  const textFade = useSharedValue(1);
  const rowOpacity = useSharedValue(1);
  const rowHeight = useSharedValue(1);
  const [completing, setCompleting] = useState(false);

  const handleComplete = async () => {
    if (completing) return;
    setCompleting(true);

    const success = await onComplete(event.eventKey);
    if (!success) {
      setCompleting(false);
      return;
    }

    // Phase 1: Check fills, strikethrough draws, text fades
    checkFill.value = withTiming(1, {
      duration: 200,
      easing: Easing.out(Easing.quad),
    });
    strikethrough.value = withDelay(
      100,
      withTiming(1, { duration: 250, easing: Easing.out(Easing.quad) })
    );
    textFade.value = withDelay(
      100,
      withTiming(0.4, { duration: 250, easing: Easing.out(Easing.quad) })
    );

    // Phase 2: After a hold, collapse and fade the row
    rowOpacity.value = withDelay(
      650,
      withTiming(0, { duration: 300, easing: Easing.out(Easing.quad) })
    );
    rowHeight.value = withDelay(
      650,
      withTiming(0, { duration: 300, easing: Easing.out(Easing.quad) })
    );
  };

  const checkCircleStyle = useAnimatedStyle(() => ({
    backgroundColor:
      checkFill.value > 0.5 ? colors.primary : "transparent",
    borderColor: checkFill.value > 0.5 ? colors.primary : colors.border,
    transform: [{ scale: 1 + checkFill.value * 0.1 }],
  }));

  const checkIconStyle = useAnimatedStyle(() => ({
    opacity: checkFill.value,
    transform: [{ scale: 0.5 + checkFill.value * 0.5 }],
  }));

  const strikethroughStyle = useAnimatedStyle(() => ({
    width: `${strikethrough.value * 100}%` as any,
  }));

  const textStyle = useAnimatedStyle(() => ({
    opacity: textFade.value,
  }));

  const rowContainerStyle = useAnimatedStyle(() => ({
    opacity: rowOpacity.value,
    maxHeight: rowHeight.value === 1 ? 200 : rowHeight.value * 200,
    marginBottom: rowHeight.value === 1 ? 0 : rowHeight.value * 0,
    transform: [{ scaleY: 0.5 + rowHeight.value * 0.5 }],
  }));

  return (
    <Animated.View style={[styles.eventRow, rowContainerStyle]}>
      <Pressable onPress={handleComplete} disabled={completing}>
        <Animated.View style={[styles.completeCircle, checkCircleStyle]}>
          <Animated.Text style={[styles.completeCheck, checkIconStyle]}>
            {"\u2713"}
          </Animated.Text>
        </Animated.View>
      </Pressable>

      <View style={styles.eventDateBadge}>
        <Animated.Text style={[styles.eventDateText, textStyle]}>
          {formatDueDate(event.dueDate)}
        </Animated.Text>
      </View>

      <View style={styles.eventDetails}>
        <View style={styles.eventNameContainer}>
          <Animated.Text
            style={[styles.eventName, textStyle]}
            numberOfLines={1}
          >
            {event.assessmentName}
          </Animated.Text>
          <Animated.View style={[styles.strikethroughLine, strikethroughStyle]} />
        </View>
        <Animated.Text
          style={[styles.eventCourse, textStyle]}
          numberOfLines={1}
        >
          {event.courseCode}
          {event.startTime ? ` \u00B7 ${event.startTime}` : ""}
        </Animated.Text>
      </View>
    </Animated.View>
  );
}

type Props = {
  onGoToCalendar: () => void;
  onGoToMyCourses: () => void;
  onGoToLeaderboard: () => void;
  onJoinLeaderboard: () => void;
  onAuthError: () => void;
};

// Two layouts. Below WIDE everything stacks in one column, in priority order. From WIDE up, the
// GPA and leaderboard cards form a fixed rail and the due dates fill the column beside it, so the
// two columns always end level instead of leaving a hole under the shorter card.
const WIDE = 768;
const DESKTOP = 1024;

// Narrowest a course card can get before its code and grade start to collide.
const COURSE_MIN_WIDTH = 220;
const COURSE_GAP = spacing.md;

/**
 * Splits `count` cards into rows that always span the full width.
 *
 * Column count is the most that fit at COURSE_MIN_WIDTH, then rebalanced so rows come out even
 * (6 courses that fit 5 across become 3 and 3, not 5 and 1). A short final row stretches to the
 * edge too, so a student with two courses gets two wide cards rather than two narrow ones and an
 * empty strip on the right.
 */
function courseRows(count: number, width: number): number[] {
  if (count === 0 || width <= 0) return [];
  const fit = Math.max(1, Math.floor((width + COURSE_GAP) / (COURSE_MIN_WIDTH + COURSE_GAP)));
  const rows = Math.ceil(count / Math.min(fit, count));
  const perRow = Math.ceil(count / rows);
  const out: number[] = [];
  for (let left = count; left > 0; left -= perRow) out.push(Math.min(perRow, left));
  return out;
}

export default function DashboardScreen({
  onGoToCalendar,
  onGoToMyCourses,
  onGoToLeaderboard,
  onJoinLeaderboard,
  onAuthError,
}: Props) {
  const queryClient = useQueryClient();
  const { currentTerm } = useTerm();
  const [use12Scale, setUse12Scale] = useState(false);
  const [showTargetModal, setShowTargetModal] = useState(false);
  const [targetInput4, setTargetInput4] = useState("");
  const [targetInput12, setTargetInput12] = useState("");
  const [savingTarget, setSavingTarget] = useState(false);
  const [courseGridWidth, setCourseGridWidth] = useState(0);

  const { width: windowWidth } = useWindowDimensions();
  const wide = windowWidth >= WIDE;
  const desktop = windowWidth >= DESKTOP;

  // Animation for scale toggle
  const toggleProgress = useSharedValue(0);

  const dashboardQuery = useQuery({
    queryKey: qk.dashboard,
    queryFn: () => authedGet<DashboardData>("/api/dashboard"),
    staleTime: 5 * 60_000,
    retry: queryRetry,
  });

  const upcomingEventsQuery = useQuery({
    queryKey: qk.upcomingEvents,
    queryFn: () => authedGet<UpcomingEvent[]>("/api/dashboard/upcoming-events"),
    staleTime: 2 * 60_000,
    retry: queryRetry,
  });

  // Pinned to the current term, not the picker. The GPA beside these cards is computed from
  // the current term only, so following a past-term selection here would put a past term's
  // courses next to a number that does not describe them. When the picker is on the current
  // term this is the same key My Courses uses and TanStack dedupes the fetch.
  const currentCoursesQuery = useQuery({
    queryKey: qk.currentCourses(currentTerm),
    queryFn: () =>
      authedGet<CurrentCourse[]>(
        `/api/my-courses/current-courses?term=${encodeURIComponent(currentTerm)}`
      ),
    staleTime: 5 * 60_000,
    retry: queryRetry,
    enabled: !!currentTerm,
  });

  const data = dashboardQuery.data ?? null;
  const loading = dashboardQuery.isPending;
  const upcomingEvents = upcomingEventsQuery.data ?? [];
  const currentCourses = currentCoursesQuery.data ?? [];

  // An expired token surfaces as AuthError from any of the three queries.
  const authFailed = [
    dashboardQuery.error,
    upcomingEventsQuery.error,
    currentCoursesQuery.error,
  ].some(isAuthError);

  useEffect(() => {
    if (authFailed) onAuthError();
  }, [authFailed, onAuthError]);

  const toggleScale = () => {
    const next = !use12Scale;
    setUse12Scale(next);
    toggleProgress.value = withTiming(next ? 1 : 0, {
      duration: 300,
      easing: Easing.inOut(Easing.ease),
    });
  };

  const toggleKnobStyle = useAnimatedStyle(() => ({
    transform: [{ translateX: toggleProgress.value * 28 }],
  }));

  const openTargetModal = () => {
    setTargetInput4(data?.targetGpa4 != null ? String(data.targetGpa4) : "");
    setTargetInput12(data?.targetGpa12 != null ? String(data.targetGpa12) : "");
    setShowTargetModal(true);
  };

  const saveTargetGpa = async () => {
    setSavingTarget(true);
    try {
      const body: any = {};
      if (targetInput4.trim()) body.targetGpa4 = parseFloat(targetInput4);
      if (targetInput12.trim()) body.targetGpa12 = parseFloat(targetInput12);

      await authedFetch("/api/dashboard/target-gpa", {
        method: "PUT",
        body: JSON.stringify(body),
      });
      setShowTargetModal(false);
      // The dashboard is mounted, so this refetches immediately.
      queryClient.invalidateQueries({ queryKey: qk.dashboard });
    } catch (err) {
      if (isAuthError(err)) onAuthError();
    } finally {
      setSavingTarget(false);
    }
  };

  const maxScale = use12Scale ? 12 : 4;
  const currentGpa = use12Scale
    ? (data?.gpa12 ?? 0)
    : (data?.gpa4 ?? 0);
  const targetGpa = use12Scale
    ? (data?.targetGpa12 ?? null)
    : (data?.targetGpa4 ?? null);

  const markEventComplete = useCallback(
    async (eventKey: string): Promise<boolean> => {
      try {
        const res = await authedFetch(
          `/api/dashboard/upcoming-events/${encodeURIComponent(eventKey)}`,
          { method: "PUT" }
        );
        if (!res.ok) return false;
        // Drop it from the cache after the animation, rather than refetching.
        setTimeout(() => {
          queryClient.setQueryData<UpcomingEvent[]>(qk.upcomingEvents, (prev) =>
            (prev ?? []).filter((e) => e.eventKey !== eventKey)
          );
          queryClient.invalidateQueries({ queryKey: qk.calendarEvents });
        }, 1000);
        return true;
      } catch (err) {
        if (isAuthError(err)) onAuthError();
        return false;
      }
    },
    [onAuthError, queryClient]
  );

  const formatDueDate = (dateStr: string) => {
    const [year, month, day] = dateStr.split("-").map(Number);
    const date = new Date(year, month - 1, day);
    const now = new Date();
    const tomorrow = new Date(now);
    tomorrow.setDate(tomorrow.getDate() + 1);

    if (
      date.getDate() === now.getDate() &&
      date.getMonth() === now.getMonth() &&
      date.getFullYear() === now.getFullYear()
    ) {
      return "Today";
    }
    if (
      date.getDate() === tomorrow.getDate() &&
      date.getMonth() === tomorrow.getMonth() &&
      date.getFullYear() === tomorrow.getFullYear()
    ) {
      return "Tomorrow";
    }
    return date.toLocaleDateString("en-US", {
      month: "short",
      day: "numeric",
    });
  };

  if (loading) {
    return (
      <View style={styles.loadingContainer}>
        <ActivityIndicator size="large" color={colors.primary} />
      </View>
    );
  }

  const rows = courseRows(currentCourses.length, courseGridWidth);
  const onCourseGridLayout = (e: LayoutChangeEvent) => {
    const w = Math.floor(e.nativeEvent.layout.width);
    if (w !== courseGridWidth) setCourseGridWidth(w);
  };

  const gpaCard = (
    <View style={[styles.card, !wide && styles.cardCompact]}>
      <View style={styles.gpaHeader}>
        <Text style={styles.cardTitle}>GPA Overview</Text>

        {/* Scale toggle */}
        <Pressable
          style={styles.toggleContainer}
          onPress={toggleScale}
          accessibilityRole="switch"
          accessibilityLabel="Show GPA on the 12.0 scale"
          accessibilityState={{ checked: use12Scale }}
        >
          <Text
            style={[styles.toggleLabel, !use12Scale && styles.toggleLabelActive]}
          >
            4.0
          </Text>
          <View style={styles.toggleTrack}>
            <Animated.View style={[styles.toggleKnob, toggleKnobStyle]} />
          </View>
          <Text
            style={[styles.toggleLabel, use12Scale && styles.toggleLabelActive]}
          >
            12.0
          </Text>
        </Pressable>
      </View>

      <View style={styles.arcWrap}>
        <GpaArc
          gpa={currentGpa}
          targetGpa={targetGpa}
          maxScale={maxScale}
        />
      </View>

      {data?.targetLocked ? (
        <View style={styles.targetLockedNote}>
          <Feather name="lock" size={12} color={colors.bodyText} />
          <Text style={styles.targetLockedText}>
            Set when you joined the leaderboard. Locked for the season.
          </Text>
        </View>
      ) : (
        <Pressable style={styles.setTargetButton} onPress={openTargetModal}>
          <Text style={styles.setTargetText}>
            {targetGpa != null ? "Edit Target GPA" : "Set Target GPA"}
          </Text>
        </Pressable>
      )}
    </View>
  );

  return (
    <ScrollView contentContainerStyle={[styles.container, !wide && styles.containerCompact]}>
      <View style={styles.page}>
        {/* The logo shares a line with the shell's floating hamburger instead of sitting under
            it, which is what used to leave a tall empty band above the greeting. */}
        <View style={[styles.topBar, !wide && styles.topBarCompact]}>
          <OnTrackLogo size={22} />
        </View>

        <View style={[styles.header, wide && styles.headerWide]}>
          <Text style={[styles.title, !wide && styles.titleCompact]}>
            Welcome back, {data?.username ?? "Student"}!
          </Text>
          <View style={styles.headerActions}>
            <Pressable style={styles.addAssignmentButton} onPress={onGoToMyCourses}>
              <Feather name="arrow-up-right" size={16} color={colors.onField} style={{ marginRight: 6 }} />
              <Text style={styles.addAssignmentText}>Add Assignment</Text>
            </Pressable>
            <Pressable style={styles.syncButton} onPress={onGoToCalendar}>
              <Feather name="calendar" size={16} color={colors.boldText} style={{ marginRight: 6 }} />
              <Text style={styles.syncText}>Sync</Text>
            </Pressable>
          </View>
        </View>

        <View style={[styles.overview, wide && styles.overviewWide]}>
          {/* The two things a student is ranked on, kept together. A fixed-width rail when there
              is room, the first two blocks of the stack when there is not. */}
          <View style={[styles.rail, wide && { width: desktop ? 360 : 320 }]}>
            {gpaCard}
            <LeaderboardCard
              onJoin={onJoinLeaderboard}
              onOpen={onGoToLeaderboard}
              onAuthError={onAuthError}
            />
          </View>

          {/* Upcoming Due Dates. Stretches to the rail's height on wide screens. */}
          <View style={[styles.card, styles.dueDatesCard, !wide && styles.cardCompact]}>
            <View style={styles.dueDatesHeader}>
              <Text style={styles.cardTitle}>Upcoming Due Dates</Text>
              <Pressable onPress={onGoToCalendar} style={styles.viewAllButton}>
                <Text style={styles.viewAllText}>View All</Text>
              </Pressable>
            </View>

            {upcomingEvents.length === 0 ? (
              <View style={[styles.emptyState, !wide && styles.emptyStateCompact]}>
                <View style={styles.checkCircle}>
                  <Feather name="check" size={22} color={colors.success} />
                </View>
                <Text style={styles.emptyText}>You're all caught up!</Text>
              </View>
            ) : (
              <View style={styles.eventsList}>
                {upcomingEvents.map((event) => (
                  <EventRow
                    key={event.eventKey}
                    event={event}
                    formatDueDate={formatDueDate}
                    onComplete={markEventComplete}
                  />
                ))}
              </View>
            )}
          </View>
        </View>

        {/* Current Semester Courses */}
        {currentCourses.length > 0 && (
          <View style={styles.semesterSection}>
            <View style={styles.semesterHeader}>
              <Text style={styles.cardTitle}>Current Semester Courses</Text>
              <Text style={styles.semesterCount}>
                {currentCourses.length} {currentCourses.length === 1 ? "course" : "courses"}
              </Text>
            </View>
            {/* Rows are sized from the measured width, so the grid always reaches both edges.
                The first frame has no width yet and renders nothing rather than guessing. */}
            <View style={styles.semesterGrid} onLayout={onCourseGridLayout}>
              {rows.map((count, r) => {
                const start = rows.slice(0, r).reduce((a, b) => a + b, 0);
                return (
                  <View key={r} style={styles.semesterRow}>
                    {currentCourses.slice(start, start + count).map((course, i) => {
                      const index = start + i;
                      const accent = ACCENT_COLORS[index % ACCENT_COLORS.length];
                      const gradeDisplay = course.grade != null
                        ? `${course.grade.toFixed(2)}%`
                        : "No grade";
                      return (
                        <View
                          key={`${course.courseCode}-${course.term}-${index}`}
                          style={styles.semesterCard}
                        >
                          <View style={[styles.semesterAccent, { backgroundColor: accent }]} />
                          <View style={styles.semesterCardContent}>
                            <View style={styles.semesterCardHeader}>
                              <Text style={styles.semesterCourseCode} numberOfLines={1}>
                                {course.courseCode}
                              </Text>
                              <Text style={[styles.semesterGrade, { color: accent }]}>
                                {gradeDisplay}
                              </Text>
                            </View>
                            <Text style={styles.semesterCourseName} numberOfLines={1}>
                              {course.courseName}
                            </Text>
                            <Text style={styles.semesterMeta}>
                              {course.credits} Credits
                            </Text>
                          </View>
                        </View>
                      );
                    })}
                  </View>
                );
              })}
            </View>
          </View>
        )}
      </View>

      {/* Target GPA Modal */}
      <Modal
        visible={showTargetModal}
        transparent
        animationType="fade"
        onRequestClose={() => setShowTargetModal(false)}
      >
        <Pressable
          {...noHover}
          style={styles.modalBackdrop}
          onPress={() => setShowTargetModal(false)}
        >
          <Pressable {...noHover} style={styles.modalContent} onPress={() => {}}>
            <Text style={styles.modalTitle}>Set Target GPA</Text>

            <Text style={styles.inputLabel}>Target GPA (4.0 Scale)</Text>
            <TextInput
              style={styles.input}
              value={targetInput4}
              onChangeText={setTargetInput4}
              keyboardType="decimal-pad"
              placeholder="e.g. 3.8"
              placeholderTextColor={colors.placeholder}
            />

            <Text style={styles.inputLabel}>Target GPA (12.0 Scale)</Text>
            <TextInput
              style={styles.input}
              value={targetInput12}
              onChangeText={setTargetInput12}
              keyboardType="decimal-pad"
              placeholder="e.g. 11.0"
              placeholderTextColor={colors.placeholder}
            />

            <View style={styles.modalButtons}>
              <Pressable
                style={styles.cancelButton}
                onPress={() => setShowTargetModal(false)}
              >
                <Text style={styles.cancelText}>Cancel</Text>
              </Pressable>
              <Pressable
                style={styles.saveButton}
                onPress={saveTargetGpa}
                disabled={savingTarget}
              >
                <Text style={styles.saveText}>
                  {savingTarget ? "Saving..." : "Save"}
                </Text>
              </Pressable>
            </View>
          </Pressable>
        </Pressable>
      </Modal>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  loadingContainer: {
    flex: 1,
    justifyContent: "center",
    alignItems: "center",
    backgroundColor: colors.background,
  },
  container: {
    // Top padding lines the logo up with the shell's floating hamburger (10pt down, 32pt tall).
    paddingTop: 10,
    paddingHorizontal: spacing.lg,
    paddingBottom: spacing.lg * 2,
    backgroundColor: colors.background,
    minHeight: "100%",
  },
  containerCompact: {
    paddingHorizontal: spacing.md,
    paddingBottom: spacing.lg,
  },
  // Caps line length on very wide monitors so the rail and grid don't drift apart.
  page: {
    width: "100%",
    maxWidth: 1360,
    alignSelf: "center",
  },
  topBar: {
    height: 32,
    justifyContent: "center",
    // The hamburger's right edge sits at x=52; this starts the logo just past it.
    marginLeft: 52 + 12 - spacing.lg,
    marginBottom: spacing.lg,
  },
  topBarCompact: {
    marginLeft: 52 + 12 - spacing.md,
    marginBottom: spacing.md,
  },
  header: {
    gap: 12,
    marginBottom: spacing.lg,
  },
  headerWide: {
    flexDirection: "row",
    flexWrap: "wrap",
    alignItems: "center",
    justifyContent: "space-between",
  },
  title: {
    ...typo.h1,
    color: colors.boldText,
    flexShrink: 1,
  },
  titleCompact: {
    fontSize: 24,
    letterSpacing: -0.3,
  },
  headerActions: {
    flexDirection: "row",
    flexWrap: "wrap",
    alignItems: "center",
    gap: 10,
  },
  addAssignmentButton: {
    flexDirection: "row",
    alignItems: "center",
    backgroundColor: colors.primary,
    paddingHorizontal: 16,
    paddingVertical: 10,
    borderRadius: radius.md,
  },
  addAssignmentText: {
    ...typo.label,
    color: colors.onField,
  },
  syncButton: {
    flexDirection: "row",
    alignItems: "center",
    backgroundColor: colors.surface,
    paddingHorizontal: 14,
    paddingVertical: 10,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.border,
  },
  syncText: {
    ...typo.label,
    color: colors.boldText,
  },
  overview: {
    gap: spacing.md,
  },
  // Row children stretch by default, so the due dates card grows to the rail's height.
  overviewWide: {
    flexDirection: "row",
  },
  rail: {
    gap: spacing.md,
  },
  card: {
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    padding: spacing.lg,
    borderWidth: 1,
    borderColor: colors.borderSubtle,
  },
  cardCompact: {
    padding: spacing.md + 4,
  },
  dueDatesCard: {
    flex: 1,
  },
  arcWrap: {
    alignItems: "center",
  },
  gpaHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    marginBottom: 16,
  },
  cardTitle: {
    ...typo.h2,
    color: colors.boldText,
  },
  toggleContainer: {
    flexDirection: "row",
    alignItems: "center",
    gap: 6,
  },
  toggleLabel: {
    ...typo.caption,
    fontFamily: fonts.bodyMedium,
    fontVariant: ["tabular-nums"],
    color: colors.faintText,
  },
  toggleLabelActive: {
    color: colors.primary,
  },
  toggleTrack: {
    width: 44,
    height: 24,
    borderRadius: radius.md,
    backgroundColor: colors.surfaceSunken,
    justifyContent: "center",
    paddingHorizontal: 3,
  },
  toggleKnob: {
    width: 18,
    height: 18,
    borderRadius: 9,
    backgroundColor: colors.primary,
  },
  setTargetButton: {
    alignSelf: "center",
    marginTop: 12,
    paddingVertical: 8,
    paddingHorizontal: 16,
    borderRadius: radius.sm,
    backgroundColor: colors.primarySoft,
  },
  setTargetText: {
    ...typo.label,
    fontSize: 13,
    color: colors.primary,
  },
  targetLockedNote: {
    alignSelf: "center",
    flexDirection: "row",
    alignItems: "center",
    gap: 6,
    marginTop: 12,
    paddingVertical: 6,
    paddingHorizontal: 12,
  },
  targetLockedText: {
    ...typo.caption,
    color: colors.bodyText,
    textAlign: "center",
  },
  dueDatesHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    marginBottom: spacing.md,
  },
  // Pads the link out to a comfortable touch target without moving the text.
  viewAllButton: {
    paddingVertical: 8,
    paddingHorizontal: 8,
    marginVertical: -8,
    marginRight: -8,
    borderRadius: radius.sm,
  },
  viewAllText: {
    ...typo.label,
    color: colors.primary,
    textDecorationLine: "underline",
  },
  emptyState: {
    flex: 1,
    justifyContent: "center",
    alignItems: "center",
    paddingVertical: 40,
  },
  emptyStateCompact: {
    paddingVertical: spacing.lg,
  },
  checkCircle: {
    width: 48,
    height: 48,
    borderRadius: 24,
    backgroundColor: colors.successSoft,
    alignItems: "center",
    justifyContent: "center",
    marginBottom: 12,
  },
  emptyText: {
    ...typo.bodyLarge,
    color: colors.bodyText,
  },
  eventsList: {
    gap: 4,
  },
  eventRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: 12,
    paddingVertical: 12,
    borderBottomWidth: 1,
    borderBottomColor: colors.borderSubtle,
    overflow: "hidden",
  },
  completeCircle: {
    width: 28,
    height: 28,
    borderRadius: 14,
    borderWidth: 2,
    alignItems: "center",
    justifyContent: "center",
  },
  // Animated.Text skips the app Text wrapper, so these name their face outright.
  completeCheck: {
    fontFamily: fonts.display,
    fontSize: 14,
    color: colors.onField,
  },
  eventDateBadge: {
    backgroundColor: colors.primarySoft,
    borderRadius: radius.sm,
    paddingHorizontal: 10,
    paddingVertical: 6,
    minWidth: 72,
    alignItems: "center",
  },
  eventDateText: {
    ...typo.caption,
    fontFamily: fonts.bodyMedium,
    fontVariant: ["tabular-nums"],
    color: colors.primary,
  },
  eventDetails: {
    flex: 1,
  },
  eventNameContainer: {
    position: "relative",
    justifyContent: "center",
  },
  eventName: {
    fontFamily: fonts.bodyMedium,
    fontSize: 16,
    letterSpacing: 0.32,
    color: colors.boldText,
  },
  strikethroughLine: {
    position: "absolute",
    left: 0,
    top: "50%",
    height: 1.5,
    backgroundColor: colors.primary,
    borderRadius: 1,
  },
  eventCourse: {
    ...typo.caption,
    color: colors.bodyText,
    marginTop: 2,
  },
  // Semester Courses
  semesterSection: {
    marginTop: spacing.lg * 2,
  },
  semesterHeader: {
    flexDirection: "row",
    alignItems: "baseline",
    justifyContent: "space-between",
    marginBottom: spacing.md,
  },
  semesterCount: {
    ...typo.caption,
    color: colors.faintText,
  },
  semesterGrid: {
    gap: COURSE_GAP,
  },
  semesterRow: {
    flexDirection: "row",
    gap: COURSE_GAP,
  },
  semesterCard: {
    flex: 1,
    minWidth: 0,
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    overflow: "hidden",
    borderWidth: 1,
    borderColor: colors.borderSubtle,
  },
  semesterAccent: {
    height: 4,
    width: "100%",
  },
  semesterCardContent: {
    padding: spacing.md,
  },
  semesterCardHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    gap: spacing.sm,
    marginBottom: 4,
  },
  semesterCourseCode: {
    ...typo.h3,
    color: colors.boldText,
    flexShrink: 1,
  },
  semesterGrade: {
    ...typo.data,
    fontFamily: fonts.bodyMedium,
    fontSize: 16,
    letterSpacing: 0.32,
  },
  semesterCourseName: {
    ...typo.body,
    color: colors.bodyText,
    marginBottom: spacing.sm,
  },
  semesterMeta: {
    ...typo.caption,
    color: colors.faintText,
  },
  // Modal
  modalBackdrop: {
    flex: 1,
    backgroundColor: colors.scrim,
    justifyContent: "center",
    alignItems: "center",
  },
  modalContent: {
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    padding: 24,
    width: 340,
    maxWidth: "90%",
  },
  modalTitle: {
    ...typo.h2,
    color: colors.boldText,
    marginBottom: 20,
  },
  inputLabel: {
    ...typo.body,
    fontFamily: fonts.bodyMedium,
    color: colors.bodyText,
    marginBottom: 6,
  },
  input: {
    ...typo.data,
    fontSize: 16,
    letterSpacing: 0.32,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.md,
    paddingHorizontal: 14,
    paddingVertical: 10,
    marginBottom: 16,
    color: colors.boldText,
  },
  modalButtons: {
    flexDirection: "row",
    justifyContent: "flex-end",
    gap: 12,
    marginTop: 8,
  },
  cancelButton: {
    paddingVertical: 10,
    paddingHorizontal: 20,
    borderRadius: radius.md,
  },
  cancelText: {
    ...typo.label,
    color: colors.bodyText,
  },
  saveButton: {
    paddingVertical: 10,
    paddingHorizontal: 20,
    borderRadius: radius.md,
    backgroundColor: colors.primary,
  },
  saveText: {
    ...typo.label,
    color: colors.onField,
  },
});
