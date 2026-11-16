import { useEffect } from "react";
import { Pressable, StyleProp, StyleSheet, ViewStyle } from "react-native";
import { Feather } from "@expo/vector-icons";
import Animated, {
  Easing,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withTiming,
} from "react-native-reanimated";
import { useTheme, useThemeControl } from "../theme/ThemeContext";
import { radius } from "../theme/spacing";

/**
 * The light/dark switch.
 *
 * The icon shows where the button takes you, not where you are: a moon on the light theme, a sun
 * on the dark one. That is the convention students already know from their phones, and it means
 * the label and the glyph always agree ("Switch to dark theme" next to a moon).
 *
 * `tone` picks the ink. "ground" sits on the app background next to the hamburger and uses the
 * same bold ink the hamburger does. "field" sits on the deep maroon brand field (leaderboard
 * headers) and uses the on-field tokens, which do not move between themes.
 *
 * The one bit of motion: the incoming icon turns a quarter and settles. It is the only feedback
 * that the press landed before the whole screen repaints, so it is kept short and skipped
 * entirely under reduced motion.
 */

type Props = {
  tone?: "ground" | "field";
  size?: number;
  style?: StyleProp<ViewStyle>;
};

export default function ThemeToggle({ tone = "ground", size = 20, style }: Props) {
  const c = useTheme();
  const { scheme, toggle } = useThemeControl();
  const reduceMotion = useReducedMotion();
  const turn = useSharedValue(0);

  useEffect(() => {
    if (reduceMotion) return;
    turn.value = -90;
    turn.value = withTiming(0, { duration: 320, easing: Easing.out(Easing.exp) });
  }, [scheme, reduceMotion, turn]);

  const iconStyle = useAnimatedStyle(() => ({
    transform: [{ rotate: `${turn.value}deg` }],
    opacity: 1 - Math.abs(turn.value) / 180,
  }));

  const toDark = scheme === "light";
  const ink = tone === "field" ? c.onFieldMuted : c.boldText;

  return (
    <Pressable
      onPress={toggle}
      hitSlop={6}
      accessibilityRole="button"
      accessibilityLabel={toDark ? "Switch to dark theme" : "Switch to light theme"}
      style={[styles.button, style]}
    >
      <Animated.View style={iconStyle}>
        <Feather name={toDark ? "moon" : "sun"} size={size} color={ink} />
      </Animated.View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  button: {
    width: 38,
    height: 38,
    alignItems: "center",
    justifyContent: "center",
    borderRadius: radius.md,
  },
});
