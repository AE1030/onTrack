import { Platform } from "react-native";
import { API_BASE_URL } from "../config/api";
import { getToken } from "../utils/tokenStorage";
import {
  AuthError,
  HttpError,
  authedGet,
  authedMutate,
  readServerError,
} from "../cache/authedGet";
import { AvatarConfig } from "./avatar";
import {
  BoardScope,
  HandleCheck,
  LeaderboardResponse,
  LeaderboardStatus,
  TargetPreview,
  TranscriptBaseline,
} from "./types";

const BASE = "/api/leaderboard";

/** GET the board plus the caller's own row. */
export const fetchBoard = (limit?: number) =>
  authedGet<LeaderboardResponse>(limit ? `${BASE}?limit=${limit}` : BASE);

/** Cheap enough to call on every open of the screen: a couple of indexed reads, no recomputation. */
export const fetchStatus = () => authedGet<LeaderboardStatus>(`${BASE}/status`);

/** What a candidate target is worth. The server decides mode and ceiling; the slider only guesses. */
export const fetchPreview = (target: number) =>
  authedGet<TargetPreview>(`${BASE}/preview?target=${encodeURIComponent(target.toFixed(2))}`);

export async function joinBoard(input: {
  targetGpa12: number;
  handle: string;
  acceptRules: boolean;
  avatar: AvatarConfig;
}): Promise<LeaderboardStatus> {
  const res = await authedMutate(`${BASE}/join`, {
    method: "POST",
    body: JSON.stringify(input),
  });
  return res.json();
}

/**
 * Whether a handle is free, for the field to answer while it is being typed.
 *
 * Reserves nothing: two students can both be told a handle is available and the second one to
 * submit loses. That is the right trade, because reserving on a keystroke would let anyone empty
 * the namespace by typing in the box.
 */
export const checkHandle = (handle: string) =>
  authedGet<HandleCheck>(`${BASE}/handle/check?handle=${encodeURIComponent(handle)}`);

export async function changeHandle(handle: string): Promise<LeaderboardStatus> {
  const res = await authedMutate(`${BASE}/handle`, {
    method: "POST",
    body: JSON.stringify({ handle }),
  });
  return res.json();
}

/** Changes the face on the board. Not rate limited, and shows on the board immediately. */
export async function changeAvatar(avatar: AvatarConfig): Promise<LeaderboardStatus> {
  const res = await authedMutate(`${BASE}/avatar`, {
    method: "POST",
    body: JSON.stringify({ avatar }),
  });
  return res.json();
}

/** Forfeits this season's standing. Rejoining restores the frozen baseline and target. */
export async function withdraw(): Promise<void> {
  await authedMutate(`${BASE}/entry`, { method: "DELETE" });
}

/**
 * Step 1 of onboarding: establish the season's baseline from a transcript.
 *
 * Hand-rolled rather than going through authedMutate because this one is multipart. Setting
 * Content-Type ourselves would omit the boundary and the server would reject every upload, so the
 * header is deliberately absent and fetch fills it in.
 */
export async function submitTranscript(file: {
  uri: string;
  name: string;
  mimeType?: string | null;
  file?: unknown;
}): Promise<TranscriptBaseline> {
  const token = await getToken();
  if (!token) throw new AuthError();

  const form = new FormData();
  if (Platform.OS === "web") {
    form.append("file", file.file as Blob, file.name);
  } else {
    form.append("file", {
      uri: file.uri,
      name: file.name,
      type: file.mimeType ?? "application/pdf",
    } as any);
  }

  const res = await fetch(`${API_BASE_URL.replace(/\/$/, "")}${BASE}/transcript`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
    body: form,
  });

  if (res.status === 401 || res.status === 403) throw new AuthError();
  if (!res.ok) throw new HttpError(res.status, (await readServerError(res)) ?? undefined);
  return res.json();
}

// ------------------------------------------------------------------------ the gaps

/**
 * Which scopes the server actually serves.
 *
 * One, today. Program is a column the student record already has, so narrowing the board to it is
 * a filter param on the backend and nothing more, but until that exists the tab says it is showing
 * the global field rather than quietly showing a different one. Inventing a subset client-side
 * would be a lie with rank numbers on it.
 *
 * Friends has nothing behind it at all in v2 and is drawn disabled.
 */
export const SCOPE_SERVED: Record<BoardScope, boolean> = {
  global: true,
  program: false,
  friends: false,
};
