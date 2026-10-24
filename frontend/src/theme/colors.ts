/**
 * The app's colour tokens, in both themes.
 *
 * Previously a flat object every screen imported directly. A static import cannot re-render when
 * the theme changes, so the shape is now { light, dark } and screens read it through useTheme()
 * rather than importing it. The flat `colors` export below is kept as the light palette so screens
 * that have not been converted yet still compile and still look exactly as they did.
 *
 * Two groups are worth reading before adding to this file:
 *
 *   Brand field (fieldA/fieldB/onField). The leaderboard draws a full-bleed maroon field — board
 *   header, podium, dashboard join card — and that field stays deep maroon in BOTH themes. It is
 *   not `primary`: primary lifts to #EE86AC in dark so maroon text stays legible on a dark ground,
 *   and a pink header would read as a different product. Anything drawn on a brand field needs the
 *   pair: a token for the field, a token for the text on it.
 *
 *   Semantic tokens (danger/success/muted/scrim/...). The palette used to be brand-only, so these
 *   were hardcoded at ~70 call sites. A black scrim over a dark ground is invisible, which is why
 *   `scrim` is a token rather than an opacity applied to black.
 */

export type ThemeName = "light" | "dark";

export type Palette = {
  // Brand
  primary: string;
  primarySoft: string;
  accent: string;
  accentSoft: string;

  // Grounds
  background: string;
  surface: string;
  surfaceAlt: string;
  surfaceSunken: string;

  // Type
  boldText: string;
  bodyText: string;
  faintText: string;

  // Lines
  border: string;
  borderSubtle: string;

  // Brand field: deep maroon in both themes, with its own on-field type tokens.
  fieldA: string;
  fieldB: string;
  onField: string;
  onFieldMuted: string;
  onFieldFaint: string;
  // Rank movement on the field. Semantic success/danger are picked for white and near-black grounds
  // and fall to ~1.6:1 on maroon, so the field gets its own pair. Up is a solid mint chip with dark
  // ink so a climb lands; down stays a quiet coral on the translucent chip, never an alarm.
  onFieldUp: string;
  onFieldUpInk: string;
  onFieldDown: string;

  // Semantic
  danger: string;
  dangerDeep: string;
  dangerSoft: string;
  success: string;
  successSoft: string;
  muted: string;
  placeholder: string;
  disabled: string;

  // Depth
  scrim: string;
  shadow: string;
  shadowStrong: string;

  // Per-course accents. Picked against white in light, re-picked against near-black in dark.
  courseAccents: string[];

  // Legacy aliases. Screens still reference these; remove as each screen converts.
  secondary: string;
  muted2: string;
  text: string;
};

const light: Palette = {
  primary: "#7A003C",
  primarySoft: "#F2E6EC",
  accent: "#F2B94A",
  accentSoft: "#FDF2DC",

  background: "#F5F1F3",
  surface: "#FFFFFF",
  surfaceAlt: "#FBF7F9",
  surfaceSunken: "#EFE8EC",

  // Neutrals lean toward the maroon hue rather than flat grey, and every one used as text clears
  // 4.5:1 on background and surface. The old #888 body and #AAA placeholder sat near 3:1.
  boldText: "#3B0A1D",
  bodyText: "#5E4E56",
  faintText: "#76666F",

  border: "#E0D7DE",
  borderSubtle: "#EDE7EB",

  fieldA: "#7A003C",
  fieldB: "#5C002D",
  onField: "#FFFFFF",
  onFieldMuted: "rgba(255,255,255,0.78)",
  onFieldFaint: "rgba(255,255,255,0.55)",
  onFieldUp: "#7CE8A6", // 7.4:1 against the field
  onFieldUpInk: "#04391C", // 8.7:1 on onFieldUp
  onFieldDown: "#FFB8AD", // 4.8:1 on the translucent chip

  danger: "#D32F2F",
  dangerDeep: "#A81E14",
  dangerSoft: "#FDECEA",
  success: "#2E7D32",
  successSoft: "#E8F5E9",
  muted: "#968890", // icons and inactive marks: 3:1 against surface, not body text
  placeholder: "#76666F",
  disabled: "#DDDDDD",

  scrim: "rgba(0,0,0,0.40)",
  shadow: "rgba(0,0,0,0.10)",
  shadowStrong: "rgba(0,0,0,0.18)",

  courseAccents: [
    "#7A003C", // maroon
    "#F2B94A", // gold
    "#2C3E50", // dark navy
    "#1A7A5A", // deep teal
    "#8B4513", // saddle brown
    "#4A148C", // deep purple
  ],

  secondary: "#F2B94A",
  muted2: "#E0D7DE",
  text: "#3B0A1D",
};

const dark: Palette = {
  // Lifted off the brand maroon: #7A003C as type on a dark ground fails contrast outright.
  primary: "#EE86AC",
  primarySoft: "#3A1526",
  accent: "#F2B94A", // passes on both grounds, so it does not move
  accentSoft: "#3A2E16",

  background: "#140A10",
  surface: "#1E121A",
  surfaceAlt: "#261722",
  surfaceSunken: "#0E060A",

  boldText: "#F6EAF0",
  bodyText: "#A99AA2",
  faintText: "#96858F",

  border: "#3A2733",
  borderSubtle: "#2E1E28",

  // Unchanged. The field is the one place deep maroon survives dark mode, because the text on it
  // is near-white either way.
  fieldA: "#7A003C",
  fieldB: "#5C002D",
  onField: "#FFFFFF",
  onFieldMuted: "rgba(255,255,255,0.76)",
  onFieldFaint: "rgba(255,255,255,0.52)",
  onFieldUp: "#7CE8A6", // 7.4:1 against the field
  onFieldUpInk: "#04391C", // 8.7:1 on onFieldUp
  onFieldDown: "#FFB8AD", // 4.8:1 on the translucent chip

  danger: "#FF6B6B",
  dangerDeep: "#FF8A80",
  dangerSoft: "#3A1A1A",
  success: "#6BBF6F",
  successSoft: "#16301A",
  muted: "#8A7882",
  placeholder: "#96858F",
  disabled: "#3A2733",

  // Not a darker black. A black scrim over a near-black ground reads as nothing at all, so this
  // leans harder and the drawer still separates from what is behind it.
  scrim: "rgba(0,0,0,0.66)",
  shadow: "rgba(0,0,0,0.50)",
  shadowStrong: "rgba(0,0,0,0.70)",

  courseAccents: [
    "#EE86AC", // maroon, lifted
    "#F2B94A", // gold
    "#7FA8CC", // navy, lifted
    "#4FC3A1", // teal, lifted
    "#C9915E", // brown, lifted
    "#B388E0", // purple, lifted
  ],

  secondary: "#F2B94A",
  muted2: "#3A2733",
  text: "#F6EAF0",
};

export const palettes: Record<ThemeName, Palette> = { light, dark };

/**
 * The light palette under its old name.
 *
 * Screens that have not been converted to useTheme() still import this, and they render light-only
 * until they are. Kept deliberately rather than deleted: breaking sixteen screens at once to prove
 * a point about a hook is not an improvement.
 */
export const colors = light;
