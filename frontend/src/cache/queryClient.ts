import { AppState, Platform } from "react-native";
import NetInfo from "@react-native-community/netinfo";
import { QueryClient, focusManager, onlineManager } from "@tanstack/react-query";
import { mutationRetry } from "./authedGet";

/**
 * Single QueryClient held at module scope so the cache survives screen
 * unmounts. app/index.tsx swaps screen components on every tab switch, so a
 * cache owned by a component would die with it.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 5 * 60_000,
      gcTime: 30 * 60_000,
      retry: 1,
      refetchOnWindowFocus: false,
    },
    mutations: {
      // Connection failures back off and retry; server rejections fail straight
      // through to onError so the caller can roll back. See mutationRetry.
      retry: mutationRetry,
      retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 30_000),
    },
  },
});

// React Native has no window focus events; bridge AppState into focusManager
// so backgrounding and returning behaves like a browser tab regaining focus.
if (Platform.OS !== "web") {
  AppState.addEventListener("change", (status) => {
    focusManager.setFocused(status === "active");
  });

  // Nor does it have navigator.onLine, so without this bridge onlineManager
  // reports "online" forever and paused mutations would fire into a dead
  // socket instead of waiting for the connection to come back.
  onlineManager.setEventListener((setOnline) =>
    NetInfo.addEventListener((state) => {
      setOnline(state.isConnected === true && state.isInternetReachable !== false);
    })
  );
}
