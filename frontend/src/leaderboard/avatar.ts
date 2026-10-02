import { Platform } from "react-native";
import * as SecureStore from "expo-secure-store";

/**
 * Avatars, as five numbers.
 *
 * Nothing user-supplied is ever rendered. The leaderboard is the one screen in this app where an
 * image a student chose is visible to strangers, and not having that surface at all is cheaper than
 * moderating it. So a head is drawn from a config rather than loaded from a file: about 9,000
 * distinct faces out of one component, roughly sixty bytes each, at any size the board asks for.
 *
 * Where it lives. The chosen config is stored on leaderboard_profile and copied onto each board row,
 * so it follows the student across devices and survives a rename. A null avatar means the student
 * never picked one, and the face is derived from the handle instead.
 */

export type AvatarConfig = {
  /** 0-5. Hairstyle or headwear, from crop to swoop. Named `face` because stored configs use it. */
  face: number;
  /** 0-7, the last two fantasy tones. */
  skin: number;
  /** 0-7, the last two synthetic. */
  hair: number;
  /** 0-3: none, glasses, hearts, shades. */
  gear: number;
  /** 0-5. Picks the backdrop disc and the shirt together, so a head reads at 28px. */
  accent: number;
};

export const AVATAR_AXES = {
  face: 6,
  skin: 8,
  hair: 8,
  gear: 4,
  accent: 6,
} as const;

export const FACE_LABELS = ["Crop", "Buns", "Bob", "Mohawk", "Beanie", "Swoop"];

export const GEAR_LABELS = ["None", "Glasses", "Hearts", "Shades"];

export const SKIN_TONES = [
  "#F6D7C3",
  "#EBC09E",
  "#D39A6E",
  "#A86B45",
  "#7A4A2E",
  "#4E3022",
  "#8FD16A", // fantasy green
  "#A99BF2", // fantasy lavender
];

/** Shadow side of each tone, for the jaw, ears and nose. Kept explicit rather than computed: a
 *  blanket darken turns the deepest two muddy. */
export const SKIN_SHADES = [
  "#DDB39A",
  "#D09F7C",
  "#B47D53",
  "#8A5334",
  "#5E3822",
  "#3A2219",
  "#6DB24A",
  "#8676D6",
];

export const HAIR_COLORS = [
  "#1B1714", // black
  "#4A2C1A", // dark brown
  "#8B5A2B", // chestnut
  "#EAD9A0", // platinum
  "#A83B2A", // auburn
  "#BDB6B0", // silver
  "#7ED957", // synthetic lime
  "#E040C8", // synthetic magenta
];

/** The backdrop disc. Saturated on purpose: the heads are pale and soft, so the ground carries
 *  the colour, and it holds up on both a light row and the maroon podium. */
export const ACCENT_COLORS = [
  "#8E44EC",
  "#3F72F0",
  "#E83E8C",
  "#13999B",
  "#E9772E",
  "#21A35B",
];

/** The shirt each backdrop is paired with. Chosen per pair rather than derived, so a pastel tee
 *  never lands on a pastel ground. */
export const SHIRT_COLORS = [
  "#F0B8E2",
  "#26262E",
  "#25358F",
  "#ECE7DA",
  "#2B2B31",
  "#1E2A44",
];

/**
 * A stable 32-bit hash of a handle.
 *
 * FNV-1a, because it has to agree with nothing on the server and only has to be stable and spread
 * evenly. Two handles one character apart must not land on the same face, which is the whole job.
 */
function hashHandle(handle: string): number {
  let h = 0x811c9dc5;
  for (let i = 0; i < handle.length; i++) {
    h ^= handle.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h >>> 0;
}

/**
 * The face a handle gets when nobody has chosen one.
 *
 * Used for every other player on the board, and pre-rolled for the student at onboarding so the
 * avatar step can be skipped. Deterministic, so a board does not reshuffle between renders.
 */
export function avatarFor(handle: string): AvatarConfig {
  const h = hashHandle(handle || "player");
  // Independent bit ranges rather than repeated modulo of the same number, which would correlate
  // the axes and produce visibly fewer distinct heads than the arithmetic promises.
  return {
    face: (h >>> 0) % AVATAR_AXES.face,
    skin: (h >>> 5) % AVATAR_AXES.skin,
    hair: (h >>> 10) % AVATAR_AXES.hair,
    gear: (h >>> 16) % AVATAR_AXES.gear,
    accent: (h >>> 21) % AVATAR_AXES.accent,
  };
}

const clampAxis = (v: unknown, n: number) =>
  typeof v === "number" && Number.isFinite(v) ? ((Math.trunc(v) % n) + n) % n : 0;

/** Anything read back from storage goes through here. A stored 99 must not blank the head. */
export function normalizeAvatar(raw: Partial<AvatarConfig> | null | undefined): AvatarConfig | null {
  if (!raw || typeof raw !== "object") return null;
  return {
    face: clampAxis(raw.face, AVATAR_AXES.face),
    skin: clampAxis(raw.skin, AVATAR_AXES.skin),
    hair: clampAxis(raw.hair, AVATAR_AXES.hair),
    gear: clampAxis(raw.gear, AVATAR_AXES.gear),
    accent: clampAxis(raw.accent, AVATAR_AXES.accent),
  };
}

// ------------------------------------------------------------- legacy local copy

/**
 * Before avatars were stored on the server, the chosen one was kept only on the device under this
 * key. It is read once to upload it (see useBackfillLocalAvatar) and then removed. Nothing writes
 * it any more.
 */
const AVATAR_KEY = "ontrack.leaderboard.avatar";

export async function loadLegacyAvatar(): Promise<AvatarConfig | null> {
  try {
    const raw =
      Platform.OS === "web"
        ? localStorage.getItem(AVATAR_KEY)
        : await SecureStore.getItemAsync(AVATAR_KEY);
    return raw ? normalizeAvatar(JSON.parse(raw)) : null;
  } catch {
    return null;
  }
}

export async function clearLegacyAvatar(): Promise<void> {
  try {
    if (Platform.OS === "web") localStorage.removeItem(AVATAR_KEY);
    else await SecureStore.deleteItemAsync(AVATAR_KEY);
  } catch {
    // Nothing to do. The server copy wins regardless.
  }
}
