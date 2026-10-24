import { Platform } from "react-native";
import * as SecureStore from "expo-secure-store";
import { getLoginStamp } from "../utils/tokenStorage";

/**
 * The once-per-login gate for the leaderboard's standings reveal.
 *
 * The reveal is keyed to the sign-in stamp startSession writes, not to a timer or an app launch: a
 * reload, a silent token refresh or a second visit in the same session all find the stamp already
 * spent. A new sign-in writes a new stamp, which is the only thing that earns the reveal again.
 */

const KEY = "lb_reveal_spent_for";
// A session signed in before the stamp existed still gets one reveal, under a fixed stand-in.
const LEGACY = "legacy";

async function read(): Promise<string | null> {
  try {
    if (Platform.OS === "web") return localStorage.getItem(KEY);
    return await SecureStore.getItemAsync(KEY);
  } catch {
    return null;
  }
}

async function write(value: string) {
  try {
    if (Platform.OS === "web") localStorage.setItem(KEY, value);
    else await SecureStore.setItemAsync(KEY, value);
  } catch {
    // Storage blocked. The in-memory guard below still holds for the rest of this run.
  }
}

// Belt and braces for the life of the JS runtime, so a failed write cannot replay the reveal on
// every remount of the screen.
const spentThisRun = new Set<string>();

/** True when this sign-in has not seen the reveal yet. Does not spend it. */
export async function revealPending(): Promise<boolean> {
  const stamp = (await getLoginStamp()) ?? LEGACY;
  if (spentThisRun.has(stamp)) return false;
  return (await read()) !== stamp;
}

/** Spends this sign-in's reveal. Called the moment it is shown, not when it is dismissed. */
export async function spendReveal(): Promise<void> {
  const stamp = (await getLoginStamp()) ?? LEGACY;
  spentThisRun.add(stamp);
  await write(stamp);
}
