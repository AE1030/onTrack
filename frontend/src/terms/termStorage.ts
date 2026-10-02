import { Platform } from "react-native";
import * as SecureStore from "expo-secure-store";

/** Where the selected term is kept, so a reload does not throw the choice away. */
export const TERM_KEY = "ontrack.selectedTerm";

export async function getStoredTerm(): Promise<string | null> {
  try {
    if (Platform.OS === "web") return localStorage.getItem(TERM_KEY);
    return await SecureStore.getItemAsync(TERM_KEY);
  } catch {
    // Private-mode Safari throws on localStorage access rather than returning null.
    return null;
  }
}

export async function setStoredTerm(value: string | null): Promise<void> {
  try {
    if (Platform.OS === "web") {
      if (value === null) localStorage.removeItem(TERM_KEY);
      else localStorage.setItem(TERM_KEY, value);
      return;
    }
    if (value === null) await SecureStore.deleteItemAsync(TERM_KEY);
    else await SecureStore.setItemAsync(TERM_KEY, value);
  } catch {
    // A term that fails to persist is a worse session, not a broken one.
  }
}
