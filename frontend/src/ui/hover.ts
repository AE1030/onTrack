/**
 * Spread onto a Pressable or Touchable to exclude it from the shared web hover tint in
 * HoverStyles.tsx. Renders as data-hover="none" on web and is ignored on native.
 *
 * Use it for touchables that cover the screen (modal scrims, modal cards that only swallow taps)
 * and for ones that already draw their own hover state.
 *
 * Typed loosely because React Native's prop types do not declare react-native-web's dataSet.
 */
export const noHover = { dataSet: { hover: "none" } } as {};
