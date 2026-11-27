// The options page, drawn as the app's Settings (SettingsScreen.kt): its panes, rows and words,
// with what the browser adds (where lookups come from, sharing settings with the app). With the
// app installed, lookups and cards are its own, and the player's settings and keys are too while
// they're shared. Without it, this is also where the extension's own engine gets its
// dictionaries and Anki setup.
import { bindingName, capLabel } from './content/keys';
import { h } from './dom';
import { appVersion, changePlayer as changeSettings, drawTheme, ownPlayer, PlayerSettings, Read, readPlayer, systemDark } from './player-settings';
import {
  ANKI_CONNECTING, AnkiState, askAnki, BUILT_IN, builtInInstalled, CatalogEntry, connectAnki as connect, Dictionary, DOWNLOAD_ORIGINS, downloadZip,
  engine as askOwnEngine, importDictionary, installBuiltIn, LANGUAGES, SENTENCES_DECK_URL, SENTENCES_SOURCE_URL, themePicker as picker,
} from './setup';
import { api, DEFAULT_SETTINGS, loadSettings, saveSettings, Settings } from './shared';
import { siteLogo, WATCH_SITES } from './sites';
import { PEEKS, Preview, preview } from './previews';

const $ = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;

const PANES = ['General', 'Dictionaries', 'Playback', 'Appearance', 'Anki', 'Keyboard'] as const;
/** The panes, and About, which Mizune's name at the sidebar's foot opens, as in the app. */
type Pane = (typeof PANES)[number] | 'About';
const paneNamed = (name: string): Pane | undefined => (name === 'About' ? 'About' : PANES.find((p) => p === name));

let settings: Settings;
let pane: Pane = paneNamed(decodeURIComponent(location.hash.slice(1))) ?? 'General';
/** The app's version while it answers lookups; undefined while the extension's own engine does. */
let app: string | undefined;
let checked = false;

// ---- the player's settings and keys: the app's while they're shared, else the extension's ---

let read: Read;
let player: PlayerSettings;
let syncError: string | null = null;

function use(next: Read) {
  read = next;
  player = next.player;
  syncError = next.error;
}

async function loadPlayer() {
  use(await readPlayer(settings, app));
}

async function changePlayer(change: Partial<PlayerSettings>) {
  use({ ...read, player: { ...player, ...change } });
  render();
  const done = await changeSettings(settings, app, read, change);
  settings = done.settings;
  use(done.read);
  render();
}

systemDark.addEventListener('change', () => read && drawTheme(read));
// the toolbar's popup opens a pane by its name
addEventListener('hashchange', () => {
  const asked = paneNamed(decodeURIComponent(location.hash.slice(1)));
  if (!asked || asked === pane) return;
  pane = asked;
  document.querySelector('.scroll')!.scrollTop = 0;
  render();
});

// ---- the design's parts ------------------------------------------------------------------------

type Child = Node | string | null | undefined | false;

function append(parent: HTMLElement, children: Child[]): HTMLElement {
  for (const child of children) if (child) parent.append(child);
  return parent;
}

function section(title: string, note: string | null, ...children: Child[]): HTMLElement {
  const label = h('div', 'label');
  label.append(h('span', 'text', title));
  if (note) label.append(h('span', 'note', note));
  const el = append(h('section'), [label, ...children]);
  el.dataset.k = `s:${title}`;
  return el;
}

/** Rows on a card, a line between each. */
function card(...rows: Child[]): HTMLElement {
  const box = h('div', 'card');
  rows.filter(Boolean).forEach((row, i) => {
    if (i > 0) box.append(h('div', 'divider'));
    box.append(row as Node);
  });
  return box;
}

function row(title: string, detail: Child | Child[], ...controls: Child[]): HTMLElement {
  const words = h('div', 'words');
  words.append(h('div', 'title', title));
  const details = Array.isArray(detail) ? detail : [detail];
  if (details.some(Boolean)) words.append(append(h('div', 'detail'), details));
  const line = h('div', 'row');
  line.dataset.k = `r:${title}`;
  line.append(words);
  if (controls.some(Boolean)) line.append(append(h('div', 'controls'), controls));
  return line;
}

function toggle(on: boolean, change: (on: boolean) => void): HTMLButtonElement {
  const button = h('button', on ? 'toggle on' : 'toggle');
  button.setAttribute('role', 'switch');
  button.setAttribute('aria-checked', String(on));
  button.addEventListener('click', () => change(!on));
  return button;
}

function toggleRow(title: string, detail: string | null, on: boolean, change: (on: boolean) => void): HTMLElement {
  const button = toggle(on, change);
  button.setAttribute('aria-label', title);
  button.dataset.k = `t:${title}`;
  return row(title, detail, button);
}

function smallButton(text: string, onClick: () => void): HTMLButtonElement {
  const button = h('button', 'small', text);
  button.addEventListener('click', onClick);
  return button;
}

function textAction(text: string, kind: '' | 'primary' | 'error', onClick: () => void, enabled = true): HTMLButtonElement {
  const button = h('button', `text-action ${kind}`.trim(), text);
  button.disabled = !enabled;
  button.addEventListener('click', onClick);
  return button;
}

/** The design's select: the value and a ▾, opening a menu of [options]. */
function select(value: string, options: string[], onSelect: (index: number) => void, width: number, muted = false): HTMLElement {
  const box = h('div', 'select-box');
  box.style.width = `${width}px`;
  const button = h('button', muted ? 'select muted' : 'select');
  button.append(h('span', 'value', value), h('span', 'arrow', '▾'));
  button.addEventListener('click', (event) => {
    event.stopPropagation();
    const open = box.querySelector('.dropdown');
    closeMenus();
    if (open) return;
    const menu = h('div', 'dropdown');
    options.forEach((option, i) => {
      const item = h('button', undefined, option);
      item.addEventListener('click', () => {
        closeMenus();
        onSelect(i);
      });
      menu.append(item);
    });
    box.append(menu);
  });
  box.append(button);
  return box;
}

function closeMenus() {
  for (const menu of document.querySelectorAll('.dropdown')) menu.remove();
}

addEventListener('click', closeMenus);

/** A text field, saved once typing pauses; [key] keeps it focused while the pane's drawn again. */
function textInput(key: string, value: string, onChange: (value: string) => void, width: number, placeholder = ''): HTMLInputElement {
  const input = h('input', 'input');
  input.dataset.key = key;
  input.value = value;
  input.placeholder = placeholder;
  input.style.width = `${width}px`;
  let timer = 0;
  input.addEventListener('input', () => {
    clearTimeout(timer);
    timer = setTimeout(() => onChange(input.value), 600);
  });
  return input;
}

const keycap = (text: string) => h('span', 'keycap', text);

/** Words for something still on its way, breathing while they're there. */
const waiting = (text: string) => h('span', 'waiting', text);

/** How far along something is: [fraction] of the way, or a sweep while that's not known. */
function bar(fraction?: number): HTMLElement {
  const track = h('div', fraction == null ? 'bar unknown' : 'bar');
  track.dataset.k = 'bar';
  const fill = h('i');
  if (fraction != null) fill.style.width = `${Math.round(fraction * 1000) / 10}%`;
  track.append(fill);
  fill.dataset.k = 'bar-fill';
  return track;
}

/** A row's shape while what goes in it is still loading. */
function skeleton(): HTMLElement {
  const line = h('div', 'row skeleton');
  const words = h('div', 'words');
  words.append(h('i', 'bone'), h('i', 'bone short'));
  line.append(words);
  return line;
}

function below(text: string, error = false): HTMLElement {
  return h('div', error ? 'below error' : 'below', text);
}

// ---- the panes -------------------------------------------------------------------------------

/** Opens [p], from the sidebar. */
function openPane(p: Pane) {
  if (p === pane) return;
  pane = p;
  history.replaceState(null, '', `#${p}`);
  document.querySelector('.scroll')!.scrollTop = 0;
  render();
}

$('about').addEventListener('click', () => openPane('About'));

/** The open pane's preview, beside it. */
let shownPreview: Preview | null = null;

// pointing at a row in the pane picks it out in the preview (the keyboard's keys)
let pointedAt: string | null = null;
$('content').addEventListener('mouseover', (event) => {
  const row = (event.target as HTMLElement).closest<HTMLElement>('.row.key');
  const title = row?.querySelector('.title')?.textContent ?? null;
  if (title === pointedAt) return;
  pointedAt = title;
  shownPreview?.point?.(title);
});
$('content').addEventListener('mouseleave', () => {
  pointedAt = null;
  shownPreview?.point?.(null);
});

/** The pane last drawn, so a new one rises in and the same one is only changed. */
let shownPane: Pane | null = null;

/**
 * Draws the open pane. It's built afresh each time, so for what changed to move rather than jump,
 * each keyed part (data-k) is first drawn as it was, then, a frame on, as it is now; parts that
 * weren't there before fade in.
 */
function render() {
  if (!player) return;
  drawTheme(read);
  const before = snapshot();
  const entering = shownPane !== pane;
  shownPane = pane;

  const nav = $('nav');
  const items = PANES.map((p) => {
    const item = h('button', p === pane ? 'nav on' : 'nav', p);
    item.dataset.k = `n:${p}`;
    item.addEventListener('click', () => openPane(p));
    return item;
  });
  $('about').classList.toggle('on', pane === 'About');
  // the highlight stays the one element throughout, so it glides on from wherever it is
  for (const old of nav.querySelectorAll(':scope > .nav')) old.remove();
  nav.append(...items);

  const name = $('pane-name');
  if (entering) {
    const title = h('span', 'pane-title', pane);
    name.replaceChildren(title);
  }
  const content = $('content');
  // a field being typed in stays so
  const typing = document.activeElement instanceof HTMLInputElement ? document.activeElement : null;
  const sections = {
    General: general, Dictionaries: dictionariesPane, Playback: playback, Appearance: appearance, Anki: ankiPane, Keyboard: keyboard, About: about,
  }[pane]().filter((s): s is HTMLElement => !!s);
  sections.forEach((section, i) => section.style.setProperty('--i', String(i)));
  content.classList.remove('entering');
  content.replaceChildren(...sections);
  // and beside it, how its settings look
  shownPreview?.stop?.();
  shownPreview = preview({
    pane, settings, player, app, dictionaries, anki, fill, rounded,
    actions: ACTIONS, fixed: FIXED.filter((f) => f.caps.length),
  });
  const side = $('preview');
  side.classList.remove('entering');
  side.replaceChildren(...(shownPreview ? [shownPreview.element] : []));
  if (pointedAt) shownPreview?.point?.(pointedAt);
  // nothing new may be measured before this, or it's drawn as it is now, and moves from there
  carryOver(before, entering);
  if (entering) {
    void content.offsetWidth;
    content.classList.add('entering');
    side.classList.add('entering');
    // once it's in, it lets go of the animation, which otherwise can leave it unpainted
    side.firstElementChild?.addEventListener('animationend', () => side.classList.remove('entering'), { once: true });
  }
  movePill(pane === 'About' ? null : items[PANES.indexOf(pane)]);
  const again = typing?.dataset.key && content.querySelector<HTMLInputElement>(`[data-key="${typing.dataset.key}"]`);
  if (again && typing) {
    again.value = typing.value;
    again.focus();
    again.setSelectionRange(typing.selectionStart, typing.selectionEnd);
  }
}

/** The open pane's highlight, onto [item]: put there the first time, gliding there after. About has none. */
function movePill(item: HTMLElement | null) {
  let pill = $('nav').querySelector<HTMLElement>(':scope > .pill');
  const first = !pill;
  if (!pill) {
    pill = h('div', 'pill');
    pill.style.transition = 'none';
    $('nav').prepend(pill);
  }
  pill.classList.toggle('away', !item);
  if (!item) return;
  pill.style.transform = `translateY(${item.offsetTop}px)`;
  pill.style.height = `${item.offsetHeight}px`;
  if (first) {
    void pill.offsetWidth;
    pill.style.transition = '';
  }
}

interface Looks { className: string; style: string }

const looksOf = (el: HTMLElement): Looks => ({
  className: el.className.replace(/\s*\bappear\b/g, ''),
  style: (el.getAttribute('style') ?? '').replace(/\s*animation-delay:[^;]*;?/, ''),
});

/** When each part that's fading in started to, so drawing it again carries on the fade. */
const appearing = new Map<string, number>();
const APPEAR_MS = 300;

function snapshot(): Map<string, Looks> {
  const looks = new Map<string, Looks>();
  for (const el of document.querySelectorAll<HTMLElement>('[data-k]')) looks.set(el.dataset.k!, looksOf(el));
  return looks;
}

function carryOver(before: Map<string, Looks>, entering: boolean) {
  if (!before.size) return;
  const changed: [HTMLElement, Looks][] = [];
  const now = performance.now();
  for (const el of document.querySelectorAll<HTMLElement>('[data-k]')) {
    const key = el.dataset.k!;
    const was = before.get(key);
    if (!was) {
      // new here: it fades in, unless a whole new pane is rising in, or what it's in is already fading in
      if (!entering && !el.parentElement?.closest('.appear')) {
        el.classList.add('appear');
        appearing.set(key, now);
      }
      continue;
    }
    const since = appearing.has(key) ? now - appearing.get(key)! : Infinity;
    if (since < APPEAR_MS && !entering) {
      el.classList.add('appear');
      el.style.animationDelay = `${-since}ms`;
      continue;
    }
    appearing.delete(key);
    const looks = looksOf(el);
    if (was.className === looks.className && was.style === looks.style) continue;
    el.className = was.className;
    el.setAttribute('style', was.style);
    changed.push([el, looks]);
  }
  if (!changed.length) return;
  void document.body.offsetWidth;
  for (const [el, looks] of changed) {
    el.className = looks.className;
    el.setAttribute('style', looks.style);
  }
}

// General ----------------------------------------------------------------------------------------

function general(): Child[] {
  const sources = ['The Mizune app', 'The extension'];
  const source = !checked ? waiting('Finding Mizune…')
    : app !== undefined ? `Mizune ${app} looks words up and makes the cards`
    : settings.engine === 'browser' ? 'The extension’s own dictionaries, and Anki through AnkiConnect'
    : 'Mizune isn’t installed, so the extension’s own dictionaries, and Anki through AnkiConnect';
  const peekKey = player.keys.Peek?.[0];
  const peekOptions = [['', 'Off'], ...LANGUAGES];
  const peek = peekOptions.find(([code]) => code === player.peekLanguage)?.[1] ?? player.peekLanguage;
  return [
    section('Lookups', null,
      card(
        row('Lookups and cards', source,
          select(sources[settings.engine === 'browser' ? 1 : 0], sources, (i) => setEngine(i === 1 ? 'browser' : 'auto'), 150)),
        app !== undefined && toggleRow(
          'Share settings with the app',
          'The player’s settings and keys are the app’s, changed here or in it. Off, the extension keeps its own',
          settings.syncWithApp,
          async (on) => {
            settings = await saveSettings({ syncWithApp: on });
            api.runtime.sendMessage({ type: 'settingsChanged' }).catch(() => {});
            await loadPlayer();
            render();
          },
        ),
      ),
      syncError && below(syncError, true),
    ),
    section('Sites', 'Shortcuts in the toolbar’s button',
      card(...WATCH_SITES.map((site) => {
        const line = toggleRow(site.name, site.note, settings.sites.includes(site.id), async (on) => {
          settings = await saveSettings({ sites: WATCH_SITES.map((s) => s.id).filter((id) => (id === site.id ? on : settings.sites.includes(id))) });
          render();
        });
        line.prepend(siteLogo(site));
        return line;
      }))),
    section('Languages', null,
      card(row('Peek language',
        peekKey ? ['Hold', keycap(capLabel(peekKey)), 'to show subtitles in this language'] : 'Hold the line to show subtitles in this language',
        select(peek, peekOptions.map(([, name]) => name), (i) => changePlayer({ peekLanguage: peekOptions[i][0] }), 150))),
    ),
  ];
}

async function setEngine(engine: Settings['engine']) {
  settings = await saveSettings({ engine });
  api.runtime.sendMessage({ type: 'settingsChanged' }).catch(() => {});
  await findEngine();
}

/** Whether the app answers lookups, and with it, whose settings these are. */
async function findEngine() {
  app = await appVersion();
  checked = true;
  await loadPlayer();
  render();
  if (app === undefined) {
    await loadDictionaries();
    if (settings.anki) await connectAnki();
    // last: while a dictionary goes in, the engine answers nothing else
    installMissingBuiltIn();
  }
}

// Dictionaries -----------------------------------------------------------------------------------

/** A message to the extension's own engine. */
const engine = (message: Record<string, unknown>) => askOwnEngine(settings, message);

let dictionaries: Dictionary[] | null = null;
let catalog: CatalogEntry[] = [];
/** What's being downloaded or imported, and how far along, while something is. */
let importing: { text: string; fraction?: number } | null = null;
let importError: string | null = null;

async function loadDictionaries(reply?: any) {
  const list = reply?.type === 'dictionaries' ? reply : await engine({ type: 'dictionaries' });
  dictionaries = list?.dictionaries ?? [];
  catalog = (await engine({ type: 'catalog' }))?.entries ?? [];
  render();
}

const count = (n: number) => n.toLocaleString();

function dictionariesPane(): Child[] {
  if (app !== undefined) {
    return [section('Installed dictionaries', null,
      card(row('The app’s dictionaries', 'Words are looked up in the dictionaries installed in Mizune. Change them in its settings'))),
    ];
  }
  const list = dictionaries ?? [];
  const rows: HTMLElement[] = list.map((d, i) => {
    const line = h('div', 'row dictionary');
    line.dataset.k = `d:${d.id}`;
    const arrows = h('div', 'arrows');
    arrows.append(
      textAction('▲', '', async () => loadDictionaries(await engine({ type: 'moveDictionary', id: d.id, direction: -1 })), i > 0),
      textAction('▼', '', async () => loadDictionaries(await engine({ type: 'moveDictionary', id: d.id, direction: 1 })), i < list.length - 1),
    );
    const words = h('div', 'words');
    words.append(
      h('div', 'title strong', d.title),
      h('div', 'detail', `${count(d.terms)} ${d.terms === 1 ? 'term' : 'terms'}${d.meta ? ` · ${count(d.meta)} frequency/pitch` : ''}`),
    );
    const enabled = toggle(d.enabled, async (on) => loadDictionaries(await engine({ type: 'enableDictionary', id: d.id, enabled: on })));
    enabled.dataset.k = `td:${d.id}`;
    enabled.setAttribute('aria-label', d.title);
    line.append(arrows, words, enabled,
      textAction('Remove', 'error', async () => {
        if (!confirm(`Remove ${d.title} from this browser?`)) return;
        await loadDictionaries(await engine({ type: 'deleteDictionary', id: d.id }));
      }));
    return line;
  });
  if (!dictionaries) rows.push(skeleton(), skeleton());
  else if (!list.length) rows.push(row('No dictionaries yet', 'Download one below, or import a Yomitan dictionary'));
  const strip = h('div', 'strip');
  strip.append(textAction('+ Import Yomitan dictionary…', 'primary', () => $('zip').click(), !importing));
  if (importing) {
    strip.append(h('span', 'status', importing.text), bar(importing.fraction));
  }
  return [
    section('Installed dictionaries', 'Looked up in order, top first',
      append(card(...rows), [rows.length ? h('div', 'divider') : null, strip]),
      importError && below(importError, true)),
    catalog.length > 0 && section('Download', null,
      card(...catalog.map((entry) => row(
        entry.name + (entry.recommended ? ' · recommended' : ''),
        `${entry.description} About ${entry.megabytes} MB.`,
        smallButton(entry.installed == null ? 'Download' : 'Download again', () => download(entry)),
      )))),
  ];
}

async function download(entry: CatalogEntry) {
  if (importing) return;
  // asked for here, before anything is awaited, while the click still counts
  const allowed = await api.permissions.request({ origins: DOWNLOAD_ORIGINS });
  if (!allowed) return failImport(`Mizune needs to reach GitHub to download ${entry.name}`);
  try {
    await importZip(`${entry.name}.zip`, await downloadZip(entry, progress));
  } catch (e) {
    failImport(`Couldn’t download ${entry.name}: ${(e as Error).message}`);
  }
}

async function importZip(name: string, data: ArrayBuffer) {
  progress(`Importing ${name}…`);
  let reply: any;
  try {
    reply = await importDictionary(name, data, (terms) => progress(`Importing ${name}… ${count(terms)} terms`));
  } catch (e) {
    return failImport((e as Error).message);
  }
  importing = null;
  await loadDictionaries(reply);
}

/** The built-in JMdict, when it hasn't gone in yet (the welcome page puts it in first). */
async function installMissingBuiltIn() {
  if (importing || await builtInInstalled()) return;
  progress(`Setting up ${BUILT_IN.name}…`);
  try {
    const reply = await installBuiltIn((terms) => progress(`Setting up ${BUILT_IN.name}… ${count(terms)} terms`));
    importing = null;
    await loadDictionaries(reply ?? undefined);
  } catch (e) {
    failImport(`Couldn’t set up ${BUILT_IN.name}: ${(e as Error).message}`);
  }
}

function progress(text: string, fraction?: number) {
  importing = { text, fraction };
  importError = null;
  render();
}

function failImport(message: string) {
  importing = null;
  importError = message;
  render();
}

$<HTMLInputElement>('zip').addEventListener('change', async () => {
  const file = $<HTMLInputElement>('zip').files?.[0];
  $<HTMLInputElement>('zip').value = '';
  if (file) await importZip(file.name, await file.arrayBuffer());
});

// Playback ---------------------------------------------------------------------------------------

function playback(): Child[] {
  return [
    section('Playback', null, card(
      toggleRow('Stop at the end of each line', 'Pauses after every subtitle so you can read it', player.autoPause, (on) => changePlayer({ autoPause: on })),
      toggleRow('Pause when you look up a word', null, player.pauseOnLookup, (on) => changePlayer({ pauseOnLookup: on })),
      toggleRow('Copy each line to the clipboard', null, player.copyLines, (on) => changePlayer({ copyLines: on })),
    )),
  ];
}

// Appearance -------------------------------------------------------------------------------------

/** The picture fills the area, and the video and panel are rounded cards: kept with the player's layout (main.ts saveView). */
let fill = false;
let rounded = false;

/** Changes the player's layout in the tabs playing. */
async function changeView(change: { fill?: boolean; rounded?: boolean }) {
  const { view } = (await api.storage.local.get('view')) as { view?: object };
  await api.storage.local.set({ view: { ...view, ...change } });
  api.runtime.sendMessage({ type: 'settingsChanged' }).catch(() => {});
}

function appearance(): Child[] {
  return [
    section('Theme', null, card(themePicker())),
    section('Video', null, card(
      toggleRow('Fill the video area', 'Crops the edges instead of letterboxing', fill, async (on) => {
        fill = on;
        await changeView({ fill: on });
        render();
      }),
      toggleRow('Frame', 'Rounded corners and a gap round the video and panel', rounded, async (on) => {
        rounded = on;
        await changeView({ rounded: on });
        render();
      }),
    )),
    section('Subtitles', null, append(h('div', 'card'), subtitleSize())),
  ];
}

const themePicker = () => picker(player, changePlayer);

const SIZE_MIN = 18;
const SIZE_MAX = 56;

function subtitleSize(): HTMLElement[] {
  const line = h('div', 'size');
  const slider = h('input', 'slider');
  slider.type = 'range';
  slider.min = String(SIZE_MIN);
  slider.max = String(SIZE_MAX);
  const value = h('span', 'value');
  const preview = h('div', 'preview');
  const text = h('span', undefined, '今日はいい天気ですね');
  preview.append(text);
  const show = (size: number) => {
    slider.value = String(size);
    slider.style.setProperty('--at', `${((size - SIZE_MIN) / (SIZE_MAX - SIZE_MIN)) * 100}%`);
    value.textContent = `${size} pt`;
    text.style.fontSize = `${size}px`;
  };
  show(Math.min(SIZE_MAX, Math.max(SIZE_MIN, player.lineSize)));
  slider.addEventListener('input', () => show(Number(slider.value)));
  slider.addEventListener('change', () => changePlayer({ lineSize: Number(slider.value) }));
  const small = h('span', 'a', 'A');
  small.style.fontSize = '11px';
  const large = h('span', 'a', 'A');
  large.style.fontSize = '17px';
  line.append(h('span', 'name', 'Size'), small, slider, large, value);
  return [line, preview];
}

// Anki -------------------------------------------------------------------------------------------

let anki: AnkiState | null = null;

/** Asks AnkiConnect for the collection again; [quietly], what's shown stays until it answers. */
async function connectAnki(quietly = false) {
  if (!quietly || !anki?.connected) {
    anki = ANKI_CONNECTING;
    render();
  }
  anki = await connect(settings, PEEKS[settings.peekLanguage] ?? '');
  // the first time, a known note type if the collection has one, as the app does
  if (anki.connected && !settings.noteType && anki.pick) {
    settings = await saveSettings({ noteType: anki.pick });
    anki = await connect(settings, PEEKS[settings.peekLanguage] ?? '');
  }
  await keepFormat();
  render();
}

/** The chosen note type's fields kept as they're filled, so a later guess doesn't change them. */
async function keepFormat() {
  const { noteType } = settings;
  const format = anki?.format;
  if (!noteType || !format) return;
  const saved = settings.cardFormats[noteType] ?? {};
  const same = Object.keys(saved).length === Object.keys(format).length && Object.entries(format).every(([field, source]) => saved[field] === source);
  if (!same) settings = await saveSettings({ cardFormats: { ...settings.cardFormats, [noteType]: format } });
}

/** Fills the chosen note type's fields with [format]: one changed, or all of them reset. */
async function saveFormat(format: Record<string, string>) {
  anki = { ...anki!, format };
  // the preview's card is filled again, by the engine, to match
  await changeAnki({ cardFormats: { ...settings.cardFormats, [settings.noteType]: format } }, 'quietly');
}

async function allowInAnki() {
  anki = { ...anki!, status: 'Answer the question in Anki…' };
  render();
  const reply = await askAnki(settings);
  if (reply.granted) return connectAnki();
  anki = { ...anki!, status: reply.message ?? 'Anki didn’t allow it' };
  render();
}

async function changeAnki(change: Partial<Settings>, reconnect: boolean | 'quietly' = false) {
  settings = await saveSettings(change);
  if (reconnect) await connectAnki(reconnect === 'quietly');
  else render();
}

/** Under the cards: the note type they're set up for, and where to get it. */
function noteTypeSource(inCollection: boolean): HTMLElement {
  const link = (text: string, url: string) => {
    const a = h('a', undefined, text);
    a.href = url;
    a.target = '_blank';
    a.rel = 'noopener';
    return a;
  };
  const text = below(inCollection
    ? 'Cards are set up for Japanese sentences, the mpvacious note type.'
    : 'Cards are set up for Japanese sentences, the mpvacious note type, which isn’t in your collection.');
  text.append(' Get it with the ', link('example deck on AnkiWeb', SENTENCES_DECK_URL), ' (importing the deck adds the note type), or from ',
    link('its source', SENTENCES_SOURCE_URL), '.');
  return text;
}

const seconds = (value: number) => `${value} s`;

function ankiPane(): Child[] {
  if (app !== undefined) {
    return [section('Anki cards', null,
      card(row('From the Mizune app', 'Cards are made by the app, with its own Anki settings. Change them in its settings'))),
    ];
  }
  const on = settings.anki;
  const failed = on && anki && !anki.connected && anki.status !== 'Connecting…';
  const out: Child[] = [
    section('Anki cards', null,
      card(
        toggleRow('Make Anki cards', 'Press A in the player, or + on a word. Anki has to be open', on, async (next) => {
          await changeAnki({ anki: next }, next);
        }),
        on && row('AnkiConnect', !anki || anki.status === 'Connecting…' || anki.status.endsWith('…') ? waiting(anki?.status ?? 'Connecting…') : anki.status,
          textInput('ankiUrl', settings.ankiUrl, (url) => changeAnki({ ankiUrl: url.trim() || DEFAULT_SETTINGS.ankiUrl }, true), 190, DEFAULT_SETTINGS.ankiUrl),
          anki?.refused && smallButton('Allow in Anki', allowInAnki),
          smallButton('Retry', connectAnki)),
      ),
      failed && !anki!.refused && below('Cards are added through Anki, so it has to be open, with the AnkiConnect add-on (code 2055492159, under Tools › Add-ons › Get Add-ons).'),
    ),
  ];
  if (!on || !anki?.connected) return out;
  const { decks, noteTypes } = anki;
  out.push(
    section('Cards', null, card(
      row('Note type', null, select(settings.noteType || 'Choose…', noteTypes, (i) => {
        // the old note type's fields go until the new one's come
        anki = { ...anki!, fields: null, format: null, suggested: null };
        changeAnki({ noteType: noteTypes[i] }, 'quietly');
      }, 220, !settings.noteType)),
      row('Deck', null, select(settings.deck || 'Choose…', decks, (i) => changeAnki({ deck: decks[i] }), 220, !settings.deck)),
      row('Tags', 'Separated by spaces', textInput('tags', settings.tags, (tags) => changeAnki({ tags: tags.trim() }), 220)),
      toggleRow('Warn about duplicates', 'Ask before adding a card the deck already has. Off, it’s added again',
        settings.warnDuplicates, (warn) => changeAnki({ warnDuplicates: warn })),
    ), noteTypeSource(!!anki.pick)),
  );
  // what goes into each of the note type's fields, as in the app
  const { fields, format, suggested } = anki;
  const sources = anki.sources ?? [];
  if (settings.noteType && fields?.length && format) {
    const label = (name: string) => sources.find((s) => s.name === name)?.label ?? name;
    const strip = h('div', 'strip');
    if (suggested) {
      strip.append(textAction(anki.preset ? `Reset to the ${settings.noteType} defaults` : 'Reset to the suggested fields', 'primary',
        () => saveFormat({ ...suggested })));
    }
    out.push(section('Fields', anki.preset ? 'A known format: set up already' : 'Guessed from the field names',
      card(
        ...fields.map((field) => {
          const source = format[field] ?? 'None';
          return row(field, null, select(label(source), sources.map((s) => s.label), (i) => saveFormat({ ...format, [field]: sources[i].name }), 240, source === 'None'));
        }),
        strip,
      )));
  }
  out.push(
    section('Media', null, card(
      row('Audio around the line', 'Kept before and after it, so the first and last sounds aren’t cut',
        select(seconds(settings.audioPadding), PADDINGS.map(seconds), (i) => changeAnki({ audioPadding: PADDINGS[i] }), 100)),
      row('Screenshot', settings.imageAnimated ? 'The whole line, moving, like a GIF' : 'The frame on screen when you add the card',
        select(settings.imageAnimated ? 'Animated' : 'Still', ['Animated', 'Still'], (i) => changeAnki({ imageAnimated: i === 0 }), 100)),
      row('Screenshot height', settings.imageAnimated ? 'WebP: around 55 KB a second at 360 px, by motion' : 'WebP: about 10 KB at 360 px',
        select(`${settings.imageHeight} px`, HEIGHTS.map((px) => `${px} px`), (i) => changeAnki({ imageHeight: HEIGHTS[i] }), 100)),
    )),
  );
  return out;
}

const PADDINGS = [0, 0.15, 0.3, 0.5, 0.75];
const HEIGHTS = [240, 360, 480, 720];

// Keyboard ---------------------------------------------------------------------------------------

/** The player's actions that work in the browser, in the app's order, with its words (KeyMap.kt). */
const ACTIONS: { name: string; label: string; note?: string }[] = [
  { name: 'PreviousLine', label: 'Previous line, then keep playing' },
  { name: 'NextLine', label: 'Next line, then keep playing', note: 'while paused, carries on playing first' },
  { name: 'PreviousLineStop', label: 'Previous line, stop at its end' },
  { name: 'NextLineStop', label: 'Next line, stop at its end', note: 'while paused, carries on playing first' },
  { name: 'ReplayLine', label: 'Replay this line, stop at its end' },
  { name: 'Peek', label: 'Show the peek language', note: 'hold, or hold the line' },
  { name: 'SeekBack', label: 'Seek 5 s back' },
  { name: 'SeekForward', label: 'Seek 5 s forward' },
  { name: 'SubtitlesEarlier', label: 'Shift subtitles 0.1 s earlier' },
  { name: 'SubtitlesLater', label: 'Shift subtitles 0.1 s later' },
  { name: 'StopEveryLine', label: 'Stop at the end of every line, on or off', note: 'or the button under the panel' },
  { name: 'Panel', label: 'Put the panel away, or bring it back', note: 'the line then goes over the video' },
  { name: 'FillFit', label: 'Fill the area, or fit the whole picture' },
  { name: 'AddCard', label: 'Add the looked-up word to Anki', note: 'when Anki cards are on' },
];
/** The app's actions the browser leaves to the site, which a key map file from the app has too. */
const APP_ONLY = new Set(['PlayPause', 'FullScreen', 'PlayNext']);
const MAX_KEYS = 2;

/** Keys the player and the site keep for themselves, and why. */
const KEPT: Record<string, string> = {
  Space: 'Space is kept for playing and pausing',
  Escape: 'Esc is kept for closing things',
  Enter: 'That key can’t be used',
  Tab: 'That key can’t be used',
};

/** Keys that stay as they are, with the app's words where it has them. */
const FIXED: { caps: string[]; action: string; note?: string }[] = [
  { caps: ['Space'], action: 'Play / pause' },
  { caps: ['F'], action: 'Full screen', note: 'the site’s own' },
  { caps: ['Esc'], action: 'Close the menu or a lookup over the video' },
  { caps: [], action: 'Look up a word', note: 'click or drag it; over the video, point at it' },
];

let tutorialSeen = false;
/** The key being set (an action and which of its keys), and what to say in a row after a change. */
let recording: { action: string; slot: number } | null = null;
let said: { action: string; text: string } | null = null;
let imported: string[] | null = null;

const label = (action: string) => ACTIONS.find((a) => a.name === action)?.label ?? action;

function keyboard(): Child[] {
  const isDefault = ACTIONS.every(({ name }) => (player.keys[name] ?? []).join() === (DEFAULT_SETTINGS.keys[name] ?? []).join());
  const buttons = h('div', 'buttons');
  append(buttons, [
    smallButton('Export…', exportKeys),
    smallButton('Import…', () => $('key-file').click()),
    !isDefault && smallButton('Reset to defaults', () => {
      said = null;
      imported = null;
      changePlayer({ keys: { ...DEFAULT_SETTINGS.keys } });
    }),
  ]);
  return [
    section('Getting started', null, card(row('Welcome and setup', 'The steps shown when the extension was installed',
      smallButton('Open', () => api.tabs.create({ url: api.runtime.getURL('welcome.html') }))), row('Player tutorial',
      tutorialSeen ? 'The short tour of the player shown the first time a video opens' : 'Shows the next time a video opens',
      tutorialSeen && smallButton('Show again', async () => {
        await api.storage.local.set({ tutorialSeen: false });
        tutorialSeen = false;
        render();
      })))),
    section('In the player', null,
      card(...ACTIONS.map(({ name, label: text, note }) => {
        const message = said?.action === name ? said.text : null;
        const keys = player.keys[name] ?? [];
        return keyRow(text, message ?? note, !!message, [
          ...keys.map((binding, slot) => keyButton(name, slot, binding)),
          keys.length < MAX_KEYS && keyButton(name, keys.length, null),
        ]);
      })),
      buttons,
      imported && below(imported.length ? `Imported, except: ${imported.join('; ')}.` : 'Imported.'),
    ),
    section('Always', null, card(...FIXED.map(({ caps, action, note }) => keyRow(action, note, false, caps.map(keycap))))),
  ];
}

function keyRow(action: string, note: string | undefined | null, highlight: boolean, keys: Child[]): HTMLElement {
  const line = row(action, note ?? null, append(h('div', 'keys'), keys));
  line.classList.add('key');
  if (highlight) line.querySelector('.detail')!.classList.add('highlight');
  return line;
}

/** One of an action's keys ([binding] null: the + for adding one). Clicked, it waits for a key; hovered, an × takes it off. */
function keyButton(action: string, slot: number, binding: string | null): HTMLElement {
  const waiting = recording?.action === action && recording.slot === slot;
  const button = h('button', `key-button${binding ? '' : ' add'}${waiting ? ' recording' : ''}`);
  button.append(waiting ? h('span', 'plain', 'Press a key…') : binding ? keycap(capLabel(binding)) : h('span', 'plain', '+'));
  button.title = binding ? 'Change this key' : 'Add a key';
  button.addEventListener('click', () => {
    recording = { action, slot };
    said = null;
    render();
  });
  if (binding) {
    const remove = h('span', 'remove', '×');
    remove.title = 'Remove this key';
    remove.addEventListener('click', (event) => {
      event.stopPropagation();
      said = null;
      changePlayer({ keys: { ...player.keys, [action]: (player.keys[action] ?? []).filter((_, i) => i !== slot) } });
    });
    button.append(remove);
  }
  return button;
}

addEventListener('keydown', (event) => {
  if (!recording) return;
  event.preventDefault();
  event.stopPropagation();
  const { action, slot } = recording;
  if (event.code === 'Escape') {
    recording = null;
    return render();
  }
  const name = bindingName(event);
  // a modifier on its own: wait for the key that goes with it
  if (!name) return;
  recording = null;
  if (KEPT[name]) {
    said = { action, text: KEPT[name] };
    return render();
  }
  // as in the app, a key another action had moves here, and the row says so
  const keys: Record<string, string[]> = {};
  let from: string | null = null;
  for (const [other, bound] of Object.entries(player.keys)) {
    if (other !== action && bound.includes(name)) from = other;
    keys[other] = bound.filter((b) => b !== name);
  }
  const own = [...(keys[action] ?? [])];
  if (slot < own.length) own[slot] = name;
  else own.push(name);
  keys[action] = own.slice(0, MAX_KEYS);
  said = from ? { action, text: `${capLabel(name)} was ${label(from)}` } : null;
  changePlayer({ keys });
}, true);

/** The keys as the app's key map file has them (KeyMap.toJson), for the app or another browser. */
function exportKeys() {
  const keys = Object.fromEntries(ACTIONS.map(({ name }) => [name, player.keys[name] ?? []]));
  const file = new Blob([JSON.stringify({ mizune: 'keys', version: 1, keys }, null, 2)], { type: 'application/json' });
  const link = h('a');
  link.href = URL.createObjectURL(file);
  link.download = 'Mizune keys.json';
  link.click();
  URL.revokeObjectURL(link.href);
}

/** A key map file read as the app reads one (KeyMap.fromJson), with what it couldn't take. */
function readKeys(text: string): { keys: Record<string, string[]>; problems: string[] } {
  let json: any;
  try { json = JSON.parse(text); } catch { json = null; }
  if (typeof json?.keys !== 'object' || !json.keys) return { keys: player.keys, problems: ['This isn’t a Mizune key map file'] };
  const problems: string[] = [];
  const known = new Set(ACTIONS.map((a) => a.name));
  for (const name of Object.keys(json.keys).sort()) if (!known.has(name) && !APP_ONLY.has(name)) problems.push(`Unknown action "${name}"`);
  const keys: Record<string, string[]> = {};
  const taken = new Map<string, string>();
  for (const { name, label: text } of ACTIONS) {
    keys[name] = [];
    const names = json.keys[name];
    if (!Array.isArray(names)) continue;
    for (const binding of names.map(String)) {
      if (KEPT[binding]) problems.push(`${text}: ${KEPT[binding]}`);
      else if (taken.has(binding)) problems.push(`${text}: ${capLabel(binding)} is already ${label(taken.get(binding)!)}`);
      else if (keys[name].length === MAX_KEYS) problems.push(`${text}: only ${MAX_KEYS} keys, so ${capLabel(binding)} is left out`);
      else {
        keys[name].push(binding);
        taken.set(binding, name);
      }
    }
  }
  return { keys, problems };
}

$<HTMLInputElement>('key-file').addEventListener('change', async () => {
  const file = $<HTMLInputElement>('key-file').files?.[0];
  $<HTMLInputElement>('key-file').value = '';
  if (!file) return;
  const read = readKeys(await file.text());
  imported = read.problems;
  said = null;
  await changePlayer({ keys: read.keys });
});

// About ------------------------------------------------------------------------------------------

const REPO_PAGE = 'https://github.com/finchett/Mizune';

/** A link out, as the app's: a quiet outlined button with an arrow. */
function linkButton(text: string, url: string): HTMLAnchorElement {
  const link = h('a', 'link-button', `${text} ↗`);
  link.href = url;
  link.target = '_blank';
  link.rel = 'noopener';
  return link;
}

function about(): Child[] {
  const hero = h('section', 'about-hero');
  hero.dataset.k = 's:about';
  const icon = h('img');
  icon.src = 'icons/128.png';
  icon.alt = '';
  const words = h('div', 'words');
  words.append(
    h('div', 'name', 'Mizune'),
    h('div', 'line', `Extension version ${api.runtime.getManifest().version}`),
    h('div', 'line', app !== undefined
      ? `Working with the Mizune app ${app}, which looks words up and makes the cards.`
      : checked ? 'Working on its own, with its own dictionaries and Anki through AnkiConnect.' : ''),
    h('div', 'line', 'Mizune’s player and dictionary, over Netflix and Crunchyroll.'),
  );
  hero.append(icon, words);
  const links = append(h('section', 'about-links'), [
    linkButton('GitHub', REPO_PAGE),
    linkButton('Report an issue', `${REPO_PAGE}/issues`),
    app === undefined && linkButton('Get the Mizune app', 'https://mizune.app'),
  ]);
  links.dataset.k = 's:about-links';
  const credits = h('section', 'about-credits',
    'Free and open source under the Mozilla Public License 2.0. The built-in dictionary is JMdict, © the Electronic Dictionary '
    + 'Research and Development Group, used under CC BY-SA 4.0 (edrdg.org/edrdg/licence.html), in its Yomitan conversion by '
    + 'rikaitan-import. The dictionaries you add are their makers’ own. Netflix, Crunchyroll and YouTube are '
    + 'trademarks of their owners; their logos here only say which site a shortcut opens. Thank you for using it.');
  credits.dataset.k = 's:about-credits';
  return [hero, links, credits];
}

// ---- starting ----------------------------------------------------------------------------------

async function start() {
  settings = await loadSettings();
  use(ownPlayer(settings));
  $('version').textContent = `Version ${api.runtime.getManifest().version}`;
  const stored = (await api.storage.local.get(['tutorialSeen', 'view'])) as { tutorialSeen?: boolean; view?: { fill?: boolean; rounded?: boolean } };
  tutorialSeen = !!stored.tutorialSeen;
  fill = !!stored.view?.fill;
  rounded = !!stored.view?.rounded;
  render();
  await findEngine();
}

start();
