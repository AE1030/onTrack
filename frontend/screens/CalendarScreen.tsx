//imports
import { useEffect, useState } from "react";
import {
  View,
  Text,
  StyleSheet,
  Pressable,
  ScrollView,
  Modal,
  TextInput,
  ActivityIndicator,
} from "react-native";
import { Calendar, DateData } from "react-native-calendars";
import * as WebBrowser from "expo-web-browser";
import { Feather } from "@expo/vector-icons";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { colors } from "../src/theme/colors";
import { spacing } from "../src/theme/spacing";
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

type InternalCalendarEvent = {
  courseCode: string;
  assessmentName: string;
  dueDate: string;
  startTime: string | null;
  endTime: string | null;
  allDay: boolean;
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

  const formatEventLabel = (event: InternalCalendarEvent) => {
    const prefix = event.startTime ? `${event.startTime} ` : "";
    return `${prefix}${event.courseCode} ${event.assessmentName}`;
  };

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
        <Text style={styles.subtitle}>
          Keep track of all your upcoming deadlines.
        </Text>
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
          <Pressable onPress={() => navigateMonth(-1)} style={styles.navArrow}>
            <Feather name="chevron-left" size={20} color={colors.boldText} />
          </Pressable>
          <Pressable onPress={goToToday} style={styles.todayButton}>
            <Text style={styles.todayButtonText}>Today</Text>
          </Pressable>
          <Pressable onPress={() => navigateMonth(1)} style={styles.navArrow}>
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
            const isToday = dateStr === new Date().toISOString().split("T")[0];

            return (
              <View style={styles.dayCell}>
                <Text
                  style={[
                    styles.dayNumber,
                    isToday && styles.todayNumber,
                  ]}
                >
                  {date.day}
                </Text>
                {dayEvents.map((event, i) => (
                  <View key={i} style={styles.eventPill}>
                    <Text style={styles.eventPillText} numberOfLines={1}>
                      {formatEventLabel(event)}
                    </Text>
                  </View>
                ))}
              </View>
            );
          }}
          theme={{
            calendarBackground: "transparent",
            textSectionTitleColor: colors.text,
            textDayHeaderFontWeight: "600" as const,
            textDayHeaderFontSize: 13,
          }}
        />
      </View>

      {/* Export Modal */}
      <Modal
        visible={exportModalVisible}
        transparent
        animationType="fade"
        onRequestClose={closeExportModal}
      >
        <Pressable
          style={styles.modalOverlay}
          onPress={closeExportModal}
        >
          <Pressable style={styles.modalContent} onPress={() => {}}>
            <Text style={styles.modalTitle}>Export to Google Calendar</Text>
            <Text style={styles.modalSubtitle}>
              Enter the Google email to sync your events to.
            </Text>
            <TextInput
              style={styles.emailInput}
              placeholder="example@gmail.com"
              placeholderTextColor="#aaa"
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
                  <ActivityIndicator size="small" color="white" />
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
        <Pressable style={styles.modalOverlay} onPress={closeDisconnectModal}>
          <Pressable style={styles.modalContent} onPress={() => {}}>
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
                          <ActivityIndicator size="small" color="white" />
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
          style={styles.modalOverlay}
          onPress={() => setOutlookModalVisible(false)}
        >
          <Pressable style={styles.modalContent} onPress={() => {}}>
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
    marginBottom: spacing.lg,
  },
  title: {
    fontSize: 28,
    fontWeight: "700",
    color: colors.boldText,
  },
  subtitle: {
    marginTop: 4,
    color: colors.bodyText,
    fontSize: 14,
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
    borderRadius: 10,
    flexDirection: "row",
    alignItems: "center",
  },
  exportButtonText: {
    color: colors.primary,
    fontWeight: "600",
    fontSize: 14,
  },
  connectedRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
    marginTop: spacing.sm,
  },
  connectedText: {
    flexShrink: 1,
    color: colors.bodyText,
    fontSize: 14,
  },
  disconnectLink: {
    paddingVertical: 4,
    paddingHorizontal: 6,
  },
  disconnectLinkText: {
    color: colors.danger,
    fontWeight: "600",
    fontSize: 14,
  },
  checkboxRow: {
    flexDirection: "row",
    alignItems: "flex-start",
    gap: 10,
    marginBottom: spacing.md,
  },
  checkboxLabel: {
    flex: 1,
    fontSize: 14,
    color: colors.boldText,
    lineHeight: 20,
  },
  disconnectButton: {
    backgroundColor: colors.danger,
    paddingVertical: 10,
    paddingHorizontal: 20,
    borderRadius: 10,
  },
  card: {
    backgroundColor: "white",
    borderRadius: 16,
    padding: spacing.md,
    marginBottom: spacing.md,
    boxShadow: "0px 2px 6px rgba(0, 0, 0, 0.06)",
    elevation: 2,
  },
  monthNav: {
    flexDirection: "row",
    alignItems: "center",
    marginBottom: spacing.sm,
    paddingHorizontal: 4,
    gap: spacing.sm,
  },
  monthLabel: {
    fontSize: 20,
    fontWeight: "700",
    color: colors.boldText,
    marginRight: 4,
  },
  navArrow: {
    width: 32,
    height: 32,
    borderRadius: 8,
    backgroundColor: colors.background,
    justifyContent: "center",
    alignItems: "center",
  },
  todayButton: {
    paddingHorizontal: 12,
    paddingVertical: 4,
    borderRadius: 8,
    backgroundColor: colors.background,
  },
  todayButtonText: {
    color: colors.boldText,
    fontWeight: "600",
    fontSize: 14,
  },
  dayCell: {
    width: "100%",
    minHeight: 80,
    borderWidth: 0.5,
    borderColor: colors.border,
    padding: 4,
  },
  dayNumber: {
    fontSize: 12,
    color: colors.boldText,
    marginBottom: 2,
  },
  todayNumber: {
    fontWeight: "700",
    color: colors.primary,
  },
  eventPill: {
    backgroundColor: "#F2E6EC",
    borderLeftWidth: 3,
    borderLeftColor: colors.primary,
    borderRadius: 4,
    paddingHorizontal: 4,
    paddingVertical: 2,
    marginTop: 2,
  },
  eventPillText: {
    fontSize: 10,
    color: colors.primary,
    fontWeight: "600",
  },
  // Modal styles
  modalOverlay: {
    flex: 1,
    backgroundColor: "rgba(0,0,0,0.4)",
    justifyContent: "center",
    alignItems: "center",
  },
  modalContent: {
    backgroundColor: "white",
    borderRadius: 16,
    padding: spacing.lg,
    width: "85%",
    maxWidth: 400,
    boxShadow: "0px 8px 24px rgba(0, 0, 0, 0.15)",
    elevation: 8,
  },
  modalTitle: {
    fontSize: 20,
    fontWeight: "700",
    color: colors.boldText,
    marginBottom: 4,
  },
  modalSubtitle: {
    fontSize: 14,
    color: colors.bodyText,
    marginBottom: spacing.md,
  },
  emailInput: {
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: 10,
    padding: 12,
    fontSize: 16,
    marginBottom: spacing.md,
    color: colors.boldText,
  },
  exportStatus: {
    fontSize: 14,
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
    borderRadius: 10,
  },
  cancelText: {
    color: colors.bodyText,
    fontWeight: "600",
  },
  exportSubmitButton: {
    backgroundColor: colors.primary,
    paddingVertical: 10,
    paddingHorizontal: 20,
    borderRadius: 10,
  },
  exportSubmitText: {
    color: "white",
    fontWeight: "600",
  },
  disabledButton: {
    opacity: 0.6,
  },
});
