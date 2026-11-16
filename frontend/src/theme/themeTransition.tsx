import { useEffect, useRef, useState } from "react";
import { StyleSheet } from "react-native";
import Animated, {
  Easing,
  runOnJS,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withTiming,
} from "react-native-reanimated";
import { ThemeName, palettes } from "./colors";

/**
 * The theme switch as a fade rather than a cut (iOS and Android).
 *
 * Native has no page snapshot to crossfade, so a veil in the outgoing theme's ground covers the
 * screen for the frame the colours swap, then dissolves to reveal the new theme. Same timing as
 * web. Under reduced motion it is quicker and starts part-way, so it still softens the cut without
 * holding anything back.
 */

export function installTransitionStyles() {}

export function withThemeTransition(update: () => void) {
  update();
}

export function ThemeVeil({ scheme }: { scheme: ThemeName }) {
  const reduceMotion = useReducedMotion();
  const previous = useRef(scheme);
  const [ground, setGround] = useState<string | null>(null);
  const opacity = useSharedValue(0);

  useEffect(() => {
    if (previous.current === scheme) return;
    const outgoing = palettes[previous.current].background;
    previous.current = scheme;
    setGround(outgoing);
    opacity.value = reduceMotion ? 0.6 : 1;
    opacity.value = withTiming(
      0,
      { duration: reduceMotion ? 140 : 380, easing: Easing.bezier(0.16, 1, 0.3, 1) },
      (finished) => {
        if (finished) runOnJS(setGround)(null);
      }
    );
  }, [scheme, reduceMotion, opacity]);

  const style = useAnimatedStyle(() => ({ opacity: opacity.value }));

  if (!ground) return null;
  return (
    <Animated.View
      pointerEvents="none"
      style={[StyleSheet.absoluteFill, { backgroundColor: ground, zIndex: 9999 }, style]}
    />
  );
}
