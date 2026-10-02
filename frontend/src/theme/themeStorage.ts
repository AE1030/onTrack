import { Platform } from "react-native";
import * as SecureStore from "expo-secure-store";

/**
 * Where an explicit theme choice is kept.
 *
 * Shared with app/+html.tsx, which reads the same localStorage key from a raw <script> before
 * React mounts. If the key here and the key there ever drift, the web build paints the wrong
 * ground for one frame on every load, so the constant is exported rather than spelled twice.
 */
export const THEME_KEY = "ontrack.theme";

/** null means "follow the system", which is the default and is stored as an absent key. */
export type ThemeOverride = "light" | "dark" | null;

const parse = (raw: string | null): ThemeOverride =>
  raw === "light" || raw === "dark" ? raw : null;

/**
 * The stored choice, synchronously, on web only.
 *
 * Web can read localStorage before first paint and native cannot read SecureStore at all without
 * awaiting, so the hook seeds from this and then reconciles with the async read. Without it the
 * web app renders light for a frame and flashes.
 */
export function getThemeOverrideSync(): ThemeOverride {
  if (Platform.OS !== "web") return null;
  try {
    return parse(localStorage.getItem(THEME_KEY));
  } catch {
    // Private-mode Safari throws on localStorage access rather than returning null.
    return null;
  }
}

export async function getThemeOverride(): Promise<ThemeOverride> {
  if (Platform.OS === "web") return getThemeOverrideSync();
  try {
    return parse(await SecureStore.getItemAsync(THEME_KEY));
  } catch {
    return null;
  }
}

export async function setThemeOverride(value: ThemeOverride): Promise<void> {
  try {
    if (Platform.OS === "web") {
      if (value === null) localStorage.removeItem(THEME_KEY);
      else localStorage.setItem(THEME_KEY, value);
      return;
    }
    if (value === null) await SecureStore.deleteItemAsync(THEME_KEY);
    else await SecureStore.setItemAsync(THEME_KEY, value);
  } catch {
    // A theme that fails to persist is a worse session, not a broken one.
  }
}
