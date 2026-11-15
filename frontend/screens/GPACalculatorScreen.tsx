import {
  View,
  Pressable,
  StyleSheet,
  ScrollView,
  Modal,
  ActivityIndicator,
  useWindowDimensions,
} from "react-native";
import { Text, TextInput } from "../src/ui/Text";
import { noHover } from "../src/ui/hover";
import { Picker } from "@react-native-picker/picker";
import { useState, useEffect, useRef } from "react";
import Animated, {
  useSharedValue,
  useAnimatedStyle,
  withTiming,
  Easing,
} from "react-native-reanimated";
import { Feather } from "@expo/vector-icons";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { colors } from "../src/theme/colors";
import { spacing, radius } from "../src/theme/spacing";
import { qk } from "../src/cache/keys";
import { authedFetch, authedGet, isAuthError, queryRetry } from "../src/cache/authedGet";
import TranscriptUploadScreen from "./TranscriptUploadScreen";
import { fonts, typo } from "../src/theme/typography";

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

  const queryClient = useQueryClient();
  // Reopen on the transcript tab when an upload from an earlier visit is still
  // running or holds a result, so leaving and coming back lands on it.
  const [mode, setMode] = useState<"manual" | "transcript">(() =>
    queryClient.getMutationCache().find({ mutationKey: qk.transcriptGpa })
      ? "transcript"
      : "manual"
  );
  const [rows, setRows] = useState<CourseRow[]>([
    { id: "1", courseName: "", credits: "", grade: "A" },
  ]);

  const seededRows = useRef(false);

  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<{
    gpa: number;
    totalCredits: number;
  } | null>(null);

  const tabPosition = useSharedValue(0);
  const [tabWidth, setTabWidth] = useState(0);

  const animatedIndicatorStyle = useAnimatedStyle(() => ({
    transform: [{ translateX: tabPosition.value }],
  }));

  // The indicator starts at the manual tab. Move it once the width is known if we opened on transcript.
  useEffect(() => {
    if (mode === "transcript" && tabWidth) tabPosition.value = tabWidth / 2;
  }, [tabWidth]);

  const switchMode = (next: "manual" | "transcript") => {
    setMode(next);
    tabPosition.value = withTiming(
      next === "manual" ? 0 : tabWidth / 2,
      { duration: 250, easing: Easing.out(Easing.cubic) }
    );
  };

  const pastCoursesQuery = useQuery({
    queryKey: qk.pastCourses,
    queryFn: () =>
      authedGet<{ courseName: string; credits: string; grade: string }[]>(
        "/api/my-courses/past-courses"
      ),
    staleTime: 5 * 60_000,
    retry: queryRetry,
  });

  const loadingCourses = pastCoursesQuery.isPending;

  useEffect(() => {
    if (isAuthError(pastCoursesQuery.error)) onAuthError();
  }, [pastCoursesQuery.error, onAuthError]);

  // `rows` is an editable form, so seed it only once per mount. A later
  // background refetch must not overwrite what the user is typing.
  useEffect(() => {
    if (seededRows.current) return;
    const data = pastCoursesQuery.data;
    if (!data) return;
    seededRows.current = true;

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
  }, [pastCoursesQuery.data]);

  // A transcript upload rewrites the course list wholesale, so drop every
  // cached read and let the form re-seed from the new data.
  const handleTranscriptUploaded = () => {
    seededRows.current = false;
    queryClient.invalidateQueries();
  };

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
      const encoded = encodeURIComponent(row.courseName);
      const res = await authedFetch(
        `/api/my-courses/past-courses?courseName=${encoded}`,
        { method: "DELETE" }
      );
      if (res.ok) {
        setRows((prev) => prev.filter((r) => r.id !== id));
        // Deleting a past course changes the GPA the dashboard shows.
        queryClient.invalidateQueries({ queryKey: qk.pastCourses });
        queryClient.invalidateQueries({ queryKey: qk.dashboard });
      }
    } catch (err) {
      if (isAuthError(err)) onAuthError();
    }
    finally {
      setDeletingRow(null);
    }
  };

  const [confirmClearVisible, setConfirmClearVisible] = useState(false);
  const [clearing, setClearing] = useState(false);
  const [clearError, setClearError] = useState<string | null>(null);
  const hasCourses = rows.some((r) => r.courseName || r.credits);

  const openConfirmClear = () => {
    setClearError(null);
    setConfirmClearVisible(true);
  };

  const closeConfirmClear = () => {
    if (clearing) return;
    setConfirmClearVisible(false);
  };

  // One request for the whole list. Deleting row by row spent a request per course and ran
  // into the rate limit on a long transcript.
  const clearAllRows = async () => {
    setClearing(true);
    setClearError(null);
    try {
      const res = await authedFetch("/api/my-courses/past-courses/all", { method: "DELETE" });
      if (!res.ok) {
        setClearError("Couldn't delete your courses. Please try again.");
        return;
      }
      setRows([{ id: Date.now().toString(), courseName: "", credits: "", grade: "A" }]);
      setResult(null);
      setConfirmClearVisible(false);
      queryClient.invalidateQueries({ queryKey: qk.pastCourses });
      queryClient.invalidateQueries({ queryKey: qk.dashboard });
    } catch (err) {
      if (isAuthError(err)) {
        setConfirmClearVisible(false);
        onAuthError();
        return;
      }
      setClearError("Couldn't delete your courses. Please try again.");
    } finally {
      setClearing(false);
    }
  };

  const calculateGPA = async () => {
    setLoading(true);

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

    try {
      const res = await authedFetch("/api/manual-upload/calculate-gpa", {
        method: "POST",
        body: JSON.stringify(courseDtos),
      });
      const data = await res.json();
      setResult(data);
    } catch (err) {
      if (isAuthError(err)) onAuthError();
    } finally {
      setLoading(false);
    }
  };

  const resultCard = result ? (
    <View style={styles.resultCard}>
      <Text style={styles.resultTitle}>Predicted GPA</Text>
      <Text style={styles.gpa}>{result.gpa.toFixed(2)}</Text>
      <Text style={styles.resultSub}>
        {result.totalCredits} credits
      </Text>
    </View>
  ) : null;

  return (
    <ScrollView ref={scrollRef} contentContainerStyle={styles.container}>
      <View style={styles.header}>
        <Text style={styles.title}>GPA Calculator</Text>
        <Text style={styles.subtitle}>
          Enter your courses to work out your cumulative GPA.
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
              <Feather name="bar-chart-2" size={15} color={colors.onField} style={{ marginRight: 6 }} />
              <Text style={styles.calculateText}>
                {loading ? "Calculating..." : "Calculate GPA"}
              </Text>
            </Pressable>

            {hasCourses && !loadingCourses && (
              <Pressable style={styles.add} onPress={openConfirmClear}>
                <Feather name="trash-2" size={15} color={colors.danger} style={{ marginRight: 4 }} />
                <Text style={styles.deleteAllText}>Delete All</Text>
              </Pressable>
            )}
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
                    placeholderTextColor={colors.placeholder}
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
                      style={styles.picker}
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
                      style={styles.picker}
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
            <View style={styles.sideColumn}>{resultCard}</View>
          )}
        </View>
      )}

      <Modal
        visible={confirmClearVisible}
        transparent
        animationType="fade"
        onRequestClose={closeConfirmClear}
      >
        <Pressable {...noHover} style={styles.modalOverlay} onPress={closeConfirmClear}>
          <Pressable {...noHover} style={styles.modalContent} onPress={() => {}}>
            <Text style={styles.modalTitle}>Delete all courses?</Text>
            <Text style={styles.modalSubtitle}>
              This removes every course in the calculator and the past courses saved to your
              account. Your dashboard GPA will update to match. This can't be undone.
            </Text>
            {clearError && <Text style={styles.modalError}>{clearError}</Text>}
            <View style={styles.modalActions}>
              <Pressable
                style={styles.cancelButton}
                onPress={closeConfirmClear}
                disabled={clearing}
              >
                <Text style={styles.cancelText}>Cancel</Text>
              </Pressable>
              <Pressable
                style={[styles.dangerButton, clearing && styles.disabledButton]}
                onPress={clearAllRows}
                disabled={clearing}
              >
                {clearing ? (
                  <ActivityIndicator size="small" color={colors.onField} />
                ) : (
                  <Text style={styles.dangerButtonText}>Delete All</Text>
                )}
              </Pressable>
            </View>
          </Pressable>
        </Pressable>
      </Modal>

      {/* TRANSCRIPT UPLOAD */}
      {mode === "transcript" && (
        <View style={styles.contentWrap}>
          <TranscriptUploadScreen
            onBack={() => switchMode("manual")}
            onAuthError={onAuthError}
            onUploadSuccess={handleTranscriptUploaded}
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
    ...typo.h1,
    color: colors.boldText,
    textAlign: "center",
    marginBottom: 6,
  },
  subtitle: {
    ...typo.bodyLarge,
    color: colors.bodyText,
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
    borderRadius: radius.md,
    padding: 4,
    marginBottom: spacing.lg,
    position: "relative",
    overflow: "hidden",
  },
  tabIndicator: {
    position: "absolute",
    top: 4,
    bottom: 4,
    backgroundColor: colors.surface,
    borderRadius: radius.sm,
  },
  tabButton: {
    flex: 1,
    alignItems: "center",
    padding: spacing.sm,
    zIndex: 1,
  },
  activeTabText: {
    ...typo.label,
    color: colors.boldText,
    fontFamily: fonts.display,
  },
  inactiveTabText: {
    ...typo.label,
    color: colors.bodyText,
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
    flexWrap: "wrap",
    justifyContent: "center",
    alignItems: "center",
    gap: 12,
    marginBottom: spacing.lg,
  },
  loadingText: {
    ...typo.body,
    textAlign: "center",
    color: colors.bodyText,
    marginVertical: spacing.lg,
  },
  row: {
    flexDirection: "row",
    alignItems: "center",
    backgroundColor: colors.surface,
    padding: spacing.md,
    borderRadius: radius.md,
    marginBottom: spacing.sm,
    borderWidth: 1,
    borderColor: colors.borderSubtle,
  },
  input: {
    ...typo.body,
    flex: 1,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.md,
    paddingHorizontal: 12,
    paddingVertical: 10,
    color: colors.boldText,
    backgroundColor: colors.surface,
    marginRight: spacing.sm,
  },
  pickerWrap: {
    flex: 1,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.md,
    backgroundColor: colors.surface,
    marginRight: spacing.sm,
    height: 42,
    justifyContent: "center",
    overflow: "hidden",
  },
  picker: {
    ...typo.data,
    color: colors.boldText,
    borderWidth: 0,
    backgroundColor: "transparent",
  },
  add: {
    flexDirection: "row",
    alignItems: "center",
    borderWidth: 1,
    borderStyle: "dashed",
    borderColor: colors.border,
    borderRadius: radius.md,
    paddingVertical: 10,
    paddingHorizontal: 16,
  },
  addText: {
    ...typo.label,
    color: colors.bodyText,
  },
  calculate: {
    flexDirection: "row",
    alignItems: "center",
    backgroundColor: colors.primary,
    paddingVertical: 10,
    paddingHorizontal: 20,
    borderRadius: radius.md,
  },
  calculateText: {
    ...typo.label,
    color: colors.onField,
    fontFamily: fonts.display,
  },
  deleteAllText: {
    ...typo.label,
    color: colors.danger,
  },
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
  modalError: {
    ...typo.body,
    color: colors.danger,
    marginBottom: spacing.md,
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
  dangerButton: {
    backgroundColor: colors.danger,
    paddingVertical: 10,
    paddingHorizontal: 20,
    borderRadius: radius.md,
    minWidth: 110,
    alignItems: "center",
  },
  dangerButtonText: {
    ...typo.label,
    color: colors.onField,
    fontFamily: fonts.display,
  },
  disabledButton: {
    opacity: 0.6,
  },
  resultCard: {
    backgroundColor: colors.primary,
    padding: spacing.lg,
    borderRadius: radius.lg,
    alignItems: "center",
    marginTop: spacing.md,
  },
  resultTitle: {
    ...typo.h3,
    color: colors.onField,
    marginBottom: spacing.sm,
    alignSelf: "flex-start",
  },
  gpa: {
    ...typo.display,
    color: colors.onField,
    fontSize: 48,
    marginBottom: 4,
    lineHeight: 52,
    fontVariant: ["tabular-nums"],
  },
  resultSub: {
    ...typo.caption,
    color: colors.onFieldMuted,
    marginTop: 6,
    fontVariant: ["tabular-nums"],
  },
});
