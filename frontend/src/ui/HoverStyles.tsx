import { useEffect } from "react";
import { Platform } from "react-native";
import { useThemeControl } from "../theme/ThemeContext";

/**
 * Hover and press feedback for every button on web, from one stylesheet.
 *
 * React Native has no :hover, and wiring onHoverIn/onHoverOut into each of the app's hundred-odd
 * Pressables would drift the moment someone adds a new one. Instead this injects a "state layer":
 * a translucent wash painted over any enabled touchable while the pointer is on it, a little
 * stronger while it is held down. Because it is an overlay rather than a colour swap, the same
 * rule reads on a filled maroon button, a gold one, an outlined one and a bare icon: it darkens
 * in light mode and lightens in dark mode.
 *
 * The selector leans on how react-native-web renders touchables: an enabled Pressable or
 * Touchable* is a div (or a <button>, when it has accessibilityRole="button") with tabindex="0",
 * and a disabled one drops to tabindex="-1". Every RN-web
 * View is already position: relative, so the ::after overlay has a box to fill without touching
 * layout. role="none" excludes the zero-size focus brackets RN-web puts around a Modal.
 *
 * Opt out with `{...noHover}` (see ./hover.ts) on a touchable that covers the screen (a modal
 * scrim, or a modal card that only swallows taps) or that draws its own hover, like the drawer's
 * nav items.
 *
 * Native platforms have no pointer hover, so this renders nothing there.
 */

const STYLE_ID = "ontrack-hover-states";

const TINT = {
  light: { hover: "rgba(59, 10, 29, 0.07)", press: "rgba(59, 10, 29, 0.13)" }, // boldText
  dark: { hover: "rgba(246, 234, 240, 0.08)", press: "rgba(246, 234, 240, 0.15)" }, // boldText
} as const;

// :has() keeps an outer pressable card from tinting while the pointer is on a button inside it,
// so only the thing that will actually receive the click lights up.
const TOUCHABLE = ':is(div, button)[tabindex="0"]:not([role="none"]):not([data-hover="none"])';
const TARGET = `${TOUCHABLE}:not(:has(${TOUCHABLE}:hover))`;

function css(scheme: "light" | "dark"): string {
  const t = TINT[scheme];
  return `
${TARGET}::after {
  content: "";
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background-color: ${t.hover};
  opacity: 0;
  pointer-events: none;
  transition: opacity 120ms cubic-bezier(0.25, 1, 0.5, 1);
}
@media (hover: hover) {
  ${TARGET}:hover::after { opacity: 1; }
}
${TARGET}:active::after { opacity: 1; background-color: ${t.press}; }
@media (prefers-reduced-motion: reduce) {
  ${TARGET}::after { transition: none; }
}
`;
}

export function HoverStyles() {
  const { scheme } = useThemeControl();

  useEffect(() => {
    if (Platform.OS !== "web" || typeof document === "undefined") return;
    let el = document.getElementById(STYLE_ID) as HTMLStyleElement | null;
    if (!el) {
      el = document.createElement("style");
      el.id = STYLE_ID;
      document.head.appendChild(el);
    }
    el.textContent = css(scheme);
  }, [scheme]);

  return null;
}
