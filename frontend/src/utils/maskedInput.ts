/**
 * Live formatting for digit fields with fixed sections, like a YYYY-MM-DD date or an HH:mm time.
 *
 * Separators are inserted for the student: finishing a section adds the separator after it, so
 * typing 20261015 reads 2026-10-15 as it goes. A separator the student types themselves closes a
 * short section and pads it ("2026-3-" becomes "2026-03-"), and a first digit that cannot begin a
 * two-digit value is padded at once ("4" in the day becomes "04"). While deleting, no separator
 * is added back, or backspace could never get past one.
 */

export type MaskSection = {
  /** How many digits the section holds. */
  size: number;
  /** A first digit above this cannot start a two-digit value, so it is padded with a zero. */
  padAbove?: number;
};

export const DATE_MASK: MaskSection[] = [{ size: 4 }, { size: 2, padAbove: 1 }, { size: 2, padAbove: 3 }];
export const TIME_MASK: MaskSection[] = [{ size: 2, padAbove: 2 }, { size: 2, padAbove: 5 }];

export function formatMasked(
  raw: string,
  previous: string,
  sections: MaskSection[],
  separator: string
): string {
  const deleting = raw.length < previous.length;
  const filled: string[] = [""];

  for (const ch of raw) {
    let i = filled.length - 1;
    if (/\d/.test(ch)) {
      if (filled[i].length >= sections[i].size) {
        if (i + 1 >= sections.length) continue;
        filled.push("");
        i++;
      }
      const { padAbove } = sections[i];
      filled[i] =
        filled[i] === "" && padAbove !== undefined && Number(ch) > padAbove ? `0${ch}` : filled[i] + ch;
    } else if (filled[i] !== "" && i + 1 < sections.length) {
      // Only a two-digit section can be closed early; a short year stays open.
      if (filled[i].length < sections[i].size) {
        if (sections[i].size !== 2) continue;
        filled[i] = filled[i].padStart(2, "0");
      }
      filled.push("");
    }
  }

  const last = filled.length - 1;
  let out = filled.join(separator);
  if (!deleting && last + 1 < sections.length && filled[last].length === sections[last].size) {
    out += separator;
  }
  return out;
}
