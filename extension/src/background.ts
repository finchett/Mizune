// The extension's background: the one connection to Mizune, or to the extension's own engine
// when the app isn't installed; the subtitle files each tab's page found, the toolbar button,
// and the parts of a card only the background can make (a picture of the tab, and in Chrome
// the tab's audio).
import { askEngine, engineReady, engineRunsHere } from './engine-client';
import { family } from './themes';
import { api, encodeStill, FoundShow, FoundTrack, Hello, isGecko, isSitePage, listen, loadSettings, meanLuma, mergeShow, Settings, StillFormat, TabVideo, ToBackground } from './shared';

const HOST = 'app.mizune.host';

// ---- Mizune ---------------------------------------------------------------------------------

let port: chrome.runtime.Port | null = null;
let nextId = 1;
const pending = new Map<string, (reply: any) => void>();

function connect(): chrome.runtime.Port {
  if (port) return port;
  const opened = api.runtime.connectNative(HOST);
  opened.onMessage.addListener((message: any) => {
    const resolve = pending.get(message.id);
    pending.delete(message.id);
    resolve?.(message);
  });
  opened.onDisconnect.addListener(() => {
    // Chrome says why in lastError, Firefox on the port
    const why = api.runtime.lastError?.message ?? (opened as any).error?.message ?? '';
    const message = /not found|no such native application/i.test(why)
      ? 'notInstalled'
      : `Mizune stopped${why ? ': ' + why : ''}`;
    for (const resolve of pending.values()) resolve({ type: 'error', message });
    pending.clear();
    if (port === opened) port = null;
  });
  port = opened;
  return opened;
}

function askHost(message: Record<string, unknown>): Promise<any> {
  const id = String(nextId++);
  return new Promise((resolve) => {
    pending.set(id, resolve);
    try {
      connect().postMessage({ ...message, id });
    } catch (e) {
      pending.delete(id);
      resolve({ type: 'error', message: 'notInstalled' });
    }
  });
}

// ---- the extension's own engine -------------------------------------------------------------

/** The app wasn't there when last asked; until the background starts again, the engine answers. */
let appMissing = false;

/** Firefox's background page runs the engine's worker itself; Chrome's offscreen document does. */
let worker: Worker | null = null;
if (isGecko) engineRunsHere();

async function startEngine() {
  if (isGecko) worker ??= new Worker('engine.js', { type: 'module' });
  else await offscreenDocument();
  await engineReady();
}

/** What the engine needs of the settings: everything but which engine to use. */
function engineSettings(settings: Settings) {
  const { engine: _, ...rest } = settings;
  return rest;
}

/** The app when it's installed (unless the options say otherwise), and the extension's engine when not. */
async function ask(message: Record<string, unknown>): Promise<any> {
  const settings = await loadSettings();
  if (settings.engine !== 'browser' && !appMissing) {
    const reply = await askHost(message);
    if (!(reply?.type === 'error' && reply.message === 'notInstalled')) {
      return reply?.type === 'hello' && !settings.syncWithApp && !message.asApp ? ownSettings(reply, settings) : reply;
    }
    appMissing = true;
  }
  await startEngine();
  const answer = await askEngine({ kind: 'message', message: { ...message, settings: engineSettings(settings) } });
  const reply = answer.message;
  return reply?.type === 'hello' ? ownSettings(reply, settings) : reply;
}

/**
 * The extension's own settings over what [hello] says: always without the app, and with it while
 * they're not synced. The app's dictionaries, Anki setup and theme colours still count.
 */
function ownSettings(hello: Hello, settings: Settings): Hello {
  const own = family(settings.themeFamily);
  return {
    ...hello,
    theme: { mode: settings.themeMode, family: own.name, light: own.light ?? undefined, dark: own.dark ?? undefined },
    lineSize: settings.lineSize,
    peekLanguage: settings.peekLanguage || null,
    pauseOnLookup: settings.pauseOnLookup,
    copyLines: settings.copyLines,
    autoPause: settings.autoPause,
    keys: { ...hello.keys, ...settings.keys },
  };
}

/** Which engine answers, for the options page. */
async function whichEngine(): Promise<{ engine: 'app' | 'browser'; version?: string }> {
  const settings = await loadSettings();
  if (settings.engine !== 'browser') {
    const hello = await askHost({ type: 'hello' });
    if (hello?.type === 'hello') {
      appMissing = false;
      return { engine: 'app', version: hello.version };
    }
    appMissing = true;
  }
  await startEngine();
  return { engine: 'browser' };
}

/**
 * Chrome's offscreen document: it records the tab's audio and runs the engine's worker, and
 * there can only be one.
 */
async function offscreenDocument() {
  const contexts = await api.runtime.getContexts({ contextTypes: ['OFFSCREEN_DOCUMENT' as chrome.runtime.ContextType] });
  if (contexts.length) return;
  try {
    await api.offscreen.createDocument({
      url: 'offscreen.html',
      reasons: ['WORKERS' as chrome.offscreen.Reason, 'USER_MEDIA' as chrome.offscreen.Reason],
      justification: "Runs Mizune's dictionary engine, and records the line being spoken for an Anki card's audio",
    });
  } catch (e) {
    // another call made it meanwhile
    if (!/single offscreen/i.test(String(e))) throw e;
  }
}

// ---- subtitle files the pages found ---------------------------------------------------------

const tracksByTab = new Map<number, FoundTrack[]>();

function addTrack(tabId: number, track: FoundTrack) {
  const tracks = tracksByTab.get(tabId) ?? [];
  // the same file again (pages ask for manifests more than once) replaces the old copy
  const kept = tracks.filter((t) => !(t.videoId === track.videoId && t.language === track.language && t.captions === track.captions));
  tracksByTab.set(tabId, [...kept, track].slice(-40));
  api.tabs.sendMessage(tabId, { type: 'tracksChanged' }).catch(() => {});
}

/** The shows the pages found, for the player's list of episodes. */
const showsByTab = new Map<number, FoundShow[]>();

function addShow(tabId: number, show: FoundShow) {
  const known = showsByTab.get(tabId) ?? [];
  const same = known.find((s) => s.site === show.site && s.id === show.id);
  const kept = known.filter((s) => s !== same);
  showsByTab.set(tabId, [...kept, same ? mergeShow(same, show) : show].slice(-6));
  api.tabs.sendMessage(tabId, { type: 'showsChanged' }).catch(() => {});
}

api.tabs.onRemoved.addListener((tabId) => {
  tracksByTab.delete(tabId);
  showsByTab.delete(tabId);
  invoked.delete(tabId);
});

// ---- the toolbar button and its shortcut ---------------------------------------------------------

/** Tabs the button was clicked on (its popup opened there): Chrome lets the extension record those tabs' audio. */
const invoked = new Set<number>();

/** Mizune on or off in [tabId]; on a page that isn't Netflix or Crunchyroll, the click lets it in first. */
async function toggleTab(tabId: number): Promise<boolean> {
  invoked.add(tabId);
  const answered = await api.tabs.sendMessage(tabId, { type: 'toggle' }).catch(() => null);
  if (answered) return true;
  try {
    await api.scripting.executeScript({ target: { tabId, allFrames: true }, files: ['content.js'] });
    return !!(await api.tabs.sendMessage(tabId, { type: 'toggle' }));
  } catch (e) {
    console.warn('Mizune could not start on this page', e);
    return false;
  }
}

api.commands.onCommand.addListener(async (command, tab) => {
  if (command !== 'toggle') return;
  const id = tab?.id ?? (await api.tabs.query({ active: true, currentWindow: true }))[0]?.id;
  if (id != null) await toggleTab(id);
});

/** Every tab Mizune's playing in takes up changed settings. */
async function settingsChanged() {
  for (const tab of await api.tabs.query({})) {
    if (tab.id != null) api.tabs.sendMessage(tab.id, { type: 'settingsChanged' }).catch(() => {});
  }
}

// the welcome page, once, when the extension's first installed
api.runtime.onInstalled.addListener(({ reason }) => {
  if (reason === 'install') api.tabs.create({ url: api.runtime.getURL('welcome.html') });
});

// ---- messages from the pages ----------------------------------------------------------------

listen<ToBackground>((message, sender) => {
  const tabId = sender.tab?.id;
  switch (message.type) {
    case 'host':
      return ask(message.message);
    case 'engine':
      return whichEngine();
    case 'popupOpened':
      invoked.add(message.tabId);
      return Promise.resolve({ ok: true });
    case 'toggleTab':
      return toggleTab(message.tabId).then((ok) => ({ ok }));
    case 'settingsChanged':
      return settingsChanged().then(() => ({ ok: true }));
    case 'keepAlive':
      // a page waiting on the engine (Firefox): it wakes this page, and keeps it from stopping
      return startEngine().then(() => ({ ok: true }));
    case 'openOptions':
      return api.runtime.openOptionsPage().then(() => ({ ok: true }));
    case 'trackFound':
      if (tabId != null) addTrack(tabId, message.track);
      return Promise.resolve({ ok: true });
    case 'tracks':
      return Promise.resolve(tabId != null ? tracksByTab.get(tabId) ?? [] : []);
    case 'showFound':
      if (tabId != null) addShow(tabId, message.show);
      return Promise.resolve({ ok: true });
    case 'shows':
      return Promise.resolve(tabId != null ? showsByTab.get(tabId) ?? [] : []);
    case 'openPage':
      if (tabId == null || !isSitePage(message.url)) return Promise.resolve({ ok: false });
      return api.tabs.update(tabId, { url: message.url }).then(() => ({ ok: true }));
    case 'still':
      return sender.tab ? still(sender.tab, message.rect, message.viewportWidth, message.format) : undefined;
    case 'recordStart':
      return tabId != null ? recordStart(tabId, message.video) : undefined;
    case 'recordStop':
      return recordStop();
    case 'fetchText':
      return fetch(message.url).then((r) => r.text());
  }
  return undefined;
});

// ---- a picture of the video -----------------------------------------------------------------

/**
 * The visible tab cropped to the video, as a PNG. Video with DRM can come out black, depending
 * on the machine: then there's no picture rather than a black one.
 */
async function still(tab: chrome.tabs.Tab, rect: { x: number; y: number; width: number; height: number }, viewportWidth: number, format: StillFormat) {
  const dataUrl = await api.tabs.captureVisibleTab(tab.windowId, { format: 'png' });
  const bitmap = await createImageBitmap(await (await fetch(dataUrl)).blob());
  const scale = bitmap.width / viewportWidth;
  const x = Math.max(0, rect.x * scale), y = Math.max(0, rect.y * scale);
  const width = Math.min(bitmap.width - x, rect.width * scale), height = Math.min(bitmap.height - y, rect.height * scale);
  if (width < 16 || height < 16) return { type: 'still', image: null, reason: 'The video is too small for a picture' };
  const canvas = new OffscreenCanvas(Math.round(width), Math.round(height));
  const context = canvas.getContext('2d')!;
  context.drawImage(bitmap, x, y, width, height, 0, 0, canvas.width, canvas.height);

  if (meanLuma(context.getImageData(0, 0, canvas.width, canvas.height).data) < 3) return { type: 'still', image: null, reason: 'The browser shows this video to extensions as black' };

  const picture = await encodeStill(canvas, canvas.width, canvas.height, format);
  return { type: 'still', image: picture.image, imageType: picture.type };
}

// ---- the tab's audio (Chrome) ---------------------------------------------------------------
// Firefox has no tabCapture: there the content script records the video element itself.

async function recordStart(tabId: number, video?: TabVideo) {
  if (isGecko || !api.tabCapture?.getMediaStreamId) return { type: 'error', message: 'unsupported' };
  let streamId: string;
  try {
    streamId = await api.tabCapture.getMediaStreamId({ targetTabId: tabId });
  } catch (e) {
    // only after the toolbar button (or its shortcut) on this tab
    return { type: 'error', message: invoked.has(tabId) ? String(e) : 'needsClick' };
  }
  await offscreenDocument();
  return api.runtime.sendMessage({ target: 'offscreen', type: 'start', streamId, video });
}

async function recordStop() {
  if (isGecko) return { type: 'error', message: 'unsupported' };
  return api.runtime.sendMessage({ target: 'offscreen', type: 'stop' });
}
