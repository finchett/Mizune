// The player's settings as the options page and the toolbar's popup show and change them: the
// app's while they're shared with it, else the extension's own; and the theme those pages are in.
import { api, Colors, DEFAULT_SETTINGS, Hello, saveSettings, Settings } from './shared';
import { family, FAMILIES, luminance, resolve, ThemeMode } from './themes';

export interface PlayerSettings {
  themeFamily: string;
  themeMode: ThemeMode;
  lineSize: number;
  peekLanguage: string;
  pauseOnLookup: boolean;
  autoPause: boolean;
  copyLines: boolean;
  keys: Record<string, string[]>;
}

/** The app's colours for its theme, as it tunes them; none for the extension's own. */
export interface AppColors {
  light?: Colors;
  dark?: Colors;
}

export interface Read {
  player: PlayerSettings;
  colors: AppColors;
  /** Why the app's couldn't be read or changed, while they're shared. */
  error: string | null;
}

/** The app's version while it answers lookups; undefined while the extension's own engine does. */
export async function appVersion(): Promise<string | undefined> {
  const which = await api.runtime.sendMessage({ type: 'engine' });
  return which?.engine === 'browser' ? undefined : which?.version ?? '';
}

export const isShared = (settings: Settings, app: string | undefined) => app !== undefined && settings.syncWithApp;

export function fromHello(hello: Hello): Read {
  return {
    player: {
      themeFamily: hello.theme?.family ?? FAMILIES[0].name,
      themeMode: hello.theme?.mode ?? 'auto',
      lineSize: hello.lineSize,
      peekLanguage: hello.peekLanguage === undefined ? 'en' : hello.peekLanguage ?? '',
      pauseOnLookup: hello.pauseOnLookup,
      autoPause: hello.autoPause,
      copyLines: !!hello.copyLines,
      keys: { ...DEFAULT_SETTINGS.keys, ...hello.keys },
    },
    colors: { light: hello.theme?.light, dark: hello.theme?.dark },
    error: null,
  };
}

export function ownPlayer(settings: Settings): Read {
  const { themeFamily, themeMode, lineSize, peekLanguage, pauseOnLookup, autoPause, copyLines, keys } = settings;
  return { player: { themeFamily, themeMode, lineSize, peekLanguage, pauseOnLookup, autoPause, copyLines, keys }, colors: {}, error: null };
}

/** The settings as the player has them. */
export async function readPlayer(settings: Settings, app: string | undefined): Promise<Read> {
  if (!isShared(settings, app)) return ownPlayer(settings);
  const hello = await api.runtime.sendMessage({ type: 'host', message: { type: 'hello', asApp: true } });
  if (hello?.type === 'hello') return fromHello(hello);
  return { ...ownPlayer(settings), error: 'Couldn’t read the app’s settings, so these are the extension’s own.' };
}

/**
 * [change] into the app's settings while they're shared, else the extension's own, and to the
 * tabs playing. Answers what they are now, and the extension's own settings after it.
 */
export async function changePlayer(settings: Settings, app: string | undefined, current: Read, change: Partial<PlayerSettings>): Promise<{ read: Read; settings: Settings }> {
  let read: Read;
  if (isShared(settings, app)) {
    const hello = await api.runtime.sendMessage({ type: 'host', message: { type: 'updateSettings', settings: change } });
    if (hello?.type === 'hello') {
      read = fromHello(hello);
    } else {
      read = {
        ...current,
        player: { ...current.player, ...change },
        error: /unknown|updateSettings/i.test(hello?.message ?? '')
          ? 'This Mizune is too old to take settings from here. Update it, or stop sharing.'
          : `Couldn’t change the app’s settings: ${hello?.message ?? 'no answer'}`,
      };
    }
  } else {
    settings = await saveSettings(change);
    read = ownPlayer(settings);
  }
  api.runtime.sendMessage({ type: 'settingsChanged' }).catch(() => {});
  return { read, settings };
}

export const systemDark = matchMedia('(prefers-color-scheme: dark)');

/** Draws the page in the player's theme: its colours as CSS variables, and whether it's light. */
export function drawTheme({ player, colors }: Read) {
  const f = family(player.themeFamily);
  const tuned = colors.light && colors.dark ? { ...f, light: colors.light, dark: colors.dark } : f;
  const scheme = resolve(tuned, player.themeMode, systemDark.matches);
  const root = document.documentElement;
  for (const [name, value] of Object.entries(scheme)) root.style.setProperty(`--${name}`, value);
  root.classList.toggle('light', luminance(scheme.background) > 0.5);
  root.classList.toggle('light-primary', luminance(scheme.primary) > 0.5);
}
