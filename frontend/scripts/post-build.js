const fs = require('fs');
const path = require('path');

const dist = path.join(__dirname, '..', 'dist');

// Overwrite Expo SPA index.html with the landing page so / serves the landing page
fs.copyFileSync(path.join(dist, 'landing-page.html'), path.join(dist, 'index.html'));

// Remove leftover Expo template pages
const stale = ['modal.html', '_sitemap.html', 'two.html'];
for (const file of stale) {
  const p = path.join(dist, file);
  if (fs.existsSync(p)) fs.unlinkSync(p);
}
const tabsDir = path.join(dist, '(tabs)');
if (fs.existsSync(tabsDir)) fs.rmSync(tabsDir, { recursive: true });

console.log('Post-build: landing page → index.html, cleaned stale files');
