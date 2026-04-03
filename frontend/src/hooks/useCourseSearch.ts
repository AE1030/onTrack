import { useEffect, useMemo, useRef, useState } from "react";
import { getToken } from "../utils/tokenStorage";

export type CourseLookupDTO = {
  id: string;
  courseCode: string;
  courseName: string;
  courseCredits: number;
};

export type PageResponse<T> = {
  content: T[];
  number: number;
  size: number;
  totalPages: number;
  last: boolean;
};

type Params = {
  query: string;
  baseUrl: string;
  pageSize?: number;
  onAuthError?: () => void;
  debounceMs?: number;
};

export function useCourseSearch({
  query,
  baseUrl,
  pageSize = 20,
  onAuthError,
  debounceMs = 250,
}: Params) {
  const [results, setResults] = useState<CourseLookupDTO[]>([]);
  const [loading, setLoading] = useState(false);
  const [page, setPage] = useState(0);
  const [last, setLast] = useState(false);

  const debounceTimer = useRef<ReturnType<typeof setTimeout> | null>(
    null
  );
  const [debouncedQuery, setDebouncedQuery] = useState("");
  const requestIdRef = useRef(0);

  useEffect(() => {
    if (debounceTimer.current) {
      clearTimeout(debounceTimer.current);
    }

    debounceTimer.current = setTimeout(() => {
      setDebouncedQuery(query.trim());
    }, debounceMs);

    return () => {
      if (debounceTimer.current) {
        clearTimeout(debounceTimer.current);
      }
    };
  }, [query, debounceMs]);

  useEffect(() => {
    setResults([]);
    setPage(0);
    setLast(false);
  }, [debouncedQuery]);

  const canSearch = useMemo(
    () => debouncedQuery.length >= 2,
    [debouncedQuery]
  );

  const fetchPage = async (targetPage: number) => {
    if (!canSearch) return;

    const currentRequestId = ++requestIdRef.current;
    setLoading(true);

    try {
      const token = await getToken();
      if (!token) {
        onAuthError?.();
        setLoading(false);
        return;
      }

      const url = new URL(
        `${baseUrl.replace(/\/$/, "")}/api/courses/search`
      );
      url.searchParams.set("q", debouncedQuery);
      url.searchParams.set("page", String(targetPage));
      url.searchParams.set("size", String(pageSize));

      const response = await fetch(url.toString(), {
        headers: {
          Authorization: `Bearer ${token}`,
        },
      });

      if (response.status === 401 || response.status === 403) {
        onAuthError?.();
        setLoading(false);
        return;
      }

      if (!response.ok) {
        throw new Error("Failed to search courses");
      }

      const data: PageResponse<CourseLookupDTO> =
        await response.json();

      if (currentRequestId !== requestIdRef.current) {
        return;
      }

      setResults((prev) =>
        targetPage === 0 ? data.content : [...prev, ...data.content]
      );
      setPage(data.number);
      setLast(data.last);
    } catch {
      if (currentRequestId !== requestIdRef.current) {
        return;
      }
    } finally {
      if (currentRequestId === requestIdRef.current) {
        setLoading(false);
      }
    }
  };

  useEffect(() => {
    if (!canSearch) return;
    fetchPage(0);
  }, [canSearch, debouncedQuery]);

  const loadNext = () => {
    if (loading || last) return;
    fetchPage(page + 1);
  };

  return {
    results,
    loading,
    canSearch,
    loadNext,
    reset: () => {
      setResults([]);
      setPage(0);
      setLast(false);
    },
  };
}
