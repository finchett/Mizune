// The toolbar's popup: what Mizune's doing in this tab (the video it's on, or what to do here
// instead), and what's still to set up while something is. Everything else is in the settings,
// behind the cog, or in the player's own ⚙ menu.
import { ICONS } from './content/controls';
import { h } from './dom';
import { appVersion, drawTheme, fromHello, ownPlayer, Read, systemDark } from './player-settings';
import { api, Hello, isOff, loadSettings, setOff, Settings, TabStatus } from './shared';
import { chosenSites, siteRow } from './sites';

const $ = (id: string) => document.getElementById(id)!;

// The browser opens the popup at the size it's first drawn at, and grows or shrinks it, where it
// can be seen, as what's in it changes. So nothing in it shows till all of it's drawn (it starts
// out waiting, in popup.html), and meanwhile it keeps the height it had last time, which it
// mostly has again.
const HEIGHT = 'popupHeight';
const remembered = (() => {
  try {
    return Number(localStorage.getItem(HEIGHT)) || 0;
  } catch {
    return 0;
  }
})();
if (remembered) document.documentElement.style.height = `${remembered}px`;
/** Where what's in it ends (not the window's height, which is what it was a moment ago). */
const drawnHeight = () => Math.ceil(document.body.getBoundingClientRect().bottom + parseFloat(getComputedStyle(document.body).marginBottom));
/** Shows what's drawn, at its own height; once, after everything, or after a while at most. */
function reveal() {
  if (!document.body.classList.contains('waiting')) return;
  document.documentElement.style.height = '';
  document.body.classList.remove('waiting');
  try {
    localStorage.setItem(HEIGHT, String(drawnHeight()));
  } catch {
    // (the next popup just opens at its own height)
  }
}
setTimeout(reveal, 600);

let settings: Settings;
let app: string | undefined;
let read: Read;
let hello: Hello | null = null;
let tab: chrome.tabs.Tab | undefined;
/** Turned off everywhere, with the switch at the top. */
let off = false;

// ---- this tab ------------------------------------------------------------------------------------

/** What the tab card shows. */
type View =
  | { kind: 'loading' }
  | { kind: 'playing' | 'looking' | 'off'; status: TabStatus }
  /** On Netflix or Crunchyroll, but not watching anything. */
  | { kind: 'browse'; site: string }
  /** Some other page, with a video Mizune could go over. */
  | { kind: 'page' }
  /** Mizune's turned off everywhere. */
  | { kind: 'disabled' }
  | { kind: 'none' };

let view: View = { kind: 'loading' };
let shownKey = '';
let belowKey = '';

const SITES: Record<string, string> = { netflix: 'Netflix', crunchyroll: 'Crunchyroll' };

function siteOf(url: string | undefined): string | null {
  const host = url ? new URL(url).hostname : '';
  if (host.endsWith('netflix.com')) return 'netflix';
  if (host.endsWith('crunchyroll.com')) return 'crunchyroll';
  return null;
}

async function look(): Promise<View> {
  // no tab to speak of (a window of the browser's own, say)
  if (off) return { kind: 'disabled' };
  if (tab?.id == null || tab.id < 0) return { kind: 'none' };
  const status: TabStatus | null = await api.tabs.sendMessage(tab.id, { type: 'status' }).catch(() => null);
  if (status?.type === 'status') {
    if (!status.watching && status.site !== 'other') return { kind: 'browse', site: SITES[status.site] };
    if (!status.on) return { kind: 'off', status };
    return { kind: status.lines ? 'playing' : 'looking', status };
  }
  const site = siteOf(tab.url);
  if (site) return { kind: 'browse', site: SITES[site] };
  // a page Mizune isn't on yet: the popup's opening lets it look for a video there
  const found = await api.scripting
    .executeScript({ target: { tabId: tab.id, allFrames: true }, func: () => !!document.querySelector('video') })
    .catch(() => []);
  return found.some((r) => r.result) ? { kind: 'page' } : { kind: 'none' };
}

/** Draws the tab card; the screen's message only changes, fading, when what it says does. */
function drawTab() {
  const eyebrow = $('eyebrow');
  const below = $('below');
  const switchBox = $('tab-switch');
  const screen = $('screen');
  const on = view.kind === 'playing' || view.kind === 'looking';
  const site = view.kind === 'browse' ? view.site : 'status' in view ? SITES[view.status.site] : undefined;
  // with nothing being watched in the tab, or Mizune off, the card's only the sites to watch on, if any
  const bare = view.kind === 'none' || view.kind === 'browse' || view.kind === 'disabled';
  const sites = chosenSites(settings.sites);
  $('tab').hidden = bare && !sites.length;
  // while it plays, the video has the line already: the card's only what's on and how far in
  screen.hidden = bare || view.kind === 'playing';
  eyebrow.replaceChildren(...(on ? [h('i', 'live')] : []), bare ? 'Watch on' : site ?? 'This tab');
  switchBox.replaceChildren();
  if (view.kind === 'playing' || view.kind === 'looking' || view.kind === 'off' || view.kind === 'page') {
    const button = toggle(on, toggleTab);
    button.setAttribute('aria-label', 'Mizune on this tab');
    switchBox.append(button);
  }

  let key: string;
  let content: () => HTMLElement;
  switch (view.kind) {
    case 'loading':
      key = 'loading';
      content = () => message('', '');
      break;
    case 'looking':
      key = 'looking';
      content = () => message('Looking for Japanese subtitles', 'They come up as the site sends them', true);
      break;
    case 'off':
      key = `off:${view.status.stoppedByUser}`;
      content = () => view.kind === 'off' && view.status.stoppedByUser
        ? message('Mizune’s off on this video', 'Turn it on to have the lines and lookups back')
        : message('No Japanese subtitles yet', 'Mizune comes up by itself once the site has them');
      break;
    case 'page':
      key = 'page';
      content = () => message('There’s a video here', 'Turn Mizune on, then drop a subtitle file on the video');
      break;
    default:
      // not shown (playing, or bare)
      key = 'bare';
      content = () => message('', '');
  }
  if (key !== shownKey) {
    shownKey = key;
    swap(screen, content());
  }

  if (view.kind === 'playing' || view.kind === 'looking' || view.kind === 'off') {
    belowKey = '';
    below.replaceChildren();
    const about = h('div', 'about');
    about.append(h('span', 'show', view.status.title ?? ''));
    if (view.status.lines) about.append(h('span', 'meta', `Line ${(view.status.at + 1).toLocaleString()} of ${view.status.lines.toLocaleString()}`));
    if (view.status.title || view.status.lines) below.append(about);
  } else if (bare) {
    // the sites watched on, as chosen in the welcome or the settings; drawn again only when they
    // change, so a tile under the pointer stays the one it's over
    const key = `sites:${sites.map((s) => s.id).join()}`;
    if (key !== belowKey) {
      belowKey = key;
      below.replaceChildren(siteRow(sites, (site) => {
        api.tabs.create({ url: site.url });
        close();
      }));
    }
  } else {
    belowKey = '';
    below.replaceChildren();
  }
}

function message(title: string, detail: string, waiting = false): HTMLElement {
  const box = h('div', 'message');
  const b = h('b', waiting ? 'waiting' : undefined, title);
  box.append(b, h('span', undefined, detail));
  return box;
}

/** [next] on the screen in place of what's there: the old fading up and out, the new rising in. */
function swap(screen: HTMLElement, next: HTMLElement) {
  for (const old of screen.querySelectorAll<HTMLElement>(':scope > *')) {
    if (old.classList.contains('gone')) continue;
    old.classList.add('gone');
    setTimeout(() => old.remove(), 280);
  }
  next.classList.add('coming');
  screen.prepend(next);
  requestAnimationFrame(() => requestAnimationFrame(() => next.classList.remove('coming')));
}

async function toggleTab() {
  if (tab?.id == null) return;
  // the switch moves at once; what the tab does catches up
  const knob = $('tab-switch').querySelector('.toggle');
  knob?.classList.toggle('on');
  await api.runtime.sendMessage({ type: 'toggleTab', tabId: tab.id });
  await refresh();
}

let timer = 0;

async function refresh() {
  view = await look();
  drawTab();
  clearTimeout(timer);
  // the line count as it changes, while there's one to follow
  timer = window.setTimeout(refresh, view.kind === 'playing' || view.kind === 'looking' ? 400 : 1500);
}

// ---- the way to the rest -------------------------------------------------------------------------

function toggle(on: boolean, change: (on: boolean) => void): HTMLButtonElement {
  const button = h('button', on ? 'toggle on' : 'toggle');
  button.setAttribute('role', 'switch');
  button.setAttribute('aria-checked', String(on));
  button.addEventListener('click', () => change(!button.classList.contains('on')));
  return button;
}

/** The options, opened at [pane]: the tab they're in already, if they are. */
async function openOptions(pane: string) {
  const url = api.runtime.getURL(`options.html#${pane}`);
  const [open] = await api.tabs.query({ url: api.runtime.getURL('options.html*') }).catch(() => []);
  if (open?.id != null) {
    await api.tabs.update(open.id, { url, active: true });
    if (open.windowId != null) await api.windows.update(open.windowId, { focused: true });
  } else {
    await api.tabs.create({ url });
  }
  close();
}

/** The dictionary the extension's own engine is still missing, while it is. */
function drawSetup() {
  $('setup').hidden = !(hello?.local && !hello.dictionaries);
}

/**
 * A shortcut as a Mac writes it (⌥⇧M), where the browser gives it in words, as Firefox does
 * (Alt+Shift+M; its Ctrl is the Mac's ⌘, and MacCtrl its ⌃).
 */
function keysFor(shortcut: string): string {
  if (!/Mac/.test(navigator.platform) || !shortcut.includes('+')) return shortcut;
  const MAC: Record<string, string> = { Alt: '⌥', Shift: '⇧', Ctrl: '⌘', Command: '⌘', MacCtrl: '⌃' };
  return shortcut.split('+').map((key) => MAC[key] ?? key).join('');
}

async function drawFoot() {
  const foot = $('foot');
  const shortcut = (await api.commands.getAll().catch(() => [])).find((c) => c.name === 'toggle')?.shortcut;
  if (shortcut) foot.append(h('span', 'keycap', keysFor(shortcut)), h('span', 'hint', 'turns it on or off here'));
  foot.append(h('span', 'grow'));
  // without the app, where to get it
  const site = h('a', undefined, hello?.local && settings.engine === 'auto' ? 'Get the Mizune app' : 'mizune.app');
  site.href = 'https://mizune.app';
  site.target = '_blank';
  foot.append(site);
}

/** Under the name: on or off, or what's wrong while something is. */
function drawState() {
  const state = $('state');
  let text: string;
  let warn = false;
  if (!off && !hello) {
    text = app !== undefined ? 'Mizune didn’t answer' : 'Couldn’t start the extension’s dictionaries';
    warn = true;
  } else {
    text = off ? 'Off' : 'On';
  }
  state.classList.toggle('warn', warn);
  state.classList.toggle('off', off);
  state.replaceChildren(h('i', 'dot'), h('span', undefined, text));
  const power = toggle(!off, async (on) => {
    off = !on;
    drawState();
    await setOff(off);
    await refresh();
  });
  power.setAttribute('aria-label', 'Mizune on every site');
  $('power').replaceChildren(power);
}

// ---- starting ------------------------------------------------------------------------------------

async function start() {
  $('settings').innerHTML = ICONS.cog();
  $('settings').addEventListener('click', () => openOptions('General'));
  $('add-dictionary').addEventListener('click', () => openOptions('Dictionaries'));
  [settings, off] = await Promise.all([loadSettings(), isOff()]);
  drawState();
  read = ownPlayer(settings);
  drawTheme(read);
  [tab] = await api.tabs.query({ active: true, currentWindow: true });
  // the popup opening counts as the button's click there, which lets Mizune record its audio
  if (tab?.id != null) api.runtime.sendMessage({ type: 'popupOpened', tabId: tab.id }).catch(() => {});
  // what's in the tab, while the app's asked
  const looked = refresh();
  const [version, answer] = await Promise.all([
    appVersion(),
    api.runtime.sendMessage({ type: 'host', message: { type: 'hello' } }).catch(() => null),
  ]);
  app = version;
  hello = answer?.type === 'hello' ? answer : null;
  // the hello's the player's own: the app's settings while they're shared, else the extension's
  if (hello) read = fromHello(hello);
  drawTheme(read);
  drawState();
  drawSetup();
  await Promise.all([drawFoot(), looked]);
  reveal();
}

systemDark.addEventListener('change', () => read && drawTheme(read));

start();
