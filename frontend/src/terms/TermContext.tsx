import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from "react";
import { useQuery } from "@tanstack/react-query";

import { authedGet, queryRetry } from "../cache/authedGet";
import { qk } from "../cache/keys";
import { getStoredTerm, setStoredTerm } from "./termStorage";

/**
 * One source of truth for which term the app is showing.
 *
 * Held above the screens rather than inside one, because MyCourses and CourseCalculator both
 * need it and app/index.tsx swaps screen components on every tab switch, so state owned by a
 * screen would die with it. That is the same reason queryClient sits at module scope.
 */

export type Term = {
  term: string;
  current: boolean;
  /**
   * Whether this term may be written to. Ask this, never `current`: the day a second editable
   * term exists (a summer term running alongside a fall one) nothing here has to change.
   */
  editable: boolean;
  courseCount: number;
};

type TermControl = {
  /** The term being shown. Empty string only before the first load resolves. */
  selectedTerm: string;
  /**
   * The term that is actually running now, regardless of what is selected.
   *
   * The dashboard pins to this rather than following the picker: its GPA is computed from the
   * current term only, so showing a past term's courses beside it would be incoherent.
   */
  currentTerm: string;
  /** Every term this student has, newest first. */
  terms: Term[];
  /** Whether the selected term accepts writes. Defaults to false until the list arrives. */
  editable: boolean;
  /** True while the term list is loading for the first time. */
  loading: boolean;
  setSelectedTerm: (term: string) => void;
  /** Re-reads the term list. Call after adding or deleting a course. */
  refresh: () => void;
};

const TermContext = createContext<TermControl | null>(null);

const fetchTerms = () => authedGet<Term[]>("/api/my-courses/terms");

export function TermProvider({ children }: { children: React.ReactNode }) {
  const [selected, setSelected] = useState<string>("");
  const [restored, setRestored] = useState(false);

  // The stored choice, read once on mount. Native cannot read SecureStore synchronously, so
  // the first render has no term and the query below supplies the fallback.
  useEffect(() => {
    let cancelled = false;
    getStoredTerm().then((stored) => {
      if (cancelled) return;
      if (stored) setSelected(stored);
      setRestored(true);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  const termsQuery = useQuery({
    queryKey: qk.terms,
    queryFn: fetchTerms,
    retry: queryRetry,
  });

  const terms = useMemo(() => termsQuery.data ?? [], [termsQuery.data]);

  // Reconcile the restored choice against what the server says this student actually has. A
  // stored term that is gone (a course deleted, a different account) falls back to the current
  // one rather than leaving the app pointed at a term with nothing in it.
  useEffect(() => {
    if (!restored || terms.length === 0) return;
    const known = terms.some((t) => t.term === selected);
    if (known) return;
    const fallback = terms.find((t) => t.current) ?? terms[0];
    if (fallback) setSelected(fallback.term);
  }, [restored, terms, selected]);

  const setSelectedTerm = useCallback((term: string) => {
    setSelected(term);
    void setStoredTerm(term);
  }, []);

  const value = useMemo<TermControl>(() => {
    const active = terms.find((t) => t.term === selected);
    const current = terms.find((t) => t.current);
    return {
      selectedTerm: selected,
      currentTerm: current?.term ?? "",
      terms,
      // Default to not editable while the list is in flight. Guessing the other way would let
      // a save fire against a term the server has not confirmed is writable.
      editable: active?.editable ?? false,
      loading: termsQuery.isLoading || !restored,
      setSelectedTerm,
      refresh: () => {
        void termsQuery.refetch();
      },
    };
  }, [terms, selected, termsQuery, restored, setSelectedTerm]);

  return <TermContext.Provider value={value}>{children}</TermContext.Provider>;
}

export function useTerm(): TermControl {
  const value = useContext(TermContext);
  if (!value) {
    throw new Error("useTerm must be used inside a TermProvider");
  }
  return value;
}
