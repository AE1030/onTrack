import { API_BASE_URL } from "../config/api";
import { getToken, refreshSession } from "../utils/tokenStorage";

const BASE = API_BASE_URL.replace(/\/$/, "");

/** Thrown on 401/403, or when no token is stored. Screens map this to onAuthError. */
export class AuthError extends Error {
  constructor(message = "Not authenticated") {
    super(message);
    this.name = "AuthError";
  }
}

/** Thrown on any other non-2xx. Carries the status so callers can branch (e.g. 404). */
export class HttpError extends Error {
  status: number;
  /**
   * The server's own wording, when it sent any.
   *
   * GlobalExceptionHandler answers every rejection with {"error": "...", "status": n}, and for
   * flows like leaderboard onboarding that sentence is the only thing that distinguishes "that
   * handle is taken" from "set a target GPA to join". Screens that have nothing better to say
   * should prefer this over inventing a generic message.
   */
  serverMessage?: string;
  constructor(status: number, message?: string) {
    super(message ?? `Request failed with ${status}`);
    this.name = "HttpError";
    this.status = status;
    this.serverMessage = message;
  }
}

/**
 * Pulls the error sentence out of a failed response, or null when there isn't one.
 *
 * Most endpoints answer through GlobalExceptionHandler's {"error": "..."} shape, but a few auth
 * endpoints (forgot-password, verify/resend) answer with a bare sentence. Those are accepted too,
 * as long as they look like a sentence: an HTML error page from a proxy, or JSON in some other
 * shape, must never reach the screen.
 */
export async function readServerError(res: Response): Promise<string | null> {
  let raw: string;
  try {
    raw = (await res.text()).trim();
  } catch {
    return null;
  }
  if (!raw) return null;
  try {
    const body = JSON.parse(raw);
    return typeof body?.error === "string" ? body.error : null;
  } catch {
    const looksLikeSentence = raw.length <= 200 && !/[\n<{[]/.test(raw);
    return looksLikeSentence ? raw : null;
  }
}

/** An HttpError for a failed response, carrying the server's sentence when it sent one. */
export async function toHttpError(res: Response): Promise<HttpError> {
  return new HttpError(res.status, (await readServerError(res)) ?? undefined);
}

/**
 * fetch with the JWT attached. Throws AuthError on 401/403; does not check other statuses.
 *
 * getToken already refreshes a token it can see is expired. A 401 anyway (clock skew, or a token
 * the server stopped accepting) gets one refresh and one retry before it counts as signed out.
 */
export async function authedFetch(
  path: string,
  init: RequestInit = {}
): Promise<Response> {
  const token = await getToken();
  if (!token) throw new AuthError();

  let res = await send(path, init, token);

  if (res.status === 401) {
    const fresh = await refreshSession(token);
    if (!fresh || fresh === token) throw new AuthError();
    res = await send(path, init, fresh);
  }

  if (res.status === 401 || res.status === 403) throw new AuthError();
  return res;
}

function send(path: string, init: RequestInit, token: string): Promise<Response> {
  return fetch(`${BASE}${path}`, {
    ...init,
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
      ...(init.headers ?? {}),
    },
  });
}

/**
 * Like authedFetch, but throws HttpError on any non-2xx instead of handing back
 * the response. Mutations must use this: a save that silently returns a 400 to a
 * caller that never checks res.ok leaves the user looking at a value the server
 * rejected.
 */
export async function authedMutate(
  path: string,
  init: RequestInit = {}
): Promise<Response> {
  const res = await authedFetch(path, init);
  if (!res.ok) throw await toHttpError(res);
  return res;
}

/**
 * GET returning parsed JSON. Throws AuthError on 401/403, HttpError otherwise.
 * An empty body resolves to null rather than throwing a parse error.
 */
export async function authedGet<T>(path: string): Promise<T> {
  const res = await authedFetch(path);
  if (!res.ok) throw await toHttpError(res);

  const raw = await res.text();
  return (raw ? JSON.parse(raw) : null) as T;
}

/** True when the error should send the user back to login. */
export const isAuthError = (err: unknown): err is AuthError =>
  err instanceof AuthError;

/**
 * Queries should not burn retries on an expired token — one retry for genuine
 * network flakiness, none for auth.
 */
export const queryRetry = (failureCount: number, err: unknown) =>
  !isAuthError(err) && failureCount < 1;

/** True when the server answered and refused. Retrying will not change its mind. */
export const isServerRejection = (err: unknown): err is HttpError =>
  err instanceof HttpError;

/**
 * Splits mutation failures into the two cases that need different handling.
 *
 * A rejection (any non-2xx) is final — the caller rolls the optimistic value
 * back. Anything else means fetch itself never got an answer, i.e. the device is
 * offline or the network dropped, so we keep retrying and leave the UI alone.
 */
export const mutationRetry = (failureCount: number, err: unknown) => {
  if (isAuthError(err) || isServerRejection(err)) return false;
  return failureCount < 5;
};
