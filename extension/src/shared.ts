// What the parts of the extension say to each other and to Mizune.
import { FAMILIES, ThemeMode } from './themes';

/**
 * Chrome's API, or Firefox's own where it has one (it answers with promises). The page's own
 * script has neither in Firefox, and doesn't use it.
 */
export const api: typeof chrome = (globalThis as any).browser ?? (globalThis as any).chrome;

export const isGecko = navigator.userAgent.includes('Firefox');

export interface Cue {
  start: number;
  end: number;
  text: string;
}

export interface Definition {
  dictionary: string;
  /** The Yomitan glossary as JSON, rendered by glossary.ts as the app renders it. */
  glossary: string;
  definitionTags: string;
  termTags: string;
}

export interface Entry {
  expression: string;
  reading: string;
  length: number;
  reasons: string[];
  pitch: number[];
  frequencies: { dictionary: string; display: string }[];
  definitions: Definition[];
}

export interface LookupResult {
  type: 'lookup';
  start: number;
  matchLength: number;
  entries: Entry[];
}

export interface Colors {
  primary: string;
  onPrimary: string;
  secondary: string;
  tertiary: string;
  background: string;
  surface: string;
  surfaceVariant: string;
  onSurface: string;
  onSurfaceVariant: string;
  error: string;
}

export interface Hello {
  type: 'hello';
  protocol: number;
  version: string;
  /** Answered by the extension's own engine, the app not being installed. */
  local?: boolean;
  dictionaries: number;
  anki: boolean;
  /** The app's theme; the extension's own engine has none, and the overlay keeps its colours. */
  theme?: { mode: ThemeMode; family?: string; light?: Colors; dark?: Colors };
  lineSize: number;
  /** The language peeking shows, and cards' translations are in; null while peeking's off. Older apps don't say. */
  peekLanguage?: string | null;
  pauseOnLookup: boolean;
  /** Each line goes to the clipboard as it starts, for other tools to read. */
  copyLines?: boolean;
  autoPause: boolean;
  audioPadding: number;
  /** With the extension's own engine: the height of a card's picture, which goes in as it's made here. */
  imageHeight?: number;
  /** With the extension's own engine: the picture moves, the whole line long, rather than being the one frame. */
  imageAnimated?: boolean;
  /** Key names as the app writes them ("Shift+Left", ";"), by player action. */
  keys: Record<string, string[]>;
}

export interface HostError {
  type: 'error';
  message: string;
}

/** The app's default theme, Jade (Themes.kt), for when the app isn't there to say. */
export const DEFAULT_THEME: NonNullable<Hello['theme']> = { mode: 'auto', family: FAMILIES[0].name, light: FAMILIES[0].light!, dark: FAMILIES[0].dark! };

/** A subtitle file a site's page script found, before Mizune has parsed it. */
export interface FoundTrack {
  site: 'netflix' | 'crunchyroll';
  /** Netflix's movie ID or Crunchyroll's episode ID, so a track is only used for its own video. */
  videoId: string;
  language: string;
  label: string;
  /** Closed captions, with sound descriptions; plain subtitles are preferred. */
  captions: boolean;
  /** A file name whose extension tells Mizune how to read it. */
  name: string;
  /** Empty when the page couldn't fetch the file itself; then the background fetches [urls]. */
  text: string;
  urls?: string[];
}

/**
 * The extension's own settings, in storage.local. Without the app they're all there is; with it,
 * only [engine] counts, and the app's settings do the rest.
 */
export interface Settings {
  /** "auto": the app when it's installed, otherwise the extension's own engine. */
  engine: 'auto' | 'browser';
  /**
   * With the app: the player's settings and keys are the app's, changed here or there alike.
   * Off, the extension keeps its own, while lookups and cards still come from the app.
   */
  syncWithApp: boolean;
  /** The theme family (themes.ts), and light, dark, or as the system is; with the app's settings, its theme's. */
  themeFamily: string;
  themeMode: ThemeMode;
  anki: boolean;
  ankiUrl: string;
  deck: string;
  noteType: string;
  /** What fills each field of the note types used so far, by note type: {field: CardSource name}. */
  cardFormats: Record<string, Record<string, string>>;
  tags: string;
  warnDuplicates: boolean;
  targetLanguage: string;
  lineSize: number;
  /** The language peeking shows and cards' translations come from: a language code, or empty for off. */
  peekLanguage: string;
  pauseOnLookup: boolean;
  copyLines: boolean;
  autoPause: boolean;
  audioPadding: number;
  /** The picture's height on cards, in pixels. */
  imageHeight: number;
  /** Cards' pictures move, the whole line long, rather than being the one frame. */
  imageAnimated: boolean;
  keys: Record<string, string[]>;
  /** The sites watched on (sites.ts), as shortcuts in the toolbar's popup. */
  sites: string[];
}

/** The app's own defaults (desktop Settings.kt and KeyMap.kt). */
export const DEFAULT_SETTINGS: Settings = {
  engine: 'auto',
  syncWithApp: true,
  themeFamily: 'Jade',
  themeMode: 'auto',
  anki: false,
  ankiUrl: 'http://127.0.0.1:8765',
  deck: '',
  noteType: '',
  cardFormats: {},
  tags: 'mizune',
  warnDuplicates: true,
  targetLanguage: 'ja',
  lineSize: 30,
  peekLanguage: 'en',
  pauseOnLookup: true,
  copyLines: false,
  autoPause: false,
  audioPadding: 0.3,
  imageHeight: 360,
  imageAnimated: true,
  sites: ['netflix', 'crunchyroll'],
  keys: {
    PreviousLine: ['H', 'Left'],
    NextLine: ['L', 'Right'],
    PreviousLineStop: ['J'],
    NextLineStop: ['K'],
    ReplayLine: [';', 'Down'],
    Peek: ['I'],
    SubtitlesEarlier: ['N'],
    SubtitlesLater: ['M'],
    StopEveryLine: ['U'],
    Panel: ['S'],
    AddCard: ['A'],
    FillFit: ['Z'],
    SeekBack: ['Y', 'Shift+Left'],
    SeekForward: ['O', 'Shift+Right'],
  },
};

export async function loadSettings(): Promise<Settings> {
  const stored: Partial<Settings> = (await api.storage.local.get('settings')).settings ?? {};
  return { ...DEFAULT_SETTINGS, ...stored, keys: { ...DEFAULT_SETTINGS.keys, ...(stored.keys ?? {}) } };
}

/**
 * Mizune turned off everywhere, with the switch at the top of the toolbar's popup: it stays out
 * of every site until it's turned back on. Kept apart from the settings, which the app can share.
 */
export async function isOff(): Promise<boolean> {
  return !!(await api.storage.local.get('off').catch(() => ({ off: false }))).off;
}

export async function setOff(off: boolean) {
  await api.storage.local.set({ off });
}

export async function saveSettings(changes: Partial<Settings>): Promise<Settings> {
  const settings = { ...(await loadSettings()), ...changes };
  await api.storage.local.set({ settings });
  return settings;
}

/**
 * The extension's own engine (engine.ts) listens here, in a worker: Chrome's offscreen document
 * starts it, Firefox's background page. Requests go { to: 'engine', id, kind, ... } and answers
 * come back { to: 'client', id, kind: 'reply' | 'progress', ... }.
 */
export const ENGINE_CHANNEL = 'mizune-engine';

/**
 * How a card's picture is made: a full-size PNG for the app, which makes its own AVIF from it,
 * or with the extension's engine the card's own WebP, [height] pixels high.
 */
export type StillFormat = { type: 'image/png' } | { type: 'image/webp'; height: number };

/** An animated picture's frames a second, and each frame's WebP quality: the size is mostly these. */
export const ANIMATION = { fps: 8, quality: 0.6 };

/**
 * Where the video is in the tab, for frames taken from the tab's capture while the line replays:
 * its place and the page's size in CSS pixels, and the picture's height on the card.
 */
export interface TabVideo {
  rect: { x: number; y: number; width: number; height: number };
  viewportWidth: number;
  viewportHeight: number;
  pixelRatio: number;
  height: number;
}

/** [canvas] as [format] asks, scaled down to its height; WebP where the browser can't is a JPEG. */
export async function encodeStill(source: CanvasImageSource, width: number, height: number, format: StillFormat): Promise<{ image: string; type: string }> {
  const scale = format.type === 'image/webp' ? Math.min(1, format.height / height) : 1;
  const canvas = new OffscreenCanvas(Math.round(width * scale), Math.round(height * scale));
  const context = canvas.getContext('2d')!;
  context.imageSmoothingQuality = 'high';
  context.drawImage(source, 0, 0, canvas.width, canvas.height);
  let blob = await canvas.convertToBlob(format.type === 'image/webp' ? { type: 'image/webp', quality: 0.85 } : { type: 'image/png' });
  if (format.type === 'image/webp' && blob.type !== 'image/webp') blob = await canvas.convertToBlob({ type: 'image/jpeg', quality: 0.85 });
  return { image: await blobBase64(blob), type: blob.type };
}

/**
 * A Blob's bytes as base64, read as a data: URL. In a Firefox content script a Blob can belong
 * to the page, and the script isn't allowed to wrap the page's ArrayBuffers in a typed array.
 */
export function blobBase64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const url = String(reader.result);
      resolve(url.slice(url.indexOf(',') + 1));
    };
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob);
  });
}

/** A show's seasons and episodes, as its site's page had them, for the player's list of episodes. */
export interface FoundShow {
  site: 'netflix' | 'crunchyroll';
  /** The show's ID on the site. */
  id: string;
  title: string;
  /** The show's own page on the site. */
  home?: string;
  seasons: FoundSeason[];
  /** A film's own ID: it has no episodes, but it's this show's. */
  videoIds: string[];
}

export interface FoundSeason {
  id: string;
  /** "Season 1", or what the site calls it. */
  title: string;
  /** "S1", where the site has a short name for it. */
  short?: string;
  episodes: FoundEpisode[];
}

export interface FoundEpisode {
  id: string;
  /** "3", or null where the site doesn't number them. */
  number: string | null;
  title: string;
  /** Where it plays on the site. */
  url: string;
  minutes?: number;
  /** A still from it, where the site has one. */
  still?: string;
}

/** Whether [show] is the one [videoId] is in. */
export const showHas = (show: FoundShow, videoId: string) =>
  show.videoIds.includes(videoId) || show.seasons.some((s) => s.episodes.some((e) => e.id === videoId));

/**
 * Two descriptions of the same show, [older] and [newer], as one: what either has, the newer's
 * word first. A site sometimes describes a show again with less in it (Netflix, at the credits),
 * which mustn't lose the episodes it had.
 */
export function mergeShow(older: FoundShow, newer: FoundShow): FoundShow {
  return {
    ...older, ...defined(newer),
    title: newer.title || older.title,
    videoIds: [...new Set([...older.videoIds, ...newer.videoIds])],
    seasons: mergeById(older.seasons, newer.seasons, (o, n) => ({
      ...o, ...defined(n), title: n.title || o.title, episodes: mergeById(o.episodes, n.episodes, (a, b) => ({ ...a, ...defined(b) })),
    })),
  };
}

/** [older]'s and [newer]'s items in one list, in the order they came, those in both merged by [merge]. */
function mergeById<T extends { id: string }>(older: T[], newer: T[], merge: (o: T, n: T) => T): T[] {
  const merged = older.map((o) => {
    const n = newer.find((x) => x.id === o.id);
    return n ? merge(o, n) : o;
  });
  return [...merged, ...newer.filter((n) => !older.some((o) => o.id === n.id))];
}

/** [o] without the fields it doesn't have, so they don't hide another's. */
function defined<T extends object>(o: T): Partial<T> {
  return Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined && v !== null)) as Partial<T>;
}

/** Whether [url] is a page on a site Mizune plays: the only places the player's episode list goes. */
export function isSitePage(url: string): boolean {
  try {
    const u = new URL(url);
    return u.protocol === 'https:' && /(^|\.)(netflix|crunchyroll)\.com$/.test(u.hostname);
  } catch {
    return false;
  }
}

/** Messages to the background script. */
export type ToBackground =
  | { type: 'host'; message: Record<string, unknown> }
  | { type: 'engine' }
  | { type: 'keepAlive' }
  | { type: 'openOptions' }
  | { type: 'trackFound'; track: FoundTrack }
  | { type: 'tracks' }
  | { type: 'showFound'; show: FoundShow }
  | { type: 'shows' }
  /** The tab goes to [url], one of [isSitePage]'s: an episode from the list, or the show's page. */
  | { type: 'openPage'; url: string }
  | { type: 'still'; rect: { x: number; y: number; width: number; height: number }; viewportWidth: number; format: StillFormat }
  | { type: 'recordStart'; video?: TabVideo }
  | { type: 'recordStop' }
  | { type: 'fetchText'; url: string }
  /** From the toolbar's popup: it's open on [tabId], which counts as the button's click there. */
  | { type: 'popupOpened'; tabId: number }
  | { type: 'toggleTab'; tabId: number }
  /** The player's settings changed: the tabs playing take them up. */
  | { type: 'settingsChanged' };

/** Messages from the background script to the page's frames. */
export type ToContent = { type: 'toggle' } | { type: 'tracksChanged' } | { type: 'showsChanged' } | { type: 'status' } | { type: 'settingsChanged' };

/** What Mizune's doing in a tab, for the toolbar's popup: answered by the frame with the video. */
export interface TabStatus {
  type: 'status';
  site: 'netflix' | 'crunchyroll' | 'other';
  /** On one of the site's pages for watching, rather than browsing. */
  watching: boolean;
  on: boolean;
  /** Turned off with the button or the popup, so it doesn't come up by itself. */
  stoppedByUser: boolean;
  /** The show and episode, where the site says. */
  title: string | null;
  /** How many lines the subtitles have; null while there are none yet. */
  lines: number | null;
  /** The line on screen, or the last one. */
  line: string | null;
  /** Which line that is, from 0. */
  at: number;
}

/** Between the page's own world (page.ts) and the content script, over window.postMessage. */
export const PAGE = 'mizune-page';
export const CONTENT = 'mizune-content';

/** Answers runtime messages in a way both Chrome and Firefox accept. */
export function listen<T>(handler: (message: T, sender: chrome.runtime.MessageSender) => Promise<unknown> | undefined) {
  api.runtime.onMessage.addListener((message: T, sender, sendResponse) => {
    const reply = handler(message, sender);
    if (!reply) return false;
    reply.then(sendResponse, (e) => sendResponse({ type: 'error', message: String(e?.message ?? e) }));
    return true;
  });
}

export function toBase64(bytes: Uint8Array): string {
  let binary = '';
  for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(binary);
}

/** How bright a picture is on average, 0 to 255, from a sample of its pixels; video with DRM reads as 0. */
export function meanLuma(pixels: Uint8ClampedArray): number {
  let sum = 0, count = 0;
  for (let i = 0; i < pixels.length; i += 4 * 97) {
    sum += 0.2126 * pixels[i] + 0.7152 * pixels[i + 1] + 0.0722 * pixels[i + 2];
    count++;
  }
  return count ? sum / count : 0;
}
