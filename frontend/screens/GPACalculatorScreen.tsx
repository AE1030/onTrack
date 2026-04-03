import {
  View,
  Text,
  TextInput,
  Pressable,
  StyleSheet,
  ScrollView,
  useWindowDimensions,
} from "react-native";
import { Picker } from "@react-native-picker/picker";
import { useState, useEffect, useRef } from "react";
import Animated, {
  useSharedValue,
  useAnimatedStyle,
  withTiming,
  Easing,
} from "react-native-reanimated";
import { Feather } from "@expo/vector-icons";
import { colors } from "../src/theme/colors";
import { spacing } from "../src/theme/spacing";
import { getToken } from "../src/utils/tokenStorage";
import { API_BASE_URL } from "../src/config/api";
import TranscriptUploadScreen from "./TranscriptUploadScreen";

const GRADES = [
  "A+", "A", "A-",
  "B+", "B", "B-",
  "C+", "C", "C-",
  "D+", "D", "D-",
  "F",
  "AUD", "NC", "P", "MT", "COM", "W", "T",
];
const CREDIT_OPTIONS = Array.from({ length: 31 }, (_, i) => (i * 0.5).toString());

const FORM_MAX_WIDTH = 540;
const WIDE_BREAKPOINT = 820;

type CourseRow = {
  id: string;
  courseName: string;
  credits: string;
  grade: string;
};

type Props = {
  onAuthError: () => void;
};

export default function GPACalculatorScreen({ onAuthError }: Props) {
  const { width } = useWindowDimensions();
  const isWide = width >= WIDE_BREAKPOINT;
  const scrollRef = useRef<ScrollView>(null);

  const [mode, setMode] = useState<"manual" | "transcript">("manual");
  const [rows, setRows] = useState<CourseRow[]>([
    { id: "1", courseName: "", credits: "", grade: "A" },
  ]);

  const [loading, setLoading] = useState(false);
  const [loadingCourses, setLoadingCourses] = useState(true);
  const [result, setResult] = useState<{
    gpa: number;
    totalCredits: number;
  } | null>(null);

  const tabPosition = useSharedValue(0);
  const [tabWidth, setTabWidth] = useState(0);

  const animatedIndicatorStyle = useAnimatedStyle(() => ({
    transform: [{ translateX: tabPosition.value }],
  }));

  const switchMode = (next: "manual" | "transcript") => {
    setMode(next);
    tabPosition.value = withTiming(
      next === "manual" ? 0 : tabWidth / 2,
      { duration: 250, easing: Easing.out(Easing.cubic) }
    );
  };

  const fetchPastCourses = async () => {
    const token = await getToken();
    if (!token) {
      setLoadingCourses(false);
      onAuthError();
      return;
    }

    try {
      const res = await fetch(
        `${API_BASE_URL.replace(/\/$/, "")}/api/my-courses/past-courses`,
        {
          headers: { Authorization: `Bearer ${token}` },
        }
      );

      if (res.status === 401 || res.status === 403) {
        setLoadingCourses(false);
        onAuthError();
        return;
      }

      const data: { courseName: string; credits: string; grade: string }[] =
        await res.json();

      if (data.length > 0) {
        setRows(
          data.map((c, index) => {
            const parsed = parseFloat(c.credits);
            const normalizedCredits = !isNaN(parsed)
              ? parsed.toString()
              : c.credits;
            return {
              id: `past-${index}-${Date.now()}`,
              courseName: c.courseName,
              credits: normalizedCredits,
              grade: c.grade,
            };
          })
        );
      }
    } catch (err) {
      console.error("Failed to fetch past courses:", err);
    } finally {
      setLoadingCourses(false);
    }
  };

  useEffect(() => {
    fetchPastCourses();
  }, []);

  const addRow = () => {
    setRows((prev) => [
      ...prev,
      {
        id: Date.now().toString(),
        courseName: "",
        credits: "",
        grade: "",
      },
    ]);
    setTimeout(() => {
      scrollRef.current?.scrollToEnd({ animated: true });
    }, 100);
  };

  const updateRow = (
    id: string,
    field: keyof CourseRow,
    value: string
  ) => {
    setRows((prev) =>
      prev.map((r) => (r.id === id ? { ...r, [field]: value } : r))
    );
  };

  const [deletingRow, setDeletingRow] = useState<string | null>(null);

  const removeRow = async (id: string) => {
    if (deletingRow) return;
    const row = rows.find((r) => r.id === id);
    if (!row || !row.courseName) {
      setRows((prev) => prev.filter((r) => r.id !== id));
      return;
    }
    setDeletingRow(id);
    try {
      const token = await getToken();
      if (!token) {
        onAuthError();
        return;
      }
      const encoded = encodeURIComponent(row.courseName);
      const res = await fetch(
        `${API_BASE_URL.replace(/\/$/, "")}/api/my-courses/past-courses?courseName=${encoded}`,
        {
          method: "DELETE",
          headers: { Authorization: `Bearer ${token}` },
        }
      );
      if (res.ok) {
        setRows((prev) => prev.filter((r) => r.id !== id));
      }
    } catch {}
    finally {
      setDeletingRow(null);
    }
  };

  const calculateGPA = async () => {
    setLoading(true);
    const token = await getToken();

    if (!token) {
      setLoading(false);
      onAuthError();
      return;
    }

    const courseDtos = rows
      .map((row, index) => ({
        courseName:
          row.courseName.trim() === ""
            ? `course${index + 1}`
            : row.courseName.trim(),
        credits: row.credits,
        grade: row.grade,
      }))
      .filter((c) => c.credits !== "" && c.grade !== "");

    if (courseDtos.length === 0) {
      setLoading(false);
      return;
    }

    const res = await fetch(
      `${API_BASE_URL.replace(/\/$/, "")}/api/manual-upload/calculate-gpa`,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify(courseDtos),
      }
    );

    if (res.status === 401 || res.status === 403) {
      setLoading(false);
      onAuthError();
      return;
    }

    const data = await res.json();
    setResult(data);
    setLoading(false);
  };

  const resultCard = result ? (
    <View style={styles.resultCard}>
      <Text style={styles.resultTitle}>Predicted GPA</Text>
      <Text style={styles.resultSubtitle}>
        Based on the courses entered.
      </Text>
      <Text style={styles.gpa}>{result.gpa.toFixed(2)}</Text>
      <Text style={styles.resultSub}>
        Total Credits: {result.totalCredits}
      </Text>
    </View>
  ) : null;

  return (
    <ScrollView ref={scrollRef} contentContainerStyle={styles.container}>
      <View style={styles.header}>
        <Text style={styles.title}>GPA Calculator</Text>
        <Text style={styles.subtitle}>
          Predict your academic future. Calculate your Cumulative GPA.
        </Text>
      </View>

      {/* TOGGLE — always visible, aligned to form width */}
      <View style={styles.contentWrap}>
        <View
          style={styles.toggle}
          onLayout={(e) => setTabWidth(e.nativeEvent.layout.width - 8)}
        >
          <Animated.View
            style={[
              styles.tabIndicator,
              animatedIndicatorStyle,
              { width: tabWidth ? tabWidth / 2 : "50%" },
            ]}
          />
          <Pressable
            style={styles.tabButton}
            onPress={() => switchMode("manual")}
          >
            <Text
              style={
                mode === "manual"
                  ? styles.activeTabText
                  : styles.inactiveTabText
              }
            >
              Manual Entry
            </Text>
          </Pressable>

          <Pressable
            style={styles.tabButton}
            onPress={() => switchMode("transcript")}
          >
            <Text
              style={
                mode === "transcript"
                  ? styles.activeTabText
                  : styles.inactiveTabText
              }
            >
              Transcript Upload
            </Text>
          </Pressable>
        </View>
      </View>

      {/* MANUAL ENTRY */}
      {mode === "manual" && (
        <View style={styles.contentWrap}>
          {/* Actions at the top */}
          <View style={styles.topActions}>
            <Pressable style={styles.add} onPress={addRow}>
              <Feather name="plus" size={16} color={colors.bodyText} style={{ marginRight: 4 }} />
              <Text style={styles.addText}>Add Course</Text>
            </Pressable>

            <Pressable
              style={styles.calculate}
              onPress={calculateGPA}
              disabled={loading}
            >
              <Feather name="bar-chart-2" size={15} color="white" style={{ marginRight: 6 }} />
              <Text style={styles.calculateText}>
                {loading ? "Calculating..." : "Calculate GPA"}
              </Text>
            </Pressable>
          </View>

          {loadingCourses ? (
            <Text style={styles.loadingText}>
              Loading past courses...
            </Text>
          ) : (
            <>
              {rows.map((row, index) => (
                <View key={row.id} style={styles.row}>
                  <TextInput
                    placeholder={`Course ${index + 1}`}
                    placeholderTextColor={colors.bodyText}
                    value={row.courseName}
                    onChangeText={(v) =>
                      updateRow(row.id, "courseName", v)
                    }
                    style={styles.input}
                  />

                  <View style={styles.pickerWrap}>
                    <Picker
                      selectedValue={row.credits}
                      onValueChange={(v) =>
                        updateRow(row.id, "credits", v)
                      }
                      numberOfLines={1}
                    >
                      <Picker.Item label="Credits" value="" />
                      {CREDIT_OPTIONS.map((c) => (
                        <Picker.Item key={c} label={c} value={c} />
                      ))}
                    </Picker>
                  </View>

                  <View style={styles.pickerWrap}>
                    <Picker
                      selectedValue={row.grade}
                      onValueChange={(v) =>
                        updateRow(row.id, "grade", v)
                      }
                      numberOfLines={1}
                    >
                      <Picker.Item label="Grade" value="" />
                      {GRADES.map((g) => (
                        <Picker.Item key={g} label={g} value={g} />
                      ))}
                    </Picker>
                  </View>

                  <Pressable onPress={() => removeRow(row.id)}>
                    <Feather name="trash-2" size={16} color={colors.bodyText} />
                  </Pressable>
                </View>
              ))}
            </>
          )}

          {/* Result — below form on narrow screens */}
          {!isWide && resultCard}

          {/* Result — positioned just right of form on wide screens */}
          {isWide && result && (
            <View style={styles.sideColumn}>
              <View style={styles.resultCard}>
                <Text style={styles.resultTitle}>Predicted GPA</Text>
                <Text style={styles.resultSubtitle}>
                  Based on the courses entered.
                </Text>
                <Text style={styles.gpa}>{result.gpa.toFixed(2)}</Text>
                <Text style={styles.resultSub}>
                  Total Credits: {result.totalCredits}
                </Text>
              </View>
            </View>
          )}
        </View>
      )}

      {/* TRANSCRIPT UPLOAD */}
      {mode === "transcript" && (
        <View style={styles.contentWrap}>
          <TranscriptUploadScreen
            onBack={() => switchMode("manual")}
            onAuthError={onAuthError}
            onUploadSuccess={fetchPastCourses}
          />
        </View>
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {
    padding: spacing.lg,
    backgroundColor: colors.background,
  },
  header: {
    alignItems: "center",
    marginBottom: spacing.lg,
  },
  title: {
    fontSize: 24,
    fontWeight: "700",
    color: colors.boldText,
    textAlign: "center",
    marginBottom: 6,
  },
  subtitle: {
    color: colors.bodyText,
    fontSize: 14,
    textAlign: "center",
  },
  contentWrap: {
    maxWidth: FORM_MAX_WIDTH,
    width: "100%",
    alignSelf: "center",
    overflow: "visible",
  },
  toggle: {
    flexDirection: "row",
    backgroundColor: colors.border,
    borderRadius: 12,
    padding: 4,
    marginBottom: spacing.lg,
    position: "relative",
    overflow: "hidden",
  },
  tabIndicator: {
    position: "absolute",
    top: 4,
    bottom: 4,
    backgroundColor: "white",
    borderRadius: 8,
    boxShadow: "0px 1px 3px rgba(0, 0, 0, 0.1)",
    elevation: 2,
  },
  tabButton: {
    flex: 1,
    alignItems: "center",
    padding: spacing.sm,
    zIndex: 1,
  },
  activeTabText: {
    color: "#260D19",
    fontWeight: "600",
    fontSize: 14,
  },
  inactiveTabText: {
    color: colors.bodyText,
    fontSize: 14,
  },
  sideColumn: {
    position: "absolute",
    left: "100%",
    top: 0,
    marginLeft: spacing.lg,
    width: 220,
  },
  topActions: {
    flexDirection: "row",
    justifyContent: "center",
    alignItems: "center",
    gap: 12,
    marginBottom: spacing.lg,
  },
  loadingText: {
    textAlign: "center",
    color: colors.bodyText,
    fontSize: 14,
    marginVertical: spacing.lg,
  },
  row: {
    flexDirection: "row",
    alignItems: "center",
    backgroundColor: "white",
    padding: spacing.md,
    borderRadius: 12,
    marginBottom: spacing.sm,
    boxShadow: "0px 1px 4px rgba(0, 0, 0, 0.06)",
    elevation: 1,
  },
  input: {
    flex: 1,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: 10,
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: 14,
    color: colors.boldText,
    backgroundColor: colors.surface,
    marginRight: spacing.sm,
  },
  pickerWrap: {
    flex: 1,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: 10,
    backgroundColor: colors.surface,
    marginRight: spacing.sm,
    height: 42,
    justifyContent: "center",
    overflow: "hidden",
  },
  add: {
    flexDirection: "row",
    alignItems: "center",
    borderWidth: 1,
    borderStyle: "dashed",
    borderColor: colors.border,
    borderRadius: 10,
    paddingVertical: 10,
    paddingHorizontal: 16,
  },
  addText: {
    fontWeight: "500",
    fontSize: 13,
    color: colors.bodyText,
  },
  calculate: {
    flexDirection: "row",
    alignItems: "center",
    backgroundColor: colors.primary,
    paddingVertical: 10,
    paddingHorizontal: 20,
    borderRadius: 10,
  },
  calculateText: {
    color: "white",
    fontWeight: "500",
    fontSize: 14,
  },
  resultCard: {
    backgroundColor: colors.primary,
    padding: spacing.lg,
    borderRadius: 16,
    alignItems: "center",
    marginTop: spacing.md,
    boxShadow: "0px 4px 12px rgba(0, 0, 0, 0.15)",
    elevation: 4,
  },
  resultTitle: {
    color: "white",
    fontWeight: "700",
    fontSize: 16,
    marginBottom: 4,
    alignSelf: "flex-start",
  },
  resultSubtitle: {
    color: "rgba(255,255,255,0.7)",
    fontSize: 13,
    marginBottom: spacing.md,
    alignSelf: "flex-start",
  },
  gpa: {
    color: "white",
    fontSize: 48,
    fontWeight: "800",
    marginBottom: 4,
  },
  resultSub: {
    color: "rgba(255,255,255,0.75)",
    fontSize: 13,
    marginTop: 6,
  },
});
