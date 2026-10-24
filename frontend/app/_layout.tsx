import FontAwesome from '@expo/vector-icons/FontAwesome';
import { Feather } from '@expo/vector-icons';
import {
  DarkTheme,
  DefaultTheme,
  ThemeProvider as NavigationThemeProvider,
} from '@react-navigation/native';
import { useFonts } from 'expo-font';
import { SpaceGrotesk_500Medium } from '@expo-google-fonts/space-grotesk/500Medium';
import { SpaceGrotesk_700Bold } from '@expo-google-fonts/space-grotesk/700Bold';
import { IBMPlexSans_300Light } from '@expo-google-fonts/ibm-plex-sans/300Light';
import { IBMPlexSans_400Regular } from '@expo-google-fonts/ibm-plex-sans/400Regular';
import { IBMPlexSans_500Medium } from '@expo-google-fonts/ibm-plex-sans/500Medium';
import { Stack } from 'expo-router';
import * as SplashScreen from 'expo-splash-screen';
import { useEffect, useMemo } from 'react';
import 'react-native-reanimated';

import { QueryClientProvider } from '@tanstack/react-query';

import { queryClient } from '../src/cache/queryClient';
import { TermProvider } from '../src/terms/TermContext';
import { ThemeProvider, useTheme, useThemeControl } from '../src/theme/ThemeContext';
import { HoverStyles } from '../src/ui/HoverStyles';

// Catches any error thrown under the root layout. Replaces Expo Router's stock boundary, which
// prints the raw error message on screen.
export { default as ErrorBoundary } from '../components/ErrorFallback';

export const unstable_settings = {
  initialRouteName: 'index',
};

// Prevent the splash screen from auto-hiding before asset loading is complete.
SplashScreen.preventAutoHideAsync();

export default function RootLayout() {
  const [loaded, error] = useFonts({
    // Per-weight imports so only the five faces in src/theme/typography.ts reach the bundle.
    SpaceGrotesk_500Medium,
    SpaceGrotesk_700Bold,
    IBMPlexSans_300Light,
    IBMPlexSans_400Regular,
    IBMPlexSans_500Medium,
    ...FontAwesome.font,
    ...Feather.font,
  });

  // Expo Router uses Error Boundaries to catch errors in the navigation tree.
  useEffect(() => {
    if (error) throw error;
  }, [error]);

  useEffect(() => {
    if (loaded) {
      SplashScreen.hideAsync();
    }
  }, [loaded]);

  if (!loaded) {
    return null;
  }

  return (
    <ThemeProvider>
      <RootLayoutNav />
    </ThemeProvider>
  );
}

function RootLayoutNav() {
  const { scheme } = useThemeControl();
  const c = useTheme();

  // Navigation chrome used to read the system setting directly, which is how the app ended up
  // half-dark: the chrome flipped on a dark device and every screen under it stayed light. It now
  // reads the same resolved scheme the screens do, and takes the app's own tokens rather than
  // react-navigation's stock blue-greys.
  const navigationTheme = useMemo(() => {
    const base = scheme === 'dark' ? DarkTheme : DefaultTheme;
    return {
      ...base,
      colors: {
        ...base.colors,
        primary: c.primary,
        background: c.background,
        card: c.surface,
        text: c.boldText,
        border: c.border,
      },
    };
  }, [scheme, c]);

  return (
    <QueryClientProvider client={queryClient}>
      {/* Inside QueryClientProvider: TermProvider reads the term list through the cache. */}
      <TermProvider>
        <NavigationThemeProvider value={navigationTheme}>
          <HoverStyles />
          <Stack screenOptions={{ headerShown: false }}>
            <Stack.Screen name="index" />
            <Stack.Screen name="dashboard" />
          </Stack>
        </NavigationThemeProvider>
      </TermProvider>
    </QueryClientProvider>
  );
}
