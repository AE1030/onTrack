import React, { useCallback, useEffect, useRef, useState } from "react";
import { Pressable, StyleSheet, Text, View } from "react-native";
import { Feather } from "@expo/vector-icons";
import Animated, {
  Easing,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withTiming,
} from "react-native-reanimated";

import { colors } from "../../src/theme/colors";
import { spacing } from "../../src/theme/spacing";
import { useTerm } from "../../src/terms/TermContext";

/**
 * The term navigator that sits at the top of My Courses.
 *
 * It replaces a row of one chip per term, which had two problems. It vanished entirely for a
 * student with a single term, so the feature was invisible until they happened to have history;
 * and once a student had four or five terms the row wrapped into a block of near-identical pills
 * with no indication of which direction was backwards.
 *
 * A stepper fixes both. It is always on screen, so the current term is always named and the
 * ability to look back is always discoverable, and it has a direction: left is earlier, right is
 * later. Both arrows disable at the ends of the list rather than disappearing, which is what tells
 * a single-term student that there is simply nothing behind them yet.
 *
 * It owns no state. Selecting a term goes through {@link useTerm}, which is what the course list
 * is keyed on, so pressing an arrow re-runs exactly the fetch the old chips did.
 *
 * Reads the flat light `colors` rather than `useTheme()`, to match MyCoursesScreen. Convert both
 * together when that screen moves onto the themed palette.
 */

/**
 * colors.bodyText (#888888) is only 3.5:1 on the white surface, under the 4.5:1 floor for text
 * this size. The meta line uses the ink colour at reduced opacity instead, which measures about
 * 6.5:1 and still reads as secondary to the term name above it.
 */
const META_INK = "rgba(59,10,29,0.72)";

/** How far the label travels as it crossfades. Small enough to read as a shift, not a slide. */
const TRAVEL = 10;
const DURATION = 180;

function courseCountLabel(count: number): string {
  if (count === 0) return "No courses";
  if (count === 1) return "1 course";
  return `${count} courses`;
}

export default function TermPicker() {
  const { selectedTerm, terms, editable, loading, setSelectedTerm } = useTerm();
  const reduceMotion = useReducedMotion();

  // Terms arrive newest first, so the earlier term is the next index up and the later term is the
  // one before. Getting this backwards is the easy mistake here, hence naming them rather than
  // indexing inline.
  const index = terms.findIndex((t) => t.term === selectedTerm);
  const earlier = index >= 0 && index + 1 < terms.length ? terms[index + 1] : null;
  const later = index > 0 ? terms[index - 1] : null;
  const active = index >= 0 ? terms[index] : null;

  const ready = !loading && !!selectedTerm && !!active;

  // -1 when stepping back, +1 when stepping forward, so the incoming label enters from the side
  // it conceptually came from.
  const direction = useRef(0);
  const offset = useSharedValue(0);
  const fade = useSharedValue(1);

  useEffect(() => {
    if (reduceMotion || direction.current === 0) {
      offset.value = 0;
      fade.value = 1;
      return;
    }
    offset.value = direction.current * TRAVEL;
    fade.value = 0;
    offset.value = withTiming(0, { duration: DURATION, easing: Easing.out(Easing.quad) });
    fade.value = withTiming(1, { duration: DURATION, easing: Easing.out(Easing.quad) });
  }, [selectedTerm, reduceMotion, offset, fade]);

  const labelStyle = useAnimatedStyle(() => ({
    opacity: fade.value,
    transform: [{ translateX: offset.value }],
  }));

  const step = useCallback(
    (to: { term: string } | null, dir: number) => {
      if (!to) return;
      direction.current = dir;
      setSelectedTerm(to.term);
    },
    [setSelectedTerm]
  );

  return (
    <View style={styles.wrapper}>
      <View style={styles.stepper}>
        <Arrow
          icon="chevron-left"
          label="Show an earlier term"
          hint={earlier ? `Switches to ${earlier.term}` : undefined}
          disabled={!ready || !earlier}
          onPress={() => step(earlier, -1)}
        />

        <View style={styles.readout}>
          {ready ? (
            <Animated.View style={[styles.label, labelStyle]}>
              <Text style={styles.term} numberOfLines={1}>
                {selectedTerm}
              </Text>
              <Text style={styles.meta} numberOfLines={1}>
                {editable ? "Current term" : "View only"} ·{" "}
                {courseCountLabel(active.courseCount)}
              </Text>
            </Animated.View>
          ) : (
            // A pair of bars rather than a spinner: the control keeps its height and position, so
            // nothing below it moves when the real term arrives.
            <View accessibilityLabel="Loading terms">
              <View style={[styles.bar, styles.barTerm]} />
              <View style={[styles.bar, styles.barMeta]} />
            </View>
          )}
        </View>

        <Arrow
          icon="chevron-right"
          label="Show a later term"
          hint={later ? `Switches to ${later.term}` : undefined}
          disabled={!ready || !later}
          onPress={() => step(later, 1)}
        />
      </View>
    </View>
  );
}

/**
 * One end of the stepper.
 *
 * Disabled rather than hidden at the ends of the list: a control that removes itself makes the
 * row change width and leaves the student wondering whether they missed something.
 */
function Arrow({
  icon,
  label,
  hint,
  disabled,
  onPress,
}: {
  icon: "chevron-left" | "chevron-right";
  label: string;
  hint?: string;
  disabled: boolean;
  onPress: () => void;
}) {
  const [hovered, setHovered] = useState(false);
  const [focused, setFocused] = useState(false);

  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityHint={hint}
      accessibilityState={{ disabled }}
      disabled={disabled}
      onPress={onPress}
      onHoverIn={() => setHovered(true)}
      onHoverOut={() => setHovered(false)}
      onFocus={() => setFocused(true)}
      onBlur={() => setFocused(false)}
      // Pads the touch target out to roughly 52pt without growing the control.
      hitSlop={6}
      style={({ pressed }) => [
        styles.arrow,
        // The border is always present and merely changes colour, so a focus ring cannot shift
        // the layout of the row it sits in.
        focused && !disabled && styles.arrowFocused,
        !disabled && (hovered || pressed) && styles.arrowActive,
      ]}
    >
      <Feather
        name={icon}
        size={20}
        color={disabled ? colors.muted : colors.primary}
      />
    </Pressable>
  );
}

const styles = StyleSheet.create({
  wrapper: {
    alignItems: "center",
    marginBottom: spacing.md,
  },
  stepper: {
    flexDirection: "row",
    alignItems: "center",
    gap: 4,
    padding: 4,
    borderRadius: 999,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.surface,
    // Sized by the layout rather than by the term name. A width driven by content would resize the
    // pill on every step and slide the arrows out from under the cursor, and a fixed width wide
    // enough for "Spring/Summer 2026" would overflow a 320pt screen. This does neither.
    width: "100%",
    maxWidth: 320,
  },
  readout: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
    paddingHorizontal: spacing.sm,
  },
  label: {
    // Bounds the two Texts so numberOfLines can actually ellipsize a long term name instead of
    // letting it push the arrows apart.
    width: "100%",
  },
  term: {
    fontSize: 16,
    fontWeight: "700",
    color: colors.boldText,
    textAlign: "center",
  },
  meta: {
    fontSize: 12,
    fontWeight: "500",
    color: META_INK,
    textAlign: "center",
    marginTop: 1,
  },
  arrow: {
    width: 40,
    height: 40,
    borderRadius: 20,
    alignItems: "center",
    justifyContent: "center",
    borderWidth: 2,
    borderColor: "transparent",
    // The readout is the only thing that gives ground when the pill is narrow.
    flexShrink: 0,
  },
  arrowActive: {
    backgroundColor: colors.surfaceSunken,
  },
  arrowFocused: {
    borderColor: colors.primary,
  },
  bar: {
    backgroundColor: colors.surfaceSunken,
    borderRadius: 4,
    alignSelf: "center",
  },
  barTerm: {
    width: 104,
    height: 15,
  },
  barMeta: {
    width: 78,
    height: 10,
    marginTop: 4,
  },
});
