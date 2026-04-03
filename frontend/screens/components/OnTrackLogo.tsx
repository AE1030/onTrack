import React from "react";
import { View, Text, StyleSheet } from "react-native";

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
    fontWeight: "700",
    color: "#F2B94A",
  },
  track: {
    fontWeight: "700",
    color: "#3B0A1D",
  },
  dot: {
    backgroundColor: "#7A003C",
  },
});
