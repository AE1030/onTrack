import React, { useEffect } from "react";
import { View, Text, StyleSheet } from "react-native";
import Svg, { Path } from "react-native-svg";
import Animated, {
  useSharedValue,
  useAnimatedProps,
  withTiming,
  withDelay,
  Easing,
  interpolate,
} from "react-native-reanimated";

const AnimatedPath = Animated.createAnimatedComponent(Path);

const ARC_RADIUS = 90;
const STROKE_WIDTH = 18;
const CENTER_X = 120;
const CENTER_Y = 110;

// Semi-circle arc from left to right (180 degrees)
const ARC_LENGTH = Math.PI * ARC_RADIUS;

function describeArc(): string {
  // Draw a semi-circle from left (180deg) to right (0deg)
  const startX = CENTER_X - ARC_RADIUS;
  const startY = CENTER_Y;
  const endX = CENTER_X + ARC_RADIUS;
  const endY = CENTER_Y;
  return `M ${startX} ${startY} A ${ARC_RADIUS} ${ARC_RADIUS} 0 0 1 ${endX} ${endY}`;
}

type Props = {
  gpa: number;
  targetGpa: number | null;
  maxScale: number;
};

export default function GpaArc({ gpa, targetGpa, maxScale }: Props) {
  const gpaProgress = useSharedValue(0);
  const targetProgress = useSharedValue(0);

  const clampedGpa = Math.min(Math.max(gpa, 0), maxScale);
  const clampedTarget =
    targetGpa != null ? Math.min(Math.max(targetGpa, 0), maxScale) : 0;

  useEffect(() => {
    gpaProgress.value = 0;
    targetProgress.value = 0;

    gpaProgress.value = withDelay(
      100,
      withTiming(clampedGpa / maxScale, {
        duration: 1200,
        easing: Easing.out(Easing.cubic),
      })
    );

    if (targetGpa != null) {
      targetProgress.value = withDelay(
        100,
        withTiming(clampedTarget / maxScale, {
          duration: 1400,
          easing: Easing.out(Easing.cubic),
        })
      );
    }
  }, [gpa, targetGpa, maxScale]);

  const arcD = describeArc();

  const targetAnimatedProps = useAnimatedProps(() => ({
    strokeDashoffset: ARC_LENGTH * (1 - targetProgress.value),
  }));

  const gpaAnimatedProps = useAnimatedProps(() => ({
    strokeDashoffset: ARC_LENGTH * (1 - gpaProgress.value),
  }));

  return (
    <View style={styles.container}>
      <Svg width={240} height={140} viewBox="0 0 240 140">
        {/* Background track */}
        <Path
          d={arcD}
          stroke="#E8E0E5"
          strokeWidth={STROKE_WIDTH}
          fill="none"
          strokeLinecap="round"
        />
        {/* Target arc (yellow) — behind GPA */}
        {targetGpa != null && (
          <AnimatedPath
            d={arcD}
            stroke="#F2B94A"
            strokeWidth={STROKE_WIDTH}
            fill="none"
            strokeLinecap="round"
            strokeDasharray={`${ARC_LENGTH} ${ARC_LENGTH}`}
            animatedProps={targetAnimatedProps}
          />
        )}
        {/* GPA arc (maroon) — on top */}
        <AnimatedPath
          d={arcD}
          stroke="#7A003C"
          strokeWidth={STROKE_WIDTH}
          fill="none"
          strokeLinecap="round"
          strokeDasharray={`${ARC_LENGTH} ${ARC_LENGTH}`}
          animatedProps={gpaAnimatedProps}
        />
      </Svg>
      <View style={styles.labelContainer}>
        <Text style={styles.gpaValue}>{gpa.toFixed(2)}</Text>
        {targetGpa != null && (
          <Text style={styles.targetLabel}>Target: {targetGpa.toFixed(2)}</Text>
        )}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    alignItems: "center",
    justifyContent: "center",
  },
  labelContainer: {
    position: "absolute",
    bottom: 10,
    alignItems: "center",
  },
  gpaValue: {
    fontSize: 36,
    fontWeight: "700",
    color: "#7A003C",
  },
  targetLabel: {
    fontSize: 13,
    color: "#888",
    marginTop: 2,
  },
});
