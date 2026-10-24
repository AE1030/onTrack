import React from "react";
import { View, StyleSheet } from "react-native";
import { Text } from "../../src/ui/Text";
import { colors } from "../../src/theme/colors";
import { fonts } from "../../src/theme/typography";

type Props = {
  size?: number;
};

export default function OnTrackLogo({ size = 22 }: Props) {
  const dotSize = size * 0.3;

  return (
    <View style={styles.container}>
      <Text style={[styles.on, { fontSize: size }]}>on</Text>
      <Text style={[styles.track, { fontSize: size }]}>Track</Text>
      <View
        style={[
          styles.dot,
          {
            width: dotSize,
            height: dotSize,
            borderRadius: dotSize / 2,
            marginBottom: size * 0.45,
            marginLeft: 2,
          },
        ]}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flexDirection: "row",
    alignItems: "flex-end",
  },
  on: {
    fontFamily: fonts.display,
    color: colors.accent,
  },
  track: {
    fontFamily: fonts.display,
    color: colors.boldText,
  },
  dot: {
    backgroundColor: colors.primary,
  },
});
