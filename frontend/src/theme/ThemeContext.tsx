import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from "react";
import { Appearance, Platform } from "react-native";
import { Palette, ThemeName, palettes } from "./colors";
import {
  THEME_KEY,
  ThemeOverride,
  getThemeOverride,
  getThemeOverrideSync,
  setThemeOverride,
} from "./themeStorage";

/**
 * One source of truth for which theme is showing.
 *
 * Three things in this app decide colour and they have to agree or the app is visibly half-dark:
 * the screens (through useTheme), react-navigation's chrome (through app/_layout.tsx), and the web
 * document body (through app/+html.tsx). All three read the resolved value that comes out of here.
 *
 * An explicit choice beats the system setting and survives a restart. That ordering is the whole
 * reason this is a provider rather than a call to Appearance.getColorScheme() per screen: a student
 * who picks dark on a light phone means it.
 */

type ThemeControl = {
  /** The theme actually showing. */
  scheme: ThemeName;
  /** The stored choice. null means the system setting is in charge. */
  override: ThemeOverride;
  /** Pick a theme, or pass null to hand control back to the system. */
  setOverride: (value: ThemeOverride) => void;
  /** Light to dark and back, pinning the result as an explicit choice. */
  toggle: () => void;
};

const ThemeContext = createContext<{ palette: Palette; control: ThemeControl } | null>(null);

const systemScheme = (): ThemeName =>
  Appearance.getColorScheme() === "dark" ? "dark" : "light";

/** Keeps the web document in step, so the area outside the app's own views matches too. */
function paintDocument(scheme: ThemeName) {
  if (Platform.OS !== "web") return;
  try {
    document.documentElement.dataset.theme = scheme;
    document.body.style.backgroundColor = palettes[scheme].background;
  } catch {
    // Static rendering has no document. The <script> in +html.tsx covers that pass.
  }
}

export function ThemeProvider({ children }: { children: React.ReactNode }) {
  // Seeded synchronously so web never paints a frame in the wrong theme. Native seeds to null and
  // corrects on the first effect, which lands before the splash screen hides.
  const [override, setOverrideState] = useState<ThemeOverride>(getThemeOverrideSync);
  const [system, setSystem] = useState<ThemeName>(systemScheme);

  useEffect(() => {
    let cancelled = false;
    getThemeOverride().then((stored) => {
      if (!cancelled) setOverrideState(stored);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    const sub = Appearance.addChangeListener(({ colorScheme }) => {
      setSystem(colorScheme === "dark" ? "dark" : "light");
    });
    return () => sub.remove();
  }, []);

  // Another tab changing the theme should not leave this one disagreeing with storage.
  useEffect(() => {
    if (Platform.OS !== "web") return;
    const onStorage = (e: StorageEvent) => {
      if (e.key === THEME_KEY) setOverrideState(getThemeOverrideSync());
    };
    window.addEventListener("storage", onStorage);
    return () => window.removeEventListener("storage", onStorage);
  }, []);

  const scheme: ThemeName = override ?? system;

  useEffect(() => {
    paintDocument(scheme);
  }, [scheme]);

  const setOverride = useCallback((value: ThemeOverride) => {
    setOverrideState(value);
    void setThemeOverride(value);
  }, []);

  const value = useMemo(
    () => ({
      palette: palettes[scheme],
      control: {
        scheme,
        override,
        setOverride,
        toggle: () => setOverride(scheme === "dark" ? "light" : "dark"),
      },
    }),
    [scheme, override, setOverride]
  );

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

/**
 * The colours for the theme currently showing.
 *
 * Falls back to the light palette outside a provider rather than throwing, so a component rendered
 * in isolation (a test, a storybook-style harness) still draws.
 */
export function useTheme(): Palette {
  return useContext(ThemeContext)?.palette ?? palettes.light;
}

/** For the toggle itself, and for anything that needs to know which theme is on. */
export function useThemeControl(): ThemeControl {
  const ctx = useContext(ThemeContext);
  if (ctx) return ctx.control;
  return {
    scheme: "light",
    override: null,
    setOverride: () => {},
    toggle: () => {},
  };
}
