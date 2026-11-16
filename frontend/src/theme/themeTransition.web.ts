import { ThemeName } from "./colors";

/**
 * The theme switch as a crossfade rather than a cut (web).
 *
 * The whole page is snapshotted and the new theme dissolves over the old one through the View
 * Transitions API. Colour lives in hundreds of StyleSheets here, so animating each one is not an
 * option; a document-level crossfade is the only way every surface changes together, and it costs
 * one composited layer for under half a second.
 *
 * The timing is a routine state change, not a moment: long enough that the room dims rather than
 * flickers, short enough that nobody waits on it. Under reduced motion it stays a fade (there is no
 * movement in it) but runs quicker. Browsers without the API switch instantly, as before.
 */
const TRANSITION_CSS = `
::view-transition-old(root),
::view-transition-new(root) {
  animation-duration: 380ms;
  animation-timing-function: cubic-bezier(0.16, 1, 0.3, 1);
}
@media (prefers-reduced-motion: reduce) {
  ::view-transition-old(root),
  ::view-transition-new(root) { animation-duration: 140ms; }
}`;

// react-dom ships without types in this project; flushSync is the only thing used from it.
// eslint-disable-next-line @typescript-eslint/no-var-requires
const { flushSync } = require("react-dom") as { flushSync: (fn: () => void) => void };

export function installTransitionStyles() {
  if (typeof document === "undefined") return;
  if (document.getElementById("ontrack-theme-transition")) return;
  const style = document.createElement("style");
  style.id = "ontrack-theme-transition";
  style.textContent = TRANSITION_CSS;
  document.head.appendChild(style);
}

type ViewTransitionDocument = Document & { startViewTransition?: (update: () => void) => unknown };

/** Runs a theme change inside a view transition where the browser has one. */
export function withThemeTransition(update: () => void) {
  const doc = typeof document === "undefined" ? undefined : (document as ViewTransitionDocument);
  if (!doc || typeof doc.startViewTransition !== "function" || doc.visibilityState !== "visible") {
    update();
    return;
  }
  // flushSync so the new theme is in the DOM before the browser takes its "after" snapshot.
  doc.startViewTransition(() => flushSync(update));
}

/** Web needs no veil: the view transition already crossfades the page. */
export function ThemeVeil(_: { scheme: ThemeName }) {
  return null;
}
