//imports
import { useEffect, useState } from "react";
import {
  View,
  StyleSheet,
  Pressable,
  ScrollView,
  Modal,
  ActivityIndicator,
  useWindowDimensions,
} from "react-native";
import { Text, TextInput } from "../src/ui/Text";
import { noHover } from "../src/ui/hover";
import { Calendar, DateData } from "react-native-calendars";
import * as WebBrowser from "expo-web-browser";
import { Feather } from "@expo/vector-icons";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { colors } from "../src/theme/colors";
import { spacing, radius } from "../src/theme/spacing";
import { API_BASE_URL } from "../src/config/api";
import { getToken } from "../src/utils/tokenStorage";
import { qk } from "../src/cache/keys";
import {
  authedFetch,
  authedGet,
  authedMutate,
  HttpError,
  isAuthError,
  queryRetry,
} from "../src/cache/authedGet";
import { fonts, typo } from "../src/theme/typography";
import { useTerm } from "../src/terms/TermContext";
import EventDetailSheet, { formatTime } from "./components/EventDetailSheet";

// Below this the grid's columns are too narrow to hold a readable label, so cells show coloured
// marks and the month's events are listed under the grid instead.
const LIST_BREAKPOINT = 768;
// Chips per grid cell before the rest fold into "+N more", so one busy day can't stretch its week.
const MAX_CHIPS = 2;

const pad2 = (n: number) => String(n).padStart(2, "0");
/** Today as YYYY-MM-DD in local time. toISOString() is UTC, which marks tomorrow late at night. */
const localToday = () => {
  const d = new Date();
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
};

/** McMaster's term for a due date: Jan to Apr is Winter, May to Aug Spring/Summer, else Fall. */
const termFor = (dueDate: string) => {
  const [y, m] = dueDate.split("-").map(Number);
  const season = m <= 4 ? "Winter" : m <= 8 ? "Spring/Summer" : "Fall";
  return `${season} ${y}`;
};

type InternalCalendarEvent = {
  courseCode: string;
  assessmentName: string;
  dueDate: string;
  startTime: string | null;
  endTime: string | null;
  allDay: boolean;
  /** Sent by the server; set once the student ticks the event off on the dashboard. */
  completed?: boolean;
  eventKey: string;
  description: string | null;
  location: string | null;
};

type CalendarProvider = "GOOGLE" | "MICROSOFT";

type CalendarAccountStatus = {
  provider: CalendarProvider;
  email: string | null;
  needsReconnect: boolean;
};

type DisconnectResult = { eventsRemoved: number; eventsNotRemoved: number };

const PROVIDER_LABEL: Record<CalendarProvider, string> = {
  GOOGLE: "Google Calendar",
  MICROSOFT: "Outlook Calendar",
};

// Shown inside the export modal. Alert.alert does nothing on web, so results are rendered inline.
type ExportStatus = { tone: "success" | "info" | "error"; text: string };

type Props = {
  onAuthError: () => void;
  onBack: () => void;
};

export default function CalendarScreen({ onAuthError, onBack }: Props) {
  const [exportModalVisible, setExportModalVisible] = useState(false);
  const [email, setEmail] = useState("");
  const [exporting, setExporting] = useState(false);
  const [exportStatus, setExportStatus] = useState<ExportStatus | null>(null);
  const [outlookModalVisible, setOutlookModalVisible] = useState(false);
  const [disconnectTarget, setDisconnectTarget] = useState<CalendarAccountStatus | null>(null);
  const [removeEvents, setRemoveEvents] = useState(true);
  const [disconnecting, setDisconnecting] = useState(false);
  const [disconnectStatus, setDisconnectStatus] = useState<ExportStatus | null>(null);
  const queryClient = useQueryClient();
  const [selectedEvent, setSelectedEvent] = useState<InternalCalendarEvent | null>(null);
  const [selectedDay, setSelectedDay] = useState<string | null>(null);
  const { width } = useWindowDimensions();
  const listMode = width < LIST_BREAKPOINT;
  const { terms } = useTerm();
  const [currentDate, setCurrentDate] = useState(() => {
    const now = new Date();
    return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-01`;
  });

  const eventsQuery = useQuery({
    queryKey: qk.calendarEvents,
    queryFn: async (): Promise<InternalCalendarEvent[]> => {
      const res = await authedFetch("/api/calendar/events");
      // No calendar yet is a normal empty state, not an error.
      if (res.status === 404) return [];
      if (!res.ok) return [];
      const raw = await res.text();
      const data = raw ? JSON.parse(raw) : null;
      return data?.events ?? [];
    },
    staleTime: 2 * 60_000,
    retry: queryRetry,
  });

  // Same key the reconnect banner polls, so the two never disagree about what is connected.
  const accountsQuery = useQuery({
    queryKey: qk.calendarAccounts,
    queryFn: () => authedGet<CalendarAccountStatus[]>("/api/calendar/accounts"),
    staleTime: 60_000,
    retry: queryRetry,
  });
  const accounts = accountsQuery.data ?? [];

  const events = eventsQuery.data ?? [];
  const loading = eventsQuery.isPending;

  useEffect(() => {
    if (isAuthError(eventsQuery.error)) onAuthError();
  }, [eventsQuery.error, onAuthError]);

  const openExportModal = () => {
    setExportStatus(null);
    const google = accounts.find((a) => a.provider === "GOOGLE");
    if (!email.trim() && google?.email) setEmail(google.email);
    setExportModalVisible(true);
  };

  const closeExportModal = () => {
    setExportModalVisible(false);
    setExportStatus(null);
  };

  const handleExport = async () => {
    if (!email.trim()) return;
    setExporting(true);
    setExportStatus(null);

    try {
      const token = await getToken();
      const res = await fetch(`${API_BASE_URL}/api/calendar/google/sync`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({ calendarEmail: email.trim() }),
      });

      if (res.status === 401 || res.status === 403) {
        onAuthError();
        return;
      }

      const data = await res.json();

      if (res.status === 409 && data.action === "connect") {
        setExportModalVisible(false);
        // The backend runs the first sync itself once OAuth completes, and the page Google
        // redirects to tells the student how it went. No retry here: openBrowserAsync resolves
        // as soon as the tab opens on web and Android, long before the student has consented.
        await WebBrowser.openBrowserAsync(data.authorizationUrl);
        return;
      }

      if (res.ok) {
        const count: number = data.eventCount ?? 0;
        setExportStatus(
          count === 0
            ? { tone: "info", text: "No assessments with due dates to export yet." }
            : {
                tone: "success",
                text: `Synced ${count} ${count === 1 ? "assessment" : "assessments"} to Google Calendar.`,
              },
        );
      } else {
        setExportStatus({ tone: "error", text: data.error ?? "Sync failed. Please try again." });
      }
    } catch {
      setExportStatus({ tone: "error", text: "Could not connect to server." });
    } finally {
      setExporting(false);
    }
  };

  const openDisconnectModal = (account: CalendarAccountStatus) => {
    setRemoveEvents(true);
    setDisconnectStatus(null);
    setDisconnectTarget(account);
  };

  const closeDisconnectModal = () => {
    if (disconnecting) return;
    setDisconnectTarget(null);
    setDisconnectStatus(null);
  };

  const handleDisconnect = async () => {
    if (!disconnectTarget) return;
    const label = PROVIDER_LABEL[disconnectTarget.provider];
    setDisconnecting(true);
    setDisconnectStatus(null);
    try {
      const res = await authedMutate(
        `/api/calendar/accounts/${disconnectTarget.provider.toLowerCase()}?removeEvents=${removeEvents}`,
        { method: "DELETE" },
      );
      const result: DisconnectResult = await res.json();
      await queryClient.invalidateQueries({ queryKey: qk.calendarAccounts });
      if (result.eventsNotRemoved > 0) {
        // Keep the modal open so the student sees what was left behind.
        const n = result.eventsNotRemoved;
        setDisconnectStatus({
          tone: "info",
          text: `Disconnected. ${n} ${n === 1 ? "assessment" : "assessments"} couldn't be removed from ${label}, so you may need to delete ${n === 1 ? "it" : "them"} there yourself.`,
        });
      } else {
        setDisconnectTarget(null);
      }
    } catch (err) {
      if (isAuthError(err)) {
        onAuthError();
        return;
      }
      if (err instanceof HttpError && err.status === 404) {
        // Already gone, from another device say. The list is stale, not wrong to act on.
        await queryClient.invalidateQueries({ queryKey: qk.calendarAccounts });
        setDisconnectTarget(null);
        return;
      }
      setDisconnectStatus({
        tone: "error",
        text:
          (err instanceof HttpError && err.serverMessage) ||
          "Couldn't disconnect. Please try again.",
      });
    } finally {
      setDisconnecting(false);
    }
  };

  // Group events by date
  const eventsByDate: Record<string, InternalCalendarEvent[]> = {};
  for (const event of events) {
    if (!eventsByDate[event.dueDate]) {
      eventsByDate[event.dueDate] = [];
    }
    eventsByDate[event.dueDate].push(event);
  }

  // Build marked dates for react-native-calendars
  const markedDates: Record<string, { marked: boolean; dotColor: string }> = {};
  for (const date of Object.keys(eventsByDate)) {
    markedDates[date] = { marked: true, dotColor: colors.primary };
  }

  // One colour per course, stable for the session: assigned in alphabetical order of course code.
  const courseOrder = Array.from(new Set(events.map((e) => e.courseCode))).sort();
  const accentFor = (courseCode: string) =>
    colors.courseAccents[Math.max(0, courseOrder.indexOf(courseCode)) % colors.courseAccents.length];

  // Only look up weight and grade in a term the student actually has.
  const knownTerms = new Set(terms.map((t) => t.term));
  const selectedTerm = selectedEvent ? termFor(selectedEvent.dueDate) : null;

  // The event as a full-width card: the phone list under the grid and the "+N more" day sheet.
  const renderEventCard = (event: InternalCalendarEvent, onPress: () => void) => (
    <Pressable
      style={[styles.agendaCard, event.completed && styles.eventDone]}
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel={`${event.courseCode} ${event.assessmentName}. Show details`}
    >
      <View style={[styles.agendaDot, { backgroundColor: accentFor(event.courseCode) }]} />
      <View style={styles.agendaBody}>
        <Text
          style={[styles.agendaName, event.completed && styles.chipTextDone]}
          numberOfLines={1}
        >
          {event.assessmentName}
        </Text>
        <Text style={styles.agendaCourse} numberOfLines={1}>
          {event.courseCode}
          {event.allDay || !event.startTime ? "" : `, ${formatTime(event.startTime)}`}
        </Text>
      </View>
      <Feather name="chevron-right" size={18} color={colors.muted} />
    </Pressable>
  );

  const today = localToday();
  const visibleMonth = currentDate.slice(0, 7);
  const monthEvents = events
    .filter((e) => e.dueDate.startsWith(visibleMonth))
    .sort((a, b) =>
      a.dueDate === b.dueDate
        ? (a.startTime ?? "").localeCompare(b.startTime ?? "")
        : a.dueDate.localeCompare(b.dueDate)
    );

  const navigateMonth = (direction: number) => {
    const [year, month] = currentDate.split("-").map(Number);
    const newMonth = month - 1 + direction;
    const d = new Date(year, newMonth, 1);
    setCurrentDate(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-01`);
  };

  const goToToday = () => {
    const now = new Date();
    setCurrentDate(`${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-01`);
  };

  const currentMonthLabel = () => {
    const [year, month] = currentDate.split("-").map(Number);
    const d = new Date(year, month - 1, 1);
    return d.toLocaleString("default", { month: "long", year: "numeric" });
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
        <Text style={styles.title}>Calendar</Text>
        <View style={styles.headerActions}>
          <Pressable
            style={styles.exportButton}
            onPress={openExportModal}
          >
            <Feather name="upload-cloud" size={16} color={colors.primary} style={{ marginRight: 6 }} />
            <Text style={styles.exportButtonText}>Export to Google</Text>
          </Pressable>
          <Pressable
            style={styles.exportButton}
            onPress={() => setOutlookModalVisible(true)}
          >
            <Feather name="mail" size={16} color={colors.primary} style={{ marginRight: 6 }} />
            <Text style={styles.exportButtonText}>Export to Outlook</Text>
          </Pressable>
        </View>
        {accounts.map((account) => (
          <View key={account.provider} style={styles.connectedRow}>
            <Feather name="check-circle" size={16} color={colors.success} />
            <Text style={styles.connectedText} numberOfLines={1}>
              {account.email
                ? `Connected to ${PROVIDER_LABEL[account.provider]} as ${account.email}`
                : `Connected to ${PROVIDER_LABEL[account.provider]}`}
            </Text>
            <Pressable
              accessibilityRole="button"
              onPress={() => openDisconnectModal(account)}
              style={styles.disconnectLink}
            >
              <Text style={styles.disconnectLinkText}>Disconnect</Text>
            </Pressable>
          </View>
        ))}
      </View>

      {/* Calendar Card */}
      <View style={styles.card}>
        {/* Custom month navigation */}
        <View style={styles.monthNav}>
          <Text style={styles.monthLabel}>{currentMonthLabel()}</Text>
          <Pressable
            onPress={() => navigateMonth(-1)}
            style={styles.navArrow}
            accessibilityRole="button"
            accessibilityLabel="Previous month"
          >
            <Feather name="chevron-left" size={20} color={colors.boldText} />
          </Pressable>
          <Pressable onPress={goToToday} style={styles.todayButton}>
            <Text style={styles.todayButtonText}>Today</Text>
          </Pressable>
          <Pressable
            onPress={() => navigateMonth(1)}
            style={styles.navArrow}
            accessibilityRole="button"
            accessibilityLabel="Next month"
          >
            <Feather name="chevron-right" size={20} color={colors.boldText} />
          </Pressable>
        </View>

        <Calendar
          key={currentDate}
          current={currentDate}
          markedDates={markedDates}
          hideArrows
          hideExtraDays={false}
          renderHeader={() => null}
          dayComponent={({ date }: { date?: DateData }) => {
            if (!date) return null;
            const dateStr = date.dateString;
            const dayEvents = eventsByDate[dateStr] ?? [];
            const isToday = dateStr === today;

            return (
              <View style={[styles.dayCell, listMode && styles.dayCellCompact]}>
                <Text
                  style={[
                    styles.dayNumber,
                    isToday && styles.todayNumber,
                  ]}
                >
                  {date.day}
                </Text>
                {listMode ? (
                  // Too narrow for words: one mark per event, coloured by course. The list under
                  // the grid is where these are read and opened.
                  dayEvents.length > 0 && (
                    <View style={styles.markRow}>
                      {dayEvents.map((event) => (
                        <View
                          key={event.eventKey}
                          style={[
                            styles.mark,
                            { backgroundColor: accentFor(event.courseCode) },
                            event.completed && styles.eventDone,
                          ]}
                        />
                      ))}
                    </View>
                  )
                ) : (
                  <>
                  {dayEvents.slice(0, MAX_CHIPS).map((event) => (
                    <Pressable
                      key={event.eventKey}
                      style={[styles.eventChip, event.completed && styles.eventDone]}
                      onPress={() => setSelectedEvent(event)}
                      accessibilityRole="button"
                      accessibilityLabel={`${event.courseCode} ${event.assessmentName}. Show details`}
                    >
                      <View style={[styles.chipDot, { backgroundColor: accentFor(event.courseCode) }]} />
                      <Text
                        style={[styles.chipText, event.completed && styles.chipTextDone]}
                        numberOfLines={1}
                      >
                        <Text style={styles.chipCourse}>{event.courseCode}</Text>
                        {"  "}
                        {event.assessmentName}
                      </Text>
                    </Pressable>
                  ))}
                  {dayEvents.length > MAX_CHIPS && (
                    <Pressable
                      style={styles.moreChip}
                      onPress={() => setSelectedDay(dateStr)}
                      accessibilityRole="button"
                      accessibilityLabel={`Show all ${dayEvents.length} events on this day`}
                    >
                      <Text style={styles.moreChipText}>
                        +{dayEvents.length - MAX_CHIPS} more
                      </Text>
                    </Pressable>
                  )}
                  </>
                )}
              </View>
            );
          }}
          theme={{
            calendarBackground: "transparent",
            textSectionTitleColor: colors.bodyText,
            textDayHeaderFontFamily: fonts.bodyMedium,
            textDayHeaderFontSize: 12,
          }}
        />
      </View>

      {listMode && (
        <View style={styles.agenda}>
          <Text style={styles.agendaTitle}>Due in {currentMonthLabel()}</Text>
          {monthEvents.length === 0 ? (
            <Text style={styles.agendaEmpty}>Nothing due this month.</Text>
          ) : (
            monthEvents.map((event, i) => {
              const showDate = i === 0 || monthEvents[i - 1].dueDate !== event.dueDate;
              const [y, m, d] = event.dueDate.split("-").map(Number);
              const dateLabel = new Date(y, m - 1, d).toLocaleDateString("en-US", {
                weekday: "short",
                month: "short",
                day: "numeric",
              });
              return (
                <View key={event.eventKey}>
                  {showDate && (
                    <Text style={[styles.agendaDate, event.dueDate === today && styles.agendaDateToday]}>
                      {event.dueDate === today ? `Today, ${dateLabel}` : dateLabel}
                    </Text>
                  )}
                  {renderEventCard(event, () => setSelectedEvent(event))}
                </View>
              );
            })
          )}
        </View>
      )}

      {/* Every event on a busy day, opened from "+N more" in the grid. */}
      <Modal
        visible={selectedDay !== null}
        transparent
        animationType="fade"
        onRequestClose={() => setSelectedDay(null)}
      >
        <Pressable {...noHover} style={styles.modalOverlay} onPress={() => setSelectedDay(null)}>
          <Pressable {...noHover} style={[styles.modalContent, styles.daySheet]} onPress={() => {}}>
            <View style={styles.daySheetHeader}>
              <Text style={styles.modalTitle}>
                {selectedDay &&
                  (() => {
                    const [y, m, d] = selectedDay.split("-").map(Number);
                    return new Date(y, m - 1, d).toLocaleDateString("en-US", {
                      weekday: "long",
                      month: "long",
                      day: "numeric",
                    });
                  })()}
              </Text>
              <Pressable
                onPress={() => setSelectedDay(null)}
                style={styles.daySheetClose}
                accessibilityRole="button"
                accessibilityLabel="Close"
              >
                <Feather name="x" size={20} color={colors.bodyText} />
              </Pressable>
            </View>
            {selectedDay &&
              (eventsByDate[selectedDay] ?? []).map((event) => (
                <View key={event.eventKey}>
                  {renderEventCard(event, () => {
                    setSelectedDay(null);
                    setSelectedEvent(event);
                  })}
                </View>
              ))}
          </Pressable>
        </Pressable>
      </Modal>

      <EventDetailSheet
        event={selectedEvent}
        accent={selectedEvent ? accentFor(selectedEvent.courseCode) : colors.primary}
        term={selectedTerm && knownTerms.has(selectedTerm) ? selectedTerm : null}
        onClose={() => setSelectedEvent(null)}
      />

      {/* Export Modal */}
      <Modal
        visible={exportModalVisible}
        transparent
        animationType="fade"
        onRequestClose={closeExportModal}
      >
        <Pressable
          {...noHover}
          style={styles.modalOverlay}
          onPress={closeExportModal}
        >
          <Pressable {...noHover} style={styles.modalContent} onPress={() => {}}>
            <Text style={styles.modalTitle}>Export to Google Calendar</Text>
            <Text style={styles.modalSubtitle}>
              Enter the Google email to sync your events to.
            </Text>
            <TextInput
              style={styles.emailInput}
              placeholder="example@gmail.com"
              placeholderTextColor={colors.placeholder}
              keyboardType="email-address"
              autoCapitalize="none"
              value={email}
              onChangeText={(value) => {
                setEmail(value);
                setExportStatus(null);
              }}
            />
            {exportStatus && (
              <Text
                style={[
                  styles.exportStatus,
                  exportStatus.tone === "success" && styles.exportStatusSuccess,
                  exportStatus.tone === "error" && styles.exportStatusError,
                ]}
              >
                {exportStatus.text}
              </Text>
            )}
            <View style={styles.modalActions}>
              <Pressable
                style={styles.cancelButton}
                onPress={closeExportModal}
              >
                <Text style={styles.cancelText}>
                  {exportStatus?.tone === "success" ? "Close" : "Cancel"}
                </Text>
              </Pressable>
              <Pressable
                style={[
                  styles.exportSubmitButton,
                  exporting && styles.disabledButton,
                ]}
                onPress={handleExport}
                disabled={exporting}
              >
                {exporting ? (
                  <ActivityIndicator size="small" color={colors.onField} />
                ) : (
                  <Text style={styles.exportSubmitText}>Export</Text>
                )}
              </Pressable>
            </View>
          </Pressable>
        </Pressable>
      </Modal>

      {/* Disconnect Modal */}
      <Modal
        visible={disconnectTarget !== null}
        transparent
        animationType="fade"
        onRequestClose={closeDisconnectModal}
      >
        <Pressable {...noHover} style={styles.modalOverlay} onPress={closeDisconnectModal}>
          <Pressable {...noHover} style={styles.modalContent} onPress={() => {}}>
            {disconnectTarget && (
              <>
                <Text style={styles.modalTitle}>
                  Disconnect {PROVIDER_LABEL[disconnectTarget.provider]}?
                </Text>
                <Text style={styles.modalSubtitle}>
                  {disconnectTarget.email
                    ? `onTrack will stop sending your assessments to ${disconnectTarget.email}.`
                    : "onTrack will stop sending your assessments to this calendar."}
                </Text>
                {disconnectStatus?.tone !== "info" && (
                  <Pressable
                    accessibilityRole="checkbox"
                    accessibilityState={{ checked: removeEvents }}
                    style={styles.checkboxRow}
                    onPress={() => setRemoveEvents((v) => !v)}
                    disabled={disconnecting}
                  >
                    <Feather
                      name={removeEvents ? "check-square" : "square"}
                      size={20}
                      color={colors.primary}
                    />
                    <Text style={styles.checkboxLabel}>
                      Also remove the assessments onTrack added to{" "}
                      {PROVIDER_LABEL[disconnectTarget.provider]}
                    </Text>
                  </Pressable>
                )}
                {disconnectStatus && (
                  <Text
                    style={[
                      styles.exportStatus,
                      disconnectStatus.tone === "error" && styles.exportStatusError,
                    ]}
                  >
                    {disconnectStatus.text}
                  </Text>
                )}
                <View style={styles.modalActions}>
                  {disconnectStatus?.tone === "info" ? (
                    <Pressable style={styles.exportSubmitButton} onPress={closeDisconnectModal}>
                      <Text style={styles.exportSubmitText}>Close</Text>
                    </Pressable>
                  ) : (
                    <>
                      <Pressable
                        style={styles.cancelButton}
                        onPress={closeDisconnectModal}
                        disabled={disconnecting}
                      >
                        <Text style={styles.cancelText}>Cancel</Text>
                      </Pressable>
                      <Pressable
                        style={[styles.disconnectButton, disconnecting && styles.disabledButton]}
                        onPress={handleDisconnect}
                        disabled={disconnecting}
                      >
                        {disconnecting ? (
                          <ActivityIndicator size="small" color={colors.onField} />
                        ) : (
                          <Text style={styles.exportSubmitText}>Disconnect</Text>
                        )}
                      </Pressable>
                    </>
                  )}
                </View>
              </>
            )}
          </Pressable>
        </Pressable>
      </Modal>

      {/* Outlook Coming Soon Modal */}
      <Modal
        visible={outlookModalVisible}
        transparent
        animationType="fade"
        onRequestClose={() => setOutlookModalVisible(false)}
      >
        <Pressable
          {...noHover}
          style={styles.modalOverlay}
          onPress={() => setOutlookModalVisible(false)}
        >
          <Pressable {...noHover} style={styles.modalContent} onPress={() => {}}>
            <Text style={styles.modalTitle}>Coming Soon</Text>
            <Text style={styles.modalSubtitle}>
              Outlook calendar sync is not yet available. Stay tuned!
            </Text>
            <View style={styles.modalActions}>
              <Pressable
                style={styles.exportSubmitButton}
                onPress={() => setOutlookModalVisible(false)}
              >
                <Text style={styles.exportSubmitText}>Got it</Text>
              </Pressable>
            </View>
          </Pressable>
        </Pressable>
      </Modal>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {
    padding: spacing.lg,
    backgroundColor: colors.background,
    flexGrow: 1,
  },
  loadingContainer: {
    flex: 1,
    justifyContent: "center",
    alignItems: "center",
    backgroundColor: colors.background,
  },
  header: {
    // Clears the shell's hamburger, which floats over the top-left corner.
    paddingTop: spacing.lg,
    marginBottom: spacing.lg,
  },
  title: {
    ...typo.h1,
    color: colors.boldText,
  },
  headerActions: {
    flexDirection: "row",
    flexWrap: "wrap",
    gap: 10,
    marginTop: spacing.md,
  },
  exportButton: {
    borderWidth: 1,
    borderColor: colors.primary,
    paddingVertical: 10,
    paddingHorizontal: spacing.md,
    borderRadius: radius.md,
    flexDirection: "row",
    alignItems: "center",
  },
  exportButtonText: {
    ...typo.label,
    color: colors.primary,
  },
  connectedRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
    marginTop: spacing.sm,
  },
  connectedText: {
    ...typo.body,
    flexShrink: 1,
    color: colors.bodyText,
  },
  disconnectLink: {
    paddingVertical: 4,
    paddingHorizontal: 6,
  },
  disconnectLinkText: {
    ...typo.label,
    color: colors.danger,
  },
  checkboxRow: {
    flexDirection: "row",
    alignItems: "flex-start",
    gap: 10,
    marginBottom: spacing.md,
  },
  checkboxLabel: {
    ...typo.body,
    flex: 1,
    color: colors.boldText,
  },
  disconnectButton: {
    backgroundColor: colors.danger,
    paddingVertical: 10,
    paddingHorizontal: 20,
    borderRadius: radius.md,
  },
  card: {
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    padding: spacing.md,
    marginBottom: spacing.md,
    borderWidth: 1,
    borderColor: colors.borderSubtle,
  },
  monthNav: {
    flexDirection: "row",
    alignItems: "center",
    marginBottom: spacing.sm,
    paddingHorizontal: 4,
    gap: spacing.sm,
  },
  monthLabel: {
    ...typo.h2,
    color: colors.boldText,
    marginRight: 4,
  },
  navArrow: {
    width: 32,
    height: 32,
    borderRadius: radius.sm,
    backgroundColor: colors.background,
    justifyContent: "center",
    alignItems: "center",
  },
  todayButton: {
    paddingHorizontal: 12,
    paddingVertical: 4,
    borderRadius: radius.sm,
    backgroundColor: colors.background,
  },
  todayButtonText: {
    ...typo.label,
    color: colors.boldText,
  },
  dayCell: {
    width: "100%",
    minHeight: 102,
    borderWidth: 0.5,
    borderColor: colors.border,
    padding: 4,
  },
  dayNumber: {
    ...typo.caption,
    color: colors.boldText,
    marginBottom: 2,
    fontVariant: ["tabular-nums"],
  },
  todayNumber: {
    color: colors.primary,
    fontFamily: fonts.display,
  },
  moreChip: {
    alignSelf: "flex-start",
    paddingHorizontal: 5,
    paddingVertical: 2,
    marginTop: 3,
    borderRadius: radius.sm,
  },
  moreChipText: {
    ...typo.caption,
    fontSize: 11,
    lineHeight: 14,
    fontFamily: fonts.bodyMedium,
    color: colors.primary,
  },
  daySheet: {
    maxWidth: 440,
  },
  daySheetHeader: {
    flexDirection: "row",
    alignItems: "flex-start",
    justifyContent: "space-between",
    gap: spacing.sm,
    marginBottom: spacing.sm,
  },
  daySheetClose: {
    width: 32,
    height: 32,
    borderRadius: radius.md,
    alignItems: "center",
    justifyContent: "center",
    marginTop: -4,
    marginRight: -6,
  },
  dayCellCompact: {
    minHeight: 52,
    padding: 3,
  },
  // A grid event: one line, never wraps. The dot carries the course colour so a week with four
  // courses can be scanned without reading every label.
  eventChip: {
    flexDirection: "row",
    alignItems: "center",
    gap: 5,
    backgroundColor: colors.surfaceAlt,
    borderWidth: 1,
    borderColor: colors.borderSubtle,
    borderRadius: radius.sm,
    paddingHorizontal: 5,
    paddingVertical: 3,
    marginTop: 3,
    overflow: "hidden",
  },
  chipDot: {
    width: 6,
    height: 6,
    borderRadius: 3,
    flexShrink: 0,
  },
  chipText: {
    flex: 1,
    minWidth: 0,
    fontSize: 11,
    lineHeight: 14,
    letterSpacing: 0.22,
    color: colors.bodyText,
    fontFamily: fonts.body,
  },
  chipCourse: {
    fontFamily: fonts.bodyMedium,
    color: colors.boldText,
  },
  chipTextDone: {
    textDecorationLine: "line-through",
  },
  eventDone: {
    opacity: 0.5,
  },
  markRow: {
    flexDirection: "row",
    flexWrap: "wrap",
    gap: 3,
    marginTop: 4,
  },
  mark: {
    width: 6,
    height: 6,
    borderRadius: 3,
  },
  agenda: {
    marginBottom: spacing.lg,
  },
  agendaTitle: {
    ...typo.h2,
    color: colors.boldText,
    marginTop: spacing.sm,
  },
  agendaEmpty: {
    ...typo.body,
    color: colors.bodyText,
    marginTop: spacing.sm,
  },
  agendaDate: {
    ...typo.caption,
    fontFamily: fonts.bodyMedium,
    color: colors.bodyText,
    marginTop: spacing.md,
    marginBottom: 6,
  },
  agendaDateToday: {
    color: colors.primary,
  },
  agendaCard: {
    flexDirection: "row",
    alignItems: "center",
    gap: 12,
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.borderSubtle,
    borderRadius: radius.md,
    paddingVertical: 12,
    paddingHorizontal: spacing.md,
    marginBottom: spacing.sm,
  },
  agendaDot: {
    width: 10,
    height: 10,
    borderRadius: 5,
  },
  agendaBody: {
    flex: 1,
    minWidth: 0,
  },
  agendaName: {
    ...typo.body,
    fontFamily: fonts.bodyMedium,
    color: colors.boldText,
  },
  agendaCourse: {
    ...typo.caption,
    color: colors.bodyText,
    marginTop: 2,
  },
  // Modal styles
  modalOverlay: {
    flex: 1,
    backgroundColor: colors.scrim,
    justifyContent: "center",
    alignItems: "center",
  },
  modalContent: {
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    padding: spacing.lg,
    width: "85%",
    maxWidth: 400,
  },
  modalTitle: {
    ...typo.h2,
    color: colors.boldText,
    marginBottom: 4,
  },
  modalSubtitle: {
    ...typo.body,
    color: colors.bodyText,
    marginBottom: spacing.md,
  },
  emailInput: {
    ...typo.body,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.md,
    padding: 12,
    fontSize: 16,
    marginBottom: spacing.md,
    color: colors.boldText,
    letterSpacing: 0.32,
  },
  exportStatus: {
    ...typo.body,
    color: colors.bodyText,
    marginBottom: spacing.md,
  },
  exportStatusSuccess: {
    color: colors.success,
  },
  exportStatusError: {
    color: colors.danger,
  },
  modalActions: {
    flexDirection: "row",
    justifyContent: "flex-end",
    gap: 12,
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
  exportSubmitButton: {
    backgroundColor: colors.primary,
    paddingVertical: 10,
    paddingHorizontal: 20,
    borderRadius: radius.md,
  },
  exportSubmitText: {
    ...typo.label,
    color: colors.onField,
    fontFamily: fonts.display,
  },
  disabledButton: {
    opacity: 0.6,
  },
});
