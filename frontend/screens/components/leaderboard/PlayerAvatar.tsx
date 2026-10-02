import React, { useId, useMemo } from "react";
import { StyleSheet, View } from "react-native";
import Svg, {
  Circle,
  ClipPath,
  Defs,
  Ellipse,
  G,
  LinearGradient,
  Path,
  RadialGradient,
  Rect,
  Stop,
} from "react-native-svg";
import {
  ACCENT_COLORS,
  AvatarConfig,
  HAIR_COLORS,
  SHIRT_COLORS,
  SKIN_SHADES,
  SKIN_TONES,
  avatarFor,
} from "../../../src/leaderboard/avatar";

/**
 * One head, drawn from five numbers.
 *
 * Everything is laid out in a 100x100 viewBox and scaled by the caller, which is the reason this is
 * an SVG at all: the board draws the same face at 30px in a row, 56px on the podium and 116px in
 * the builder, and a bitmap set would need three exports per variant.
 *
 * The look is a soft, chunky toy figure: an oversized head with ears that stick out, big eyes,
 * heavy brows, and shoulders cropped by a saturated disc. The roundness comes from gradients rather
 * than outlines, because outlines turn to mush at 30px and shading does not.
 *
 * Every layer is clipped to the disc. Hair, ears and shoulders are drawn larger than the circle and
 * the clip is what gives them a clean edge, so nothing can poke out of the profile icon however a
 * style is shaped.
 *
 * Draw order is load-bearing. Back hair sits behind the head, the head and ears sit on top of it,
 * the face sits on the head, eyewear sits on the face, and the fringe or hat is last so it can fall
 * over the brows. Reordering any of those produces a face with hair through it.
 */

const EYE = "#1E1418";
const FRAME = "#24242C";

type Props = {
  /** Omit to derive a face from the handle, which is what every other player on the board gets. */
  config?: AvatarConfig | null;
  handle?: string;
  size?: number;
  /** Gold, for rank 1 and for the student's own row. Reserved: it is what makes them findable. */
  ring?: "gold" | "none";
  /** The backdrop disc is the only thing separating a head from a row, so it can be dropped. */
  showBackdrop?: boolean;
};

export default function PlayerAvatar({
  config,
  handle = "player",
  size = 40,
  ring = "none",
  showBackdrop = true,
}: Props) {
  const a = useMemo(() => config ?? avatarFor(handle), [config, handle]);
  // Gradient and clip ids are document-global on web, so every instance needs its own or a board
  // of thirty heads would all paint with whichever definition rendered last.
  const uid = `av${useId().replace(/[^a-zA-Z0-9]/g, "")}`;
  const id = (name: string) => `${uid}-${name}`;
  const url = (name: string) => `url(#${id(name)})`;

  const skin = SKIN_TONES[a.skin] ?? SKIN_TONES[0];
  const shade = SKIN_SHADES[a.skin] ?? SKIN_SHADES[0];
  const hair = HAIR_COLORS[a.hair] ?? HAIR_COLORS[0];
  const backdrop = ACCENT_COLORS[a.accent] ?? ACCENT_COLORS[0];
  const shirt = SHIRT_COLORS[a.accent] ?? SHIRT_COLORS[0];

  const brow = mix(hair, "#1B1310", 0.55);
  const mouth = mix(shade, "#2A1418", 0.45);
  const hairLight = mix(hair, "#FFFFFF", 0.35);

  const style = a.face;
  const hidesEars = style === 2;

  return (
    <View style={[styles.disc, { width: size, height: size, borderRadius: size / 2 }]}>
      <Svg width={size} height={size} viewBox="0 0 100 100">
        <Defs>
          <ClipPath id={id("disc")}>
            <Circle cx={50} cy={50} r={50} />
          </ClipPath>
          <RadialGradient id={id("head")} cx="40%" cy="32%" r="68%" fx="40%" fy="32%">
            <Stop offset="0" stopColor={mix(skin, "#FFFFFF", 0.22)} />
            <Stop offset="0.55" stopColor={skin} />
            <Stop offset="1" stopColor={shade} />
          </RadialGradient>
          <LinearGradient id={id("shirt")} x1="0" y1="0" x2="0" y2="1">
            <Stop offset="0" stopColor={mix(shirt, "#FFFFFF", 0.16)} />
            <Stop offset="1" stopColor={mix(shirt, "#000000", 0.12)} />
          </LinearGradient>
          <RadialGradient id={id("glow")} cx="50%" cy="38%" r="60%" fx="50%" fy="38%">
            <Stop offset="0" stopColor="#FFFFFF" stopOpacity={0.22} />
            <Stop offset="1" stopColor="#FFFFFF" stopOpacity={0} />
          </RadialGradient>
        </Defs>

        <G clipPath={url("disc")}>
          {showBackdrop && (
            <>
              <Rect x={0} y={0} width={100} height={100} fill={backdrop} />
              <Rect x={0} y={0} width={100} height={100} fill={url("glow")} />
            </>
          )}

          <BackHair style={style} hair={hair} />

          {/* Shoulders, cropped by the disc rather than drawn to a seam. */}
          <Path
            d="M6 106 C6 90 19 82 35 79.5 C40 84.5 60 84.5 65 79.5 C81 82 94 90 94 106 Z"
            fill={url("shirt")}
          />
          <Path d="M42 66 L58 66 L58 80 C58 85 42 85 42 80 Z" fill={shade} />
          <Path
            d="M35 79.5 C40 86 60 86 65 79.5"
            stroke={mix(shirt, "#000000", 0.28)}
            strokeWidth={2.6}
            strokeLinecap="round"
            fill="none"
          />

          {!hidesEars && (
            <>
              <Ellipse cx={25.5} cy={50} rx={6} ry={8} fill={skin} />
              <Ellipse cx={26.2} cy={50.5} rx={2.8} ry={4.4} fill={shade} opacity={0.7} />
              <Ellipse cx={74.5} cy={50} rx={6} ry={8} fill={skin} />
              <Ellipse cx={73.8} cy={50.5} rx={2.8} ry={4.4} fill={shade} opacity={0.7} />
            </>
          )}

          {/* Head. Slightly squared at the jaw, which is most of what makes it read as a figure. */}
          <Path
            d="M50 19 C67 19 76 31 76 47 C76 64 66 76 50 76 C34 76 24 64 24 47 C24 31 33 19 50 19 Z"
            fill={url("head")}
          />

          {/* Face */}
          <Circle cx={34} cy={60} r={4} fill="#FF7A7A" opacity={0.14} />
          <Circle cx={66} cy={60} r={4} fill="#FF7A7A" opacity={0.14} />

          <Ellipse cx={40.5} cy={49.5} rx={5.4} ry={5.8} fill="#FFFFFF" />
          <Ellipse cx={59.5} cy={49.5} rx={5.4} ry={5.8} fill="#FFFFFF" />
          <Circle cx={41.3} cy={50.3} r={3} fill={EYE} />
          <Circle cx={60.3} cy={50.3} r={3} fill={EYE} />
          <Circle cx={42.4} cy={49} r={1} fill="#FFFFFF" />
          <Circle cx={61.4} cy={49} r={1} fill="#FFFFFF" />

          <Path d="M34 41.5 Q40 37.2 46.5 40" stroke={brow} strokeWidth={3.4} strokeLinecap="round" fill="none" />
          <Path d="M53.5 40 Q60 37.2 66 41.5" stroke={brow} strokeWidth={3.4} strokeLinecap="round" fill="none" />

          <Ellipse cx={50} cy={58} rx={3.6} ry={2.8} fill={shade} opacity={0.75} />
          <Ellipse cx={49} cy={57} rx={1.4} ry={0.9} fill="#FFFFFF" opacity={0.35} />

          <Path d="M44.5 65 Q50 68.6 55.5 65" stroke={mouth} strokeWidth={2} strokeLinecap="round" fill="none" />

          <Gear gear={a.gear} />

          <FrontHair style={style} hair={hair} highlight={hairLight} shirt={shirt} />
        </G>

        {/* Reserved for rank 1 and for your own row. Drawn inside the viewBox so it never clips. */}
        {ring === "gold" && (
          <Circle cx={50} cy={50} r={47.5} stroke="#F2B94A" strokeWidth={5} fill="none" />
        )}
      </Svg>
    </View>
  );
}

/** Hair that sits behind the head. Only the bob and the buns have any. */
function BackHair({ style, hair }: { style: number; hair: string }) {
  if (style === 1) {
    return (
      <G>
        <Circle cx={31} cy={20} r={10.5} fill={hair} />
        <Circle cx={69} cy={20} r={10.5} fill={hair} />
      </G>
    );
  }
  if (style === 2) {
    return (
      <Path
        d="M20 50 C18 25 32 12 50 12 C68 12 82 25 80 50 L81 72 C74 77 67 75 64 70 L36 70 C33 75 26 77 19 72 Z"
        fill={hair}
      />
    );
  }
  return null;
}

function FrontHair({
  style,
  hair,
  highlight,
  shirt,
}: {
  style: number;
  hair: string;
  highlight: string;
  shirt: string;
}) {
  switch (style) {
    case 0: // Crop: a messy cap, lumpy on top, with one tuft flicking up.
      return (
        <G>
          <Path
            d="M23 47 C21 31 29 20 37 17 C39 10 47 9 50 13 C54 8 63 10 64 16 C73 20 79 31 77 47 C74 39 71 35 66 33 C62 36 56 35 52 31 C48 35 40 36 34 33 C29 36 26 40 23 47 Z"
            fill={hair}
          />
          <Path d="M50 14 C51 6 58 3 63 5 C58 7 56 10 55 15 Z" fill={hair} />
          <Path d="M37 23 Q48 18 61 21" stroke={highlight} strokeWidth={2.2} strokeLinecap="round" fill="none" opacity={0.5} />
        </G>
      );
    case 1: // Buns: zigzag bangs, loose strands, and a band at the base of each bun.
      return (
        <G>
          <Path
            d="M24 49 C22 30 33 18 50 18 C67 18 78 30 76 49 C73 41 69 37 63 35 L59 41 L54 34 L48 40 L43 34 L38 40 L36 35 C30 37 26 42 24 49 Z"
            fill={hair}
          />
          <Path d="M25 47 C21 57 27 63 23 71" stroke={hair} strokeWidth={2.6} strokeLinecap="round" fill="none" />
          <Path d="M75 47 C79 57 73 63 77 71" stroke={hair} strokeWidth={2.6} strokeLinecap="round" fill="none" />
          <Path d="M23 25 Q31 31 40 25" stroke="#F2D24A" strokeWidth={2.4} strokeLinecap="round" fill="none" />
          <Path d="M60 25 Q69 31 77 25" stroke="#F2D24A" strokeWidth={2.4} strokeLinecap="round" fill="none" />
          <Path d="M36 24 Q48 19 62 23" stroke={highlight} strokeWidth={2} strokeLinecap="round" fill="none" opacity={0.45} />
        </G>
      );
    case 2: // Bob: a side-swept fringe, with the sides falling in front of the ears.
      return (
        <G>
          <Path
            d="M24 51 C21 30 33 17 50 17 C67 17 79 30 76 51 C73 43 71 37 64 32 C56 38 44 40 33 34 C29 39 26 45 24 51 Z"
            fill={hair}
          />
          <Path d="M24 38 C19 51 20 64 25 72 L31 72 C28 62 28 50 30 40 Z" fill={hair} />
          <Path d="M76 38 C81 51 80 64 75 72 L69 72 C72 62 72 50 70 40 Z" fill={hair} />
          <Path d="M36 23 Q48 18 62 22" stroke={highlight} strokeWidth={2.2} strokeLinecap="round" fill="none" opacity={0.5} />
        </G>
      );
    case 3: // Mohawk, over shaved sides. Wide enough to survive being drawn 30px across in a row.
      return (
        <G>
          <Path
            d="M24.5 45 C23.5 28 34 19 50 19 C66 19 76.5 28 75.5 45 C72 34 62 28 50 28 C38 28 28 34 24.5 45 Z"
            fill={hair}
            opacity={0.4}
          />
          <Path d="M41 33 C38 20 43 7 50 2 C57 7 62 20 59 33 C54 29.5 46 29.5 41 33 Z" fill={hair} />
          <Path d="M47 10 Q50 6 53 10" stroke={highlight} strokeWidth={1.8} strokeLinecap="round" fill="none" opacity={0.55} />
        </G>
      );
    case 4: {
      // Beanie, in a deepened version of the shirt so the outfit reads as one choice.
      const hat = mix(shirt, "#1C1C24", 0.4);
      const cuff = mix(hat, "#000000", 0.15);
      return (
        <G>
          <Path d="M26 38 L31 38 L31 49 L27 46 Z" fill={hair} />
          <Path d="M74 38 L69 38 L69 49 L73 46 Z" fill={hair} />
          <Path d="M23 35 C23 17 35 7 50 7 C65 7 77 17 77 35 Z" fill={hat} />
          <Path d="M36 14 Q48 9 62 13" stroke="#FFFFFF" strokeWidth={2.2} strokeLinecap="round" fill="none" opacity={0.18} />
          <Rect x={20} y={27} width={60} height={11} rx={5.5} fill={cuff} />
          {[27, 34, 41, 48, 55, 62, 69].map((x) => (
            <Path key={x} d={`M${x + 1.5} 29.5 V35.5`} stroke="#000000" strokeWidth={1.4} strokeLinecap="round" opacity={0.18} />
          ))}
        </G>
      );
    }
    case 5: // Swoop: spiky fringe swept across to one side.
      return (
        <G>
          <Path
            d="M23 45 C21 27 33 14 52 14 C68 14 79 26 77 45 C75 39 73 36 70 34 L73 47 C66 39 60 37 56 35 L58 43 C50 37 44 34 38 33 L35 39 C31 37 27 40 23 45 Z"
            fill={hair}
          />
          <Path d="M28 26 C36 9 58 3 82 13 C66 11 52 14 42 22 Z" fill={hair} />
          <Path d="M44 17 C55 5 72 5 86 20 C73 15 61 17 52 21 Z" fill={hair} />
          <Path d="M38 21 Q52 16 66 22" stroke={highlight} strokeWidth={2.2} strokeLinecap="round" fill="none" opacity={0.5} />
        </G>
      );
    default:
      return null;
  }
}

function Gear({ gear }: { gear: number }) {
  switch (gear) {
    case 1: // Glasses: round clear frames.
      return (
        <G>
          <Circle cx={40.5} cy={49.5} r={8} stroke={FRAME} strokeWidth={2.2} fill="#FFFFFF" fillOpacity={0.12} />
          <Circle cx={59.5} cy={49.5} r={8} stroke={FRAME} strokeWidth={2.2} fill="#FFFFFF" fillOpacity={0.12} />
          <Path d="M48.5 48.5 Q50 46.8 51.5 48.5" stroke={FRAME} strokeWidth={2} fill="none" />
          <Path d="M32.5 48 L25 46 M67.5 48 L75 46" stroke={FRAME} strokeWidth={2} strokeLinecap="round" />
        </G>
      );
    case 2: // Hearts: tinted heart-shaped lenses.
      return (
        <G>
          <Path d={heart(40.5, 49.5, 1.25)} fill="#FF7CC0" fillOpacity={0.55} stroke="#F24B9A" strokeWidth={2.2} strokeLinejoin="round" />
          <Path d={heart(59.5, 49.5, 1.25)} fill="#FF7CC0" fillOpacity={0.55} stroke="#F24B9A" strokeWidth={2.2} strokeLinejoin="round" />
          <Path d="M48.5 47.5 Q50 45.8 51.5 47.5" stroke="#F24B9A" strokeWidth={2} fill="none" />
          <Path d="M32 47 L25 45.5 M68 47 L75 45.5" stroke="#F24B9A" strokeWidth={2} strokeLinecap="round" />
        </G>
      );
    case 3: // Shades: round and opaque, with a glint.
      return (
        <G>
          <Circle cx={40.5} cy={49.5} r={8} fill="#15151C" />
          <Circle cx={59.5} cy={49.5} r={8} fill="#15151C" />
          <Path d="M36 46 Q38.5 43.5 41.5 43.5" stroke="#FFFFFF" strokeWidth={1.6} strokeLinecap="round" fill="none" opacity={0.35} />
          <Path d="M55 46 Q57.5 43.5 60.5 43.5" stroke="#FFFFFF" strokeWidth={1.6} strokeLinecap="round" fill="none" opacity={0.35} />
          <Path d="M48.5 48.5 Q50 46.8 51.5 48.5" stroke="#15151C" strokeWidth={2.2} fill="none" />
          <Path d="M32.5 48 L25 46 M67.5 48 L75 46" stroke="#15151C" strokeWidth={2.2} strokeLinecap="round" />
        </G>
      );
    default:
      return null;
  }
}

/** A heart centred on (cx, cy), roughly 7 units wide at scale 1. */
function heart(cx: number, cy: number, s: number): string {
  const p = (x: number, y: number) => `${(cx + x * s).toFixed(2)} ${(cy + y * s).toFixed(2)}`;
  return (
    `M${p(0, 5)} ` +
    `C${p(-8, -0.5)} ${p(-7.5, -7)} ${p(-3.5, -7)} ` +
    `C${p(-1.4, -7)} ${p(0, -5.5)} ${p(0, -4.2)} ` +
    `C${p(0, -5.5)} ${p(1.4, -7)} ${p(3.5, -7)} ` +
    `C${p(7.5, -7)} ${p(8, -0.5)} ${p(0, 5)} Z`
  );
}

/** Linear blend of two #RRGGBB colours, t = 0 is a and t = 1 is b. */
function mix(a: string, b: string, t: number): string {
  const pa = parseInt(a.slice(1), 16);
  const pb = parseInt(b.slice(1), 16);
  const ch = (shift: number) => {
    const x = (pa >> shift) & 0xff;
    const y = (pb >> shift) & 0xff;
    return Math.round(x + (y - x) * t);
  };
  return `#${((1 << 24) | (ch(16) << 16) | (ch(8) << 8) | ch(0)).toString(16).slice(1)}`;
}

const styles = StyleSheet.create({
  // The SVG clip already keeps every layer inside the circle. This is the second guard, for the
  // platforms where a transformed parent can make an SVG clip path render late.
  disc: {
    overflow: "hidden",
  },
});
