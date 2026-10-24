import React, { createContext, forwardRef, useContext } from "react";
import {
  StyleSheet,
  Text as RNText,
  TextInput as RNTextInput,
  TextInputProps,
  TextProps,
  TextStyle,
} from "react-native";
import { faceFor, fonts, trackingFor } from "../theme/typography";

/**
 * Text and TextInput with the app's typefaces applied.
 *
 * React Native has no inherited root font, so a bare <Text> renders in the system face. Screens
 * import these instead of the react-native ones and get IBM Plex Sans by default, a face matched to
 * any `fontWeight` they set, and the small-text tracking from DESIGN.md, without each style having
 * to say so. An explicit fontFamily or letterSpacing always wins.
 */

// Nested Text inherits its parent's face. Without this, an inline span with no font props would be
// reset to Plex Regular inside a Space Grotesk heading.
const InsideText = createContext(false);

const RN_DEFAULT_SIZE = 14;

function resolve(style: TextProps["style"], nested: boolean): TextStyle | undefined {
  const flat: TextStyle = StyleSheet.flatten(style) ?? {};
  if (nested && flat.fontFamily == null && flat.fontWeight == null && flat.fontSize == null) {
    return flat;
  }

  const family = flat.fontFamily ?? faceFor(flat.fontWeight);
  const out: TextStyle = { ...flat, fontFamily: family };
  // A custom face with a weight on top makes iOS hunt for a bold variant that does not exist and
  // fall back to the system font. The weight is already in the family name.
  if (flat.fontFamily == null || Object.values(fonts).includes(flat.fontFamily as never)) {
    delete out.fontWeight;
  }
  if (flat.letterSpacing == null) {
    out.letterSpacing = trackingFor(flat.fontSize ?? RN_DEFAULT_SIZE, family);
  }
  return out;
}

export const Text = forwardRef<React.ComponentRef<typeof RNText>, TextProps>(function Text(
  { style, ...rest },
  ref
) {
  const nested = useContext(InsideText);
  const text = <RNText ref={ref} {...rest} style={resolve(style, nested)} />;
  return nested ? text : <InsideText.Provider value>{text}</InsideText.Provider>;
});

export const TextInput = forwardRef<React.ComponentRef<typeof RNTextInput>, TextInputProps>(
  function TextInput({ style, ...rest }, ref) {
    return <RNTextInput ref={ref} {...rest} style={resolve(style, false)} />;
  }
);

// Same-named types so `useRef<TextInput>(null)` keeps working after the import swap.
export type Text = React.ComponentRef<typeof RNText>;
export type TextInput = React.ComponentRef<typeof RNTextInput>;
