import React, { useId } from "react";
import { StyleProp, StyleSheet, View, ViewStyle } from "react-native";
import Svg, { Defs, LinearGradient, Rect, Stop } from "react-native-svg";
import { useTheme } from "../../../src/theme/ThemeContext";

/**
 * The deep maroon field: board header, podium, and the dashboard join card.
 *
 * This is the only place in the app a full-bleed brand ground appears, and that is what makes the
 * leaderboard read as a different space rather than another card on the dashboard. It stays deep
 * maroon in both themes — see fieldA/fieldB in colors.ts for why it is not `primary`.
 *
 * Drawn as an SVG rather than a CSS gradient because expo-linear-gradient is not a dependency and
 * react-native-web's backgroundImage does not reach native. react-native-svg already ships for the
 * GPA arc, so this costs nothing.
 */
export default function BrandField({
  style,
  children,
}: {
  style?: StyleProp<ViewStyle>;
  children?: React.ReactNode;
}) {
  const c = useTheme();
  // Gradient ids are document-global in SVG, so two fields on one screen would share whichever
  // definition rendered last.
  const id = `field-${useId().replace(/:/g, "")}`;

  return (
    <View style={[styles.field, style]}>
      <Svg style={StyleSheet.absoluteFill} width="100%" height="100%">
        <Defs>
          <LinearGradient id={id} x1="0" y1="0" x2="0.65" y2="1">
            <Stop offset="0" stopColor={c.fieldA} />
            <Stop offset="1" stopColor={c.fieldB} />
          </LinearGradient>
        </Defs>
        <Rect x="0" y="0" width="100%" height="100%" fill={`url(#${id})`} />
      </Svg>
      {children}
    </View>
  );
}

const styles = StyleSheet.create({
  field: {
    // Without this the gradient paints past any corner radius the caller sets.
    overflow: "hidden",
  },
});
