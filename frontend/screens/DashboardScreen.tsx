import React, { useEffect, useState, useCallback } from "react";
import {
  View,
  Text,
  StyleSheet,
  Pressable,
  ScrollView,
  TextInput,
  Modal,
  ActivityIndicator,
  Linking,
} from "react-native";
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
import { spacing } from "../src/theme/spacing";
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

const ACCENT_COLORS = [
  colors.primary,    // maroon
  colors.accent,     // gold
  "#2C3E50",         // dark navy
  "#1A7A5A",         // deep teal
  "#8B4513",         // saddle brown
  "#4A148C",         // deep purple
];

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
      checkFill.value > 0.5 ? "#7A003C" : "transparent",
    borderColor: checkFill.value > 0.5 ? "#7A003C" : "#E0D7DE",
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

  return (
    <ScrollView contentContainerStyle={styles.container}>
      {/* Header */}
      <View style={styles.header}>
        <View style={{ marginBottom: 12 }}>
          <OnTrackLogo size={28} />
        </View>
        <Text style={styles.title}>
          Welcome back, {data?.username ?? "Student"}!
        </Text>
        <Text style={styles.subtitle}>
          Here's what's happening with your studies today.
        </Text>
        <View style={styles.headerActions}>
          <Pressable style={styles.addAssignmentButton} onPress={onGoToMyCourses}>
            <Feather name="arrow-up-right" size={16} color="white" style={{ marginRight: 6 }} />
            <Text style={styles.addAssignmentText}>Add Assignment</Text>
          </Pressable>
          <Pressable style={styles.syncButton} onPress={onGoToCalendar}>
            <Feather name="calendar" size={16} color={colors.boldText} style={{ marginRight: 6 }} />
            <Text style={styles.syncText}>Sync</Text>
          </Pressable>
          <Pressable
            style={styles.syncButton}
            onPress={() => Linking.openURL("https://forms.gle/ZsMy6unVXjBSR5sX7")}
          >
            <Feather name="alert-circle" size={16} color={colors.boldText} style={{ marginRight: 6 }} />
            <Text style={styles.syncText}>Report a Bug</Text>
          </Pressable>
        </View>
      </View>

      {/* Main content row */}
      <View style={styles.cardsRow}>
        {/* GPA Overview Card */}
        <View style={[styles.card, styles.gpaCard]}>
          <View style={styles.gpaHeader}>
            <View>
              <Text style={styles.cardTitle}>GPA Overview</Text>
              <Text style={styles.cardSubtitle}>
                Current vs Target ({maxScale}.0 Scale)
              </Text>
            </View>

            {/* Scale toggle */}
            <Pressable style={styles.toggleContainer} onPress={toggleScale}>
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

          <GpaArc
            gpa={currentGpa}
            targetGpa={targetGpa}
            maxScale={maxScale}
          />

          {data?.targetLocked ? (
            <View style={styles.targetLockedNote}>
              <Feather name="lock" size={12} color="#6B5B63" />
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

        {/* Leaderboard Card. Beside the GPA card on purpose: the two things a student is ranked
            on belong next to each other. cardsRow already wraps, so on a narrow phone this drops
            under the GPA card at full width rather than squeezing three cards onto one line. */}
        <View style={styles.leaderboardCard}>
          <LeaderboardCard
            onJoin={onJoinLeaderboard}
            onOpen={onGoToLeaderboard}
            onAuthError={onAuthError}
          />
        </View>

        {/* Upcoming Due Dates Card */}
        <View style={[styles.card, styles.dueDatesCard]}>
          <View style={styles.dueDatesHeader}>
            <Text style={styles.cardTitle}>Upcoming Due Dates</Text>
            <Pressable onPress={onGoToCalendar}>
              <Text style={styles.viewAllText}>View All</Text>
            </Pressable>
          </View>

          {upcomingEvents.length === 0 ? (
            <View style={styles.emptyState}>
              <View style={styles.checkCircle}>
                <Text style={styles.checkMark}>&#10003;</Text>
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
          <Text style={styles.semesterTitle}>Current Semester Courses</Text>
          <ScrollView
            horizontal
            showsHorizontalScrollIndicator={false}
            contentContainerStyle={styles.semesterScroll}
          >
            {currentCourses.map((course, index) => {
              const accent = ACCENT_COLORS[index % ACCENT_COLORS.length];
              const gradeDisplay = course.grade != null
                ? `${course.grade.toFixed(2)}%`
                : "--";
              return (
                <View
                  key={`${course.courseCode}-${course.term}-${index}`}
                  style={styles.semesterCard}
                >
                  <View style={[styles.semesterAccent, { backgroundColor: accent }]} />
                  <View style={styles.semesterCardContent}>
                    <View style={styles.semesterCardHeader}>
                      <Text style={styles.semesterCourseCode}>{course.courseCode}</Text>
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
          </ScrollView>
        </View>
      )}

      {/* Target GPA Modal */}
      <Modal
        visible={showTargetModal}
        transparent
        animationType="fade"
        onRequestClose={() => setShowTargetModal(false)}
      >
        <Pressable
          style={styles.modalBackdrop}
          onPress={() => setShowTargetModal(false)}
        >
          <Pressable style={styles.modalContent} onPress={() => {}}>
            <Text style={styles.modalTitle}>Set Target GPA</Text>

            <Text style={styles.inputLabel}>Target GPA (4.0 Scale)</Text>
            <TextInput
              style={styles.input}
              value={targetInput4}
              onChangeText={setTargetInput4}
              keyboardType="decimal-pad"
              placeholder="e.g. 3.8"
              placeholderTextColor="#AAA"
            />

            <Text style={styles.inputLabel}>Target GPA (12.0 Scale)</Text>
            <TextInput
              style={styles.input}
              value={targetInput12}
              onChangeText={setTargetInput12}
              keyboardType="decimal-pad"
              placeholder="e.g. 11.0"
              placeholderTextColor="#AAA"
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
    padding: spacing.lg,
    backgroundColor: "#F5F1F3",
    minHeight: "100%",
  },
  header: {
    marginBottom: spacing.lg,
  },
  title: {
    fontSize: 28,
    fontWeight: "700",
    color: "#3B0A1D",
  },
  subtitle: {
    marginTop: 4,
    color: "#888",
    fontSize: 14,
  },
  headerActions: {
    flexDirection: "row",
    flexWrap: "wrap",
    alignItems: "center",
    gap: 10,
    marginTop: spacing.md,
  },
  addAssignmentButton: {
    flexDirection: "row",
    alignItems: "center",
    backgroundColor: colors.primary,
    paddingHorizontal: 16,
    paddingVertical: 10,
    borderRadius: 10,
    boxShadow: "0px 6px 12px rgba(122, 0, 60, 0.35)",
    elevation: 6,
  },
  addAssignmentText: {
    color: "white",
    fontSize: 14,
    fontWeight: "600",
  },
  syncButton: {
    flexDirection: "row",
    alignItems: "center",
    backgroundColor: "#FFF",
    paddingHorizontal: 14,
    paddingVertical: 10,
    borderRadius: 10,
    borderWidth: 1,
    borderColor: "#E0D7DE",
  },
  syncText: {
    fontSize: 14,
    fontWeight: "600",
    color: "#3B0A1D",
  },
  cardsRow: {
    flexDirection: "row",
    gap: 16,
    flexWrap: "wrap",
  },
  card: {
    backgroundColor: "#FFF",
    borderRadius: 16,
    padding: spacing.lg,
    minWidth: 280,
    boxShadow: "0px 2px 6px rgba(0, 0, 0, 0.06)",
    elevation: 2,
  },
  gpaCard: {
    flex: 1,
    minWidth: 300,
    maxWidth: 380,
  },
  leaderboardCard: {
    flex: 1,
    minWidth: 300,
    maxWidth: 380,
  },
  dueDatesCard: {
    flex: 1.5,
    minWidth: 300,
    minHeight: 300,
  },
  gpaHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "flex-start",
    marginBottom: 16,
  },
  cardTitle: {
    fontSize: 18,
    fontWeight: "700",
    color: "#3B0A1D",
  },
  cardSubtitle: {
    fontSize: 13,
    color: "#888",
    marginTop: 2,
  },
  toggleContainer: {
    flexDirection: "row",
    alignItems: "center",
    gap: 6,
  },
  toggleLabel: {
    fontSize: 12,
    fontWeight: "600",
    color: "#BBB",
  },
  toggleLabelActive: {
    color: "#7A003C",
  },
  toggleTrack: {
    width: 44,
    height: 24,
    borderRadius: 12,
    backgroundColor: "#E8E0E5",
    justifyContent: "center",
    paddingHorizontal: 3,
  },
  toggleKnob: {
    width: 18,
    height: 18,
    borderRadius: 9,
    backgroundColor: "#7A003C",
  },
  setTargetButton: {
    alignSelf: "center",
    marginTop: 12,
    paddingVertical: 6,
    paddingHorizontal: 16,
    borderRadius: 8,
    backgroundColor: "#F5F0F3",
  },
  setTargetText: {
    fontSize: 12,
    fontWeight: "600",
    color: "#7A003C",
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
    fontSize: 12,
    color: "#6B5B63",
    textAlign: "center",
  },
  dueDatesHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    marginBottom: spacing.lg,
  },
  viewAllText: {
    fontSize: 14,
    fontWeight: "600",
    color: "#7A003C",
    textDecorationLine: "underline",
  },
  emptyState: {
    flex: 1,
    justifyContent: "center",
    alignItems: "center",
    paddingVertical: 40,
  },
  checkCircle: {
    width: 48,
    height: 48,
    borderRadius: 24,
    backgroundColor: "#F0ECF0",
    alignItems: "center",
    justifyContent: "center",
    marginBottom: 12,
  },
  checkMark: {
    fontSize: 20,
    color: "#BBB",
  },
  emptyText: {
    fontSize: 15,
    color: "#888",
  },
  eventsList: {
    gap: 12,
  },
  eventRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: 12,
    paddingVertical: 8,
    borderBottomWidth: 1,
    borderBottomColor: "#F0ECF0",
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
  completeCheck: {
    fontSize: 14,
    fontWeight: "700",
    color: "#FFFFFF",
  },
  eventDateBadge: {
    backgroundColor: "#F5F0F3",
    borderRadius: 8,
    paddingHorizontal: 10,
    paddingVertical: 6,
    minWidth: 72,
    alignItems: "center",
  },
  eventDateText: {
    fontSize: 13,
    fontWeight: "600",
    color: "#7A003C",
  },
  eventDetails: {
    flex: 1,
  },
  eventNameContainer: {
    position: "relative",
    justifyContent: "center",
  },
  eventName: {
    fontSize: 15,
    fontWeight: "600",
    color: "#3B0A1D",
  },
  strikethroughLine: {
    position: "absolute",
    left: 0,
    top: "50%",
    height: 1.5,
    backgroundColor: "#7A003C",
    borderRadius: 1,
  },
  eventCourse: {
    fontSize: 13,
    color: "#888",
    marginTop: 2,
  },
  // Semester Courses
  semesterSection: {
    marginTop: spacing.lg,
  },
  semesterTitle: {
    fontSize: 18,
    fontWeight: "700",
    color: colors.boldText,
    marginBottom: spacing.md,
  },
  semesterScroll: {
    gap: spacing.md,
    paddingBottom: spacing.sm,
  },
  semesterCard: {
    backgroundColor: "white",
    borderRadius: 14,
    overflow: "hidden",
    width: 240,
    boxShadow: "0px 2px 6px rgba(0, 0, 0, 0.06)",
    elevation: 2,
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
    marginBottom: 4,
  },
  semesterCourseCode: {
    fontSize: 16,
    fontWeight: "700",
    color: colors.boldText,
  },
  semesterGrade: {
    fontSize: 16,
    fontWeight: "700",
  },
  semesterCourseName: {
    fontSize: 13,
    color: colors.bodyText,
    marginBottom: spacing.sm,
  },
  semesterMeta: {
    fontSize: 12,
    color: colors.bodyText,
  },
  // Modal
  modalBackdrop: {
    flex: 1,
    backgroundColor: "rgba(0,0,0,0.4)",
    justifyContent: "center",
    alignItems: "center",
  },
  modalContent: {
    backgroundColor: "#FFF",
    borderRadius: 16,
    padding: 24,
    width: 340,
    maxWidth: "90%",
  },
  modalTitle: {
    fontSize: 20,
    fontWeight: "700",
    color: "#3B0A1D",
    marginBottom: 20,
  },
  inputLabel: {
    fontSize: 13,
    fontWeight: "600",
    color: "#666",
    marginBottom: 6,
  },
  input: {
    borderWidth: 1,
    borderColor: "#E0D7DE",
    borderRadius: 10,
    paddingHorizontal: 14,
    paddingVertical: 10,
    fontSize: 16,
    marginBottom: 16,
    color: "#3B0A1D",
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
    borderRadius: 10,
  },
  cancelText: {
    fontSize: 14,
    color: "#888",
    fontWeight: "600",
  },
  saveButton: {
    paddingVertical: 10,
    paddingHorizontal: 20,
    borderRadius: 10,
    backgroundColor: "#7A003C",
  },
  saveText: {
    fontSize: 14,
    color: "#FFF",
    fontWeight: "600",
  },
});
