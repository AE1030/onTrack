import { useEffect, useRef } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { qk } from "../cache/keys";
import { changeAvatar } from "./api";
import { clearLegacyAvatar, loadLegacyAvatar } from "./avatar";
import { LeaderboardStatus } from "./types";

/**
 * Uploads an avatar that was only ever saved on this device.
 *
 * Students who joined before avatars moved to the server have their pick in local storage and a
 * null on the server, so everyone else sees their handle's face. The first time the status shows
 * that gap, the local copy is sent up and then removed. A server avatar that already exists wins
 * and the local copy is simply dropped.
 */
export function useBackfillLocalAvatar(status: LeaderboardStatus | undefined) {
  const queryClient = useQueryClient();
  const attempted = useRef(false);

  useEffect(() => {
    if (!status?.joined || attempted.current) return;
    attempted.current = true;

    (async () => {
      const local = await loadLegacyAvatar();
      if (!local) return;
      if (status.avatar) {
        await clearLegacyAvatar();
        return;
      }
      try {
        await changeAvatar(local);
        await clearLegacyAvatar();
        await queryClient.invalidateQueries({ queryKey: qk.leaderboardStatus });
        await queryClient.invalidateQueries({ queryKey: qk.leaderboard });
      } catch {
        // Kept locally, so the next app start tries again.
      }
    })();
  }, [status, queryClient]);
}
