import { HttpError } from "../cache/authedGet";

export const NETWORK_MESSAGE =
  "Could not reach the server. Check your connection and try again.";

/**
 * fetch rejects with a TypeError when no response came back at all. The wording is the
 * platform's own and differs everywhere: "Network request failed" (React Native), "Failed to
 * fetch" (Chrome), "Load failed" (Safari), "NetworkError when attempting..." (Firefox).
 */
export const isNetworkError = (err: unknown): boolean =>
  err instanceof TypeError && /network|fetch|load failed/i.test(err.message);

/**
 * The sentence to show for a failed request.
 *
 * Only two kinds of error carry wording fit for a student: a sentence the server chose to send
 * back, and a dropped connection, which gets our own. Everything else (JSON parse errors,
 * TypeErrors from a bug, "Internal server error") is developer text, and gets the caller's
 * fallback instead.
 */
export function toUserMessage(err: unknown, fallback: string): string {
  if (err instanceof HttpError) {
    return err.status < 500 && err.serverMessage ? err.serverMessage : fallback;
  }
  if (isNetworkError(err)) return NETWORK_MESSAGE;
  return fallback;
}
