import React, { createContext, useContext, useEffect } from "react";
import { DimensionValue, StyleProp, StyleSheet, View, ViewStyle } from "react-native";
import Animated, {
  Easing,
  SharedValue,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withRepeat,
  withTiming,
} from "react-native-reanimated";
import { useTheme } from "../theme/ThemeContext";
import { radius as radii } from "../theme/spacing";

/**
 * Loading placeholders shaped like the content they stand in for.
 *
 * A spinner says "wait"; a skeleton says "this is what is coming, and where". Each screen draws
 * its own skeleton out of these blocks, matched to its real layout, so when the data lands the
 * page fills in rather than jumping from a centred wheel to a full screen.
 *
 * One clock per group. Every block inside a <SkeletonGroup> breathes on the same shared value, so
 * the whole placeholder pulses as one surface instead of thirty blocks drifting out of step. A
 * block outside a group runs its own clock, which is fine for a lone line.
 *
 * Under reduced motion the pulse stops and the blocks sit still at full strength.
 */

const PulseContext = createContext<SharedValue<number> | null>(null);

function usePulseClock(): SharedValue<number> {
  const reduceMotion = useReducedMotion();
  const pulse = useSharedValue(1);
  useEffect(() => {
    if (reduceMotion) {
      pulse.value = 1;
      return;
    }
    pulse.value = withRepeat(
      withTiming(0.5, { duration: 900, easing: Easing.inOut(Easing.quad) }),
      -1,
      true
    );
  }, [reduceMotion, pulse]);
  return pulse;
}

type GroupProps = {
  children: React.ReactNode;
  style?: StyleProp<ViewStyle>;
  /** What is loading, for screen readers. Read once; the blocks themselves are hidden. */
  label?: string;
};

export function SkeletonGroup({ children, style, label = "Loading" }: GroupProps) {
  const pulse = usePulseClock();
  return (
    <PulseContext.Provider value={pulse}>
      <View
        style={style}
        accessible
        accessibilityRole="progressbar"
        accessibilityLabel={label}
        accessibilityState={{ busy: true }}
      >
        {children}
      </View>
    </PulseContext.Provider>
  );
}

type BlockProps = {
  width?: DimensionValue;
  height?: number;
  radius?: number;
  /** "field" for blocks drawn on the maroon brand field. */
  tone?: "ground" | "field";
  style?: StyleProp<ViewStyle>;
};

export function Skeleton({ width = "100%", height = 14, radius = radii.sm, tone = "ground", style }: BlockProps) {
  const shared = useContext(PulseContext);
  // Hooks run unconditionally; the private clock is simply unused inside a group.
  const own = usePulseClock();
  const pulse = shared ?? own;
  const c = useTheme();

  const animated = useAnimatedStyle(() => ({ opacity: pulse.value }));

  return (
    <Animated.View
      aria-hidden
      style={[
        {
          width,
          height,
          borderRadius: radius,
          backgroundColor: tone === "field" ? c.onFieldSkeleton : c.skeleton,
        },
        animated,
        style,
      ]}
    />
  );
}

/** A paragraph's worth of lines, the last one short so it reads as text rather than bars. */
export function SkeletonLines({
  lines = 2,
  height = 12,
  gap = 8,
  lastWidth = "60%",
  tone,
}: {
  lines?: number;
  height?: number;
  gap?: number;
  lastWidth?: DimensionValue;
  tone?: "ground" | "field";
}) {
  return (
    <View style={{ gap }}>
      {Array.from({ length: lines }).map((_, i) => (
        <Skeleton key={i} height={height} tone={tone} width={i === lines - 1 && lines > 1 ? lastWidth : "100%"} />
      ))}
    </View>
  );
}

/** A circle, for avatars and the check circles on event rows. */
export function SkeletonCircle({ size, tone }: { size: number; tone?: "ground" | "field" }) {
  return <Skeleton width={size} height={size} radius={size / 2} tone={tone} />;
}

export const skeletonStyles = StyleSheet.create({
  row: { flexDirection: "row", alignItems: "center" },
});
