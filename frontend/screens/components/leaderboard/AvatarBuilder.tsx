import React from "react";
import { Pressable, ScrollView, StyleSheet, Text, View } from "react-native";
import { Feather } from "@expo/vector-icons";
import { useTheme } from "../../../src/theme/ThemeContext";
import {
  ACCENT_COLORS,
  AVATAR_AXES,
  AvatarConfig,
  FACE_LABELS,
  GEAR_LABELS,
  HAIR_COLORS,
  SKIN_TONES,
} from "../../../src/leaderboard/avatar";
import PlayerAvatar from "./PlayerAvatar";

/**
 * Five axes, one row each.
 *
 * Colour axes are swatches and shape axes are named chips, because a row of six near-identical
 * head thumbnails is unreadable at any size that fits on a phone, while "Mohawk" is not.
 *
 * Nothing here can fail, and nothing here blocks Next: a default is pre-rolled from the handle at
 * the step before, so a student who does not care skips the card and still gets a face.
 */
export default function AvatarBuilder({
  value,
  onChange,
}: {
  value: AvatarConfig;
  onChange: (next: AvatarConfig) => void;
}) {
  const c = useTheme();
  const set = (patch: Partial<AvatarConfig>) => onChange({ ...value, ...patch });

  const shuffle = () =>
    onChange({
      face: Math.floor(Math.random() * AVATAR_AXES.face),
      skin: Math.floor(Math.random() * AVATAR_AXES.skin),
      hair: Math.floor(Math.random() * AVATAR_AXES.hair),
      gear: Math.floor(Math.random() * AVATAR_AXES.gear),
      accent: Math.floor(Math.random() * AVATAR_AXES.accent),
    });

  return (
    <View style={styles.wrap}>
      <View style={styles.preview}>
        <PlayerAvatar config={value} size={116} />
        <Pressable
          onPress={shuffle}
          style={[styles.shuffle, { borderColor: c.border, backgroundColor: c.surface }]}
        >
          <Feather name="shuffle" size={14} color={c.primary} />
          <Text style={[styles.shuffleText, { color: c.primary }]}>Shuffle</Text>
        </Pressable>
      </View>

      <Axis label="Style">
        <ChipRow
          labels={FACE_LABELS}
          selected={value.face}
          onSelect={(face) => set({ face })}
        />
      </Axis>

      <Axis label="Skin">
        <SwatchRow
          colors={SKIN_TONES}
          selected={value.skin}
          onSelect={(skin) => set({ skin })}
        />
      </Axis>

      <Axis label="Hair">
        <SwatchRow
          colors={HAIR_COLORS}
          selected={value.hair}
          onSelect={(hair) => set({ hair })}
        />
      </Axis>

      <Axis label="Eyewear">
        <ChipRow
          labels={GEAR_LABELS}
          selected={value.gear}
          onSelect={(gear) => set({ gear })}
        />
      </Axis>

      <Axis label="Backdrop">
        <SwatchRow
          colors={ACCENT_COLORS}
          selected={value.accent}
          onSelect={(accent) => set({ accent })}
        />
      </Axis>
    </View>
  );
}

function Axis({ label, children }: { label: string; children: React.ReactNode }) {
  const c = useTheme();
  return (
    <View style={styles.axis}>
      <Text style={[styles.axisLabel, { color: c.bodyText }]}>{label}</Text>
      {children}
    </View>
  );
}

function SwatchRow({
  colors,
  selected,
  onSelect,
}: {
  colors: string[];
  selected: number;
  onSelect: (i: number) => void;
}) {
  const c = useTheme();
  return (
    <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.row}>
      {colors.map((color, i) => (
        <Pressable
          key={color + i}
          onPress={() => onSelect(i)}
          style={[
            styles.swatch,
            { backgroundColor: color, borderColor: c.border },
            selected === i && { borderColor: c.primary, borderWidth: 3 },
          ]}
        />
      ))}
    </ScrollView>
  );
}

function ChipRow({
  labels,
  selected,
  onSelect,
}: {
  labels: string[];
  selected: number;
  onSelect: (i: number) => void;
}) {
  const c = useTheme();
  return (
    <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.row}>
      {labels.map((label, i) => {
        const active = selected === i;
        return (
          <Pressable
            key={label}
            onPress={() => onSelect(i)}
            style={[
              styles.chip,
              { borderColor: c.border, backgroundColor: c.surface },
              active && { borderColor: c.primary, backgroundColor: c.primarySoft },
            ]}
          >
            <Text
              style={[
                styles.chipText,
                { color: active ? c.primary : c.bodyText },
              ]}
            >
              {label}
            </Text>
          </Pressable>
        );
      })}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  wrap: {
    gap: 14,
  },
  preview: {
    alignItems: "center",
    gap: 10,
  },
  shuffle: {
    flexDirection: "row",
    alignItems: "center",
    gap: 6,
    borderWidth: 1,
    borderRadius: 999,
    paddingHorizontal: 12,
    paddingVertical: 6,
  },
  shuffleText: {
    fontSize: 13,
    fontWeight: "600",
  },
  axis: {
    gap: 6,
  },
  axisLabel: {
    fontSize: 12,
    fontWeight: "700",
    letterSpacing: 0.4,
    textTransform: "uppercase",
  },
  row: {
    flexDirection: "row",
    gap: 8,
    paddingVertical: 2,
    paddingRight: 8,
  },
  swatch: {
    width: 34,
    height: 34,
    borderRadius: 17,
    borderWidth: 1.5,
  },
  chip: {
    borderWidth: 1,
    borderRadius: 999,
    paddingHorizontal: 12,
    paddingVertical: 7,
  },
  chipText: {
    fontSize: 13,
    fontWeight: "600",
  },
});
