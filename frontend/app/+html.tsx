import { ScrollViewStyleReset } from 'expo-router/html';
import { palettes } from '../src/theme/colors';
import { THEME_KEY } from '../src/theme/themeStorage';

// This file is web-only and used to configure the root HTML for every
// web page during static rendering.
// The contents of this function only run in Node.js environments and
// do not have access to the DOM or browser APIs.
export default function Root({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <head>
        <meta charSet="utf-8" />
        <meta httpEquiv="X-UA-Compatible" content="IE=edge" />
        <meta name="viewport" content="width=device-width, initial-scale=1, shrink-to-fit=no" />

        {/*
          Disable body scrolling on web. This makes ScrollView components work closer to how they do on native.
          However, body scrolling is often nice to have for mobile web. If you want to enable it, remove this line.
        */}
        <ScrollViewStyleReset />

        {/* Using raw CSS styles as an escape-hatch to ensure the background color never flickers in dark-mode. */}
        <style dangerouslySetInnerHTML={{ __html: responsiveBackground }} />

        {/* Runs before first paint so a stored choice wins over the media query. */}
        <script dangerouslySetInnerHTML={{ __html: themeBootstrap }} />

        {/* Add any additional <head> elements that you want globally available on web... */}
      </head>
      <body>{children}</body>
    </html>
  );
}

// The body used to be #fff/#000 on prefers-color-scheme alone. Neither is a brand colour, and
// neither knew about an in-app override, so the page painted the wrong ground for a frame on every
// load where the student had chosen against their system setting. The media query is still the
// fallback for the first paint of a first visit; data-theme, set by the script below, overrides it.
const responsiveBackground = `
body {
  background-color: ${palettes.light.background};
}
@media (prefers-color-scheme: dark) {
  body {
    background-color: ${palettes.dark.background};
  }
}
html[data-theme="light"] body { background-color: ${palettes.light.background}; }
html[data-theme="dark"] body { background-color: ${palettes.dark.background}; }`;

// Deliberately tiny and dependency-free: it runs in <head>, before the bundle exists. Reading the
// same key ThemeContext writes is what keeps the document and the app from disagreeing.
const themeBootstrap = `
(function () {
  try {
    var v = localStorage.getItem(${JSON.stringify(THEME_KEY)});
    if (v === 'light' || v === 'dark') document.documentElement.dataset.theme = v;
  } catch (e) {}
})();`;
