import React from "react";
import { Modal, Pressable, StyleSheet, View, useWindowDimensions } from "react-native";
import { Feather } from "@expo/vector-icons";
import { useQuery } from "@tanstack/react-query";
import { Text } from "../../src/ui/Text";
import { noHover } from "../../src/ui/hover";
import { colors } from "../../src/theme/colors";
import { radius, spacing } from "../../src/theme/spacing";
import { fonts, typo } from "../../src/theme/typography";
import { qk } from "../../src/cache/keys";
import { queryRetry } from "../../src/cache/authedGet";
import {
  AssessmentRow,
  AssessmentTable,
  fetchAssessmentTable,
} from "../../src/cache/assessmentTable";

export type CalendarEventDetail = {
  courseCode: string;
  assessmentName: string;
  dueDate: string;
  startTime: string | null;
  endTime: string | null;
  allDay: boolean;
  completed?: boolean;
  description: string | null;
  location: string | null;
};

type Props = {
  event: CalendarEventDetail | null;
  /** The course's colour in the grid, so the sheet reads as the chip that opened it. */
  accent: string;
  /** The term the event falls in, when it is one the student has. Null skips the weight lookup. */
  term: string | null;
  onClose: () => void;
};

const SHEET_BREAKPOINT = 600;

/** Parses a YYYY-MM-DD string as a local date. `new Date(str)` would read it as UTC midnight. */
function localDate(dateStr: string): Date {
  const [y, m, d] = dateStr.split("-").map(Number);
  return new Date(y, m - 1, d);
}

export function formatTime(t: string): string {
  const match = /^(\d{1,2}):(\d{2})/.exec(t);
  if (!match) return t;
  const h = Number(match[1]);
  const suffix = h >= 12 ? "PM" : "AM";
  return `${h % 12 === 0 ? 12 : h % 12}:${match[2]} ${suffix}`;
}

function whenLabel(e: CalendarEventDetail): string {
  const date = localDate(e.dueDate).toLocaleDateString("en-US", {
    weekday: "long",
    month: "long",
    day: "numeric",
    year: "numeric",
  });
  if (e.allDay || !e.startTime) return `${date}, all day`;
  const start = formatTime(e.startTime);
  return e.endTime ? `${date}, ${start} to ${formatTime(e.endTime)}` : `${date}, ${start}`;
}

/** "Due in 3 days", "Due today", "Was due 2 days ago". */
function countdown(e: CalendarEventDetail): { text: string; tone: "soon" | "later" | "past" | "done" } {
  if (e.completed) return { text: "Completed", tone: "done" };
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const days = Math.round((localDate(e.dueDate).getTime() - today.getTime()) / 86_400_000);
  if (days === 0) return { text: "Due today", tone: "soon" };
  if (days === 1) return { text: "Due tomorrow", tone: "soon" };
  if (days > 1) return { text: `Due in ${days} days`, tone: days <= 7 ? "soon" : "later" };
  if (days === -1) return { text: "Was due yesterday", tone: "past" };
  // Past a month, a day count stops meaning anything; the date above already says when.
  if (days < -30) return { text: "Past due", tone: "past" };
  return { text: `Was due ${-days} days ago`, tone: "past" };
}

const fmtPct = (n: number) => `${Number.isInteger(n) ? n : n.toFixed(1)}%`;

/**
 * The row in the course's assessment table this event came from, across every marking scheme.
 * Weight can differ between schemes, so it comes back as a range when it does.
 */
function lookup(table: AssessmentTable | null | undefined, name: string) {
  if (!table) return null;
  const rows: AssessmentRow[] = Object.values(table)
    .flat()
    .filter((r) => r.assessmentName.trim().toLowerCase() === name.trim().toLowerCase());
  if (rows.length === 0) return null;

  const weights = rows
    .map((r) => r.weights.find((w) => w !== 0) ?? r.weights[0])
    .filter((w): w is number => typeof w === "number");
  const min = Math.min(...weights);
  const max = Math.max(...weights);
  const weight =
    weights.length === 0 ? null : min === max ? fmtPct(min) : `${fmtPct(min)} to ${fmtPct(max)}`;
  const grade = rows.find((r) => r.grade != null)?.grade ?? null;
  return { weight, grade: grade != null ? fmtPct(grade) : null };
}

export default function EventDetailSheet({ event, accent, term, onClose }: Props) {
  const { width } = useWindowDimensions();
  const asSheet = width < SHEET_BREAKPOINT;

  const tableQuery = useQuery({
    queryKey: qk.assessmentTable(event?.courseCode ?? "", term ?? ""),
    queryFn: () => fetchAssessmentTable(event!.courseCode, term!),
    enabled: !!event && !!term,
    staleTime: 5 * 60_000,
    retry: queryRetry,
  });

  if (!event) return null;

  const status = countdown(event);
  const found = lookup(tableQuery.data, event.assessmentName);
  // Exports write "COURSE - Assessment" as the description; repeating it here says nothing new.
  const notes =
    event.description &&
    event.description.trim() !== `${event.courseCode} - ${event.assessmentName}`
      ? event.description.trim()
      : null;
  const location = event.location?.trim() || null;

  return (
    <Modal visible transparent animationType="fade" onRequestClose={onClose}>
      <Pressable
        {...noHover}
        style={[styles.overlay, asSheet && styles.overlaySheet]}
        onPress={onClose}
        accessibilityLabel="Close event details"
      >
        <Pressable
          {...noHover}
          style={[styles.panel, asSheet && styles.panelSheet]}
          onPress={() => {}}
          accessibilityViewIsModal
        >
          <View style={styles.topRow}>
            <View style={styles.coursePill}>
              <View style={[styles.dot, { backgroundColor: accent }]} />
              <Text style={styles.courseText}>{event.courseCode}</Text>
            </View>
            <Pressable
              onPress={onClose}
              style={styles.closeButton}
              accessibilityRole="button"
              accessibilityLabel="Close"
              hitSlop={6}
            >
              <Feather name="x" size={20} color={colors.bodyText} />
            </Pressable>
          </View>

          <Text style={styles.title}>{event.assessmentName}</Text>
          <Text style={styles.when}>{whenLabel(event)}</Text>

          <View style={[styles.status, statusStyles[status.tone]]}>
            <Text style={[styles.statusText, statusTextStyles[status.tone]]}>{status.text}</Text>
          </View>

          {/* Weight and grade come from the course's assessment table. When the event has no
              matching row there is nothing true to show, so the section is left out. */}
          {term && tableQuery.isPending ? (
            <Text style={styles.loading}>Loading weight and grade...</Text>
          ) : (
            found && (found.weight || found.grade) && (
              <View style={styles.stats}>
                {found.weight && (
                  <View style={styles.stat}>
                    <Text style={styles.statLabel}>Weight</Text>
                    <Text style={styles.statValue}>{found.weight}</Text>
                  </View>
                )}
                {found.weight && <View style={styles.statDivider} />}
                <View style={styles.stat}>
                  <Text style={styles.statLabel}>Your grade</Text>
                  <Text style={[styles.statValue, !found.grade && styles.statEmpty]}>
                    {found.grade ?? "Not graded"}
                  </Text>
                </View>
              </View>
            )
          )}

          {(location || notes) && (
            <View style={styles.details}>
              {location && (
                <View style={styles.detailRow}>
                  <Feather name="map-pin" size={15} color={colors.bodyText} />
                  <Text style={styles.detailText}>{location}</Text>
                </View>
              )}
              {notes && (
                <View style={styles.detailRow}>
                  <Feather name="align-left" size={15} color={colors.bodyText} />
                  <Text style={styles.detailText}>{notes}</Text>
                </View>
              )}
            </View>
          )}
        </Pressable>
      </Pressable>
    </Modal>
  );
}

const styles = StyleSheet.create({
  overlay: {
    flex: 1,
    backgroundColor: colors.scrim,
    justifyContent: "center",
    alignItems: "center",
    padding: spacing.md,
  },
  overlaySheet: {
    justifyContent: "flex-end",
    padding: 0,
  },
  panel: {
    width: "100%",
    maxWidth: 420,
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    padding: spacing.lg,
  },
  panelSheet: {
    maxWidth: "100%",
    borderBottomLeftRadius: 0,
    borderBottomRightRadius: 0,
    paddingBottom: spacing.lg * 1.5,
  },
  topRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    marginBottom: spacing.md,
  },
  coursePill: {
    flexDirection: "row",
    alignItems: "center",
    gap: 6,
  },
  dot: {
    width: 8,
    height: 8,
    borderRadius: 4,
  },
  courseText: {
    ...typo.label,
    color: colors.bodyText,
  },
  closeButton: {
    width: 32,
    height: 32,
    borderRadius: radius.md,
    alignItems: "center",
    justifyContent: "center",
    marginRight: -6,
  },
  title: {
    ...typo.h2,
    fontSize: 22,
    color: colors.boldText,
  },
  when: {
    ...typo.body,
    color: colors.bodyText,
    marginTop: 4,
  },
  status: {
    alignSelf: "flex-start",
    borderRadius: radius.sm,
    paddingHorizontal: 8,
    paddingVertical: 4,
    marginTop: spacing.md,
  },
  statusText: {
    ...typo.caption,
    fontFamily: fonts.bodyMedium,
  },
  loading: {
    ...typo.caption,
    color: colors.faintText,
    marginTop: spacing.lg,
  },
  stats: {
    flexDirection: "row",
    alignItems: "center",
    marginTop: spacing.lg,
    paddingTop: spacing.md,
    borderTopWidth: 1,
    borderTopColor: colors.borderSubtle,
  },
  stat: {
    flex: 1,
  },
  statDivider: {
    width: 1,
    alignSelf: "stretch",
    backgroundColor: colors.borderSubtle,
    marginHorizontal: spacing.md,
  },
  statLabel: {
    ...typo.caption,
    color: colors.bodyText,
  },
  statValue: {
    fontFamily: fonts.display,
    fontSize: 22,
    letterSpacing: -0.2,
    fontVariant: ["tabular-nums"],
    color: colors.boldText,
    marginTop: 2,
  },
  statEmpty: {
    ...typo.body,
    color: colors.faintText,
    marginTop: 6,
  },
  details: {
    gap: spacing.sm,
    marginTop: spacing.md,
    paddingTop: spacing.md,
    borderTopWidth: 1,
    borderTopColor: colors.borderSubtle,
  },
  detailRow: {
    flexDirection: "row",
    alignItems: "flex-start",
    gap: spacing.sm,
  },
  detailText: {
    ...typo.body,
    flex: 1,
    color: colors.boldText,
  },
});

const statusStyles = StyleSheet.create({
  soon: { backgroundColor: colors.primarySoft },
  later: { backgroundColor: colors.surfaceSunken },
  past: { backgroundColor: colors.dangerSoft },
  done: { backgroundColor: colors.successSoft },
});

const statusTextStyles = StyleSheet.create({
  soon: { color: colors.primary },
  later: { color: colors.bodyText },
  past: { color: colors.dangerDeep },
  done: { color: colors.success },
});
