import { useEffect, useRef, useState } from "react";
import { checkHandle } from "./api";
import { localHandleRejection } from "./handle";

/**
 * Asks the server whether a handle is free, while the student is still typing.
 *
 * Debounced, because the alternative is a request per keystroke for an answer that only matters
 * once they stop. Anything the local mirror can already reject never leaves the device: there is
 * no point asking whether a 25-character handle is taken.
 *
 * Nothing is reserved by asking. Two students can both be told a handle is free and the second one
 * to submit loses, which is the right trade: holding a handle on a keystroke would let anyone empty
 * the namespace by typing in the box. The server checks again on submit and that answer is final.
 */

const DEBOUNCE_MS = 400;

export type HandleAvailability =
  | { state: "idle" }
  | { state: "checking" }
  | { state: "available" }
  | { state: "taken"; reason: string };

export function useHandleAvailability(
  handle: string,
  options?: { skip?: boolean }
): HandleAvailability {
  const [result, setResult] = useState<HandleAvailability>({ state: "idle" });

  // Only the newest request is allowed to write an answer. Without this, a slow reply for "ahme"
  // can land after a fast one for "ahmed" and label the finished handle with a stale verdict.
  const latest = useRef(0);
  const skip = options?.skip ?? false;
  const trimmed = handle.trim();

  useEffect(() => {
    if (skip || trimmed.length === 0 || localHandleRejection(trimmed) !== null) {
      setResult({ state: "idle" });
      return;
    }

    const request = ++latest.current;
    setResult({ state: "checking" });

    const timer = setTimeout(async () => {
      try {
        const answer = await checkHandle(trimmed);
        if (request !== latest.current) return;
        setResult(
          answer.available
            ? { state: "available" }
            : { state: "taken", reason: answer.reason ?? "Pick a different handle." }
        );
      } catch {
        // A check that could not run is not a rejection. Staying quiet leaves the student able to
        // submit, where the server decides for real.
        if (request === latest.current) setResult({ state: "idle" });
      }
    }, DEBOUNCE_MS);

    return () => clearTimeout(timer);
  }, [trimmed, skip]);

  return result;
}
