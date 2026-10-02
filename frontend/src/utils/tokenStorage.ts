import { Platform } from "react-native";
import * as SecureStore from "expo-secure-store";
import { API_BASE_URL } from "../config/api";

const TOKEN_KEY = "jwt";
const REFRESH_KEY = "refresh_token";

/** Refresh this long before the access token's exp, so a request never leaves with a dying token. */
const EXPIRY_SKEW_MS = 60_000;

const BASE = API_BASE_URL.replace(/\/$/, "");

async function read(key: string): Promise<string | null> {
  if (Platform.OS === "web") return localStorage.getItem(key);
  return await SecureStore.getItemAsync(key);
}

async function write(key: string, value: string) {
  if (Platform.OS === "web") localStorage.setItem(key, value);
  else await SecureStore.setItemAsync(key, value);
}

async function remove(key: string) {
  if (Platform.OS === "web") localStorage.removeItem(key);
  else await SecureStore.deleteItemAsync(key);
}

export async function setTokens(accessToken: string, refreshToken: string) {
  await write(TOKEN_KEY, accessToken);
  await write(REFRESH_KEY, refreshToken);
}

/** Clears the whole session from this device. Does not tell the server; see endSession. */
export async function removeToken() {
  await remove(TOKEN_KEY);
  await remove(REFRESH_KEY);
}

/**
 * The access token, silently refreshed first when it has expired or is about to.
 *
 * Every caller that attaches a bearer header goes through here, so screens that still call fetch
 * directly get refresh for free. Null means there is no session left and the user must sign in.
 */
export async function getToken(): Promise<string | null> {
  const token = await read(TOKEN_KEY);
  if (token && !isExpiring(token)) return token;
  if (!token && !(await read(REFRESH_KEY))) return null;
  return refreshSession(token);
}

/** Signs out on the server (best effort) and clears the device. */
export async function endSession() {
  const refreshToken = await read(REFRESH_KEY);
  await removeToken();
  if (!refreshToken) return;
  try {
    await fetch(`${BASE}/logout`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken }),
    });
  } catch {
    // Offline. The token is gone from this device and expires on its own.
  }
}

let inflight: Promise<string | null> | null = null;

/**
 * Trades the stored refresh token for a new pair.
 *
 * @param rejected the access token that was found expired or was just refused with a 401. If
 *   storage already holds a different, still-valid token, someone else refreshed first and that
 *   token is returned without another round trip.
 * @returns the new access token; null when the session is over (storage is cleared); or the
 *   current stored token when the refresh could not be attempted, e.g. offline, so a network
 *   blip does not sign the user out.
 */
export function refreshSession(rejected: string | null): Promise<string | null> {
  // Single flight: a screen mounting with an expired token fires several queries at once, and
  // each refresh rotates the token, so only one of them may actually call the server.
  if (!inflight) {
    inflight = withCrossTabLock(() => doRefresh(rejected)).finally(() => {
      inflight = null;
    });
  }
  return inflight;
}

async function doRefresh(rejected: string | null): Promise<string | null> {
  const current = await read(TOKEN_KEY);
  if (current && current !== rejected && !isExpiring(current)) return current;

  const refreshToken = await read(REFRESH_KEY);
  if (!refreshToken) {
    await removeToken();
    return null;
  }

  let res: Response;
  try {
    res = await fetch(`${BASE}/refresh`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken }),
    });
  } catch {
    return current;
  }

  if (res.ok) {
    try {
      const body = await res.json();
      if (typeof body?.accessToken === "string" && typeof body?.refreshToken === "string") {
        await setTokens(body.accessToken, body.refreshToken);
        return body.accessToken;
      }
    } catch {}
    return current;
  }

  if (res.status === 400 || res.status === 401) {
    // Another tab may have rotated the token while this request was out.
    if ((await read(REFRESH_KEY)) !== refreshToken) return read(TOKEN_KEY);
    await removeToken();
    return null;
  }

  // 429 or 5xx: the session may well still be good, so keep it.
  return current;
}

/**
 * On web, every tab shares one stored refresh token, and it is single use. The Web Locks API makes
 * tabs take turns; the second tab then finds the first tab's fresh token in doRefresh.
 */
function withCrossTabLock<T>(fn: () => Promise<T>): Promise<T> {
  const locks = Platform.OS === "web" ? (globalThis.navigator as any)?.locks : undefined;
  if (!locks?.request) return fn();
  return locks.request("ontrack-token-refresh", fn);
}

/** True when the JWT's exp is within the skew. Unreadable tokens are left to the server to judge. */
function isExpiring(token: string): boolean {
  const exp = readExp(token);
  return exp !== null && exp * 1000 - EXPIRY_SKEW_MS <= Date.now();
}

function readExp(token: string): number | null {
  const payload = token.split(".")[1];
  if (!payload || typeof atob !== "function") return null;
  try {
    const base64 = payload.replace(/-/g, "+").replace(/_/g, "/");
    const padded = base64 + "=".repeat((4 - (base64.length % 4)) % 4);
    const claims = JSON.parse(atob(padded));
    return typeof claims?.exp === "number" ? claims.exp : null;
  } catch {
    return null;
  }
}
