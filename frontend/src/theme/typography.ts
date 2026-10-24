import { TextStyle } from "react-native";

/**
 * The app's two voices.
 *
 * Space Grotesk is for what a student is proud of: screen titles, section headers, the GPA hero,
 * leaderboard ranks. IBM Plex Sans is for what they have to read carefully: body copy, form fields,
 * grade tables. Inter and Geist are out on purpose (see DESIGN.md).
 *
 * React Native bakes the weight into the family name, so a style picks a face, not a weight. The
 * Text wrapper in src/ui/Text.tsx maps any leftover `fontWeight` onto the right face, which is why
 * screens can still write `fontWeight: "700"` and land on Space Grotesk Bold.
 */
export const fonts = {
  display: "SpaceGrotesk_700Bold",
  displayMedium: "SpaceGrotesk_500Medium",
  bodyLight: "IBMPlexSans_300Light",
  body: "IBMPlexSans_400Regular",
  bodyMedium: "IBMPlexSans_500Medium",
} as const;

const DISPLAY_FACES = new Set<string>([fonts.display, fonts.displayMedium]);

/**
 * Positive tracking on small Plex text so grades and table rows are not crushed. letterSpacing in
 * React Native is in points, so the em value is multiplied out against the size.
 */
export function trackingFor(fontSize: number, family: string): number {
  if (DISPLAY_FACES.has(family)) {
    // Large display type looks loose at default spacing; small display labels get a touch open.
    if (fontSize >= 24) return round(fontSize * -0.02);
    if (fontSize <= 14) return round(fontSize * 0.02);
    return 0;
  }
  if (fontSize <= 12) return round(fontSize * 0.04);
  if (fontSize <= 14) return round(fontSize * 0.03);
  if (fontSize <= 18) return round(fontSize * 0.02);
  return 0;
}

const round = (n: number) => Math.round(n * 100) / 100;

/** Picks the face for a style that names a weight but no family. */
export function faceFor(weight: TextStyle["fontWeight"]): string {
  const w = weight === "bold" ? 700 : weight === "normal" || weight == null ? 400 : Number(weight);
  if (w >= 700) return fonts.display;
  if (w >= 500) return fonts.bodyMedium;
  if (w <= 300) return fonts.bodyLight;
  return fonts.body;
}

/**
 * The type scale. Five steps of body and three of display; sizes outside these are a smell.
 * Plain objects rather than StyleSheet.create so they spread into a screen's own StyleSheet entry,
 * with colour and layout added on top.
 */
export const typo = {
  /** GPA hero, podium numerals. */
  display: { fontFamily: fonts.display, fontSize: 40, lineHeight: 44, letterSpacing: -0.8 },
  /** Screen titles. */
  h1: { fontFamily: fonts.display, fontSize: 28, lineHeight: 34, letterSpacing: -0.56 },
  /** Section and card headers. */
  h2: { fontFamily: fonts.display, fontSize: 20, lineHeight: 26, letterSpacing: 0 },
  /** Row and item titles. */
  h3: { fontFamily: fonts.display, fontSize: 16, lineHeight: 22, letterSpacing: 0 },
  /** Leaderboard rank and score chips. */
  rank: { fontFamily: fonts.display, fontSize: 18, fontVariant: ["tabular-nums"] as TextStyle["fontVariant"] },
  /** Button text, tabs. */
  label: { fontFamily: fonts.displayMedium, fontSize: 14, letterSpacing: 0.28 },
  /** Intro and explanatory paragraphs. */
  bodyLarge: { fontFamily: fonts.bodyLight, fontSize: 16, lineHeight: 24, letterSpacing: 0.32 },
  /** Default body copy and form fields. */
  body: { fontFamily: fonts.body, fontSize: 14, lineHeight: 21, letterSpacing: 0.42 },
  /** Grades, weights, GPA values in tables. */
  data: {
    fontFamily: fonts.body,
    fontSize: 14,
    lineHeight: 20,
    letterSpacing: 0.42,
    fontVariant: ["tabular-nums"] as TextStyle["fontVariant"],
  },
  /** Helper text, timestamps, footnotes. */
  caption: { fontFamily: fonts.body, fontSize: 12, lineHeight: 16, letterSpacing: 0.48 },
} satisfies Record<string, TextStyle>;
