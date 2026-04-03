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
  Alert,
} from "react-native";
import { Calendar, DateData } from "react-native-calendars";
import * as WebBrowser from "expo-web-browser";
import { Feather } from "@expo/vector-icons";
import { colors } from "../src/theme/colors";
import { spacing } from "../src/theme/spacing";
import { API_BASE_URL } from "../src/config/api";
import { getToken } from "../src/utils/tokenStorage";

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

type Props = {
  onAuthError: () => void;
  onBack: () => void;
};

export default function CalendarScreen({ onAuthError, onBack }: Props) {
  const [events, setEvents] = useState<InternalCalendarEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [exportModalVisible, setExportModalVisible] = useState(false);
  const [email, setEmail] = useState("");
  const [exporting, setExporting] = useState(false);
  const [outlookModalVisible, setOutlookModalVisible] = useState(false);
  const [currentDate, setCurrentDate] = useState(() => {
    const now = new Date();
    return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-01`;
  });

  useEffect(() => {
    fetchEvents();
  }, []);

  const fetchEvents = async () => {
    try {
      const token = await getToken();
      const res = await fetch(`${API_BASE_URL}/api/calendar/events`, {
        headers: { Authorization: `Bearer ${token}` },
      });

      if (res.status === 401 || res.status === 403) {
        onAuthError();
        return;
      }

      if (res.status === 404) {
        setEvents([]);
        return;
      }

      const data = await res.json();
      setEvents(data.events ?? []);
    } catch {
      setEvents([]);
    } finally {
      setLoading(false);
    }
  };

  const handleExport = async () => {
    if (!email.trim()) return;
    setExporting(true);

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
        await WebBrowser.openBrowserAsync(data.authorizationUrl);
        // After OAuth completes, automatically retry sync with the same email
        const retryRes = await fetch(`${API_BASE_URL}/api/calendar/google/sync`, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            Authorization: `Bearer ${token}`,
          },
          body: JSON.stringify({ calendarEmail: email.trim() }),
        });
        if (retryRes.ok) {
          Alert.alert("Success", "Events synced to Google Calendar.");
        } else {
          Alert.alert("Error", "Sync failed after connecting. Please try again.");
        }
        return;
      }

      if (res.ok) {
        setExportModalVisible(false);
        Alert.alert("Success", "Events synced to Google Calendar.");
      } else {
        Alert.alert("Error", data.error ?? "Sync failed. Please try again.");
      }
    } catch {
      Alert.alert("Error", "Could not connect to server.");
    } finally {
      setExporting(false);
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
            onPress={() => setExportModalVisible(true)}
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
        onRequestClose={() => setExportModalVisible(false)}
      >
        <Pressable
          style={styles.modalOverlay}
          onPress={() => setExportModalVisible(false)}
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
              onChangeText={setEmail}
            />
            <View style={styles.modalActions}>
              <Pressable
                style={styles.cancelButton}
                onPress={() => setExportModalVisible(false)}
              >
                <Text style={styles.cancelText}>Cancel</Text>
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
