// The app's theme families (Themes.kt), from themes.json, which ExtensionThemesTest writes from
// the app's own and keeps up to date. Each has a light side and a dark one, or only one.
import type { Colors } from './shared';
import families from './themes.json';

export interface Family {
  name: string;
  label: string;
  light: Colors | null;
  dark: Colors | null;
}

export type ThemeMode = 'auto' | 'light' | 'dark';

export const FAMILIES = families as Family[];

export function family(name: string | undefined): Family {
  return FAMILIES.find((f) => f.name === name) ?? FAMILIES[0];
}

/** Whether [f] has the side [mode] asks for (Auto needs both). */
export function supports(f: Family, mode: ThemeMode): boolean {
  return mode === 'auto' ? !!f.light && !!f.dark : !!f[mode];
}

/** The side to show for [mode], [systemDark] deciding Auto; a family with one side shows that one. */
export function resolve(f: Family, mode: ThemeMode, systemDark: boolean): Colors {
  const dark = mode === 'auto' ? systemDark : mode === 'dark';
  return (dark ? f.dark ?? f.light : f.light ?? f.dark)!;
}

/** The relative luminance of a #rrggbb colour, as Compose's Color.luminance(). */
export function luminance(hex: string): number {
  const m = /^#?([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})/i.exec(hex);
  if (!m) return 0;
  const [r, g, b] = m.slice(1).map((x) => {
    const c = parseInt(x, 16) / 255;
    return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}
