// What the options page and the welcome page both set up: the theme, the languages to peek in,
// and, without the app, the extension's own engine's dictionaries and its way to Anki.
import type { CardLook } from './anki-card';
import { h } from './dom';
import { askEngine } from './engine-client';
import { PlayerSettings } from './player-settings';
import { api, Settings } from './shared';
import { family, FAMILIES, supports, ThemeMode } from './themes';

/** The languages the app offers to peek in (SubtitleFiles.LANGUAGES). */
export const LANGUAGES: [string, string][] = [
  ['ar', 'Arabic'], ['zh', 'Chinese'], ['nl', 'Dutch'], ['en', 'English'], ['fr', 'French'], ['de', 'German'],
  ['hi', 'Hindi'], ['id', 'Indonesian'], ['it', 'Italian'], ['ja', 'Japanese'], ['ko', 'Korean'], ['pl', 'Polish'],
  ['pt', 'Portuguese'], ['ru', 'Russian'], ['es', 'Spanish'], ['th', 'Thai'], ['tr', 'Turkish'], ['uk', 'Ukrainian'],
  ['vi', 'Vietnamese'],
];

/** Each language's name in itself, where that's different (SubtitleFiles.LANGUAGES). */
export const NATIVE: Record<string, string> = {
  ar: 'العربية', zh: '中文', nl: 'Nederlands', fr: 'Français', de: 'Deutsch', hi: 'हिन्दी', id: 'Indonesia', it: 'Italiano',
  ja: '日本語', ko: '한국어', pl: 'Polski', pt: 'Português', ru: 'Русский', es: 'Español', th: 'ไทย', tr: 'Türkçe',
  uk: 'Українська', vi: 'Tiếng Việt',
};

// ---- the theme -----------------------------------------------------------------------------------

const MODES: [ThemeMode, string][] = [['auto', 'Auto'], ['light', 'Light'], ['dark', 'Dark']];

/** Each theme family as its light side beside its dark one, then Auto, Light or Dark. */
export function themePicker(player: PlayerSettings, change: (change: Partial<PlayerSettings>) => void): HTMLElement {
  const picker = h('div', 'theme-picker');
  const families = h('div', 'families');
  for (const f of FAMILIES) {
    const item = h('button', f.name === player.themeFamily ? 'family on' : 'family');
    // keyed, so the settings page can move the choice rather than redraw it
    item.dataset.k = `family:${f.name}`;
    const tile = h('div', 'tile');
    const sides = h('div', 'sides');
    for (const colors of [f.light, f.dark]) {
      if (!colors) continue;
      const side = h('div', 'side');
      side.style.background = colors.background;
      const accent = h('div', 'accent');
      accent.style.background = colors.primary;
      const kana = h('div', 'kana', 'あ');
      kana.style.color = colors.onSurface;
      side.append(accent, kana);
      sides.append(side);
    }
    tile.append(sides);
    item.append(tile, h('span', 'name', f.label));
    item.addEventListener('click', () => {
      // a family without the side asked for shows the one it has
      const mode = supports(f, player.themeMode) ? player.themeMode : f.light ? 'light' : 'dark';
      change({ themeFamily: f.name, themeMode: mode });
    });
    families.append(item);
  }
  const modes = h('div', 'modes');
  const current = family(player.themeFamily);
  for (const [mode, label] of MODES) {
    const button = h('button', mode === player.themeMode ? 'on' : undefined, label);
    button.dataset.k = `mode:${mode}`;
    button.disabled = !supports(current, mode);
    button.addEventListener('click', () => change({ themeMode: mode }));
    modes.append(button);
  }
  picker.append(families, modes);
  return picker;
}

// ---- the extension's own engine ------------------------------------------------------------------

/** A message to the extension's own engine, with the settings it needs. */
export async function engine(settings: Settings, message: Record<string, unknown>): Promise<any> {
  const { engine: _, ...rest } = settings;
  return (await askEngine({ kind: 'message', message: { ...message, settings: rest } })).message;
}

export interface Dictionary { id: number; title: string; terms: number; meta: number; enabled: boolean }
export interface CatalogEntry { name: string; url: string; description: string; megabytes: number; recommended?: boolean; installed?: unknown }

/** The dictionary that comes with the extension, as with the apps. */
export const BUILT_IN = { name: 'JMdict', file: 'JMdict_english.zip', description: 'The free Japanese–English dictionary everything else builds on.' };

/** Whether the built-in JMdict has gone in. Once it has, it's left alone: one removed stays removed, as in the apps. */
export async function builtInInstalled(): Promise<boolean> {
  return !!((await api.storage.local.get('builtInInstalled')) as { builtInInstalled?: boolean }).builtInInstalled;
}

/**
 * Puts the built-in JMdict in, unless it's in already, saying how many terms it's taken so far.
 * One page at a time does it: another waits, then finds it done. Answers the dictionaries after,
 * or null when there was nothing to do.
 */
export async function installBuiltIn(terms: (count: number) => void): Promise<any> {
  return navigator.locks.request('mizune-built-in', async () => {
    if (await builtInInstalled()) return null;
    const response = await fetch(api.runtime.getURL(`dictionaries/${BUILT_IN.file}`));
    const reply = await importDictionary(BUILT_IN.file, await response.arrayBuffer(), terms);
    await api.storage.local.set({ builtInInstalled: true });
    return reply;
  });
}

/** The dictionaries come from GitHub, which the extension may only reach once it's been allowed. */
export const DOWNLOAD_ORIGINS = ['https://github.com/*', 'https://*.githubusercontent.com/*'];

/**
 * [entry]'s zip, saying how far along it is. Reaching GitHub has to be allowed first, asked for
 * in the click, before anything's awaited.
 */
export async function downloadZip(entry: CatalogEntry, progress: (text: string, fraction?: number) => void): Promise<ArrayBuffer> {
  progress(`Downloading ${entry.name}…`);
  const response = await fetch(entry.url);
  if (!response.ok || !response.body) throw new Error(`GitHub answered ${response.status}`);
  const total = Number(response.headers.get('content-length')) || 0;
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let done = 0;
  for (;;) {
    const { value, done: finished } = await reader.read();
    if (finished) break;
    chunks.push(value);
    done += value.length;
    progress(`Downloading ${entry.name}… ${(done / 1e6).toFixed(1)} MB`, total ? done / total : undefined);
  }
  const data = new Uint8Array(done);
  let at = 0;
  for (const c of chunks) {
    data.set(c, at);
    at += c.length;
  }
  return data.buffer;
}

/** A Yomitan dictionary's zip into the engine, saying how many terms it's taken so far. Answers its dictionaries after. */
export async function importDictionary(name: string, data: ArrayBuffer, terms: (count: number) => void): Promise<any> {
  const answer = await askEngine({ kind: 'import', name, data }, (p) => terms(p.terms));
  const reply = answer.message;
  if (reply?.type === 'error') throw new Error(reply.message);
  return reply;
}

// ---- Anki, through AnkiConnect -----------------------------------------------------------------

export interface AnkiState {
  connected: boolean;
  /** What to say about AnkiConnect: connecting, connected, or why it isn't. */
  status: string;
  /** AnkiConnect refused this browser, which Anki can be asked to allow. */
  refused?: boolean;
  decks: string[];
  noteTypes: string[];
  /** The note type to start with, a known one, if the collection has it. */
  pick?: string | null;
  /** The chosen note type's fields, in Anki's order, and what fills each (a source's name). */
  fields?: string[] | null;
  format?: Record<string, string> | null;
  /** What the fields would be filled with, set up afresh. */
  suggested?: Record<string, string> | null;
  /** The note type's fields are a known format's. */
  preset?: boolean | null;
  /** What can fill a field: its name, and what it's called. */
  sources?: { name: string; label: string }[];
  /** How a card of the chosen note type looks, with a sample word in it. */
  look?: CardLook | null;
}

export const ANKI_CONNECTING: AnkiState = { connected: false, status: 'Connecting…', decks: [], noteTypes: [] };

/** Whether AnkiConnect answers, and if so, the collection's decks and note types. */
/** [translation] is the sample card's, in the peek language. */
export async function connectAnki(settings: Settings, translation = ''): Promise<AnkiState> {
  const reply = await engine(settings, { type: 'anki', noteType: settings.noteType, translation });
  if (reply?.type === 'anki') {
    const { decks, noteTypes, pick, fields, format, suggested, preset, sources, look } = reply;
    return { connected: true, status: 'Connected', decks, noteTypes, pick, fields, format, suggested, preset, sources, look };
  }
  const message: string = reply?.message ?? 'Couldn’t connect';
  // AnkiConnect refuses an origin it hasn't been told to trust
  const refused = /403|forbidden|origin|permission/i.test(message);
  return { connected: false, status: refused ? 'Anki hasn’t allowed this browser yet' : message, refused, decks: [], noteTypes: [] };
}

/** Asks Anki to trust this browser; it asks the person at the computer. */
export async function askAnki(settings: Settings): Promise<{ granted: boolean; message?: string }> {
  const reply = await engine(settings, { type: 'ankiPermission' });
  return { granted: !!reply?.granted, message: reply?.message };
}

export const ANKICONNECT_PAGE = 'https://ankiweb.net/shared/info/2055492159';
/** Japanese sentences, the note type cards are set up for: AnkiWeb's example deck brings it, and its source. */
export const SENTENCES_DECK_URL = 'https://ankiweb.net/shared/info/1557722832';
export const SENTENCES_SOURCE_URL = 'https://github.com/Ajatt-Tools/AnkiNoteTypes/tree/main/templates/Japanese%20sentences';
