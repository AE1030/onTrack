import {
  View,
  Text,
  TextInput,
  Pressable,
  StyleSheet,
  ScrollView,
  ActivityIndicator,
  FlatList,
  Platform,
  useWindowDimensions,
} from "react-native";
import { useEffect, useRef, useState } from "react";
import * as DocumentPicker from "expo-document-picker";
import Animated, {
  useSharedValue,
  useAnimatedStyle,
  withTiming,
  withDelay,
  withSequence,
  runOnJS,
  Easing,
} from "react-native-reanimated";
import { Feather } from "@expo/vector-icons";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { colors } from "../src/theme/colors";
import { spacing } from "../src/theme/spacing";
import { getToken } from "../src/utils/tokenStorage";
import { API_BASE_URL } from "../src/config/api";
import { qk } from "../src/cache/keys";
import { authedFetch, authedGet, isAuthError, queryRetry } from "../src/cache/authedGet";
import { useCourseSearch } from "../src/hooks/useCourseSearch";
import { useTerm } from "../src/terms/TermContext";
import TermPicker from "./components/TermPicker";
import CourseCalculatorScreen from "./CourseCalculatorScreen";

const GRADE_REGEX = /^\d{0,3}(\.\d{0,2})?$/;
const MAX_SYLLABUS_BYTES = 10 * 1024 * 1024;
const UPLOAD_COOLDOWN_MS = 20_000;


type CourseRow = {
  id: string;
  courseCode: string;
  grade: string;
};

type CurrentCourseDTO = {
  courseName: string;
  credits: string;
  grade: number | null;
  term: string;
  courseCode: string;
  includeInGpa: boolean;
  /** False for every course in a past term. The server decides this, not the client. */
  editable: boolean;
};

type Props = {
  onAuthError: () => void;
  onBack: () => void;
  highlightDropdowns?: boolean;
  onHighlightComplete?: () => void;
};

type SyllabusJobResponse = {
  id: string;
  courseCode: string;
  term: string;
  status: string;
  errorType: string | null;
  error: string | null;
  remainingUploads: number | null;
  createdAt: string;
  updatedAt: string;
};

type UploadState = {
  uploading: boolean;
  error: string | null;
  job: SyllabusJobResponse | null;
  remainingUploads: number | null;
  cooldownUntil: number | null;
};

const isValidGradeInput = (value: string) => {
  if (value === "") return true;
  if (value === ".") return false;
  if (!GRADE_REGEX.test(value)) return false;
  const num = Number(value);
  if (Number.isNaN(num)) return false;
  return num >= 0 && num <= 100;
};

const COMPACT_BREAKPOINT = 600;

export default function MyCoursesScreen({ onAuthError, onBack, highlightDropdowns, onHighlightComplete }: Props) {
  const { width } = useWindowDimensions();
  const isCompact = width < COMPACT_BREAKPOINT;
  const queryClient = useQueryClient();
  // The term list and the setter live in TermPicker now; this screen only needs to know which
  // term it is showing and whether that term accepts writes.
  const { selectedTerm, editable: termEditable } = useTerm();
  const [deletingCourse, setDeletingCourse] = useState<string | null>(null);
  const [showAddCourses, setShowAddCourses] = useState(false);
  const [mode, setMode] = useState<"manual" | "transcript">("manual");
  const [rows, setRows] = useState<CourseRow[]>([
    { id: "1", courseCode: "", grade: "" },
  ]);
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [fileName, setFileName] = useState<string | null>(null);
  const [activeRowId, setActiveRowId] = useState<string | null>(
    null
  );
  const [query, setQuery] = useState("");
  const [expandedCourses, setExpandedCourses] = useState<Record<string, boolean>>(
    {}
  );
  const [bestGradesByCourse, setBestGradesByCourse] = useState<
    Record<string, number>
  >({});
  const [uploadStateByCourse, setUploadStateByCourse] = useState<
    Record<string, UploadState>
  >({});
  const [globalRemainingUploads, setGlobalRemainingUploads] = useState<
    number | null
  >(null);
  const [externalResponseByCourse, setExternalResponseByCourse] = useState<
    Record<string, any>
  >({});
  const [externalVersionByCourse, setExternalVersionByCourse] = useState<
    Record<string, number>
  >({});
  const [autoSaveVersionByCourse, setAutoSaveVersionByCourse] = useState<
    Record<string, number>
  >({});
  const [assessmentLoadingByCourse, setAssessmentLoadingByCourse] = useState<
    Record<string, boolean>
  >({});
  const [includeInGpaByCourse, setIncludeInGpaByCourse] = useState<
    Record<string, boolean>
  >({});
  const pollingRefs = useRef<Record<string, ReturnType<typeof setInterval> | null>>(
    {}
  );
  const assessmentLoadingRef = useRef<Record<string, boolean>>({});

  const scrollRef = useRef<ScrollView>(null);
  const tabPosition = useSharedValue(0);
  const [tabWidth, setTabWidth] = useState(0);
  const dropdownHighlight = useSharedValue(0);

  const animatedIndicatorStyle = useAnimatedStyle(() => ({
    transform: [{ translateX: tabPosition.value }],
  }));

  const dropdownHighlightStyle = useAnimatedStyle(() => ({
    backgroundColor: dropdownHighlight.value > 0
      ? `rgba(122, 0, 60, ${dropdownHighlight.value * 0.12})`
      : "transparent",
    transform: [{ scale: 1 + dropdownHighlight.value * 0.03 }],
  }));

  const savedOpacity = useSharedValue(0);
  const savedAnimatedStyle = useAnimatedStyle(() => ({
    opacity: savedOpacity.value,
  }));
  const showSavedBanner = () => {
    savedOpacity.value = withSequence(
      withTiming(1, { duration: 200 }),
      withDelay(2500, withTiming(0, { duration: 400 }))
    );
  };

  const uploadSuccessOpacity = useSharedValue(0);
  const uploadSuccessAnimatedStyle = useAnimatedStyle(() => ({
    opacity: uploadSuccessOpacity.value,
  }));
  const showUploadSuccessBanner = () => {
    uploadSuccessOpacity.value = withSequence(
      withTiming(1, { duration: 200 }),
      withDelay(2500, withTiming(0, { duration: 400 }))
    );
  };

  // Same key the dashboard uses — whichever screen mounts first pays for the
  // fetch and the other reads it from cache. Keyed by term, so switching terms is a
  // different entry rather than a refetch over the top of the old one.
  const currentCoursesQuery = useQuery({
    queryKey: qk.currentCourses(selectedTerm),
    queryFn: () =>
      authedGet<CurrentCourseDTO[]>(
        `/api/my-courses/current-courses?term=${encodeURIComponent(selectedTerm)}`
      ),
    staleTime: 5 * 60_000,
    retry: queryRetry,
    // Nothing to ask for until the term list has resolved which term we are on.
    enabled: !!selectedTerm,
  });

  const currentCourses = currentCoursesQuery.data ?? [];
  const loadingCourses = currentCoursesQuery.isPending;
  const coursesError =
    currentCoursesQuery.error && !isAuthError(currentCoursesQuery.error)
      ? "Failed to load courses"
      : null;

  useEffect(() => {
    if (isAuthError(currentCoursesQuery.error)) onAuthError();
  }, [currentCoursesQuery.error, onAuthError]);

  // Derive the per-course toggle and grade maps from whatever the query holds.
  useEffect(() => {
    const data = currentCoursesQuery.data;
    if (!data) return;

    const gpaMap: Record<string, boolean> = {};
    const gradeMap: Record<string, number> = {};
    data.forEach((c, i) => {
      const k = `${c.courseCode}-${c.term}-${i}`;
      gpaMap[k] = c.includeInGpa;
      if (c.grade != null) {
        gradeMap[k] = c.grade;
      }
    });
    setIncludeInGpaByCourse(gpaMap);
    setBestGradesByCourse((prev) => ({ ...prev, ...gradeMap }));
  }, [currentCoursesQuery.data]);

  useEffect(() => {
    if (highlightDropdowns && !loadingCourses && currentCourses.length > 0) {
      const pulse = withDelay(
        400,
        withSequence(
          withTiming(1, { duration: 400, easing: Easing.out(Easing.quad) }),
          withTiming(0, { duration: 400, easing: Easing.in(Easing.quad) }),
          withTiming(1, { duration: 400, easing: Easing.out(Easing.quad) }),
          withTiming(0, { duration: 400, easing: Easing.in(Easing.quad) })
        )
      );
      dropdownHighlight.value = pulse;
      const timer = setTimeout(() => {
        onHighlightComplete?.();
      }, 2200);
      return () => clearTimeout(timer);
    }
  }, [highlightDropdowns, loadingCourses, currentCourses.length]);

  const switchMode = (next: "manual" | "transcript") => {
    setMode(next);
    tabPosition.value = withTiming(
      next === "manual" ? 0 : tabWidth / 2,
      { duration: 250, easing: Easing.out(Easing.cubic) }
    );
  };

  const {
    results,
    loading: searching,
    canSearch,
    loadNext,
    reset,
  } = useCourseSearch({
    query,
    baseUrl: API_BASE_URL,
    onAuthError,
    debounceMs: 250,
  });

  const addRow = () =>
    setRows((prev) => [
      ...prev,
      { id: Date.now().toString(), courseCode: "", grade: "" },
    ]);

  const updateRow = (
    id: string,
    field: keyof CourseRow,
    value: string
  ) => {
    if (field === "grade" && !isValidGradeInput(value)) {
      return;
    }

    savedOpacity.value = 0;

    setRows((prev) =>
      prev.map((r) => (r.id === id ? { ...r, [field]: value } : r))
    );

    if (field === "courseCode") {
      setActiveRowId(id);
      setQuery(value);
      if (value.trim().length < 2) {
        reset();
      }
    }
  };

  const selectCourse = (id: string, courseCode: string) => {
    setRows((prev) =>
      prev.map((r) =>
        r.id === id ? { ...r, courseCode } : r
      )
    );
    setActiveRowId(null);
    setQuery("");
    reset();
  };

  const removeRow = (id: string) =>
    setRows((prev) => prev.filter((r) => r.id !== id));

  const getUploadState = (key: string): UploadState => {
    return (
      uploadStateByCourse[key] ?? {
        uploading: false,
        error: null,
        job: null,
        remainingUploads: null,
        cooldownUntil: null,
      }
    );
  };

  const updateUploadState = (key: string, patch: Partial<UploadState>) => {
    setUploadStateByCourse((prev) => {
      const current =
        prev[key] ?? {
          uploading: false,
          error: null,
          job: null,
          remainingUploads: null,
          cooldownUntil: null,
        };
      return {
        ...prev,
        [key]: { ...current, ...patch },
      };
    });
  };

  const setUploadCooldown = (key: string) => {
    const until = Date.now() + UPLOAD_COOLDOWN_MS;
    updateUploadState(key, { cooldownUntil: until });
    setTimeout(() => {
      setUploadStateByCourse((prev) => {
        const current =
          prev[key] ?? {
            uploading: false,
            error: null,
            job: null,
            remainingUploads: null,
            cooldownUntil: null,
          };
        if (current.cooldownUntil !== until) return prev;
        return {
          ...prev,
          [key]: { ...current, cooldownUntil: null },
        };
      });
    }, UPLOAD_COOLDOWN_MS);
  };

  /**
   * The saved assessment table, falling back to the extracted syllabus
   * assessments when the student has not saved one yet.
   */
  const fetchAssessmentTable = async (courseCode: string, term: string) => {
    const encodedCourse = encodeURIComponent(courseCode);
    const encodedTerm = encodeURIComponent(term);

    const assessmentRes = await authedFetch(
      `/api/assessment-table/get-assessment-table?courseCode=${encodedCourse}&term=${encodedTerm}`
    );

    if (assessmentRes.status !== 404) {
      if (!assessmentRes.ok) throw new Error("Failed to load assessment table");
      const raw = await assessmentRes.text();
      return raw ? JSON.parse(raw) : null;
    }

    const syllabusRes = await authedFetch(
      `/api/syllabus/assessments?courseCode=${encodedCourse}&term=${encodedTerm}`
    );

    // Nothing uploaded for this course yet — a normal empty state.
    if (syllabusRes.status === 404) return null;
    if (!syllabusRes.ok) throw new Error("Failed to load syllabus assessments");

    const raw = await syllabusRes.text();
    return raw ? JSON.parse(raw) : null;
  };

  const loadAssessmentTableFlow = async (
    key: string,
    courseCode: string,
    term: string
  ) => {
    if (assessmentLoadingByCourse[key]) return;

    setAssessmentLoadingByCourse((prev) => ({
      ...prev,
      [key]: true,
    }));

    try {
      // Reads through the cache — no network call while the entry is fresh.
      // Invalidated on assessment-table save and on syllabus upload completion.
      const data = await queryClient.fetchQuery({
        queryKey: qk.assessmentTable(courseCode, term),
        queryFn: () => fetchAssessmentTable(courseCode, term),
        staleTime: 5 * 60_000,
        retry: queryRetry,
      });

      setExternalResponseByCourse((prev) => ({
        ...prev,
        [key]: data,
      }));
      setExternalVersionByCourse((prev) => ({
        ...prev,
        [key]: (prev[key] ?? 0) + 1,
      }));
    } catch (err) {
      if (isAuthError(err)) {
        onAuthError();
        return;
      }
      if (externalResponseByCourse[key] === undefined) {
        setExternalResponseByCourse((prev) => ({
          ...prev,
          [key]: null,
        }));
        setExternalVersionByCourse((prev) => ({
          ...prev,
          [key]: (prev[key] ?? 0) + 1,
        }));
      }
    } finally {
      setAssessmentLoadingByCourse((prev) => ({
        ...prev,
        [key]: false,
      }));
    }
  };

  const fetchRemainingUploads = async (token?: string) => {
    const authToken = token ?? (await getToken());
    if (!authToken) {
      onAuthError();
      return;
    }

    try {
      const res = await fetch(
        `${API_BASE_URL.replace(/\/$/, "")}/api/my-courses/remaining-uploads`,
        {
          headers: {
            Authorization: `Bearer ${authToken}`,
          },
        }
      );

      if (res.status === 401) {
        onAuthError();
        return;
      }

      if (!res.ok) {
        return;
      }

      const value = await res.json();
      const remaining =
        typeof value === "number" ? value : Number(value);
      if (!Number.isNaN(remaining)) {
        setGlobalRemainingUploads(remaining);
      }
    } catch {
      // Ignore; keep prior value
    }
  };

  const fetchUploadedAssessments = async (
    key: string,
    courseCode: string,
    term: string
  ): Promise<boolean> => {
    const token = await getToken();
    if (!token) {
      onAuthError();
      return false;
    }

    const encodedCourse = encodeURIComponent(courseCode);
    const encodedTerm = encodeURIComponent(term);
    const res = await fetch(
      `${API_BASE_URL.replace(/\/$/, "")}/api/syllabus/assessments?courseCode=${encodedCourse}&term=${encodedTerm}`,
      {
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Type": "application/json",
        },
      }
    );

    if (res.status === 401) {
      onAuthError();
      return false;
    }

    if (!res.ok) {
      throw new Error("Failed to load uploaded assessments");
    }

    const raw = await res.text();
    const data = raw ? JSON.parse(raw) : null;
    setExternalResponseByCourse((prev) => ({
      ...prev,
      [key]: data,
    }));
    setExternalVersionByCourse((prev) => ({
      ...prev,
      [key]: (prev[key] ?? 0) + 1,
    }));
    // The extraction just replaced this course's table. Write the fresh result
    // straight into the cache rather than evicting and refetching it.
    queryClient.setQueryData(qk.assessmentTable(courseCode, term), data);
    return true;
  };

  const stopPolling = (key: string) => {
    const timer = pollingRefs.current[key];
    if (timer) clearInterval(timer);
    pollingRefs.current[key] = null;
  };

  const startPollingJob = async (
    key: string,
    jobId: string,
    courseCode: string,
    term: string
  ) => {
    stopPolling(key);
    pollingRefs.current[key] = setInterval(async () => {
      const token = await getToken();
      if (!token) {
        stopPolling(key);
        onAuthError();
        return;
      }

      try {
        const res = await fetch(
          `${API_BASE_URL.replace(/\/$/, "")}/api/syllabus/upload/${jobId}`,
          {
            headers: {
              Authorization: `Bearer ${token}`,
              "Content-Type": "application/json",
            },
          }
        );

        if (res.status === 401) {
          stopPolling(key);
          onAuthError();
          return;
        }

        if (!res.ok) {
          throw new Error("Failed to poll upload job");
        }

        const job: SyllabusJobResponse = await res.json();
        updateUploadState(key, {
          job,
          remainingUploads: job.remainingUploads ?? null,
          error: job.error ?? null,
        });

        if (job.error) {
          stopPolling(key);
          return;
        }

        if (job.status === "DONE") {
          stopPolling(key);
          if (job.remainingUploads != null) {
            setGlobalRemainingUploads(job.remainingUploads);
          }
          const success = await fetchUploadedAssessments(
            key,
            courseCode,
            term
          );
          if (success) {
            setAutoSaveVersionByCourse((prev) => ({
              ...prev,
              [key]: (prev[key] ?? 0) + 1,
            }));
          }
        }
      } catch (err: any) {
        updateUploadState(key, {
          error: err?.message ?? "Failed to poll upload job",
        });
        stopPolling(key);
      }
    }, 2000);
  };

  const uploadSyllabus = async (
    key: string,
    courseCode: string,
    term: string
  ) => {
    const current = getUploadState(key);
    if (current.cooldownUntil && Date.now() < current.cooldownUntil) return;

    updateUploadState(key, { uploading: true, error: null });
    setUploadCooldown(key);

    const token = await getToken();
    if (!token) {
      updateUploadState(key, { uploading: false });
      onAuthError();
      return;
    }

    const result = await DocumentPicker.getDocumentAsync({
      type: ["application/pdf"],
      copyToCacheDirectory: true,
    });

    if (result.canceled) {
      updateUploadState(key, { uploading: false });
      return;
    }

    const file = result.assets[0];
    const fileSize =
      file.size ??
      (Platform.OS === "web" && file.file ? (file.file as File).size : null);
    if (fileSize != null && fileSize > MAX_SYLLABUS_BYTES) {
      updateUploadState(key, {
        uploading: false,
        error: "File too large. Max 10MB.",
      });
      return;
    }

    const formData = new FormData();
    if (Platform.OS === "web") {
      formData.append("file", file.file as File);
    } else {
      formData.append("file", {
        uri: file.uri,
        name: file.name,
        type: file.mimeType ?? "application/pdf",
      } as any);
    }

    try {
      const encodedCourse = encodeURIComponent(courseCode);
      const encodedTerm = encodeURIComponent(term);
      const res = await fetch(
        `${API_BASE_URL.replace(/\/$/, "")}/api/syllabus/upload/submit?courseCode=${encodedCourse}&term=${encodedTerm}`,
        {
          method: "POST",
          headers: {
            Authorization: `Bearer ${token}`,
          },
          body: formData,
        }
      );

      if (res.status === 401) {
        onAuthError();
        return;
      }

      if (res.status === 403) {
        updateUploadState(key, {
          uploading: false,
          remainingUploads: 0,
          error: "Upload quota exhausted",
        });
        setGlobalRemainingUploads(0);
        return;
      }

      if (res.status === 429) {
        updateUploadState(key, {
          uploading: false,
          error: "Upload rate limit exceeded",
        });
        return;
      }

      if (!res.ok) {
        const errorText = await res.text();
        throw new Error(errorText || "Upload failed");
      }

      const job: SyllabusJobResponse = await res.json();
      updateUploadState(key, {
        uploading: false,
        job,
        remainingUploads: job.remainingUploads ?? null,
        error: job.error ?? null,
      });

      if (job.id) {
        startPollingJob(key, job.id, courseCode, term);
      }
    } catch (err: any) {
      updateUploadState(key, {
        uploading: false,
        error: err?.message ?? "Upload failed",
      });
    }
  };

  const toggleCourse = (
    key: string,
    courseCode: string,
    term: string
  ) => {
    setExpandedCourses((prev) => {
      const next = !prev[key];
      if (next) {
        loadAssessmentTableFlow(key, courseCode, term);
      }
      return {
        ...prev,
        [key]: next,
      };
    });
  };

  // The term list carries a course count, and deleting the last course in a term drops that
  // term from the picker entirely, so it is evicted alongside the course list.
  const refetchCurrentCourses = () => {
    queryClient.invalidateQueries({ queryKey: qk.currentCourses(selectedTerm) });
    queryClient.invalidateQueries({ queryKey: qk.terms });
  };

  const deleteCurrentCourse = async (courseCode: string, term: string) => {
    if (deletingCourse) return;
    setDeletingCourse(courseCode);
    try {
      const token = await getToken();
      if (!token) {
        onAuthError();
        return;
      }

      const encodedCourse = encodeURIComponent(courseCode);
      const encodedTerm = encodeURIComponent(term);
      const res = await fetch(
        `${API_BASE_URL.replace(/\/$/, "")}/api/my-courses/delete-course?courseCode=${encodedCourse}&term=${encodedTerm}`,
        {
          method: "DELETE",
          headers: {
            Authorization: `Bearer ${token}`,
          },
        }
      );

      if (res.status === 401 || res.status === 403) {
        onAuthError();
        return;
      }

      if (res.ok) {
        // Removing a course changes the GPA the dashboard shows.
        await refetchCurrentCourses();
        queryClient.invalidateQueries({ queryKey: qk.dashboard });
      }
    } finally {
      setDeletingCourse(null);
    }
  };

  // Toggling GPA inclusion changes the GPA in both directions, so this always
  // runs — unlike a grade edit, which only matters when the course counts.
  //
  // The dashboard is unmounted while the user is here, so invalidating marks it
  // stale and it refetches when they navigate back — one call on return rather
  // than one now plus one later.
  //
  // current-courses is marked stale WITHOUT refetching: this screen is observing
  // that key, and a refetch would overwrite the grade the user is still editing
  // by way of the sync effect above.
  const invalidateGpa = () => {
    queryClient.invalidateQueries({
      queryKey: qk.currentCourses(selectedTerm),
      refetchType: "none",
    });
    queryClient.invalidateQueries({ queryKey: qk.dashboard });
  };

  const toggleIncludeInGpa = async (courseCode: string, key: string, value: boolean) => {
    setIncludeInGpaByCourse((prev) => ({ ...prev, [key]: value }));
    try {
      await authedFetch("/api/my-courses/toggle-gpa", {
        method: "PUT",
        body: JSON.stringify({ courseCode, term: selectedTerm, includeInGpa: value }),
      });
      invalidateGpa();
    } catch {}
  };

  // The remaining-uploads quota is deliberately never cached — a stale count
  // would let a student exceed their limit.
  useEffect(() => {
    fetchRemainingUploads();
  }, []);

  useEffect(() => {
    return () => {
      Object.values(pollingRefs.current).forEach((timer) => {
        if (timer) clearInterval(timer);
      });
    };
  }, []);

  const uploadTranscript = async () => {
    setUploadError(null);
    uploadSuccessOpacity.value = 0;

    const token = await getToken();
    if (!token) {
      onAuthError();
      return;
    }

    const result = await DocumentPicker.getDocumentAsync({
      type: ["application/pdf", "image/*"],
      copyToCacheDirectory: true,
    });

    if (result.canceled) return;

    const file = result.assets[0];
    setFileName(file.name);
    setUploading(true);

    const formData = new FormData();
    if (Platform.OS === "web") {
      formData.append("file", file.file as File);
    } else {
      formData.append("file", {
        uri: file.uri,
        name: file.name,
        type: file.mimeType ?? "application/pdf",
      } as any);
    }

    try {
      const res = await fetch(
        `${API_BASE_URL.replace(/\/$/, "")}/api/transcript/add-current-courses`,
        {
          method: "POST",
          headers: {
            Authorization: `Bearer ${token}`,
          },
          body: formData,
        }
      );

      if (res.status === 401 || res.status === 403) {
        onAuthError();
        return;
      }

      if (!res.ok) {
        throw new Error("Upload failed");
      }

      showUploadSuccessBanner();
      // A transcript rewrites courses and GPA wholesale — drop every cached read.
      queryClient.invalidateQueries();
    } catch (err: any) {
      setUploadError(err?.message ?? "Upload failed");
    } finally {
      setUploading(false);
    }
  };

  const saveCourses = async () => {
    setLoading(true);
    savedOpacity.value = 0;

    const token = await getToken();
    if (!token) {
      setLoading(false);
      onAuthError();
      return;
    }

    const courseDtos = rows
      .map((row) => {
        const courseCode = row.courseCode.trim();
        const numericGrade = Number(row.grade);
        const grade =
          row.grade === "" || row.grade === "."
            ? null
            : Number.isNaN(numericGrade)
            ? null
            : numericGrade.toString();

        return { courseCode, grade };
      })
      .filter((c) => c.courseCode !== "");

    if (courseDtos.length === 0) {
      setLoading(false);
      return;
    }

    const res = await fetch(
      `${API_BASE_URL.replace(/\/$/, "")}/api/manual-upload/add-current-courses`,
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

    setLoading(false);
    if (res.ok) {
      showSavedBanner();
      setRows([{ id: Date.now().toString(), courseCode: "", grade: "" }]);
      // Adding courses changes the GPA the dashboard shows.
      queryClient.invalidateQueries();
    }
  };

  return (
    <View style={styles.screen}>
      {activeRowId && (
        <View style={styles.backdrop} pointerEvents="none" />
      )}
      <ScrollView
        ref={scrollRef}
        contentContainerStyle={styles.container}
        keyboardShouldPersistTaps="handled"
        onScrollBeginDrag={() => {
          if (activeRowId) {
            setActiveRowId(null);
            setQuery("");
            reset();
          }
        }}
      >

        <Text style={styles.title}>My Courses</Text>
        <Text style={styles.subtitle}>
          Manage your courses and ensure you're onTrack.
        </Text>

        {/* Always rendered, including for a student with one term: it is what names the term the
            list below belongs to, and hiding it made looking back undiscoverable until a student
            already had history. The arrows disable at the ends of the list instead. */}
        <TermPicker />

        {/* Said once, near the picker, rather than on every card below it. The picker above
            already names the term and marks it "View only", so this adds only what that cannot:
            what "view only" actually stops you doing. It also no longer calls the term "past",
            which was wrong for a term the student is enrolled in ahead of the current one. */}
        {!termEditable && selectedTerm !== "" && (
          <View style={styles.readOnlyNotice}>
            <Feather name="eye" size={14} color={colors.bodyText} />
            <Text style={styles.readOnlyNoticeText}>
              You can look, but not edit. Grades and courses are read only here.
            </Text>
          </View>
        )}

        {/* Adding a course writes an enrollment, so it belongs to the current term only. */}
        {termEditable && (
          <Pressable
            style={styles.addCoursesToggle}
            onPress={() => {
              setShowAddCourses((prev) => {
                if (!prev) {
                  setTimeout(() => {
                    scrollRef.current?.scrollToEnd({ animated: true });
                  }, 100);
                }
                return !prev;
              });
            }}
          >
            <Feather
              name={showAddCourses ? "x" : "plus"}
              size={18}
              color="white"
              style={{ marginRight: 6 }}
            />
            <Text style={styles.addCoursesToggleText}>
              {showAddCourses ? "Cancel" : "Add Courses"}
            </Text>
          </Pressable>
        )}

        {loadingCourses && (
          <View style={styles.loadingRow}>
            <ActivityIndicator size="small" color={colors.primary} />
          </View>
        )}

        {coursesError && (
          <Text style={styles.errorText}>{coursesError}</Text>
        )}

        {currentCourses.map((course, index) => {
          const key = `${course.courseCode}-${course.term}-${index}`;
          // The course's own term, as the server reported it. This used to be pinned to a
          // literal, which collapsed every term onto one cache entry.
          const effectiveTerm = course.term;
          // Asked of the course rather than worked out from the term, so a second editable
          // term would need no change here.
          const courseEditable = course.editable;
          const isExpanded = !!expandedCourses[key];
          const uploadState = getUploadState(key);
          const isAssessmentLoading = !!assessmentLoadingByCourse[key];
          const cached = externalResponseByCourse[key];
          const cooldownRemaining = uploadState.cooldownUntil
            ? Math.max(
                0,
                Math.ceil((uploadState.cooldownUntil - Date.now()) / 1000)
              )
            : 0;
          const showSyllabusPrompt = cached === null;
          const jobStatus = uploadState.job?.status ?? null;
          const isPolling =
            !!uploadState.job &&
            jobStatus !== "DONE" &&
            !uploadState.error;
          const statusProgress =
            jobStatus === "QUEUED"
              ? 0.25
              : jobStatus === "PROCESSING"
                ? 0.6
                : jobStatus === "FAILED"
                  ? 1
                  : jobStatus === "DONE"
                    ? 1
                    : 0;

          const bestGrade = bestGradesByCourse[key];

          return (
          <View
            key={key}
            style={styles.courseCard}
          >
            <View
              style={[
                styles.courseAccent,
                {
                  backgroundColor:
                    index % 2 === 0 ? "#7A003C" : "#F2B94A",
                },
              ]}
            />
            <View style={[styles.courseContent, isCompact && styles.courseContentCompact]}>
              {/* Deleting is editing, so a past term's card carries no delete control. */}
              {courseEditable && (
                <Pressable
                  style={[styles.deleteIcon, deletingCourse !== null && { opacity: 0.4 }]}
                  onPress={() => deleteCurrentCourse(course.courseCode, effectiveTerm)}
                  disabled={deletingCourse !== null}
                >
                  <Feather name="trash-2" size={16} color={colors.bodyText} />
                </Pressable>
              )}
              <View style={[styles.courseLeft, isCompact && styles.courseLeftCompact]}>
                <View style={styles.courseHeader}>
                  <Text style={styles.courseCode}>{course.courseCode}</Text>
                  <View style={styles.termPill}>
                    <Text style={styles.termText}>{effectiveTerm}</Text>
                  </View>
                </View>
                <Text style={styles.courseName}>{course.courseName}</Text>
                <Text style={styles.courseLink}>View Assignments & Grades</Text>
              </View>

              <View style={[styles.courseRight, isCompact && styles.courseRightCompact]}>
                <View style={styles.courseStat}>
                  <Text style={styles.courseStatLabel}>Current Grade</Text>
                  <Text style={styles.courseGrade}>
                    {bestGrade != null ? `${bestGrade.toFixed(2)}%` : "--"}
                  </Text>
                </View>
                <View style={[styles.courseDivider, isCompact && styles.courseDividerCompact]} />
                <View style={styles.courseStat}>
                  <Text style={styles.courseStatLabel}>Credits</Text>
                  <Text style={styles.courseCredits}>
                    {course.credits}
                  </Text>
                </View>
                <View style={[styles.courseDivider, isCompact && styles.courseDividerCompact]} />
                {/* Still shown for a past term, because whether a course counted is worth
                    seeing. Not pressable, because changing it is a write. */}
                <Pressable
                  style={styles.gpaPill}
                  disabled={!courseEditable}
                  onPress={() =>
                    toggleIncludeInGpa(
                      course.courseCode,
                      key,
                      !(includeInGpaByCourse[key] ?? true)
                    )
                  }
                >
                  <Text style={styles.gpaPillText}>
                    {(includeInGpaByCourse[key] ?? true) ? "In GPA" : "Not in GPA"}
                  </Text>
                </Pressable>
              </View>
            </View>

            <Animated.View style={[styles.dropdownToggleWrap, dropdownHighlightStyle]}>
              <Pressable
                style={styles.dropdownToggle}
                onPress={() =>
                  toggleCourse(
                    key,
                    course.courseCode,
                    effectiveTerm
                  )
                }
              >
                <Feather
                  name={isExpanded ? "chevron-up" : "chevron-down"}
                  size={18}
                  color={colors.bodyText}
                />
              </Pressable>
            </Animated.View>

            {isExpanded && (
              <View style={styles.courseCalculatorContainer}>
                {/* The whole syllabus panel is a write surface: uploading one replaces the
                    course's assessment table. A past term does not get it at all. */}
                {courseEditable && (
                <View style={styles.syllabusBox}>
                  {showSyllabusPrompt ? (
                    <>
                      <Text style={styles.syllabusTitle}>
                        Syllabus not found
                      </Text>
                      <Text style={styles.syllabusText}>
                        You can add everything manually below or upload a syllabus
                        to autofill the assessment table.
                      </Text>
                    </>
                  ) : (
                    <>
                      <Text style={styles.syllabusTitle}>
                        Upload Syllabus
                      </Text>
                      <Text style={styles.syllabusText}>
                        Upload a syllabus to autofill the assessment table.
                      </Text>
                    </>
                  )}
                  <Text style={styles.syllabusMeta}>
                    Remaining uploads:{" "}
                    {uploadState.remainingUploads ??
                      globalRemainingUploads ??
                      "--"}
                  </Text>

                  {isAssessmentLoading && (
                    <View style={styles.jobRow}>
                      <Text style={styles.syllabusMeta}>
                        Loading assessment data...
                      </Text>
                      <ActivityIndicator size="small" color={colors.primary} />
                    </View>
                  )}

                  {uploadState.error && (
                    <Text style={styles.syllabusError}>
                      {uploadState.error}
                    </Text>
                  )}

                  {uploadState.job && jobStatus !== "DONE" && (
                    <View style={styles.jobRow}>
                      <Text style={styles.syllabusMeta}>
                        Status: {uploadState.job.status}
                      </Text>
                      {isPolling && (
                        <ActivityIndicator size="small" color={colors.primary} />
                      )}
                    </View>
                  )}

                  {uploadState.job && jobStatus !== "DONE" && (
                    <View style={styles.progressTrack}>
                      <View
                        style={[
                          styles.progressFill,
                          {
                            width: `${Math.round(statusProgress * 100)}%`,
                            backgroundColor:
                              jobStatus === "FAILED"
                                ? "#c0392b"
                                : jobStatus === "DONE"
                                  ? "#2ecc71"
                                  : "#7A003C",
                          },
                        ]}
                      />
                    </View>
                  )}

                  <View style={styles.syllabusActions}>
                    <Pressable
                      style={[
                        styles.uploadButton,
                        (uploadState.uploading || cooldownRemaining > 0) &&
                          styles.uploadButtonDisabled,
                        ]}
                      onPress={() =>
                        uploadSyllabus(
                          key,
                          course.courseCode,
                          effectiveTerm
                        )
                      }
                      disabled={uploadState.uploading || cooldownRemaining > 0}
                      >
                        <Text style={styles.uploadButtonText}>
                          {uploadState.uploading
                            ? "Uploading..."
                            : "Upload Syllabus"}
                      </Text>
                    </Pressable>
                  </View>
                </View>
                )}
                <CourseCalculatorScreen
                  courseCode={course.courseCode}
                  term={effectiveTerm}
                  editable={courseEditable}
                  onAuthError={onAuthError}
                  variant="inline"
                  onBestGradeChange={(grade) => {
                    // Display only. The grade is persisted by the calculator's own
                    // save, together with the table rows it was computed from.
                    if (!Number.isFinite(grade)) return;
                    if (grade < 0 || grade > 100) return;

                    setBestGradesByCourse((prev) =>
                      prev[key] === grade ? prev : { ...prev, [key]: grade }
                    );
                  }}
                  externalResponse={externalResponseByCourse[key]}
                  externalVersion={externalVersionByCourse[key]}
                  skipFetch={true}
                  autoSaveVersion={autoSaveVersionByCourse[key]}
                  onSaved={() =>
                    queryClient.invalidateQueries({
                      queryKey: qk.assessmentTable(course.courseCode, effectiveTerm),
                    })
                  }
                />
              </View>
            )}
          </View>
        );
        })}

        {showAddCourses && (
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

            {mode === "manual" && (
              <>
                <View style={styles.topActions}>
                  <Pressable style={styles.add} onPress={addRow}>
                    <Feather name="plus" size={16} color={colors.bodyText} style={{ marginRight: 4 }} />
                    <Text style={styles.addText}>Add Course</Text>
                  </Pressable>

                  <Pressable
                    style={styles.save}
                    onPress={saveCourses}
                    disabled={loading}
                  >
                    <Feather name="save" size={15} color="white" style={{ marginRight: 6 }} />
                    <Text style={styles.saveText}>
                      {loading ? "Saving..." : "Save Courses"}
                    </Text>
                  </Pressable>
                </View>

                <Animated.View style={[styles.savedCard, savedAnimatedStyle]} pointerEvents="none">
                  <Feather name="check" size={13} color="#2E7D32" style={{ marginRight: 4 }} />
                  <Text style={styles.savedText}>Saved</Text>
                </Animated.View>

                {rows.map((row, index) => (
                  <View key={row.id} style={[styles.row, activeRowId === row.id && { zIndex: 10 }, activeRowId === row.id && canSearch && { paddingBottom: 230 }]}>
                    <View style={styles.inputWithDropdown}>
                      <TextInput
                        placeholder={`Course ${index + 1}`}
                        placeholderTextColor={colors.bodyText}
                        value={row.courseCode}
                        onChangeText={(v) =>
                          updateRow(row.id, "courseCode", v)
                        }
                        onFocus={() => {
                          setActiveRowId(row.id);
                          setQuery(row.courseCode);
                          if (row.courseCode.trim().length < 2) {
                            reset();
                          }
                        }}
                        onBlur={() => {
                          setTimeout(() => {
                            setActiveRowId((current) => {
                              if (current === row.id) {
                                setQuery("");
                                reset();
                                return null;
                              }
                              return current;
                            });
                          }, 150);
                        }}
                        style={styles.input}
                      />

                      {activeRowId === row.id && canSearch && (
                        <View style={styles.dropdown}>
                          {searching && results.length === 0 ? (
                            <View style={styles.loadingRow}>
                              <ActivityIndicator
                                size="small"
                                color={colors.primary}
                              />
                            </View>
                          ) : (
                            <FlatList
                              data={results}
                              keyExtractor={(item) => item.id}
                              nestedScrollEnabled
                              style={styles.dropdownList}
                              keyboardShouldPersistTaps="handled"
                              onEndReached={loadNext}
                              onEndReachedThreshold={0.6}
                              renderItem={({ item }) => (
                                <Pressable
                                  style={styles.dropdownItem}
                                  onPress={() =>
                                    selectCourse(row.id, item.courseCode)
                                  }
                                >
                                  <Text style={styles.dropdownTitle}>
                                    {item.courseCode}
                                  </Text>
                                  <Text style={styles.dropdownSub}>
                                    {item.courseName}
                                  </Text>
                                </Pressable>
                              )}
                              ListFooterComponent={
                                searching && results.length > 0 ? (
                                  <View style={styles.loadingRow}>
                                    <ActivityIndicator
                                      size="small"
                                      color={colors.primary}
                                    />
                                  </View>
                                ) : null
                              }
                            />
                          )}
                        </View>
                      )}
                    </View>

                    <TextInput
                      placeholder="Grade %"
                      placeholderTextColor={colors.bodyText}
                      value={row.grade}
                      onChangeText={(v) => updateRow(row.id, "grade", v)}
                      keyboardType="numeric"
                      style={styles.input}
                    />

                    <Pressable onPress={() => removeRow(row.id)}>
                      <Feather name="trash-2" size={16} color={colors.bodyText} />
                    </Pressable>
                  </View>
                ))}
              </>
            )}

            {mode === "transcript" && (
              <View style={styles.card}>
                <Text style={styles.cardTitle}>Upload Transcript</Text>
                <Text style={styles.cardSub}>
                  Upload your PDF transcript to automatically add your current
                  courses.
                </Text>

                <View style={styles.uploadBox}>
                  <View style={styles.iconCircle}>
                    <Feather name="upload" size={24} color={colors.primary} />
                  </View>

                  <Text style={styles.uploadTitle}>
                    Select your transcript file
                  </Text>

                  <Text style={styles.uploadSub}>
                    Supported formats: PDF, JPG, PNG
                  </Text>

                  <Pressable
                    style={styles.browseButton}
                    onPress={uploadTranscript}
                    disabled={uploading}
                  >
                    <Feather
                      name="file"
                      size={16}
                      color="white"
                      style={{ marginRight: 6 }}
                    />
                    <Text style={styles.browseText}>
                      {uploading ? "Uploading..." : "Browse Files"}
                    </Text>
                  </Pressable>

                  {fileName && (
                    <Text style={styles.fileName}>{fileName}</Text>
                  )}
                </View>

                <Animated.View style={[styles.savedCard, uploadSuccessAnimatedStyle]} pointerEvents="none">
                  <Feather name="check" size={13} color="#2E7D32" style={{ marginRight: 4 }} />
                  <Text style={styles.savedText}>Added</Text>
                </Animated.View>

                {uploadError && (
                  <Text style={styles.errorText}>{uploadError}</Text>
                )}
              </View>
            )}
          </View>
        )}

      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    position: "relative",
  },
  backdrop: {
    ...StyleSheet.absoluteFillObject,
    zIndex: 1,
  },
  container: {
    padding: spacing.lg,
    backgroundColor: colors.background,
  },
  title: {
    fontSize: 24,
    fontWeight: "700",
    marginBottom: 6,
    color: colors.boldText,
    textAlign: "center",
  },
  subtitle: {
    color: colors.bodyText,
    fontSize: 14,
    textAlign: "center",
    marginBottom: spacing.lg,
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
  courseCard: {
    backgroundColor: "white",
    borderRadius: 16,
    marginBottom: spacing.lg,
    overflow: "hidden",
    borderWidth: 1,
    borderColor: "#E8E1E6",
    boxShadow: "0px 2px 6px rgba(0, 0, 0, 0.06)",
    elevation: 2,
  },
  courseAccent: {
    height: 4,
    width: "100%",
  },
  courseContent: {
    flexDirection: "row",
    flexWrap: "wrap",
    padding: spacing.lg,
    alignItems: "flex-start",
    justifyContent: "space-between",
    position: "relative",
  },
  courseContentCompact: {
    flexDirection: "column",
  },
  deleteIcon: {
    position: "absolute",
    top: spacing.sm,
    right: spacing.sm,
    zIndex: 1,
    padding: 4,
  },
  courseLeft: {
    flex: 1,
    paddingRight: spacing.md,
  },
  courseLeftCompact: {
    flex: undefined,
    width: "100%",
    paddingRight: 0,
    marginBottom: spacing.md,
  },
  courseHeader: {
    flexDirection: "row",
    alignItems: "center",
    marginBottom: 4,
  },
  courseCode: {
    fontSize: 18,
    fontWeight: "600",
    color: colors.boldText,
    marginRight: spacing.sm,
  },
  readOnlyNotice: {
    flexDirection: "row",
    alignItems: "center",
    gap: spacing.sm,
    paddingVertical: 10,
    paddingHorizontal: spacing.md,
    marginBottom: spacing.md,
    borderRadius: 8,
    backgroundColor: "#EEEEF5",
  },
  readOnlyNoticeText: {
    flex: 1,
    fontSize: 13,
    color: colors.bodyText,
  },
  termPill: {
    backgroundColor: "#F2B94A",
    paddingHorizontal: spacing.sm,
    paddingVertical: 4,
    borderRadius: 999,
  },
  termText: {
    fontSize: 11,
    fontWeight: "500",
    color: "#5A2A05",
  },
  courseName: {
    color: colors.bodyText,
    fontSize: 14,
    marginBottom: spacing.md,
  },
  courseLink: {
    color: colors.primary,
    fontWeight: "500",
    fontSize: 13,
  },
  courseRight: {
    flexDirection: "row",
    alignItems: "center",
  },
  courseRightCompact: {
    width: "100%",
    justifyContent: "space-around",
  },
  courseStat: {
    alignItems: "center",
    minWidth: 60,
  },
  courseStatLabel: {
    color: colors.bodyText,
    fontSize: 11,
    fontWeight: "400",
    marginBottom: 6,
  },
  courseGrade: {
    fontSize: 18,
    fontWeight: "600",
    color: colors.primary,
  },
  courseCredits: {
    fontSize: 16,
    fontWeight: "600",
    color: colors.boldText,
  },
  courseDivider: {
    width: 1,
    height: 40,
    backgroundColor: "#E0D7DE",
    marginHorizontal: spacing.md,
  },
  courseDividerCompact: {
    height: 30,
    marginHorizontal: spacing.sm,
  },
  gpaPill: {
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 12,
    backgroundColor: "#F0EAF0",
    alignItems: "center" as const,
    justifyContent: "center" as const,
  },
  gpaPillText: {
    fontSize: 11,
    fontWeight: "500" as const,
    color: colors.primary,
  },
  dropdownToggleWrap: {
    borderRadius: 8,
    overflow: "hidden",
  },
  dropdownToggle: {
    alignSelf: "center",
    paddingVertical: 6,
    paddingHorizontal: spacing.lg,
    marginBottom: 4,
  },
  courseCalculatorContainer: {
    borderTopWidth: 1,
    borderTopColor: "#E8E1E6",
    paddingHorizontal: spacing.lg,
    paddingBottom: spacing.lg,
  },
  syllabusBox: {
    backgroundColor: "#F8F3F6",
    borderRadius: 12,
    padding: spacing.md,
    marginBottom: spacing.md,
    borderWidth: 1,
    borderColor: "#E8E1E6",
  },
  syllabusTitle: {
    fontSize: 14,
    fontWeight: "600",
    color: colors.boldText,
    marginBottom: 6,
  },
  syllabusText: {
    color: colors.bodyText,
    fontSize: 13,
    marginBottom: spacing.sm,
  },
  syllabusMeta: {
    color: "#6E6470",
    fontSize: 12,
    marginBottom: 6,
  },
  syllabusError: {
    color: "#c0392b",
    fontSize: 12,
    marginBottom: spacing.sm,
  },
  syllabusActions: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    gap: spacing.sm,
  },
  uploadButton: {
    paddingVertical: 8,
    paddingHorizontal: 12,
    borderRadius: 10,
    backgroundColor: "#7A003C",
  },
  uploadButtonDisabled: {
    opacity: 0.6,
  },
  uploadButtonText: {
    color: "white",
    fontWeight: "500",
    fontSize: 12,
  },
  jobRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    marginBottom: spacing.sm,
  },
  progressTrack: {
    height: 6,
    borderRadius: 999,
    backgroundColor: "#E8E1E6",
    overflow: "hidden",
    marginBottom: spacing.sm,
  },
  progressFill: {
    height: "100%",
    borderRadius: 999,
  },
  contentWrap: {
    maxWidth: 540,
    width: "100%",
    alignSelf: "center",
    zIndex: 5,
  },
  topActions: {
    flexDirection: "row",
    justifyContent: "center",
    alignItems: "center",
    gap: 12,
    marginBottom: spacing.lg,
  },
  addCoursesToggle: {
    alignSelf: "center",
    flexDirection: "row",
    alignItems: "center",
    backgroundColor: colors.primary,
    paddingVertical: 10,
    paddingHorizontal: 20,
    borderRadius: 10,
    marginBottom: spacing.lg,
  },
  addCoursesToggleText: {
    color: "white",
    fontWeight: "500",
    fontSize: 14,
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
    overflow: "visible",
    position: "relative",
  },
  inputWithDropdown: {
    flex: 1,
    marginRight: spacing.sm,
    position: "relative",
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
  },
  dropdown: {
    position: "absolute",
    top: 46,
    left: 0,
    right: 0,
    backgroundColor: "white",
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: 10,
    zIndex: 100,
    elevation: 10,
    maxHeight: 220,
  },
  dropdownList: {
    maxHeight: 220,
  },
  dropdownItem: {
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.md,
    borderBottomWidth: 1,
    borderBottomColor: colors.border,
  },
  dropdownTitle: {
    fontWeight: "600",
    fontSize: 14,
    color: colors.boldText,
  },
  dropdownSub: {
    color: colors.bodyText,
    fontSize: 12,
    marginTop: 2,
  },
  loadingRow: {
    paddingVertical: spacing.sm,
    alignItems: "center",
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
  save: {
    flexDirection: "row",
    alignItems: "center",
    backgroundColor: colors.primary,
    paddingVertical: 10,
    paddingHorizontal: 20,
    borderRadius: 10,
  },
  saveText: {
    color: "white",
    fontWeight: "500",
    fontSize: 14,
  },
  card: {
    backgroundColor: "white",
    borderRadius: 16,
    padding: spacing.lg,
    boxShadow: "0px 2px 6px rgba(0, 0, 0, 0.06)",
    elevation: 2,
  },
  cardTitle: {
    fontSize: 17,
    fontWeight: "600",
    color: colors.boldText,
    marginBottom: 6,
  },
  cardSub: {
    color: colors.bodyText,
    fontSize: 14,
    lineHeight: 20,
    marginBottom: spacing.lg,
  },
  uploadBox: {
    borderWidth: 1,
    borderStyle: "dashed",
    borderColor: colors.border,
    borderRadius: 14,
    padding: spacing.lg,
    alignItems: "center",
  },
  iconCircle: {
    backgroundColor: "#F2E6EC",
    borderRadius: 999,
    padding: spacing.md,
    marginBottom: spacing.md,
    alignItems: "center",
    justifyContent: "center",
  },
  uploadTitle: {
    fontWeight: "600",
    fontSize: 15,
    color: colors.boldText,
    marginBottom: 4,
  },
  uploadSub: {
    color: colors.bodyText,
    fontSize: 13,
    textAlign: "center",
    marginBottom: spacing.md,
  },
  browseButton: {
    flexDirection: "row",
    alignItems: "center",
    backgroundColor: colors.primary,
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.lg,
    borderRadius: 10,
  },
  browseText: {
    color: "white",
    fontWeight: "500",
    fontSize: 14,
  },
  fileName: {
    marginTop: spacing.sm,
    color: colors.bodyText,
    fontSize: 12,
  },
  errorText: {
    color: "#B33A3A",
    fontSize: 13,
    marginTop: spacing.md,
    textAlign: "center",
  },
  savedCard: {
    flexDirection: "row",
    alignItems: "center",
    alignSelf: "flex-start",
    backgroundColor: "#E8F5E9",
    paddingVertical: 6,
    paddingHorizontal: 10,
    borderRadius: 6,
    marginTop: spacing.sm,
  },
  savedText: {
    color: "#2E7D32",
    fontWeight: "500",
    fontSize: 12,
  },
});
