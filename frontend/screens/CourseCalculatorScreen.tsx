import React, { useEffect, useMemo, useRef, useState } from "react";
import {
  View,
  Text,
  TextInput,
  ScrollView,
  StyleSheet,
  ActivityIndicator,
  Pressable,
  Platform,
} from "react-native";
import { Feather } from "@expo/vector-icons";
import DateTimePicker from "@react-native-community/datetimepicker";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { colors } from "../src/theme/colors";
import { getToken } from "../src/utils/tokenStorage";
import { API_BASE_URL } from "../src/config/api";
import { qk } from "../src/cache/keys";
import {
  HttpError,
  authedMutate,
  isAuthError,
  isServerRejection,
} from "../src/cache/authedGet";

//
// TYPES
//

type BackendAssessmentDTO = {
  assessmentName: string;
  weights: number[];
  dueDate: string | null;
  n: number;
  startTime: string | null;
  endTime: string | null;
  location: string | null;
  grade: number | null;
};

type BackendResponse = Record<string, BackendAssessmentDTO[]>;

/** The fields this screen touches on the shared current-courses cache entry. */
type CurrentCourseSummary = {
  courseCode: string;
  grade: number | null;
  includeInGpa: boolean;
};

type Assessment = {
  id: string;
  name: string;
  nameInput?: string;
  weights: number[];
  dueDate?: string | null;
  startTime?: string | null;
  endTime?: string | null;
  location?: string | null;
  dueDateInput?: string | null;
  startTimeInput?: string | null;
  endTimeInput?: string | null;
  locationInput?: string;
  dropCount?: number;
  grade?: number;
  overrideWeight?: number;
  weightInput?: string;
  gradeInput?: string;
};

type GradingScheme = {
  name: string;
  assessments: Assessment[];
};

type Props = {
  courseCode: string;
  term: string;
  /**
   * Whether this term accepts writes. False greys the table out and, more importantly, stops
   * the autosave timer from ever being armed. Defaults to true so existing callers that only
   * ever showed the current term keep their behaviour.
   */
  editable?: boolean;
  onAuthError: () => void;
  variant?: "screen" | "inline";
  onBestGradeChange?: (grade: number) => void;
  externalResponse?: BackendResponse | null;
  externalVersion?: number;
  skipFetch?: boolean;
  autoSaveVersion?: number;
  /** Fired after a successful save (autosave included) so the parent can evict its cache. */
  onSaved?: () => void;
};

export default function CourseCalculatorScreen({
  courseCode,
  term,
  editable = true,
  onAuthError,
  variant = "screen",
  onBestGradeChange,
  externalResponse,
  externalVersion,
  skipFetch = false,
  autoSaveVersion,
  onSaved,
}: Props) {
  const queryClient = useQueryClient();
  const [schemes, setSchemes] = useState<GradingScheme[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [dirty, setDirty] = useState(false);
  const [saveState, setSaveState] = useState<
    "idle" | "saving" | "saved" | "error"
  >("idle");
  const [saveError, setSaveError] = useState<string | null>(null);
  const [schemeLimitError, setSchemeLimitError] = useState<string | null>(
    null
  );
  const [activePicker, setActivePicker] = useState<{
    schemeIndex: number;
    assessmentId: string;
    field: "dueDate" | "startTime" | "endTime";
  } | null>(null);
  const isWeb = Platform.OS === "web";
  const autosaveTimer = useRef<ReturnType<typeof setTimeout> | null>(
    null
  );
  const hasInitialized = useRef(false);
  const hasHydrated = useRef(false);
  const autoSavePending = useRef(false);

  // The term is whatever the caller selected. It used to be pinned to a literal here, which
  // collapsed every term onto one cache entry and made a term picker impossible.
  const effectiveTerm = term;

  // Changing this refetches, because it is the fetch effect's dependency. That is all the
  // wiring a term switch needs on this screen.
  const defaultCacheKey = `${courseCode}::${effectiveTerm}`;

  useEffect(() => {
    if (skipFetch) {
      return;
    }
    fetchAssessments();
  }, [defaultCacheKey, skipFetch]);

  useEffect(() => {
    if (externalResponse === undefined) return;
    hasHydrated.current = true;
    const nextSchemes =
      externalResponse === null
        ? [
            {
              name: "Marking Scheme 1",
              assessments: [],
            },
          ]
        : parseBackendResponse(externalResponse);
    setSchemes(nextSchemes);
    setDirty(false);
    setSaveState("idle");
    setError(null);
    setLoading(false);
  }, [externalResponse, externalVersion]);

  useEffect(() => {
    if (autoSaveVersion === undefined) return;
    autoSavePending.current = true;
  }, [autoSaveVersion]);

  const formatTime = (value?: string | null) => {
    if (!value) return "-";
    if (/^\d{2}:\d{2}$/.test(value)) return value;

    const parsed = new Date(value);
    if (Number.isNaN(parsed.getTime())) return "-";

    const hours = parsed.getHours().toString().padStart(2, "0");
    const minutes = parsed.getMinutes().toString().padStart(2, "0");
    return `${hours}:${minutes}`;
  };

  const formatDate = (value?: string | null) => {
    if (!value) return "-";
    if (/^\d{4}-\d{2}-\d{2}$/.test(value)) return value;

    const parsed = new Date(value);
    if (Number.isNaN(parsed.getTime())) return "-";
    return parsed.toISOString().slice(0, 10);
  };

  const getTimeDate = (value?: string | null, fallback = "12:00") => {
    if (value) {
      const parsed = new Date(value);
      if (!Number.isNaN(parsed.getTime())) return parsed;
      if (/^\d{2}:\d{2}$/.test(value)) {
        const [h, m] = value.split(":").map(Number);
        const d = new Date();
        d.setHours(h, m, 0, 0);
        return d;
      }
    }

    const [h, m] = fallback.split(":").map(Number);
    const d = new Date();
    d.setHours(h, m, 0, 0);
    return d;
  };

  const getDateValue = (value?: string | null) => {
    if (value) {
      const parsed = new Date(value);
      if (!Number.isNaN(parsed.getTime())) return parsed;
      if (/^\d{4}-\d{2}-\d{2}$/.test(value)) {
        const [y, m, d] = value.split("-").map(Number);
        return new Date(y, m - 1, d);
      }
    }
    return new Date();
  };

  const normalizeDateInput = (value: string) => {
    if (!value) return "";
    const parts = value.split("-");
    if (parts.length !== 3) return value;
    const [y, m, d] = parts;
    if (!/^\d{4}$/.test(y)) return value;
    const mm = m.padStart(2, "0");
    const dd = d.padStart(2, "0");
    return `${y}-${mm}-${dd}`;
  };

  const normalizeTimeInput = (value: string) => {
    if (!value) return "";
    const parts = value.split(":");
    if (parts.length !== 2) return value;
    const [h, m] = parts;
    const hh = h.padStart(2, "0");
    const mm = m.padStart(2, "0");
    return `${hh}:${mm}`;
  };

  const isValidDateString = (value: string) => {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
    const [y, m, d] = value.split("-").map(Number);
    if (m < 1 || m > 12 || d < 1 || d > 31) return false;
    const date = new Date(y, m - 1, d);
    return (
      date.getFullYear() === y &&
      date.getMonth() === m - 1 &&
      date.getDate() === d
    );
  };

  const isValidTimeString = (value: string) => {
    if (!/^\d{2}:\d{2}$/.test(value)) return false;
    const [h, m] = value.split(":").map(Number);
    return h >= 0 && h <= 23 && m >= 0 && m <= 59;
  };

  const shouldShowDateWarning = (value: string) =>
    value.length > 0 && !isValidDateString(value);

  const shouldShowTimeWarning = (value: string) =>
    value.length > 0 && !isValidTimeString(value);

  const isValidDatePartial = (value: string) => {
    if (value === "") return true;
    if (!/^[0-9-]*$/.test(value)) return false;
    if (value.length > 10) return false;
    const parts = value.split("-");
    if (parts.length > 3) return false;

    const [y = "", m = "", d = ""] = parts;
    if (y.length > 4 || m.length > 2 || d.length > 2) return false;

    if (y.length > 0 && !/^\d+$/.test(y)) return false;
    if (m.length > 0 && !/^\d+$/.test(m)) return false;
    if (d.length > 0 && !/^\d+$/.test(d)) return false;
    if (m.length === 2) {
      const mm = Number(m);
      if (mm < 1 || mm > 12) return false;
    }
    if (d.length === 2) {
      const dd = Number(d);
      if (dd < 1 || dd > 31) return false;
    }

    return true;
  };

  const isValidTimePartial = (value: string) => {
    if (value === "") return true;
    if (!/^[0-9:]*$/.test(value)) return false;
    if (value.length > 5) return false;
    const parts = value.split(":");
    if (parts.length > 2) return false;
    const [h = "", m = ""] = parts;
    if (h.length > 2 || m.length > 2) return false;
    if (h.length > 0 && !/^\d+$/.test(h)) return false;
    if (m.length > 0 && !/^\d+$/.test(m)) return false;
    if (h.length === 2) {
      const hh = Number(h);
      if (hh < 0 || hh > 23) return false;
    }
    if (m.length === 2) {
      const mm = Number(m);
      if (mm < 0 || mm > 59) return false;
    }

    return true;
  };

  async function fetchAssessments() {
    try {
      const token = await getToken();
      if (!token) {
        setLoading(false);
        onAuthError();
        return;
      }

      const encodedCourse = encodeURIComponent(courseCode);
      const encodedTerm = encodeURIComponent(effectiveTerm);

      const response = await fetch(
        `${API_BASE_URL.replace(/\/$/, "")}/api/assessment-table/get-assessment-table?courseCode=${encodedCourse}&term=${encodedTerm}`,
        {
          headers: {
            Authorization: `Bearer ${token}`,
            "Content-Type": "application/json",
          },
        }
      );

      if (response.status === 401) {
        setLoading(false);
        onAuthError();
        return;
      }

      if (!response.ok) {
        throw new Error(`Failed to fetch assessments`);
      }

      const raw = await response.text();
      const data: BackendResponse | null = raw ? JSON.parse(raw) : null;
      const nextSchemes =
        data === null
          ? [
              {
                name: "Marking Scheme 1",
                assessments: [],
              },
            ]
          : parseBackendResponse(data);

      hasHydrated.current = true;
      setSchemes(nextSchemes);
      setDirty(false);
      setSaveState("idle");
    } catch (err: any) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }

  function calculateSchemeGrade(
    assessments: Assessment[]
  ): number {
    const resolved =
      resolveWeights(assessments);

    let earned = 0;
    let weightSum = 0;

    for (const a of resolved) {
      if (a.grade == null) continue;

      earned +=
        (a.grade / 100) *
        a.effectiveWeight;

      weightSum +=
        a.effectiveWeight;
    }

    if (weightSum === 0) return 0;

    return (
      (earned / weightSum) * 100
    );
  }

  function resolveWeights(
    assessments: Assessment[]
  ) {
    const groups =
      groupAssessments(assessments);

    const resolved: any[] = [];

    for (const group of groups) {
      const { weights, dropCount } =
        group[0];

      if (!dropCount || weights.length === 1) {
        for (const a of group) {
          resolved.push({
            ...a,
            effectiveWeight:
              a.overrideWeight ??
              weights[0],
          });
        }
        continue;
      }

      const graded = group
        .filter((a) => a.grade != null)
        .sort(
          (a, b) =>
            a.grade! - b.grade!
        );

      const actualDrop = Math.min(
        dropCount,
        graded.length
      );

      const droppedIds = new Set(
        graded
          .slice(0, actualDrop)
          .map((a) => a.id)
      );

      const minWeight =
        Math.min(...weights);
      const maxWeight =
        Math.max(...weights);

      for (const a of group) {
        resolved.push({
          ...a,
          effectiveWeight:
            a.overrideWeight ??
            (droppedIds.has(a.id)
              ? minWeight
              : maxWeight),
        });
      }
    }

    return resolved;
  }

  function groupAssessments(
    assessments: Assessment[]
  ) {
    const map = new Map<
      string,
      Assessment[]
    >();

    for (const a of assessments) {
      const key =
        JSON.stringify(a.weights) +
        "|" +
        (a.dropCount ?? 0);

      if (!map.has(key)) {
        map.set(key, []);
      }

      map.get(key)!.push(a);
    }

    return Array.from(map.values());
  }

  const schemeGrades = useMemo(() => {
    return schemes.map((scheme) =>
      calculateSchemeGrade(scheme.assessments)
    );
  }, [schemes]);

  const bestGrade =
    schemeGrades.length > 0
      ? Math.max(...schemeGrades)
      : 0;

  useEffect(() => {
    onBestGradeChange?.(bestGrade);
  }, [bestGrade, onBestGradeChange]);

  const markDirty = () => {
    setDirty(true);
    setSaveState("idle");
    setSaveError(null);
  };


  function addAssessment(schemeIndex: number) {
    const newAssessment: Assessment = {
      id: `custom-${Date.now()}-${Math.random()
        .toString(36)
        .slice(2, 8)}`,
      name: "",
      nameInput: "",
      weights: [0],
      dueDate: null,
      startTime: null,
      endTime: null,
      location: null,
      dueDateInput: null,
      startTimeInput: null,
      endTimeInput: null,
      locationInput: "",
      weightInput: "",
      gradeInput: "",
    };

    setSchemes((prev) =>
      prev.map((scheme, i) =>
        i !== schemeIndex
          ? scheme
          : {
              ...scheme,
              assessments: [
                ...scheme.assessments,
                newAssessment,
              ],
            }
      )
    );
    markDirty();
  }

  function deleteAssessment(
    schemeIndex: number,
    id: string
  ) {
    setSchemes((prev) =>
      prev.map((scheme, i) =>
        i !== schemeIndex
          ? scheme
          : {
              ...scheme,
              assessments: scheme.assessments.filter(
                (a) => a.id !== id
              ),
            }
      )
    );
    markDirty();
  }

  function updateAssessmentName(
    schemeIndex: number,
    id: string,
    value: string
  ) {
    setSchemes((prev) =>
      prev.map((scheme, i) =>
        i !== schemeIndex
          ? scheme
          : {
              ...scheme,
              assessments: scheme.assessments.map((a) =>
                a.id === id
                  ? {
                      ...a,
                      nameInput: value,
                      name: value,
                    }
                  : a
              ),
            }
      )
    );
    markDirty();
  }

  function addScheme() {
    if (schemes.length >= 5) {
      setSchemeLimitError("Limit of 5 marking schemes reached.");
      return;
    }

    const nextIndex = schemes.length + 1;
    const newScheme: GradingScheme = {
      name: `Marking Scheme ${nextIndex}`,
      assessments: [],
    };

    setSchemes((prev) => [...prev, newScheme]);
    setSchemeLimitError(null);
    markDirty();
  }

  function deleteScheme(schemeIndex: number) {
    setSchemes((prev) =>
      prev.filter((_, i) => i !== schemeIndex)
    );
    setSchemeLimitError(null);
    markDirty();
  }

  const isValidLocation = (value: string) => value.length <= 100;
  const isNonEmptyName = (value: string) => value.trim().length > 0;
  const normalizeEmptyOrDash = (value?: string | null) => {
    if (!value) return null;
    const trimmed = value.trim();
    if (trimmed === "") return null;
    if (trimmed === "-") return null;
    return value;
  };

  const normalizeDateForSave = (value?: string | null) => {
    const normalized = normalizeEmptyOrDash(value);
    if (!normalized) return null;
    const trimmed = normalized.trim();
    if (/^\d{4}-\d{2}-\d{2}$/.test(trimmed)) {
      return isValidDateString(trimmed) ? trimmed : null;
    }
    const parsed = new Date(trimmed);
    if (Number.isNaN(parsed.getTime())) return null;
    return parsed.toISOString().slice(0, 10);
  };

  const normalizeTimeForSave = (value?: string | null) => {
    const normalized = normalizeEmptyOrDash(value);
    if (!normalized) return null;
    const trimmed = normalized.trim();
    if (/^\d{2}:\d{2}$/.test(trimmed)) {
      return isValidTimeString(trimmed) ? trimmed : null;
    }
    const parsed = new Date(trimmed);
    if (Number.isNaN(parsed.getTime())) return null;
    const hours = parsed.getHours().toString().padStart(2, "0");
    const minutes = parsed.getMinutes().toString().padStart(2, "0");
    return `${hours}:${minutes}`;
  };

  const buildSavePayload = () => {
    return {
      courseCode,
      // Sent so the server can confirm we are writing where we think we are. It refuses
      // anything but the current term with a 409 rather than trusting this value.
      term,
      schemes: schemes.map((scheme) => ({
        schemeName: scheme.name,
        assessments: scheme.assessments.map((a) => {
          const nameValue = (a.nameInput ?? a.name ?? "").trim();
          const locationValue =
            normalizeEmptyOrDash(a.locationInput ?? a.location ?? "")
              ?.trim() ?? "";
          const dueValue = normalizeDateForSave(
            a.dueDateInput ?? a.dueDate ?? null
          );
          const startValue =
            normalizeTimeForSave(
              a.startTimeInput ?? a.startTime ?? null
            );
          const endValue = normalizeTimeForSave(
            a.endTimeInput ?? a.endTime ?? null
          );

          const parsedWeightInput =
            a.weightInput && a.weightInput !== "."
              ? Number(a.weightInput)
              : null;
          const baseWeight =
            a.weights.find((w) => w !== 0) ?? a.weights[0] ?? 0;
          const normalizedBaseWeight =
            baseWeight > 1 ? baseWeight / 100 : baseWeight;
          const weight =
            a.overrideWeight != null
              ? a.overrideWeight
              : parsedWeightInput != null
                ? parsedWeightInput / 100
                : normalizedBaseWeight;

          return {
            name: nameValue,
            description: "",
            location: locationValue || null,
            dueDate: dueValue,
            startTime: startValue,
            endTime: endValue,
            weight,
            grade: a.grade ?? null,
          };
        }),
      })),
    };
  };

  const allFieldsValid = useMemo(() => {
    return schemes.every((scheme) =>
      scheme.assessments.every((a) => {
        if (a.nameInput !== undefined) {
          const nameValue = (a.nameInput ?? "").trim();
          if (!isNonEmptyName(nameValue)) return false;
        }

        if (a.locationInput !== undefined) {
          const locationValue =
            normalizeEmptyOrDash(a.locationInput)?.trim() ?? "";
          if (!isValidLocation(locationValue)) return false;
        }

        if (a.dueDateInput !== undefined) {
          const rawDue = normalizeEmptyOrDash(a.dueDateInput);
          const dueValue = normalizeDateForSave(rawDue);
          if (rawDue && !dueValue) return false;
        }

        if (a.startTimeInput !== undefined) {
          const rawStart = normalizeEmptyOrDash(a.startTimeInput);
          const startValue = normalizeTimeForSave(rawStart);
          if (rawStart && !startValue) return false;
        }

        if (a.endTimeInput !== undefined) {
          const rawEnd = normalizeEmptyOrDash(a.endTimeInput);
          const endValue = normalizeTimeForSave(rawEnd);
          if (rawEnd && !endValue) return false;
        }

        return true;
      })
    );
  }, [schemes]);

  /** The grade the server currently believes this course has, per the shared cache. */
  const cachedGrade = () =>
    queryClient
      .getQueryData<CurrentCourseSummary[]>(qk.currentCourses(term))
      ?.find((c) => c.courseCode === courseCode)?.grade ?? null;

  /**
   * Saves the table and the grade it produces in a single request.
   *
   * These used to be two calls on two timers behind two different validity gates, which
   * let them drift: a half-typed date blocked the table save while the grade PUT went
   * through anyway, leaving the server with a grade its own table did not produce. One
   * call means the grade can only ever move together with the rows behind it.
   */
  const saveMutation = useMutation({
    mutationFn: (payload: ReturnType<typeof buildSavePayload> & { grade: number | null }) =>
      authedMutate("/api/assessment-table/save", {
        method: "POST",
        body: JSON.stringify(payload),
      }),

    onMutate: async ({ grade }) => {
      setSaveState("saving");
      setSaveError(null);

      // The table itself is not written into the cache here: the GET shape and the save
      // payload are not the same, and inventing one from the other risks corrupting the
      // entry. Local `schemes` already shows the edit instantly, and the cache picks up
      // the confirmed value on success.
      if (grade === null) return { previousCourses: undefined };

      await queryClient.cancelQueries({ queryKey: qk.currentCourses(term) });
      const previousCourses = queryClient.getQueryData<CurrentCourseSummary[]>(
        qk.currentCourses(term)
      );

      queryClient.setQueryData<CurrentCourseSummary[]>(qk.currentCourses(term), (old) => {
        if (!old) return old;
        return old.map((c) =>
          c.courseCode === courseCode ? { ...c, grade } : c
        );
      });

      return { previousCourses };
    },

    onError: (err, _vars, context) => {
      // Read before the guards below narrow err down: after them TypeScript has nothing left
      // to call .status on.
      const rejection = err instanceof HttpError ? err : null;

      if (isAuthError(err)) {
        onAuthError();
        return;
      }

      // Connection failures are still retrying under mutationRetry — leave the optimistic
      // value alone. Only a refusal is final.
      if (!isServerRejection(err)) return;

      if (context?.previousCourses) {
        queryClient.setQueryData(qk.currentCourses(term), context.previousCourses);
      }
      setSaveState("error");

      // A 409 means this term is not writable. Either the screen is stale or the term rolled
      // over while it was open, so re-read the term list rather than just reporting a failure.
      if (rejection?.status === 409) {
        setSaveError(rejection.serverMessage ?? "Past terms are view only");
        queryClient.invalidateQueries({ queryKey: qk.terms });
        return;
      }
      setSaveError("Save failed");
    },

    onSuccess: (_res, { grade }) => {
      setSaveState("saved");
      setDirty(false);

      const includeInGpa =
        queryClient
          .getQueryData<CurrentCourseSummary[]>(qk.currentCourses(term))
          ?.find((c) => c.courseCode === courseCode)?.includeInGpa ?? true;

      // The GPA only moved if a grade went up and this course counts toward it.
      if (grade !== null && includeInGpa) {
        queryClient.invalidateQueries({ queryKey: qk.dashboard });
      }

      onSaved?.();
    },
  });

  const saveAssessmentTable = () => {
    // A past term never saves. The autosave effect below already returns before arming its
    // timer, and the inputs are disabled, so reaching here means something upstream changed
    // its mind. Stopping here keeps that from becoming a request the server has to refuse.
    if (!editable) return;

    if (!allFieldsValid) {
      setSaveState("error");
      setSaveError("Fix errors to save.");
      return;
    }

    const previousGrade = cachedGrade();
    // Compared at 2dp: bestGrade carries full float precision while the stored value is
    // a rounded BigDecimal, so a raw !== would report a change on every single save.
    const round2 = (n: number) => Math.round(n * 100) / 100;
    const unchanged =
      previousGrade !== null && round2(bestGrade) === round2(previousGrade);

    // Omitting an unchanged grade keeps a due-date edit from touching Postgres at all.
    const grade = unchanged ? null : bestGrade;

    saveMutation.mutate({ ...buildSavePayload(), grade });
  };

  useEffect(() => {
    if (!hasInitialized.current) {
      hasInitialized.current = true;
      return;
    }

    // The earliest exit for a past term: the timer is never armed, so nothing downstream can
    // fire a save. Disabling the inputs is what the user sees; this is what makes it true.
    if (!editable) return;
    if (!dirty) return;
    if (!allFieldsValid) return;

    if (autosaveTimer.current) {
      clearTimeout(autosaveTimer.current);
    }

    autosaveTimer.current = setTimeout(() => {
      saveAssessmentTable();
    }, 2000);

    return () => {
      if (autosaveTimer.current) {
        clearTimeout(autosaveTimer.current);
      }
    };
  }, [schemes, dirty, allFieldsValid, editable]);

  useEffect(() => {
    if (!autoSavePending.current) return;
    if (!editable) {
      // Drop the request rather than holding it: switching back to the current term must not
      // flush a save that was queued while a past term was on screen.
      autoSavePending.current = false;
      return;
    }
    if (loading) return;
    if (!allFieldsValid) return;
    autoSavePending.current = false;
    saveAssessmentTable();
  }, [schemes, loading, allFieldsValid, editable]);

  if (loading) {
    return (
      <View style={[styles.center, variant === "inline" && styles.inlineCenter]}>
        <ActivityIndicator />
      </View>
    );
  }

  if (error) {
    return (
      <View style={[styles.center, variant === "inline" && styles.inlineCenter]}>
        <Text>{error}</Text>
      </View>
    );
  }

  return (
    <ScrollView
      style={[
        styles.container,
        variant === "inline" && styles.inlineContainer,
      ]}
      nestedScrollEnabled
    >
      {/* Said once, at the top, rather than as a tooltip on every disabled field. */}
      {!editable && (
        <View style={styles.readOnlyBanner}>
          <Feather name="eye" size={14} color={colors.bodyText} />
          <Text style={styles.readOnlyBannerText}>
            {term} is a past term. You can look, but not edit.
          </Text>
        </View>
      )}

      {/* Controls that write are hidden for a past term, not shown disabled. A button that
          can never work is noise. */}
      {editable && (
        <View style={styles.saveRow}>
          <Pressable
            style={[
              styles.saveButton,
              (!allFieldsValid || !dirty) && styles.saveButtonDisabled,
            ]}
            onPress={saveAssessmentTable}
            disabled={!allFieldsValid || !dirty}
          >
            <Text style={styles.saveButtonText}>Save</Text>
          </Pressable>
          <Text style={styles.saveStatus}>
            {saveState === "saving" && "Saving..."}
            {saveState === "saved" && "Saved"}
            {saveState === "error" && (saveError ?? "Save failed")}
            {saveState === "idle" &&
              (!dirty
                ? "No changes"
                : !allFieldsValid
                  ? "Fix errors to save"
                  : "Unsaved changes")}
          </Text>
        </View>
      )}

      {editable && (
        <Pressable
          style={[
            styles.addSchemeButton,
            schemes.length >= 5 && styles.addSchemeButtonDisabled,
          ]}
          onPress={addScheme}
          disabled={schemes.length >= 5}
        >
          <Text style={styles.addSchemeButtonText}>
            Add Marking Scheme
          </Text>
        </Pressable>
      )}
      {schemeLimitError && (
        <Text style={styles.warningText}>{schemeLimitError}</Text>
      )}

      {schemes.map((scheme, schemeIndex) => (
        <View key={scheme.name} style={[styles.schemeBox, !editable && styles.readOnlyScheme]}>
          <View style={styles.schemeHeader}>
            <Text style={styles.title}>{scheme.name}</Text>
            {editable && (
              <Pressable
                style={styles.deleteButton}
                onPress={() => deleteScheme(schemeIndex)}
              >
                <Feather
                  name="trash-2"
                  size={16}
                  color={colors.bodyText}
                />
              </Pressable>
            )}
          </View>
          {editable && (
            <Pressable
              style={styles.addButton}
              onPress={() => addAssessment(schemeIndex)}
            >
              <Text style={styles.addButtonText}>
                Add Assessment
              </Text>
            </Pressable>
          )}

          <View style={styles.headerRow}>
            <Text style={[styles.col, styles.col2]}>
              Assignment
            </Text>
            <Text style={styles.col}>Due Date (YYYY-MM-DD)</Text>
            <Text style={styles.col}>Start (HH:mm)</Text>
            <Text style={styles.col}>End (HH:mm)</Text>
            <Text style={[styles.col, styles.col2]}>Location</Text>
            <Text style={styles.col}>Weight</Text>
            <Text style={styles.col}>Grade</Text>
            <View style={styles.colDelete} />
          </View>

          {scheme.assessments.map((a) => {
            const baseWeight =
              a.overrideWeight ??
              (a.weights.find((w) => w !== 0) ??
                a.weights[0]);

            return (
              <View key={a.id} style={styles.row}>
                <View style={[styles.cell, styles.col2]}>
                  {isWeb ? (
                    <TextInput
                      editable={editable}
                      style={[styles.nameInput, styles.flexFill]}
                      placeholder="Assessment name"
                      value={a.nameInput ?? a.name ?? ""}
                      onChangeText={(v) =>
                        updateAssessmentName(
                          schemeIndex,
                          a.id,
                          v
                        )
                      }
                    />
                  ) : (
                    <Text>{a.name}</Text>
                  )}
                  {a.nameInput !== undefined &&
                    !isNonEmptyName((a.nameInput ?? "").trim()) && (
                      <Text style={styles.warningText}>
                        Name is required
                      </Text>
                    )}
                </View>

                <View style={styles.cell}>
                  {isWeb ? (
                    <>
                      <TextInput
                      editable={editable}
                        style={styles.dateInput}
                        placeholder="-"
                        value={
                          a.dueDateInput !== undefined
                            ? a.dueDateInput ?? ""
                            : a.dueDate
                              ? formatDate(a.dueDate)
                              : ""
                        }
                        onChangeText={(v) =>
                          updateDueDateText(schemeIndex, a.id, v)
                        }
                        onBlur={() => {
                          const normalized = normalizeDateInput(
                            a.dueDateInput ?? ""
                          );
                          updateDueDateText(
                            schemeIndex,
                            a.id,
                            isValidDateString(normalized) ? normalized : null
                          );
                        }}
                      />
                      {shouldShowDateWarning(a.dueDateInput ?? "") && (
                        <Text style={styles.warningText}>
                          Use YYYY-MM-DD
                        </Text>
                      )}
                    </>
                  ) : (
                    <Pressable
                      disabled={!editable}
                      onPress={() =>
                        setActivePicker({
                          schemeIndex,
                          assessmentId: a.id,
                          field: "dueDate",
                        })
                      }
                    >
                      <Text style={styles.editableText}>
                        {formatDate(a.dueDateInput ?? a.dueDate)}
                      </Text>
                    </Pressable>
                  )}
                </View>

                <View style={styles.cell}>
                  {isWeb ? (
                    <>
                      <TextInput
                      editable={editable}
                        style={styles.timeInput}
                        placeholder="-"
                        value={
                          a.startTimeInput !== undefined
                            ? a.startTimeInput ?? ""
                            : a.startTime
                              ? formatTime(a.startTime)
                              : ""
                        }
                        onChangeText={(v) =>
                          updateTimeText(
                            schemeIndex,
                            a.id,
                            "startTime",
                            v
                          )
                        }
                        onBlur={() => {
                          const normalized = normalizeTimeInput(
                            a.startTimeInput ?? ""
                          );
                          updateTimeText(
                            schemeIndex,
                            a.id,
                            "startTime",
                            isValidTimeString(normalized) ? normalized : null
                          );
                        }}
                      />
                      {shouldShowTimeWarning(a.startTimeInput ?? "") && (
                        <Text style={styles.warningText}>Use HH:mm</Text>
                      )}
                    </>
                  ) : (
                    <Pressable
                      disabled={!editable}
                      onPress={() =>
                        setActivePicker({
                          schemeIndex,
                          assessmentId: a.id,
                          field: "startTime",
                        })
                      }
                    >
                      <Text style={styles.editableText}>
                        {formatTime(
                          a.startTimeInput ?? a.startTime
                        )}
                      </Text>
                    </Pressable>
                  )}
                </View>

                <View style={styles.cell}>
                  {isWeb ? (
                    <>
                      <TextInput
                      editable={editable}
                        style={styles.timeInput}
                        placeholder="-"
                        value={
                          a.endTimeInput !== undefined
                            ? a.endTimeInput ?? ""
                            : a.endTime
                              ? formatTime(a.endTime)
                              : ""
                        }
                        onChangeText={(v) =>
                          updateTimeText(
                            schemeIndex,
                            a.id,
                            "endTime",
                            v
                          )
                        }
                        onBlur={() => {
                          const normalized = normalizeTimeInput(
                            a.endTimeInput ?? ""
                          );
                          updateTimeText(
                            schemeIndex,
                            a.id,
                            "endTime",
                            isValidTimeString(normalized) ? normalized : null
                          );
                        }}
                      />
                      {shouldShowTimeWarning(a.endTimeInput ?? "") && (
                        <Text style={styles.warningText}>Use HH:mm</Text>
                      )}
                    </>
                  ) : (
                    <Pressable
                      disabled={!editable}
                      onPress={() =>
                        setActivePicker({
                          schemeIndex,
                          assessmentId: a.id,
                          field: "endTime",
                        })
                      }
                    >
                      <Text style={styles.editableText}>
                        {formatTime(a.endTimeInput ?? a.endTime)}
                      </Text>
                    </Pressable>
                  )}
                </View>

                <View style={[styles.cell, styles.col2]}>
                  <TextInput
                      editable={editable}
                    style={[styles.locationInput, styles.flexFill]}
                    placeholder="-"
                    value={a.locationInput ?? a.location ?? ""}
                    onChangeText={(v) =>
                      updateLocation(
                        schemeIndex,
                        a.id,
                        v
                      )
                    }
                  />
                  {a.locationInput !== undefined &&
                    !isValidLocation(
                      (a.locationInput ?? "").trim()
                    ) && (
                      <Text style={styles.warningText}>
                        Max 100 characters
                      </Text>
                    )}
                </View>

                <View style={styles.cell}>
                  <TextInput
                      editable={editable}
                    style={[styles.weightInput, styles.flexFill]}
                    keyboardType="decimal-pad"
                    value={
                      a.weightInput ??
                      (
                        (
                          a.overrideWeight ??
                          (a.weights.find((w) => w !== 0) ??
                            a.weights[0])
                        ) * 100
                      ).toString()
                    }
                    onChangeText={(v) =>
                      updateWeight(
                        schemeIndex,
                        a.id,
                        v
                      )
                    }
                  />
                </View>

                <View style={styles.cell}>
                  <TextInput
                      editable={editable}
                    style={[styles.gradeInput, styles.flexFill]}
                    keyboardType="decimal-pad"
                    placeholder="-"
                    value={a.gradeInput ?? ""}
                    onChangeText={(v) =>
                      updateGrade(
                        schemeIndex,
                        a.id,
                        v
                      )
                    }
                  />
                </View>

                <View style={styles.colDelete}>
                  {editable && (
                    <Pressable
                      style={styles.deleteButton}
                      onPress={() =>
                        deleteAssessment(schemeIndex, a.id)
                      }
                    >
                      <Feather
                        name="trash-2"
                        size={16}
                        color={colors.bodyText}
                      />
                    </Pressable>
                  )}
                </View>


              </View>
            );
          })}

          <Text style={styles.schemeGrade}>
            Scheme Grade:{" "}
            {schemeGrades[
              schemeIndex
            ].toFixed(2)}
            %
          </Text>
        </View>
      ))}

      {activePicker && (
        <DateTimePicker
          value={
            activePicker.field === "dueDate"
              ? getDateValue(
                  schemes[activePicker.schemeIndex]?.assessments.find(
                    (a) => a.id === activePicker.assessmentId
                  )?.dueDateInput ??
                    schemes[activePicker.schemeIndex]?.assessments.find(
                      (a) => a.id === activePicker.assessmentId
                    )?.dueDate
                )
              : getTimeDate(
                  activePicker.field === "startTime"
                    ? schemes[activePicker.schemeIndex]?.assessments.find(
                        (a) => a.id === activePicker.assessmentId
                      )?.startTimeInput ??
                        schemes[activePicker.schemeIndex]?.assessments.find(
                          (a) => a.id === activePicker.assessmentId
                        )?.startTime
                    : schemes[activePicker.schemeIndex]?.assessments.find(
                        (a) => a.id === activePicker.assessmentId
                      )?.endTimeInput ??
                        schemes[activePicker.schemeIndex]?.assessments.find(
                          (a) => a.id === activePicker.assessmentId
                        )?.endTime
                )
          }
          mode={activePicker.field === "dueDate" ? "date" : "time"}
          display="default"
          onChange={(event, selectedDate) => {
            if (event.type === "dismissed") {
              setActivePicker(null);
              return;
            }

            if (activePicker.field === "dueDate") {
              updateDueDate(
                activePicker.schemeIndex,
                activePicker.assessmentId,
                selectedDate ?? null
              );
            } else {
              updateTimeField(
                activePicker.schemeIndex,
                activePicker.assessmentId,
                activePicker.field,
                selectedDate ?? null
              );
            }
            setActivePicker(null);
          }}
        />
      )}
    </ScrollView>
  );

  function updateDueDate(
    schemeIndex: number,
    id: string,
    date: Date | null
  ) {
    setSchemes((prev) =>
      prev.map((scheme, i) =>
        i !== schemeIndex
          ? scheme
          : {
              ...scheme,
              assessments: scheme.assessments.map((a) =>
                a.id === id
                  ? {
                      ...a,
                      dueDateInput: date
                        ? date.toISOString().slice(0, 10)
                        : null,
                    }
                  : a
              ),
            }
      )
    );
    markDirty();
  }

  function updateTimeField(
    schemeIndex: number,
    id: string,
    field: "startTime" | "endTime",
    date: Date | null
  ) {
    const timeValue = date
      ? `${date.getHours().toString().padStart(2, "0")}:${date
          .getMinutes()
          .toString()
          .padStart(2, "0")}`
      : null;

    setSchemes((prev) =>
      prev.map((scheme, i) =>
        i !== schemeIndex
          ? scheme
          : {
              ...scheme,
              assessments: scheme.assessments.map((a) =>
                a.id === id
                  ? {
                      ...a,
                      startTimeInput:
                        field === "startTime" ? timeValue : a.startTimeInput,
                      endTimeInput:
                        field === "endTime" ? timeValue : a.endTimeInput,
                    }
                  : a
              ),
            }
      )
    );
    markDirty();
  }

  function updateLocation(
    schemeIndex: number,
    id: string,
    value: string
  ) {
    setSchemes((prev) =>
      prev.map((scheme, i) =>
        i !== schemeIndex
          ? scheme
          : {
              ...scheme,
              assessments: scheme.assessments.map((a) =>
                a.id === id ? { ...a, locationInput: value } : a
              ),
            }
      )
    );
    markDirty();
  }

  function updateDueDateText(
    schemeIndex: number,
    id: string,
    value: string | null
  ) {
    if (value !== null && !isValidDatePartial(value)) return;

    setSchemes((prev) =>
      prev.map((scheme, i) =>
        i !== schemeIndex
          ? scheme
          : {
              ...scheme,
              assessments: scheme.assessments.map((a) =>
                a.id === id ? { ...a, dueDateInput: value } : a
              ),
            }
      )
    );
    markDirty();
  }

  function updateTimeText(
    schemeIndex: number,
    id: string,
    field: "startTime" | "endTime",
    value: string | null
  ) {
    if (value !== null && !isValidTimePartial(value)) return;

    setSchemes((prev) =>
      prev.map((scheme, i) =>
        i !== schemeIndex
          ? scheme
          : {
              ...scheme,
              assessments: scheme.assessments.map((a) =>
                a.id === id
                  ? {
                      ...a,
                      startTimeInput:
                        field === "startTime" ? value : a.startTimeInput,
                      endTimeInput:
                        field === "endTime" ? value : a.endTimeInput,
                    }
                  : a
              ),
            }
      )
    );
    markDirty();
  }

function updateGrade(
  schemeIndex: number,
  id: string,
  value: string
) {
  if (!/^\d*\.?\d*$/.test(value)) return;

  let parsed: number | undefined = undefined;

  if (value !== "" && value !== ".") {
    const num = parseFloat(value);
    if (!isNaN(num)) {
      parsed = num;
    }
  }

  setSchemes((prev) =>
    prev.map((scheme, i) =>
      i !== schemeIndex
        ? scheme
        : {
            ...scheme,
            assessments:
              scheme.assessments.map(
                (a) =>
                  a.id === id
                    ? {
                        ...a,
                        gradeInput: value,
                        grade: parsed,
                      }
                    : a
              ),
          }
    )
  );
  markDirty();
}


  function updateWeight(
    schemeIndex: number,
    id: string,
    value: string
  ) {
    // Allow only digits and one decimal point
    if (!/^\d*\.?\d*$/.test(value)) return;

    let parsed: number | undefined = undefined;

    if (value !== "" && value !== ".") {
      const num = parseFloat(value);
      if (!isNaN(num)) {
        parsed = num / 100; // store as decimal
      }
    }

    setSchemes((prev) =>
      prev.map((scheme, i) =>
        i !== schemeIndex
          ? scheme
          : {
              ...scheme,
              assessments:
                scheme.assessments.map(
                  (a) =>
                    a.id === id
                      ? {
                          ...a,
                          weightInput: value,
                          overrideWeight: parsed,
                        }
                      : a
                ),
            }
      )
    );
    markDirty();
  }
}


function parseBackendResponse(
  data: BackendResponse
): GradingScheme[] {
  return Object.entries(data).map(
    ([schemeName, assessments]) => ({
          name: schemeName,
          assessments: assessments.map(
        (a, index) => ({
          id: `${schemeName}-${index}`,
          name: a.assessmentName,
          weights: a.weights,
          dueDate: a.dueDate,
          startTime: a.startTime,
          endTime: a.endTime,
          location: a.location,
          dropCount:
            a.n && a.n > 0
              ? a.n
              : undefined,
          grade: a.grade ?? undefined,
          gradeInput: a.grade != null ? String(a.grade) : undefined,
        })
      ),
    })
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    padding: 16,
    backgroundColor: "#f4f4f4",
  },
  inlineContainer: {
    flex: 0,
    padding: 0,
    backgroundColor: "transparent",
  },
  center: {
    flex: 1,
    justifyContent: "center",
    alignItems: "center",
  },
  inlineCenter: {
    paddingVertical: 16,
  },
  schemeBox: {
    marginBottom: 24,
    padding: 16,
    borderRadius: 12,
    backgroundColor: "white",
  },
  // Recessed rather than faded. This is a reading surface, so the text has to stay
  // comfortably legible: a low opacity would make the feature useless.
  readOnlyScheme: {
    backgroundColor: "#F4F4F7",
    opacity: 0.92,
  },
  readOnlyBanner: {
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
    paddingVertical: 10,
    paddingHorizontal: 12,
    marginBottom: 12,
    borderRadius: 8,
    backgroundColor: "#EEEEF5",
  },
  readOnlyBannerText: {
    flex: 1,
    fontSize: 13,
    color: colors.bodyText,
  },
  title: {
    fontSize: 18,
    fontWeight: "bold",
    marginBottom: 12,
  },
  headerRow: {
    flexDirection: "row",
    marginBottom: 8,
  },
  col: {
    flex: 1,
    fontWeight: "bold",
    fontSize: 12,
    color: "#666",
  },
  row: {
    flexDirection: "row",
    alignItems: "center",
    marginBottom: 12,
    paddingVertical: 6,
  },
  col2: {
    flex: 2,
  },
  colDelete: {
    flex: 0.5,
    alignItems: "center",
  },
  flexFill: {
    flex: 1,
  },
  nameInput: {
    borderWidth: 1,
    borderColor: "#ddd",
    padding: 6,
    borderRadius: 6,
  },
  cell: {
    flex: 1,
    fontSize: 14,
  },
  editableText: {
    color: "#222",
  },
  weightInput: {
    flex: 1,
    borderWidth: 1,
    borderColor: "#ddd",
    padding: 6,
    borderRadius: 6,
    textAlign: "center",
  },
  dateInput: {
    borderWidth: 1,
    borderColor: "#ddd",
    padding: 6,
    borderRadius: 6,
    textAlign: "center",
  },
  timeInput: {
    borderWidth: 1,
    borderColor: "#ddd",
    padding: 6,
    borderRadius: 6,
    textAlign: "center",
  },
  warningText: {
    color: "#c0392b",
    fontSize: 12,
    marginTop: 4,
    textAlign: "center",
  },
  locationInput: {
    borderWidth: 1,
    borderColor: "#ddd",
    padding: 6,
    borderRadius: 6,
  },
  gradeInput: {
    flex: 1,
    borderWidth: 1,
    borderColor: "#ddd",
    padding: 6,
    borderRadius: 6,
    textAlign: "center",
  },
  schemeGrade: {
    marginTop: 12,
    fontWeight: "bold",
    color: "#7B003B",
  },
  saveRow: {
    flexDirection: "row",
    alignItems: "center",
    marginBottom: 12,
    gap: 12,
  },
  saveButton: {
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: 8,
    backgroundColor: "#0f5bd7",
  },
  saveButtonDisabled: {
    opacity: 0.5,
  },
  saveButtonText: {
    color: "#fff",
    fontWeight: "bold",
    fontSize: 12,
  },
  saveStatus: {
    fontSize: 12,
    color: "#666",
  },
  schemeHeader: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    marginBottom: 8,
  },
  addSchemeButton: {
    alignSelf: "flex-start",
    marginBottom: 16,
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderRadius: 8,
    backgroundColor: "#0f5bd7",
  },
  addSchemeButtonDisabled: {
    opacity: 0.5,
  },
  addSchemeButtonText: {
    color: "#fff",
    fontWeight: "bold",
    fontSize: 12,
  },
  addButton: {
    alignSelf: "flex-start",
    marginBottom: 12,
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderRadius: 8,
    backgroundColor: "#1f6feb",
  },
  addButtonText: {
    color: "#fff",
    fontWeight: "bold",
    fontSize: 12,
  },
  deleteButton: {
    padding: 6,
    borderRadius: 6,
    alignItems: "center",
  },
});
