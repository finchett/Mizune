// The page that opens when the extension's installed: the app's onboarding (Onboarding.kt), cut
// to what the browser needs. Whether the app's there to look words up; a look; a language to
// peek in; without the app, the extension's own dictionaries and its way to Anki; then pinning
// the button and where to watch.
import { capLabel } from './content/keys';
import { h } from './dom';
import { appVersion, changePlayer, drawTheme, ownPlayer, PlayerSettings, Read, readPlayer, systemDark } from './player-settings';
import {
  ANKI_CONNECTING, AnkiState, ANKICONNECT_PAGE, askAnki, BUILT_IN, builtInInstalled, CatalogEntry, connectAnki, DOWNLOAD_ORIGINS, downloadZip,
  engine, importDictionary, installBuiltIn, LANGUAGES, NATIVE, themePicker,
} from './setup';
import { api, loadSettings, saveSettings, Settings } from './shared';
import { chosenSites, pickTile, siteRow, WATCH_SITES } from './sites';

const $ = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;

type Step = 'welcome' | 'app' | 'sites' | 'look' | 'peek' | 'dictionaries' | 'anki' | 'done';

let settings: Settings;
let read: Read;
let player: PlayerSettings;
/** The app's version while it answers lookups, undefined while the extension's own engine does; null until asked. */
let app: string | undefined | null = null;
let step: Step = 'welcome';

/** The steps there are: without the app, the extension's own dictionaries and Anki too. */
function steps(): Step[] {
  return app === undefined
    ? ['welcome', 'app', 'sites', 'look', 'peek', 'dictionaries', 'anki', 'done']
    : ['welcome', 'app', 'sites', 'look', 'peek', 'done'];
}

async function change(next: Partial<PlayerSettings>) {
  read = { ...read, player: { ...read.player, ...next } };
  player = read.player;
  draw();
  const done = await changePlayer(settings, app ?? undefined, read, next);
  settings = done.settings;
  read = done.read;
  player = read.player;
  draw();
}

// ---- the parts ---------------------------------------------------------------------------------

type Child = Node | string | null | undefined | false;

function append<T extends HTMLElement>(parent: T, ...children: Child[]): T {
  for (const child of children) if (child) parent.append(child);
  return parent;
}

const eyebrow = (text: string) => h('div', 'eyebrow', text);
const headline = (text: string) => h('h1', undefined, text);
const body = (text: string) => h('p', 'body', text);
const gap = () => h('div', 'gap');

function mascot(smaller = false) {
  const img = h('img', smaller ? 'mascot smaller' : 'mascot');
  img.src = 'icons/512.png';
  img.alt = '';
  return img;
}

/** Rows on a card, a line between each. */
function box(...rows: Child[]): HTMLElement {
  const card = h('div', 'box');
  rows.filter(Boolean).forEach((row, i) => {
    if (i > 0) card.append(h('div', 'line'));
    card.append(row as Node);
  });
  return card;
}

function row(title: Child | Child[], detail: Child, ...trailing: Child[]): HTMLElement {
  const words = h('div', 'words');
  words.append(append(h('div', 'title'), ...(Array.isArray(title) ? title : [title])));
  if (detail) words.append(append(h('div', 'detail'), detail));
  return append(h('div', 'row'), words, ...trailing);
}

function toggle(on: boolean, flip: (on: boolean) => void): HTMLButtonElement {
  const button = h('button', on ? 'toggle on' : 'toggle');
  button.setAttribute('role', 'switch');
  button.setAttribute('aria-checked', String(on));
  button.addEventListener('click', () => flip(!on));
  return button;
}

function action(text: string, run: () => void): HTMLButtonElement {
  const button = h('button', 'text-action primary', text);
  button.addEventListener('click', run);
  return button;
}

function link(text: string, url: string): HTMLAnchorElement {
  const a = h('a', 'link', text);
  a.href = url;
  a.target = '_blank';
  a.rel = 'noopener';
  return a;
}

function stepOf(): HTMLElement {
  const list: Step[] = steps().filter((s) => s !== 'welcome' && s !== 'done');
  const at = list.indexOf(step) + 1;
  return eyebrow(`Step ${at} of ${list.length}${step === 'anki' ? ' · Optional' : ''}`);
}

const keycap = (text: string) => h('span', 'keycap', text);
const keyOf = (action: string, fallback: string) => capLabel(player.keys[action]?.[0] ?? fallback);

// ---- the steps ---------------------------------------------------------------------------------

function welcome(): Child[] {
  const start = h('button', 'big', 'Let’s get started');
  start.addEventListener('click', () => go(1));
  return [
    mascot(),
    eyebrow('ようこそ'),
    headline('Welcome to Mizune'),
    body('Watch Netflix and Crunchyroll in Japanese, look up any word with a click, and keep the ones you want as flashcards. A minute of setting up and you’re watching.'),
    start,
  ];
}

function appStep(): Child[] {
  const looking = app === null;
  const status = box(row(
    looking ? 'Looking for the Mizune app…' : app !== undefined ? `Mizune ${app} is on this computer` : 'The Mizune app isn’t on this computer',
    looking ? null
      : app !== undefined ? 'It looks words up and makes the cards, with its own dictionaries and Anki settings.'
      : 'The extension looks words up itself and adds cards to Anki directly. You’ll set that up next.',
    !looking && app === undefined && action('Check again', findApp),
  ));
  status.querySelector('.row')!.prepend(h('i', looking ? 'dot looking' : app !== undefined ? 'dot on' : 'dot off'));
  return [
    stepOf(),
    headline('With the app, or on its own'),
    body('Mizune works in the browser by itself. With the Mizune app installed, the extension uses the app’s dictionaries and Anki settings, so both look words up the same way.'),
    gap(),
    status,
    app !== undefined && app !== null && h('div', 'gap'),
    app !== undefined && app !== null && box(append(
      row('Share settings with the app', 'The player’s look, keys and peek language stay the same in both.'),
      toggle(settings.syncWithApp, async (on) => {
        settings = await saveSettings({ syncWithApp: on });
        api.runtime.sendMessage({ type: 'settingsChanged' }).catch(() => {});
        read = await readPlayer(settings, app ?? undefined);
        player = read.player;
        draw();
      }),
    )),
    app === undefined && h('div', 'small-note', 'Got the app since? Open it, then check again.'),
    app === undefined && link('Get the Mizune app ↗', 'https://mizune.app'),
  ];
}

function sitesStep(): Child[] {
  const tiles = h('div', 'site-tiles');
  tiles.style.setProperty('--count', String(WATCH_SITES.length));
  tiles.append(...WATCH_SITES.map((site) => pickTile(site, settings.sites.includes(site.id), async (on) => {
    const sites = WATCH_SITES.map((s) => s.id).filter((id) => (id === site.id ? on : settings.sites.includes(id)));
    settings = await saveSettings({ sites });
    draw();
  })));
  return [
    stepOf(),
    headline('Where do you watch?'),
    body('Pick the sites you watch on. They’re the shortcuts in Mizune’s toolbar button, a click away.'),
    gap(),
    tiles,
    h('div', 'small-note', 'Mizune reads Netflix’s and Crunchyroll’s Japanese subtitles itself. On YouTube, for now, it needs a subtitle file dropped on the video. Change these any time in the settings.'),
  ];
}

function look(): Child[] {
  return [
    stepOf(),
    headline('Pick a look'),
    body('Your eyes, your call. It’s the player’s look over the video, and these pages’. Auto follows your computer’s light and dark mode.'),
    gap(),
    append(h('div', 'theme'), themePicker(player, change)),
  ];
}

function peek(): Child[] {
  const tiles = h('div', 'langs');
  for (const [code, name] of LANGUAGES) {
    if (code === 'ja') continue;
    const tile = h('button', player.peekLanguage === code ? 'lang on' : 'lang');
    tile.append(h('b', undefined, name));
    if (NATIVE[code] && NATIVE[code] !== name) tile.append(h('span', undefined, NATIVE[code]));
    tile.addEventListener('click', () => change({ peekLanguage: code }));
    tiles.append(tile);
  }
  const none = h('button', player.peekLanguage ? 'lang wide' : 'lang wide on');
  none.append(h('b', undefined, 'No peeking'), h('span', undefined, `Holding ${keyOf('Peek', 'KeyI')} shows nothing. For the strong-willed.`));
  none.addEventListener('click', () => change({ peekLanguage: '' }));
  tiles.append(none);
  return [
    stepOf(),
    headline('Peek in your language'),
    body(`Stuck on a line? Hold ${keyOf('Peek', 'KeyI')} and it shows in a language you know, from the site’s own subtitles. Pick yours, or turn peeking off if you’d rather not be tempted.`),
    gap(),
    tiles,
    h('div', 'small-note', 'You can change this any time in the settings, or from the player’s ⚙ menu.'),
  ];
}

// Dictionaries ----------------------------------------------------------------------------------

let catalog: CatalogEntry[] = [];
/** The catalog's entries ticked to download, by name. */
let picked = new Set<string>();
type Install = { state: 'queued' } | { state: 'downloading'; fraction?: number } | { state: 'importing'; terms: number } | { state: 'done' } | { state: 'failed'; message: string };
const installs = new Map<string, Install>();
let installing = false;
let downloadsRefused = false;

async function loadCatalog() {
  catalog = (await engine(settings, { type: 'catalog' }))?.entries ?? [];
  picked = new Set(catalog.filter((e) => e.recommended && e.installed == null).map((e) => e.name));
  draw();
}

/** What Continue would download: ticked, not there already and not on its way. */
const toInstall = () => catalog.filter((e) => picked.has(e.name) && e.installed == null && !installs.has(e.name));

function setInstall(name: string, install: Install) {
  installs.set(name, install);
  // only the step that shows them is drawn again, so a menu open on another isn't shut; and on
  // the last, only the list of them, until they're all done
  if (step === 'dictionaries') draw();
  else if (step === 'done') {
    if (busy()) running?.replaceWith(running = runningBox());
    else draw();
  }
}

/** Something's still downloading or being set up. */
const busy = () => [...installs.values()].some((i) => i.state !== 'done' && i.state !== 'failed');
const failed = () => [...installs.values()].some((i) => i.state === 'failed');

/** The built-in JMdict, put in as the page opens, unless it's in already. */
async function startBuiltIn() {
  if (installs.has(BUILT_IN.name)) return;
  if (await builtInInstalled()) return setInstall(BUILT_IN.name, { state: 'done' });
  setInstall(BUILT_IN.name, { state: 'importing', terms: 0 });
  try {
    await installBuiltIn((terms) => setInstall(BUILT_IN.name, { state: 'importing', terms }));
    setInstall(BUILT_IN.name, { state: 'done' });
  } catch (e) {
    setInstall(BUILT_IN.name, { state: 'failed', message: (e as Error).message });
  }
}

async function install(entries: CatalogEntry[]) {
  for (const e of entries) setInstall(e.name, { state: 'queued' });
  if (installing) return;
  installing = true;
  for (let next = [...installs].find(([, i]) => i.state === 'queued'); next; next = [...installs].find(([, i]) => i.state === 'queued')) {
    const entry = catalog.find((e) => e.name === next![0])!;
    try {
      const data = await downloadZip(entry, (_, fraction) => setInstall(entry.name, { state: 'downloading', fraction }));
      setInstall(entry.name, { state: 'importing', terms: 0 });
      await importDictionary(`${entry.name}.zip`, data, (terms) => setInstall(entry.name, { state: 'importing', terms }));
      setInstall(entry.name, { state: 'done' });
    } catch (e) {
      setInstall(entry.name, { state: 'failed', message: (e as Error).message });
    }
  }
  installing = false;
}

function installStatus(name: string, installed: boolean): Child {
  const i = installs.get(name);
  if (!i) return installed ? h('span', 'status done', '✓  Installed') : null;
  switch (i.state) {
    case 'queued': return h('span', 'status', 'Waiting its turn…');
    case 'downloading': {
      const bar = h('div', 'bar');
      const track = h('i');
      const fill = h('b');
      fill.style.width = `${(i.fraction ?? 0) * 100}%`;
      track.append(fill);
      bar.append(h('span', 'status', 'Downloading'), track);
      return bar;
    }
    case 'importing': return h('span', 'status', `Setting up… ${i.terms.toLocaleString()}`);
    case 'done': return h('span', 'status done', '✓  Installed');
    case 'failed': {
      const failed = h('span', 'status error', 'Couldn’t install');
      failed.title = i.message;
      return failed;
    }
  }
}

function dictionariesStep(): Child[] {
  const rows = catalog.map((entry) => {
    const busy = installs.has(entry.name) || entry.installed != null;
    const ticked = picked.has(entry.name);
    const status = installStatus(entry.name, entry.installed != null);
    const line = row(
      [entry.name, entry.recommended && h('span', 'badge', 'Recommended')],
      entry.description,
      status ?? append(h('span', 'size', `${entry.megabytes} MB`)),
      !status && h('span', ticked ? 'check on' : 'check', ticked ? '✓' : ''),
    );
    if (!busy) {
      line.classList.add('pick');
      line.addEventListener('click', () => {
        if (ticked) picked.delete(entry.name);
        else picked.add(entry.name);
        draw();
      });
    }
    return line;
  });
  return [
    stepOf(),
    headline('Choose your dictionaries'),
    body('Words are looked up in dictionaries kept in this browser. JMdict comes built in; these add more, downloaded straight from the people who make them.'),
    gap(),
    box(row([BUILT_IN.name, h('span', 'badge', 'Built in')], BUILT_IN.description, installStatus(BUILT_IN.name, false)),
      ...(catalog.length ? rows : [row('Getting the list…', null)])),
    h('div', 'small-note', downloadsRefused
      ? 'Mizune needs to reach GitHub to download them. Allow it, or add them later in the settings.'
      : 'More in the settings, where any Yomitan dictionary can be added.'),
  ];
}

// Anki ------------------------------------------------------------------------------------------

let anki: AnkiState | null = null;

async function checkAnki() {
  anki = ANKI_CONNECTING;
  draw();
  anki = await connectAnki(settings);
  draw();
}

function choice(title: string, value: string, options: string[], choose: (value: string) => void): HTMLElement {
  const select = h('select');
  if (!value) select.append(h('option', undefined, 'Choose…'));
  for (const option of options) {
    const o = h('option', undefined, option);
    o.selected = option === value;
    select.append(o);
  }
  select.addEventListener('change', () => choose(select.value));
  return row(title, null, select);
}

function ankiStep(): Child[] {
  const looking = !anki || anki.status === ANKI_CONNECTING.status;
  const running = anki?.connected;
  const status = row(
    looking ? 'Looking for Anki…' : running ? 'Anki is open and ready' : anki!.refused ? 'Anki hasn’t allowed this browser yet' : 'Anki isn’t answering',
    looking ? null
      : !running ? (anki!.refused ? 'Allow it, and answer the question Anki asks.' : 'Cards need Anki open with the AnkiConnect add-on. Or set it up later in the settings.')
      : !settings.anki ? 'Turn cards on to start making them.'
      : !settings.noteType ? 'Cards are on. Pick a note type and the deck they go in.'
      : !settings.deck ? 'Cards are on. Pick the deck they go in.'
      : 'Cards are on. Fields, tags and media are in the settings.',
    running && toggle(settings.anki, async (on) => {
      settings = await saveSettings({ anki: on });
      draw();
    }),
    !looking && !running && anki!.refused && action('Allow in Anki', async () => {
      anki = { ...anki!, status: 'Answer the question in Anki…' };
      draw();
      const reply = await askAnki(settings);
      if (reply.granted) return checkAnki();
      anki = { ...anki!, status: reply.message ?? 'Anki didn’t allow it' };
      draw();
    }),
    !looking && !running && !anki!.refused && action('Check again', checkAnki),
  );
  status.prepend(h('i', looking ? 'dot looking' : running ? 'dot on' : 'dot off'));
  const connected = running && settings.anki;
  return [
    stepOf(),
    headline('Make flashcards with Anki'),
    body(`Look up a word, press ${keyOf('AddCard', 'KeyA')}, and it becomes an Anki card with the sentence, its audio and a still from the scene.`),
    gap(),
    box(
      status,
      connected && choice('Note type', settings.noteType, anki!.noteTypes, async (noteType) => {
        settings = await saveSettings({ noteType });
        draw();
      }),
      connected && choice('Deck', settings.deck, anki!.decks, async (deck) => {
        settings = await saveSettings({ deck });
        draw();
      }),
    ),
    h('div', 'gap'),
    link('Get AnkiConnect ↗', ANKICONNECT_PAGE),
  ];
}

// Done ------------------------------------------------------------------------------------------

/** Whether Mizune's button is out on the toolbar, where the browser can say. */
let pinned: boolean | null = null;

async function checkPinned() {
  const settings = await (api.action as any).getUserSettings?.().catch(() => null);
  const now = typeof settings?.isOnToolbar === 'boolean' ? settings.isOnToolbar : null;
  if (now !== pinned) {
    pinned = now;
    if (step === 'done') draw();
  }
}

const PUZZLE = '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"><path d="M6.5 2.5a1.5 1.5 0 0 1 3 0V4H12a.5.5 0 0 1 .5.5V7H11a1.5 1.5 0 0 0 0 3h1.5v2.5a.5.5 0 0 1-.5.5H9.5v-1.5a1.5 1.5 0 0 0-3 0V13H4a.5.5 0 0 1-.5-.5V10H5a1.5 1.5 0 0 0 0-3H3.5V4.5A.5.5 0 0 1 4 4h2.5z"/></svg>';

function toolbar(): HTMLElement {
  const bar = h('div', 'toolbar');
  const puzzle = h('span', 'button');
  puzzle.innerHTML = PUZZLE;
  const mizune = h('span', 'button mizune');
  const icon = h('img');
  icon.src = 'icons/32.png';
  icon.alt = '';
  mizune.append(icon);
  bar.append(h('span', 'address'), puzzle, mizune);
  return bar;
}

/** On the last step, the dictionaries still on their way. */
let running: HTMLElement | null = null;

function runningBox(): HTMLElement {
  return box(...[...installs.keys()].map((name) => row(name, null, installStatus(name, false))));
}

function done(): Child[] {
  // what's on its way, or what didn't make it
  running = busy() || failed() ? runningBox() : null;
  const pin = row(
    pinned ? 'Mizune’s pinned to the toolbar' : 'Pin Mizune to the toolbar',
    pinned ? 'Its button shows what Mizune’s doing in a tab, and turns it on for videos on other sites.'
      : 'Click the puzzle piece by the address bar, then the pin beside Mizune. Its button shows what it’s doing in a tab, and turns it on for videos on other sites.',
    pinned ? h('span', 'status done', '✓') : toolbar(),
  );
  const tip = (caps: string[], text: string) => {
    const line = h('div', 'tip');
    line.append(append(h('span', 'caps'), ...caps.map(keycap)), text);
    return line;
  };
  const cards = app !== undefined || settings.anki;
  return [
    mascot(true),
    eyebrow('準備完了'),
    headline('You’re all set!'),
    body('Open a show with Japanese subtitles and Mizune’s player takes over. The first time, a short tour shows you round.'
      + (busy() ? ' Your dictionaries are still being set up, here: leave this tab open until they’re done.'
        : failed() ? ' Some dictionaries couldn’t be installed; try them again in the settings.' : '')),
    gap(),
    running,
    running && h('div', 'gap tight'),
    box(pin),
    h('div', 'gap tight'),
    box(append(h('div', 'tips'),
      tip(['Space'], 'Play and pause'),
      tip([keyOf('PreviousLine', 'ArrowLeft'), keyOf('NextLine', 'ArrowRight')], 'The previous or next line'),
      tip([keyOf('Peek', 'KeyI')], player.peekLanguage ? 'Hold to peek in your language' : 'Peeking is off; turn it on in the settings'),
      cards && tip([keyOf('AddCard', 'KeyA')], 'Turn the word you looked up into an Anki card'),
    )),
    chosenSites(settings.sites).length > 0 && append(h('div', 'sites'), siteRow(chosenSites(settings.sites), (site) => (location.href = site.url))),
  ];
}

// ---- moving through ----------------------------------------------------------------------------

function draw(direction: -1 | 0 | 1 = 0) {
  drawTheme(read);
  const list = steps();
  const at = list.indexOf(step);
  $('dots').replaceChildren(...list.map((_, i) => h('i', i === at ? 'on' : i < at ? 'done' : undefined)));
  const parts = { welcome, app: appStep, sites: sitesStep, look, peek, dictionaries: dictionariesStep, anki: ankiStep, done }[step]();
  const page = $('step');
  const scroll = page.parentElement!.scrollTop;
  page.replaceChildren(...parts.filter((p): p is Node | string => !!p));
  if (direction) {
    page.classList.remove('in-next', 'in-back');
    void page.offsetWidth;
    page.classList.add(direction > 0 ? 'in-next' : 'in-back');
    page.parentElement!.scrollTop = 0;
  } else {
    page.parentElement!.scrollTop = scroll;
  }

  $('footer').hidden = step === 'welcome';
  $('back').hidden = at <= 0;
  $('next').textContent = nextLabel();
}

function nextLabel(): string {
  switch (step) {
    case 'app': return app === null ? 'Skip' : 'Continue';
    case 'dictionaries': return toInstall().length ? 'Download and continue' : 'Continue';
    case 'anki': return settings.anki && anki?.connected ? 'Continue' : 'Maybe later';
    case 'done': return 'Close';
    default: return 'Continue';
  }
}

function go(by: -1 | 1) {
  const list = steps();
  const next = list[Math.min(list.length - 1, Math.max(0, list.indexOf(step) + by))];
  if (next === step) return;
  step = next;
  draw(by);
  if (step === 'anki' && !anki) checkAnki();
  if (step === 'done') {
    confetti();
    checkPinned();
  }
}

$('back').addEventListener('click', () => go(-1));
$('next').addEventListener('click', () => {
  if (step === 'dictionaries') {
    const wanted = toInstall();
    if (wanted.length) {
      // asked for in the click, before anything's awaited
      api.permissions.request({ origins: DOWNLOAD_ORIGINS }).catch(() => false).then((allowed) => {
        downloadsRefused = !allowed;
        if (!allowed) return draw();
        install(wanted);
        go(1);
      });
      return;
    }
  }
  if (step === 'done') {
    api.tabs.getCurrent().then((tab) => (tab?.id != null ? api.tabs.remove(tab.id) : window.close()));
    return;
  }
  go(1);
});

addEventListener('keydown', (event) => {
  if (event.target instanceof HTMLSelectElement) return;
  if (event.key === 'ArrowRight' && step !== 'done' && step !== 'welcome') $('next').click();
  if (event.key === 'ArrowLeft' && step !== 'welcome') go(-1);
});

// a pin made while the page is up shows on it
addEventListener('focus', checkPinned);
setInterval(() => step === 'done' && checkPinned(), 1500);

// ---- confetti ----------------------------------------------------------------------------------

/** A burst of paper confetti in the theme's colours, falling once (the app's Confetti). */
function confetti() {
  if (matchMedia('(prefers-reduced-motion: reduce)').matches) return;
  const canvas = h('canvas', 'confetti');
  document.body.append(canvas);
  const ratio = devicePixelRatio || 1;
  canvas.width = innerWidth * ratio;
  canvas.height = innerHeight * ratio;
  const context = canvas.getContext('2d')!;
  const style = getComputedStyle(document.documentElement);
  const colour = (name: string) => style.getPropertyValue(`--${name}`).trim() || '#B9DFC8';
  const colours = [colour('primary'), colour('secondary'), colour('tertiary'), colour('primary')];
  let seed = 7;
  const random = () => ((seed = (seed * 16807) % 2147483647) - 1) / 2147483646;
  const pieces = Array.from({ length: 90 }, (_, i) => ({
    x: random(), delay: random() * 0.35, drift: (random() - 0.5) * 0.25, spin: (random() - 0.5) * 900, colour: colours[i % 4], wide: random() > 0.5,
  }));
  const start = performance.now();
  const frame = (now: number) => {
    const t = (now - start) / 2600;
    context.clearRect(0, 0, canvas.width, canvas.height);
    if (t >= 1) return canvas.remove();
    for (const p of pieces) {
      const local = (t - p.delay) / (1 - p.delay);
      if (local <= 0 || local >= 1) continue;
      const x = canvas.width * (p.x + p.drift * local);
      const y = canvas.height * (-0.1 + 1.15 * local * local);
      const w = (p.wide ? 9 : 5) * ratio, ht = 6 * ratio;
      context.save();
      context.globalAlpha = local > 0.75 ? (1 - local) / 0.25 : 1;
      context.translate(x, y);
      context.rotate((p.spin * local * Math.PI) / 180);
      context.fillStyle = p.colour;
      context.fillRect(-w / 2, -ht / 2, w, ht);
      context.restore();
    }
    requestAnimationFrame(frame);
  };
  requestAnimationFrame(frame);
}

// ---- starting ----------------------------------------------------------------------------------

async function findApp() {
  app = null;
  draw();
  app = await appVersion();
  read = await readPlayer(settings, app);
  player = read.player;
  draw();
  if (app === undefined) {
    // the list first: while a dictionary goes in, the engine answers nothing else
    if (!catalog.length) await loadCatalog();
    startBuiltIn();
  }
}

async function start() {
  settings = await loadSettings();
  read = ownPlayer(settings);
  player = read.player;
  systemDark.addEventListener('change', () => drawTheme(read));
  draw();
  await findApp();
}

start();
