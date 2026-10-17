import { useEffect, useState } from "react";
import { ActivityIndicator, Pressable, StyleSheet, Text, View, useColorScheme } from "react-native";
import type { ErrorBoundaryProps } from "expo-router";
import * as SplashScreen from "expo-splash-screen";

import { palettes } from "../src/theme/colors";
import { spacing } from "../src/theme/spacing";
import { getThemeOverrideSync } from "../src/theme/themeStorage";

/**
 * Shown when a render throws anywhere under the root layout.
 *
 * Replaces Expo Router's stock boundary, which prints error.message to the screen. The real error
 * goes to the console instead. This sits outside ThemeProvider (the provider is part of the tree
 * that just crashed), so it resolves the theme itself: the stored choice where it can be read
 * synchronously (web), the system setting otherwise.
 */
export default function ErrorFallback({ error, retry }: ErrorBoundaryProps) {
  const system = useColorScheme();
  const c = palettes[getThemeOverrideSync() ?? (system === "dark" ? "dark" : "light")];
  const [retrying, setRetrying] = useState(false);

  useEffect(() => {
    console.error("Unhandled render error", error);
    // A font load failure lands here before the root layout ever hid the splash screen.
    SplashScreen.hideAsync().catch(() => {});
  }, [error]);

  const onRetry = async () => {
    setRetrying(true);
    try {
      await retry();
    } finally {
      setRetrying(false);
    }
  };

  return (
    <View style={[styles.container, { backgroundColor: c.background }]}>
      <Text style={[styles.title, { color: c.boldText }]}>Something went wrong</Text>
      <Text style={[styles.body, { color: c.bodyText }]}>
        This screen ran into a problem. Your saved data is safe. Try again, and if it keeps
        happening, restart the app.
      </Text>
      <Pressable
        onPress={onRetry}
        disabled={retrying}
        accessibilityRole="button"
        style={({ pressed }) => [
          styles.button,
          { backgroundColor: c.primary, opacity: pressed || retrying ? 0.8 : 1 },
        ]}
      >
        {retrying ? (
          <ActivityIndicator color={c.background} />
        ) : (
          <Text style={[styles.buttonText, { color: c.background }]}>Try again</Text>
        )}
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
    padding: spacing.lg,
  },
  title: {
    fontSize: 20,
    fontWeight: "700",
    marginBottom: spacing.sm,
    textAlign: "center",
  },
  body: {
    fontSize: 15,
    lineHeight: 22,
    maxWidth: 360,
    textAlign: "center",
    marginBottom: spacing.lg,
  },
  button: {
    minWidth: 140,
    paddingVertical: 12,
    paddingHorizontal: spacing.lg,
    borderRadius: 10,
    alignItems: "center",
  },
  buttonText: {
    fontSize: 15,
    fontWeight: "600",
  },
});
