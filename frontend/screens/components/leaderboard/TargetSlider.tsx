import React, { useMemo, useRef, useState } from "react";
import {
  LayoutChangeEvent,
  PanResponder,
  Pressable,
  StyleSheet,
  Text,
  View,
} from "react-native";
import { Feather } from "@expo/vector-icons";
import { useTheme } from "../../../src/theme/ThemeContext";
import { clamp } from "../../../src/leaderboard/scoring";

/**
 * The target picker.
 *
 * Hand-rolled on PanResponder rather than pulling in a slider package: it needs a marked boundary
 * at the growth threshold, it has to work on web where the app also ships, and the whole control is
 * eighty lines. PanResponder is in react-native itself and react-native-web implements it.
 *
 * Stepped to 0.1, which is the granularity a GPA target is meaningful at. Finer would suggest the
 * scoring cares about a hundredth of a point in a number the student is guessing at anyway.
 */

const STEP = 0.1;
const THUMB = 26;

export default function TargetSlider({
  min,
  max,
  value,
  onChange,
  /** The lowest target that still counts as growth. Drawn as the line between the two modes. */
  growthAt,
  disabled = false,
}: {
  min: number;
  max: number;
  value: number;
  onChange: (v: number) => void;
  growthAt?: number | null;
  disabled?: boolean;
}) {
  const c = useTheme();
  const [width, setWidth] = useState(0);

  // PanResponder is created once and closes over its first render's props, so everything it needs
  // at gesture time is read through refs instead.
  const state = useRef({ width: 0, min, max, disabled, onChange });
  state.current = { width, min, max, disabled, onChange };

  const snap = (raw: number) => {
    const { min: lo, max: hi } = state.current;
    const stepped = Math.round(raw / STEP) * STEP;
    return Math.round(clamp(stepped, lo, hi) * 10) / 10;
  };

  const emitFromX = (x: number) => {
    const s = state.current;
    if (s.disabled || s.width <= 0) return;
    const span = s.max - s.min || 1;
    const ratio = clamp((x - THUMB / 2) / Math.max(1, s.width - THUMB), 0, 1);
    s.onChange(snap(s.min + ratio * span));
  };

  const pan = useMemo(
    () =>
      PanResponder.create({
        onStartShouldSetPanResponder: () => !state.current.disabled,
        onMoveShouldSetPanResponder: () => !state.current.disabled,
        // locationX is relative to the view holding the handlers — the track — which is exactly
        // the coordinate space the maths wants, and it stays relative to it for the whole gesture
        // on both native and web.
        onPanResponderGrant: (e) => emitFromX(e.nativeEvent.locationX),
        onPanResponderMove: (e) => emitFromX(e.nativeEvent.locationX),
      }),
    []
  );

  const span = max - min || 1;
  const ratio = clamp((value - min) / span, 0, 1);
  const travel = Math.max(0, width - THUMB);
  const thumbLeft = travel * ratio;
  const growthRatio =
    growthAt !== null && growthAt !== undefined && growthAt > min && growthAt < max
      ? clamp((growthAt - min) / span, 0, 1)
      : null;

  const nudge = (direction: 1 | -1) => {
    if (disabled) return;
    onChange(snap(value + direction * STEP));
  };

  const onLayout = (e: LayoutChangeEvent) => setWidth(e.nativeEvent.layout.width);

  return (
    <View style={[styles.wrap, disabled && styles.disabled]}>
      <View style={styles.row}>
        <Nudge icon="minus" onPress={() => nudge(-1)} disabled={disabled || value <= min} />

        <View style={styles.trackArea} onLayout={onLayout} {...pan.panHandlers}>
          <View style={[styles.track, { backgroundColor: c.surfaceSunken }]} />
          <View
            style={[
              styles.fill,
              { backgroundColor: c.primary, width: thumbLeft + THUMB / 2 },
            ]}
          />

          {growthRatio !== null && (
            <View
              style={[
                styles.growthMark,
                { backgroundColor: c.accent, left: THUMB / 2 + travel * growthRatio - 1 },
              ]}
            />
          )}

          <View
            style={[
              styles.thumb,
              {
                left: thumbLeft,
                backgroundColor: c.surface,
                borderColor: c.primary,
                shadowColor: c.shadowStrong,
              },
            ]}
          />
        </View>

        <Nudge icon="plus" onPress={() => nudge(1)} disabled={disabled || value >= max} />
      </View>

      <View style={styles.scale}>
        <Text style={[styles.scaleText, { color: c.faintText }]}>{min.toFixed(1)}</Text>
        {growthRatio !== null && (
          <Text style={[styles.scaleText, { color: c.bodyText }]}>
            Growth from {growthAt!.toFixed(1)}
          </Text>
        )}
        <Text style={[styles.scaleText, { color: c.faintText }]}>{max.toFixed(1)}</Text>
      </View>
    </View>
  );
}

function Nudge({
  icon,
  onPress,
  disabled,
}: {
  icon: "plus" | "minus";
  onPress: () => void;
  disabled: boolean;
}) {
  const c = useTheme();
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled}
      hitSlop={8}
      style={[
        styles.nudge,
        { borderColor: c.border, backgroundColor: c.surface },
        disabled && { opacity: 0.4 },
      ]}
    >
      <Feather name={icon} size={16} color={c.primary} />
    </Pressable>
  );
}

const styles = StyleSheet.create({
  wrap: {
    gap: 8,
  },
  disabled: {
    opacity: 0.55,
  },
  row: {
    flexDirection: "row",
    alignItems: "center",
    gap: 10,
  },
  nudge: {
    width: 32,
    height: 32,
    borderRadius: 16,
    borderWidth: 1,
    alignItems: "center",
    justifyContent: "center",
  },
  trackArea: {
    flex: 1,
    height: THUMB + 12,
    justifyContent: "center",
  },
  track: {
    height: 6,
    borderRadius: 3,
    marginHorizontal: THUMB / 2,
  },
  fill: {
    position: "absolute",
    left: 0,
    height: 6,
    borderRadius: 3,
    marginLeft: THUMB / 2,
  },
  growthMark: {
    position: "absolute",
    width: 2,
    height: 14,
    borderRadius: 1,
  },
  thumb: {
    position: "absolute",
    width: THUMB,
    height: THUMB,
    borderRadius: THUMB / 2,
    borderWidth: 3,
    boxShadow: "0px 2px 6px rgba(0,0,0,0.18)",
    elevation: 3,
  },
  scale: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    paddingHorizontal: 42,
  },
  scaleText: {
    fontSize: 11,
    fontWeight: "600",
  },
});
