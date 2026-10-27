import { useState } from "react";
import { Pressable, StyleSheet, View } from "react-native";
import { Text } from "../../src/ui/Text";
import { Feather } from "@expo/vector-icons";
import { useQuery } from "@tanstack/react-query";
import * as WebBrowser from "expo-web-browser";

import { authedGet, authedMutate, queryRetry } from "../../src/cache/authedGet";
import { qk } from "../../src/cache/keys";
import { spacing, radius } from "../../src/theme/spacing";
import { useTheme } from "../../src/theme/ThemeContext";

type CalendarProvider = "GOOGLE";

type CalendarAccountStatus = {
  provider: CalendarProvider;
  email: string | null;
  needsReconnect: boolean;
};

const PROVIDER_LABEL: Record<CalendarProvider, string> = {
  GOOGLE: "Google Calendar",
};

/**
 * Starts the provider's OAuth flow again. Reconnecting replaces the account on the server, which
 * clears the flag, and the callback runs the first sync itself.
 */
const RECONNECT: Partial<Record<CalendarProvider, (email: string | null) => Promise<void>>> = {
  GOOGLE: async (email) => {
    const res = await authedMutate("/api/calendar/google/connect", {
      method: "POST",
      body: JSON.stringify({ calendarEmail: email ?? "" }),
    });
    const data = await res.json();
    await WebBrowser.openBrowserAsync(data.authorizationUrl);
  },
};

/**
 * Warns, above every signed-in screen, when a calendar connection has stopped working.
 *
 * A revoked refresh token is usually found by a background sync after a table save, where nothing
 * can reach the student. The server flags the account instead, and this polls for the flag. It
 * refetches on focus too, so the warning clears once the student returns from reconnecting.
 */
export default function CalendarReconnectBanner() {
  const palette = useTheme();
  const [error, setError] = useState<string | null>(null);

  const accountsQuery = useQuery({
    queryKey: qk.calendarAccounts,
    queryFn: () => authedGet<CalendarAccountStatus[]>("/api/calendar/accounts"),
    staleTime: 60_000,
    refetchInterval: 5 * 60_000,
    refetchOnWindowFocus: true,
    retry: queryRetry,
  });

  const broken = (accountsQuery.data ?? []).filter((a) => a.needsReconnect);
  if (broken.length === 0) return null;

  return (
    <View style={[styles.bar, { backgroundColor: palette.dangerSoft, borderTopColor: palette.danger }]}>
      {broken.map((account) => {
        const reconnect = RECONNECT[account.provider];
        const who = account.email ? ` (${account.email})` : "";
        return (
          <View key={account.provider} style={styles.row}>
            <Feather name="alert-triangle" size={16} color={palette.dangerDeep} style={styles.icon} />
            <Text style={[styles.text, { color: palette.dangerDeep }]}>
              Your {PROVIDER_LABEL[account.provider]} connection{who} stopped working, so new changes
              aren't reaching it.
            </Text>
            {reconnect && (
              <Pressable
                accessibilityRole="button"
                style={[styles.button, { backgroundColor: palette.dangerDeep }]}
                onPress={async () => {
                  setError(null);
                  try {
                    await reconnect(account.email);
                  } catch {
                    setError("Couldn't start reconnecting. Please try again.");
                  }
                }}
              >
                <Text style={styles.buttonText}>Reconnect</Text>
              </Pressable>
            )}
          </View>
        );
      })}
      {error && <Text style={[styles.error, { color: palette.dangerDeep }]}>{error}</Text>}
    </View>
  );
}

const styles = StyleSheet.create({
  bar: {
    borderTopWidth: 1,
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.md,
    gap: spacing.sm,
  },
  row: {
    flexDirection: "row",
    alignItems: "center",
    gap: spacing.sm,
  },
  icon: {
    flexShrink: 0,
  },
  text: {
    flex: 1,
    fontSize: 14,
    lineHeight: 20,
  },
  button: {
    paddingVertical: 8,
    paddingHorizontal: 14,
    borderRadius: radius.sm,
    flexShrink: 0,
  },
  buttonText: {
    color: "white",
    fontWeight: "500",
    fontSize: 14,
  },
  error: {
    fontSize: 13,
  },
});
