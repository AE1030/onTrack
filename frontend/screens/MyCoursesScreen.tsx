import {
  View,
  Pressable,
  StyleSheet,
  ScrollView,
  ActivityIndicator,
  FlatList,
  Platform,
  useWindowDimensions,
} from "react-native";
import { Text, TextInput } from "../src/ui/Text";
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
import { spacing, radius } from "../src/theme/spacing";
import { getToken } from "../src/utils/tokenStorage";
import { API_BASE_URL } from "../src/config/api";
import { qk } from "../src/cache/keys";
import { authedFetch, authedGet, isAuthError, queryRetry } from "../src/cache/authedGet";
import { toUserMessage } from "../src/utils/errorMessage";
import { useCourseSearch } from "../src/hooks/useCourseSearch";
import { useTerm } from "../src/terms/TermContext";
import { fetchAssessmentTable } from "../src/cache/assessmentTable";
import TermPicker from "./components/TermPicker";
import CourseCalculatorScreen from "./CourseCalculatorScreen";
import { fonts, typo } from "../src/theme/typography";

const GRADE_REGEX = /^\d{0,3}(\.\d{0,2})?$/;
const MAX_SYLLABUS_BYTES = 10 * 1024 * 1024;
// Stop polling a job this old. One minute past the server's 15-minute stale
// sweep, so normally the poll sees the sweep's FAILED (with its refund message)
// first and this only catches the case where it does not.
const MAX_JOB_POLL_MS = 16 * 60_000;
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

/**
 * What to tell the student about a failed extraction job.
 *
 * job.error is the server's exception message (a Gemini client error, or "NO_SYLLABUS: ..."), which
 * is for logs, not for students. errorType says what kind of failure it was, and that is enough.
 */
const jobErrorMessage = (job: SyllabusJobResponse): string => {
  switch (job.errorType) {
    case "INVALID_SYLLABUS":
      return "That file does not look like a course syllabus. Upload the course outline PDF.";
    case "TIMED_OUT":
      return "This upload stopped responding. Try uploading it again.";
    default:
      return "We could not read this syllabus. It did not count toward your uploads. Try again.";
  }
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
  // Wide enough to set the add-courses heading beside its form instead of above it.
  const addCardSplit = width >= 1024;
  const queryClient = useQueryClient();
  // The term list and the setter live in TermPicker now; this screen only needs to know which
  // term it is showing and whether that term accepts writes.
  const { selectedTerm, editable: termEditable, loading: termsLoading } = useTerm();
  const [deletingCourse, setDeletingCourse] = useState<string | null>(null);
  const [mode, setMode] = useState<"manual" | "transcript">("manual");
  const [rows, setRows] = useState<CourseRow[]>([
    { id: "1", courseCode: "", grade: "" },
  ]);
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [fileName, setFileName] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
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
  // Not isPending alone: a disabled query (no term yet) is pending forever, which spun this
  // indefinitely whenever the term list failed to load.
  const loadingCourses = selectedTerm ? currentCoursesQuery.isPending : termsLoading;
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
    // The job's own age, not how long this screen has watched it: a job resumed
    // from an earlier visit may already be most of the way to the limit.
    const startedAt = Date.now();
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
        const createdAt = Date.parse(job.createdAt);
        const age = Date.now() - (Number.isNaN(createdAt) ? startedAt : createdAt);
        if (
          (job.status === "QUEUED" || job.status === "PROCESSING") &&
          age > MAX_JOB_POLL_MS
        ) {
          stopPolling(key);
          updateUploadState(key, {
            job,
            // Cleared so the card falls back to the global count refreshed below.
            remainingUploads: null,
            error: "This upload stopped responding. Try uploading it again.",
          });
          // The count endpoint runs the server's stale sweep, which refunds the upload.
          fetchRemainingUploads();
          return;
        }

        updateUploadState(key, {
          job,
          remainingUploads: job.remainingUploads ?? null,
          error: job.error ? jobErrorMessage(job) : null,
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
      } catch (err) {
        updateUploadState(key, {
          error: toUserMessage(err, "Lost track of this upload. Reopen My Courses to check on it."),
        });
        stopPolling(key);
      }
    }, 2000);
  };

  /**
   * Picks up jobs that outlived a previous visit. The job runs on the server
   * regardless, but its id and polling timer died with the old screen, so ask
   * the server for this term's recent jobs and reattach to each one.
   */
  const resumeRecentJobs = async (term: string, courses: CurrentCourseDTO[]) => {
    let jobs: SyllabusJobResponse[];
    try {
      jobs = await authedGet<SyllabusJobResponse[]>(
        `/api/syllabus/upload/recent?term=${encodeURIComponent(term)}`
      );
    } catch (err) {
      if (isAuthError(err)) onAuthError();
      return;
    }

    jobs.forEach((job) => {
      const index = courses.findIndex(
        (c) => c.courseCode === job.courseCode && c.term === job.term
      );
      if (index === -1) return;
      const key = `${job.courseCode}-${job.term}-${index}`;
      // Already being watched: an upload started on this visit.
      if (pollingRefs.current[key]) return;

      if (job.status === "QUEUED" || job.status === "PROCESSING") {
        updateUploadState(key, {
          job,
          remainingUploads: job.remainingUploads ?? null,
          error: null,
        });
        startPollingJob(key, job.id, job.courseCode, job.term);
      } else if (job.status === "DONE") {
        // Finished while we were away, so nothing wrote the new table into
        // the cache. Mark it stale and the next expand refetches.
        queryClient.invalidateQueries({
          queryKey: qk.assessmentTable(job.courseCode, job.term),
        });
      } else if (job.status === "FAILED") {
        updateUploadState(key, {
          job,
          remainingUploads: job.remainingUploads ?? null,
          error: jobErrorMessage(job),
        });
      }
    });
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

      if (res.status === 415) {
        updateUploadState(key, {
          uploading: false,
          error: "That file is not a PDF. Upload the syllabus as a PDF.",
        });
        return;
      }

      if (res.status === 413) {
        updateUploadState(key, {
          uploading: false,
          error: "That file is too large to upload.",
        });
        return;
      }

      if (!res.ok) throw new Error("Upload failed");

      const job: SyllabusJobResponse = await res.json();
      updateUploadState(key, {
        uploading: false,
        job,
        remainingUploads: job.remainingUploads ?? null,
        error: job.error ? jobErrorMessage(job) : null,
      });

      if (job.id) {
        startPollingJob(key, job.id, courseCode, term);
      }
    } catch (err) {
      updateUploadState(key, {
        uploading: false,
        error: toUserMessage(err, "Upload failed. Try again in a moment."),
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

  // Once per term, after its courses have loaded (job keys need the course's index).
  const resumedTermRef = useRef<string | null>(null);
  useEffect(() => {
    const courses = currentCoursesQuery.data;
    if (!selectedTerm || !courses || resumedTermRef.current === selectedTerm) return;
    resumedTermRef.current = selectedTerm;
    resumeRecentJobs(selectedTerm, courses);
  }, [selectedTerm, currentCoursesQuery.data]);

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
    } catch (err) {
      setUploadError(toUserMessage(err, "Upload failed. Try again in a moment."));
    } finally {
      setUploading(false);
    }
  };

  const saveCourses = async () => {
    setLoading(true);
    setSaveError(null);
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
    } else {
      setSaveError("Those courses could not be saved. Check each course code and try again.");
    }
  };

  // Rows with a course code are the ones Save will send; blank rows are ignored.
  const filledRows = rows.filter((r) => r.courseCode.trim() !== "").length;

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

        {/* Adding a course writes an enrollment, so it belongs to the current term only. The
            form itself is the card at the end of the list; this only gets you there when a long
            list has pushed it off screen. */}
        {termEditable && currentCourses.length > 0 && (
          <Pressable
            style={styles.jumpToAdd}
            onPress={() => scrollRef.current?.scrollToEnd({ animated: true })}
            accessibilityRole="button"
            accessibilityLabel="Jump to add courses"
          >
            <Feather name="plus" size={16} color={colors.primary} />
            <Text style={styles.jumpToAddText}>Add courses</Text>
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
                    colors.courseAccents[index % colors.courseAccents.length],
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
                    {bestGrade != null ? `${bestGrade.toFixed(2)}%` : "No grade"}
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
                      "Not available"}
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
                                ? colors.danger
                                : jobStatus === "DONE"
                                  ? colors.success
                                  : colors.primary,
                          },
                        ]}
                      />
                    </View>
                  )}

                  <View style={styles.syllabusActions}>
                    <Pressable
                      style={[
                        styles.uploadButton,
                        (uploadState.uploading || cooldownRemaining > 0 || isPolling) &&
                          styles.uploadButtonDisabled,
                        ]}
                      onPress={() =>
                        uploadSyllabus(
                          key,
                          course.courseCode,
                          effectiveTerm
                        )
                      }
                      // A second upload while one is processing would be charged twice.
                      disabled={uploadState.uploading || cooldownRemaining > 0 || isPolling}
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

        {termEditable && (
          <View
            style={[
              styles.addCard,
              isCompact && styles.addCardCompact,
              addCardSplit && styles.addCardSplit,
            ]}
          >
            <View style={addCardSplit && styles.addCardIntroSplit}>
              <Text style={styles.addCardTitle}>Add courses to {selectedTerm}</Text>
              <Text style={styles.addCardSub}>
                Search for each course by its code, or upload your transcript to add them all at
                once.
              </Text>
            </View>

            <View style={addCardSplit && styles.addCardBodySplit}>

            <View
              style={[styles.toggle, styles.form]}
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
                accessibilityRole="tab"
                accessibilityState={{ selected: mode === "manual" }}
              >
                <Text
                  style={
                    mode === "manual"
                      ? styles.activeTabText
                      : styles.inactiveTabText
                  }
                >
                  Search courses
                </Text>
              </Pressable>

              <Pressable
                style={styles.tabButton}
                onPress={() => switchMode("transcript")}
                accessibilityRole="tab"
                accessibilityState={{ selected: mode === "transcript" }}
              >
                <Text
                  style={
                    mode === "transcript"
                      ? styles.activeTabText
                      : styles.inactiveTabText
                  }
                >
                  Upload transcript
                </Text>
              </Pressable>
            </View>

            {mode === "manual" && (
              <View style={styles.form}>
                {/* Labelled once over the columns rather than in every placeholder. */}
                <View style={styles.formLabels}>
                  <Text style={[styles.formLabel, styles.formLabelCourse]}>Course code</Text>
                  <Text style={[styles.formLabel, styles.formLabelGrade, isCompact && styles.gradeInputCompact]}>
                    {isCompact ? "Grade" : "Grade (optional)"}
                  </Text>
                  <View style={styles.formLabelSpacer} />
                </View>

                {rows.map((row) => (
                  <View key={row.id} style={[styles.row, activeRowId === row.id && { zIndex: 10 }, activeRowId === row.id && canSearch && { paddingBottom: 230 }]}>
                    <View style={styles.inputWithDropdown}>
                      <TextInput
                        placeholder="e.g. COMPSCI 2C03"
                        placeholderTextColor={colors.placeholder}
                        accessibilityLabel="Course code"
                        autoCapitalize="characters"
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
                      placeholder={isCompact ? "Optional" : "%"}
                      placeholderTextColor={colors.placeholder}
                      accessibilityLabel="Grade, optional"
                      value={row.grade}
                      onChangeText={(v) => updateRow(row.id, "grade", v)}
                      keyboardType="numeric"
                      style={[styles.input, styles.gradeInput, isCompact && styles.gradeInputCompact]}
                    />

                    {/* A lone row has nothing to remove; the slot stays so the columns line up. */}
                    {rows.length > 1 ? (
                      <Pressable
                        onPress={() => removeRow(row.id)}
                        style={styles.removeRow}
                        accessibilityRole="button"
                        accessibilityLabel="Remove this course"
                      >
                        <Feather name="x" size={18} color={colors.bodyText} />
                      </Pressable>
                    ) : (
                      <View style={styles.removeRow} />
                    )}
                  </View>
                ))}

                <Pressable style={styles.addRowButton} onPress={addRow}>
                  <Feather name="plus" size={16} color={colors.primary} />
                  <Text style={styles.addRowText}>Add another course</Text>
                </Pressable>

                <View style={styles.formFooter}>
                  <Animated.View style={[styles.savedCard, savedAnimatedStyle]} pointerEvents="none">
                    <Feather name="check" size={13} color={colors.success} style={{ marginRight: 4 }} />
                    <Text style={styles.savedText}>Courses added</Text>
                  </Animated.View>

                  <Pressable
                    style={[styles.save, (loading || filledRows === 0) && styles.saveDisabled]}
                    onPress={saveCourses}
                    disabled={loading || filledRows === 0}
                  >
                    <Text style={styles.saveText}>
                      {loading
                        ? "Saving..."
                        : filledRows > 1
                          ? `Save ${filledRows} courses`
                          : "Save course"}
                    </Text>
                  </Pressable>
                </View>

                {saveError && <Text style={styles.formError}>{saveError}</Text>}
              </View>
            )}

            {mode === "transcript" && (
              <View style={styles.form}>
                <View style={styles.uploadBox}>
                  <View style={styles.iconCircle}>
                    <Feather name="upload" size={24} color={colors.primary} />
                  </View>

                  <Text style={styles.uploadTitle}>Choose your transcript</Text>

                  <Text style={styles.uploadSub}>
                    We read this term's courses from it and add them to your list. PDF, JPG or
                    PNG.
                  </Text>

                  <Pressable
                    style={[styles.browseButton, uploading && styles.saveDisabled]}
                    onPress={uploadTranscript}
                    disabled={uploading}
                  >
                    <Feather
                      name="file"
                      size={16}
                      color={colors.onField}
                      style={{ marginRight: 6 }}
                    />
                    <Text style={styles.browseText}>
                      {uploading ? "Uploading..." : "Choose file"}
                    </Text>
                  </Pressable>

                  {fileName && (
                    <Text style={styles.fileName}>{fileName}</Text>
                  )}
                </View>

                <Animated.View style={[styles.savedCard, { marginTop: spacing.sm }, uploadSuccessAnimatedStyle]} pointerEvents="none">
                  <Feather name="check" size={13} color={colors.success} style={{ marginRight: 4 }} />
                  <Text style={styles.savedText}>Courses added from your transcript</Text>
                </Animated.View>

                {uploadError && (
                  <Text style={styles.formError}>{uploadError}</Text>
                )}
              </View>
            )}
            </View>
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
    ...typo.h1,
    marginBottom: 6,
    color: colors.boldText,
    textAlign: "center",
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
  courseCard: {
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    marginBottom: spacing.lg,
    overflow: "hidden",
    borderWidth: 1,
    borderColor: colors.borderSubtle,
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
    ...typo.h2,
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
    borderRadius: radius.sm,
    backgroundColor: colors.surfaceSunken,
  },
  readOnlyNoticeText: {
    ...typo.caption,
    flex: 1,
    color: colors.bodyText,
  },
  termPill: {
    backgroundColor: colors.accentSoft,
    paddingHorizontal: spacing.sm,
    paddingVertical: 4,
    borderRadius: 999,
  },
  termText: {
    ...typo.caption,
    color: colors.boldText,
    fontFamily: fonts.bodyMedium,
  },
  courseName: {
    ...typo.body,
    color: colors.bodyText,
    marginBottom: spacing.md,
  },
  courseLink: {
    ...typo.label,
    color: colors.primary,
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
    ...typo.caption,
    color: colors.bodyText,
    marginBottom: 6,
  },
  courseGrade: {
    ...typo.data,
    fontSize: 18,
    color: colors.primary,
    fontFamily: fonts.bodyMedium,
    letterSpacing: 0.36,
  },
  courseCredits: {
    ...typo.data,
    fontSize: 16,
    color: colors.boldText,
    fontFamily: fonts.bodyMedium,
    letterSpacing: 0.32,
  },
  courseDivider: {
    width: 1,
    height: 40,
    backgroundColor: colors.border,
    marginHorizontal: spacing.md,
  },
  courseDividerCompact: {
    height: 30,
    marginHorizontal: spacing.sm,
  },
  gpaPill: {
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: radius.md,
    backgroundColor: colors.surfaceSunken,
    alignItems: "center" as const,
    justifyContent: "center" as const,
  },
  gpaPillText: {
    ...typo.caption,
    color: colors.primary,
    fontFamily: fonts.bodyMedium,
  },
  dropdownToggleWrap: {
    borderRadius: radius.sm,
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
    borderTopColor: colors.borderSubtle,
    paddingHorizontal: spacing.lg,
    paddingBottom: spacing.lg,
  },
  syllabusBox: {
    backgroundColor: colors.surfaceAlt,
    borderRadius: radius.md,
    padding: spacing.md,
    marginBottom: spacing.md,
    borderWidth: 1,
    borderColor: colors.borderSubtle,
  },
  syllabusTitle: {
    ...typo.h3,
    color: colors.boldText,
    marginBottom: 6,
  },
  syllabusText: {
    ...typo.body,
    color: colors.bodyText,
    marginBottom: spacing.sm,
  },
  syllabusMeta: {
    ...typo.caption,
    color: colors.faintText,
    marginBottom: 6,
  },
  syllabusError: {
    ...typo.caption,
    color: colors.danger,
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
    borderRadius: radius.md,
    backgroundColor: colors.primary,
  },
  uploadButtonDisabled: {
    opacity: 0.6,
  },
  uploadButtonText: {
    ...typo.label,
    color: colors.onField,
    fontSize: 13,
    fontFamily: fonts.display,
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
    backgroundColor: colors.surfaceSunken,
    overflow: "hidden",
    marginBottom: spacing.sm,
  },
  progressFill: {
    height: "100%",
    borderRadius: 999,
  },
  // The one form on the page: a single card at the end of the list. Overflow stays visible so
  // the course search dropdown can hang below its row.
  addCard: {
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    borderWidth: 1,
    borderColor: colors.border,
    padding: spacing.lg,
    zIndex: 5,
  },
  addCardCompact: {
    padding: spacing.md,
  },
  addCardSplit: {
    flexDirection: "row",
    gap: spacing.lg * 2,
  },
  addCardIntroSplit: {
    width: 300,
  },
  addCardBodySplit: {
    flex: 1,
    maxWidth: 640,
  },
  addCardTitle: {
    ...typo.h2,
    color: colors.boldText,
  },
  addCardSub: {
    ...typo.body,
    color: colors.bodyText,
    marginTop: 4,
    marginBottom: spacing.md,
    maxWidth: 560,
  },
  // Inputs read best at a form's width, not stretched across a 1200px card.
  form: {
    maxWidth: 640,
    width: "100%",
  },
  formLabels: {
    flexDirection: "row",
    alignItems: "center",
    gap: spacing.sm,
    marginBottom: 6,
  },
  formLabel: {
    ...typo.caption,
    fontFamily: fonts.bodyMedium,
    color: colors.bodyText,
  },
  formLabelCourse: {
    flex: 1,
  },
  formLabelGrade: {
    width: 120,
  },
  formLabelSpacer: {
    width: 36,
  },
  row: {
    flexDirection: "row",
    alignItems: "center",
    gap: spacing.sm,
    marginBottom: spacing.sm,
    overflow: "visible",
    position: "relative",
  },
  gradeInput: {
    flex: 0,
    width: 120,
    fontVariant: ["tabular-nums"],
  },
  gradeInputCompact: {
    width: 84,
  },
  removeRow: {
    width: 36,
    height: 36,
    alignItems: "center",
    justifyContent: "center",
    borderRadius: radius.md,
  },
  addRowButton: {
    flexDirection: "row",
    alignItems: "center",
    alignSelf: "flex-start",
    gap: 6,
    paddingVertical: 8,
    paddingHorizontal: 8,
    marginLeft: -8,
    borderRadius: radius.md,
  },
  addRowText: {
    ...typo.label,
    color: colors.primary,
  },
  formFooter: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    gap: spacing.md,
    marginTop: spacing.md,
    paddingTop: spacing.md,
    borderTopWidth: 1,
    borderTopColor: colors.borderSubtle,
  },
  saveDisabled: {
    opacity: 0.45,
  },
  formError: {
    ...typo.body,
    color: colors.danger,
    marginTop: spacing.sm,
  },
  jumpToAdd: {
    flexDirection: "row",
    alignItems: "center",
    alignSelf: "center",
    gap: 6,
    paddingVertical: 8,
    paddingHorizontal: 12,
    marginBottom: spacing.md,
    borderRadius: radius.md,
  },
  jumpToAddText: {
    ...typo.label,
    color: colors.primary,
  },
  inputWithDropdown: {
    flex: 1,
    marginRight: spacing.sm,
    position: "relative",
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
  },
  dropdown: {
    position: "absolute",
    top: 46,
    left: 0,
    right: 0,
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.md,
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
    ...typo.body,
    color: colors.boldText,
    fontFamily: fonts.bodyMedium,
  },
  dropdownSub: {
    ...typo.caption,
    color: colors.bodyText,
    marginTop: 2,
  },
  loadingRow: {
    paddingVertical: spacing.sm,
    alignItems: "center",
  },
  save: {
    marginLeft: "auto",
    flexDirection: "row",
    alignItems: "center",
    backgroundColor: colors.primary,
    paddingVertical: 10,
    paddingHorizontal: 20,
    borderRadius: radius.md,
  },
  saveText: {
    ...typo.label,
    color: colors.onField,
    fontFamily: fonts.display,
  },
  uploadBox: {
    borderWidth: 1,
    borderStyle: "dashed",
    borderColor: colors.border,
    borderRadius: radius.lg,
    padding: spacing.lg,
    alignItems: "center",
  },
  iconCircle: {
    backgroundColor: colors.primarySoft,
    borderRadius: 999,
    padding: spacing.md,
    marginBottom: spacing.md,
    alignItems: "center",
    justifyContent: "center",
  },
  uploadTitle: {
    ...typo.h3,
    color: colors.boldText,
    marginBottom: 4,
  },
  uploadSub: {
    ...typo.body,
    color: colors.bodyText,
    textAlign: "center",
    marginBottom: spacing.md,
  },
  browseButton: {
    flexDirection: "row",
    alignItems: "center",
    backgroundColor: colors.primary,
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.lg,
    borderRadius: radius.md,
  },
  browseText: {
    ...typo.label,
    color: colors.onField,
    fontFamily: fonts.display,
  },
  fileName: {
    ...typo.caption,
    marginTop: spacing.sm,
    color: colors.bodyText,
  },
  errorText: {
    ...typo.body,
    color: colors.danger,
    marginTop: spacing.md,
    textAlign: "center",
  },
  savedCard: {
    flexDirection: "row",
    alignItems: "center",
    alignSelf: "flex-start",
    backgroundColor: colors.successSoft,
    paddingVertical: 6,
    paddingHorizontal: 10,
    borderRadius: radius.sm,
  },
  savedText: {
    ...typo.caption,
    color: colors.success,
    fontFamily: fonts.bodyMedium,
  },
});
