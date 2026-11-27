// Beside each settings pane, what its settings look like: the toolbar's button and peeking, a
// lookup through the dictionaries in their order, the player stopping (or not) at each line, the
// player in its theme, an Anki card as it'll be made, and the keyboard with the player's keys.
// Drawn from the settings as they are, so a change shows here as it's made.
import { capLabel } from './content/keys';
import { h } from './dom';
import { PlayerSettings } from './player-settings';
import { canRunScripts, PLAIN_LOOK, sideFrame, sides } from './anki-card';
import { sceneElement } from './scene';
import { AnkiState, Dictionary, LANGUAGES } from './setup';
import { Settings } from './shared';
import { chosenSites, siteRow } from './sites';

export interface PreviewState {
  pane: string;
  settings: Settings;
  player: PlayerSettings;
  /** The app's version while it answers lookups. */
  app: string | undefined;
  dictionaries: Dictionary[] | null;
  anki: AnkiState | null;
  fill: boolean;
  rounded: boolean;
  /** The player's actions and what they're called, for the keyboard. */
  actions: { name: string; label: string }[];
  fixed: { caps: string[]; action: string }[];
}

export interface Preview {
  element: HTMLElement;
  /** Stops what it's playing, before the next one's drawn. */
  stop?: () => void;
  /** The row pointed at in the pane (its title), or null, for the preview to pick out. */
  point?: (title: string | null) => void;
}

/** A part kept from one drawing to the next (see the options' carryOver), so a change moves. */
function keyed<T extends HTMLElement>(el: T, key: string): T {
  el.dataset.k = `pv:${key}`;
  return el;
}

function frame(label: string, caption: string | null, ...stage: HTMLElement[]): HTMLElement {
  const box = h('div', 'pv-block');
  const top = h('div', 'pv-label', label);
  // what's shown, on a panel of its own, in the middle of it
  const panel = h('div', 'pv-stage');
  panel.append(...stage);
  box.append(top, panel);
  if (caption) box.append(h('div', 'pv-caption', caption));
  return box;
}

const LINE = '今日はいい天気ですね';

/** The line in each language there is to peek in. */
export const PEEKS: Record<string, string> = {
  ar: 'الطقس جميل اليوم، أليس كذلك؟', zh: '今天天气真好啊。', nl: 'Mooi weer vandaag, hè?', en: 'Nice weather today, isn’t it?',
  fr: 'Il fait beau aujourd’hui, n’est-ce pas ?', de: 'Schönes Wetter heute, nicht wahr?', hi: 'आज मौसम अच्छा है, है ना?',
  id: 'Cuacanya bagus hari ini, ya?', it: 'Che bella giornata oggi, vero?', ja: LINE, ko: '오늘 날씨 좋네요.',
  pl: 'Ładna dziś pogoda, prawda?', pt: 'Que tempo bom hoje, não é?', ru: 'Сегодня хорошая погода, правда?',
  es: 'Hace buen tiempo hoy, ¿verdad?', th: 'วันนี้อากาศดีนะ', tr: 'Bugün hava çok güzel, değil mi?',
  uk: 'Сьогодні гарна погода, правда?', vi: 'Hôm nay trời đẹp nhỉ.',
};

/** A line with one of its words looked up. */
function lookedUp(): HTMLElement {
  const line = h('div', 'pv-line');
  line.lang = 'ja';
  line.append('今日はいい', h('span', 'pv-word', '天気'), 'ですね');
  return line;
}

// ---- General: the toolbar's button, and peeking -------------------------------------------------

function general(state: PreviewState): Preview {
  const popup = h('div', 'pv-popup');
  const head = h('div', 'pv-popup-head');
  const icon = h('img');
  icon.src = 'icons/128.png';
  icon.alt = '';
  const who = h('div', 'pv-who');
  who.append(h('b', undefined, 'Mizune'), h('span', 'pv-on', 'On'));
  head.append(icon, who, h('span', 'pv-switch'));
  popup.append(head);
  const sites = chosenSites(state.settings.sites);
  const watch = h('div', 'pv-popup-card');
  watch.append(h('div', 'pv-eyebrow', 'Watch on'));
  if (sites.length) watch.append(siteRow(sites, () => {}));
  else watch.append(h('div', 'pv-empty', 'No sites chosen'));
  popup.append(watch);

  const peek = state.player.peekLanguage;
  const name = LANGUAGES.find(([code]) => code === peek)?.[1];
  const video = h('div', 'pv-video pv-scene');
  video.append(sceneElement());
  const lines = h('div', 'pv-peek');
  const ja = h('div', 'pv-sub', LINE);
  ja.lang = 'ja';
  lines.append(ja);
  if (peek) {
    const other = h('div', 'pv-sub pv-other', PEEKS[peek] ?? '');
    other.lang = peek;
    lines.append(other);
    lines.classList.add('peeking');
  }
  video.append(lines);
  const key = state.player.keys.Peek?.[0];
  return {
    element: append(h('div', 'pv-stack'),
      frame('The toolbar’s button', 'The sites switched on above are a click away in it.', popup),
      frame('Peeking', peek
        ? `Hold ${key ? capLabel(key) : 'the line'} and the line shows in ${name}.`
        : 'Peeking’s off: the line stays in Japanese.', video)),
  };
}

// ---- Dictionaries: a lookup through them, in their order ----------------------------------------

const GLOSSES = [
  ['weather', 'the elements'],
  ['fair weather', 'fine weather', 'sunshine'],
  ['the weather, as something to talk about'],
  ['mood', 'temper'],
];

function dictionaries(state: PreviewState): Preview {
  const panel = h('div', 'pv-panel');
  panel.append(lookedUp(), h('div', 'pv-rule'));
  const head = h('div', 'pv-head');
  head.lang = 'ja';
  head.append(h('b', undefined, '天気'), h('span', undefined, 'てんき'));
  panel.append(head);
  const entry = (title: string, glosses: string[], i: number) => {
    const block = keyed(h('div', 'pv-entry'), `dict:${title}`);
    block.append(h('span', 'pv-chip', title));
    const list = h('ol');
    for (const gloss of glosses) list.append(h('li', undefined, gloss));
    block.append(list);
    block.style.setProperty('--i', String(i));
    return block;
  };
  let caption: string;
  if (state.app !== undefined) {
    panel.append(entry('The app’s dictionaries', GLOSSES[0], 0));
    caption = 'Words are looked up in the app’s dictionaries, in the order set there.';
  } else {
    const on = (state.dictionaries ?? []).filter((d) => d.enabled);
    const terms = on.filter((d) => d.terms > 0);
    const counts = on.filter((d) => d.terms === 0 && d.meta > 0);
    if (counts.length) {
      const tags = h('div', 'pv-tags');
      for (const d of counts) tags.append(keyed(h('span', 'pv-chip pv-freq', d.title), `freq:${d.title}`));
      head.append(tags);
    }
    if (terms.length) {
      terms.forEach((d, i) => panel.append(entry(d.title, GLOSSES[i % GLOSSES.length], i)));
      caption = 'A word’s meanings come from each dictionary in turn, top first. Ones turned off are left out.';
    } else {
      // the extension has no dictionary of its own: without one on, a lookup finds nothing, as in the player
      head.replaceWith(h('div', 'pv-empty', 'No dictionaries yet. Add one in the extension’s options.'));
      caption = (state.dictionaries ?? []).some((d) => d.terms > 0)
        ? 'With every dictionary turned off, words aren’t looked up. Turn one on to have them back.'
        : 'Words are looked up once there’s a dictionary: download one, or import a Yomitan dictionary.';
    }
  }
  return { element: frame('A lookup', caption, panel) };
}

// ---- Playback: the player going from line to line -----------------------------------------------

const LINES = ['駅まで歩いて行こう', '今日はいい天気ですね', 'また明日'];

function playback(state: PreviewState): Preview {
  const { autoPause, pauseOnLookup, copyLines } = state.player;
  const video = h('div', 'pv-video pv-scene pv-playing');
  const scene = sceneElement();
  video.append(scene);
  const sub = h('div', 'pv-sub');
  sub.lang = 'ja';
  const badge = h('div', 'pv-badge');
  const copied = h('div', 'pv-toast', 'Copied');
  const lookup = h('div', 'pv-bubble');
  lookup.append(h('b', undefined, '天気'), ' weather');
  video.append(sub, badge, copied, lookup);
  const track = h('div', 'pv-track');
  const head = h('i', 'pv-head-dot');
  LINES.forEach((_, i) => {
    const seg = h('span', 'pv-seg');
    seg.style.left = `${6 + i * 32}%`;
    track.append(seg);
  });
  track.append(head);
  const stage = h('div', 'pv-player-mini');
  stage.append(video, track);

  // a loop: each line plays, then the player stops at its end or goes on; on the second a word's
  // looked up, pausing the player or not
  let timer = 0;
  let line = 0;
  let at = 0;
  const step = 50;
  const LINE_MS = 1600;
  const PAUSE_MS = 1100;
  /** Left of a pause, and whether the next line comes after it (a stop at a line's end). */
  let paused = 0;
  let nextAfter = false;
  /** Left of a lookup's bubble, while the player goes on under it. */
  let looking = 0;
  const show = () => {
    sub.textContent = '';
    if (line === 1 && (looking > 0 || (paused > 0 && !nextAfter))) sub.append('今日はいい', h('span', 'pv-word', '天気'), 'ですね');
    else sub.textContent = LINES[line];
    lookup.classList.toggle('on', line === 1 && (looking > 0 || (paused > 0 && !nextAfter)));
    badge.classList.toggle('on', paused > 0);
    // a paused player's picture stands still
    scene.classList.toggle('ks-paused', paused > 0);
    head.style.left = `${6 + line * 32 + (Math.min(at, LINE_MS) / LINE_MS) * 26}%`;
  };
  const tick = () => {
    if (looking > 0) looking -= step;
    if (paused > 0) {
      paused -= step;
      if (paused <= 0 && nextAfter) {
        nextAfter = false;
        at = 0;
        next();
      }
    } else {
      at += step;
      // halfway through the second line, a word's looked up
      if (line === 1 && at === LINE_MS / 2) {
        if (pauseOnLookup) paused = PAUSE_MS;
        else looking = PAUSE_MS;
      }
      if (at >= LINE_MS) {
        if (autoPause) {
          paused = PAUSE_MS;
          nextAfter = true;
        } else {
          at = 0;
          next();
        }
      }
    }
    show();
  };
  const next = () => {
    line = (line + 1) % LINES.length;
    if (copyLines) {
      copied.classList.remove('on');
      void copied.offsetWidth;
      copied.classList.add('on');
    }
  };
  show();
  timer = window.setInterval(tick, step);

  const said = [
    autoPause ? 'It stops at the end of each line' : 'It plays straight on from line to line',
    pauseOnLookup ? 'pauses while a word’s looked up' : 'keeps playing while a word’s looked up',
    copyLines ? 'and copies each line as it comes.' : 'and leaves the clipboard alone.',
  ];
  return {
    element: frame('Playing', `${said[0]}, ${said[1]}, ${said[2]}`, stage),
    stop: () => clearInterval(timer),
  };
}

// ---- Appearance: the player, in its theme ------------------------------------------------------

function appearance(state: PreviewState): Preview {
  const player = keyed(h('div', state.rounded ? 'pv-player framed' : 'pv-player'), 'player');
  const area = h('div', 'pv-area');
  const picture = keyed(h('div', state.fill ? 'pv-picture pv-scene filled' : 'pv-picture pv-scene'), 'picture');
  picture.append(sceneElement());
  area.append(picture);
  const side = h('div', 'pv-side');
  const top = h('div', 'pv-side-top');
  const line = h('div', 'pv-side-line', LINE);
  line.lang = 'ja';
  // the panel's line, at the size set, drawn at the scale the rest of the picture is
  line.style.fontSize = `${Math.round(state.player.lineSize * 0.34 * 10) / 10}px`;
  top.append(line);
  const body = h('div', 'pv-side-body');
  const head = h('div', 'pv-head small');
  head.lang = 'ja';
  head.append(h('b', undefined, '天気'), h('span', undefined, 'てんき'));
  body.append(head, h('i', 'pv-bone'), h('i', 'pv-bone short'), h('i', 'pv-bone'));
  side.append(top, body);
  const bar = h('div', 'pv-controls');
  bar.append(h('i', 'pv-play'), h('i', 'pv-progress'));
  player.append(area, side, bar);
  return {
    element: frame('The player', [
      state.fill ? 'The picture fills its area, its edges cropped' : 'The whole picture shows, with bars where it’s wider',
      state.rounded ? 'framed with rounded corners.' : 'edge to edge.',
    ].join(', '), player),
  };
}

// ---- Anki: a card, as it'll be made ------------------------------------------------------------

function ankiCard(state: PreviewState): Preview {
  const { settings, anki } = state;
  const own = state.app === undefined;
  const on = settings.anki && own ? !!anki?.connected : settings.anki;
  const card = h('div', on ? 'pv-anki' : 'pv-anki off');
  const where = h('div', 'pv-where');
  where.append(
    h('span', undefined, settings.deck || 'No deck yet'), h('span', 'pv-sep', '›'),
    h('span', undefined, settings.noteType || 'No note type yet'),
  );
  // the note type's own look once AnkiConnect's said what it is, else Anki's plain one
  const real = own && anki?.look?.templates.length ? anki.look : null;
  const look = real ?? PLAIN_LOOK;
  const faces = h('div', 'pv-card-sides');
  for (const side of sides(look, { Tags: settings.tags, Deck: settings.deck, Type: settings.noteType }, { dark: isDark(), animated: own && settings.imageAnimated })) {
    const face = h('div', 'pv-card-face');
    face.append(h('div', 'pv-card-label', side.name), sideFrame(side));
    faces.append(face);
  }
  const tags = h('div', 'pv-tags');
  for (const tag of settings.tags.split(/\s+/).filter(Boolean)) tags.append(h('span', 'pv-chip', tag));
  card.append(where, faces);
  if (tags.childElementCount) card.append(tags);
  let caption: string;
  if (!own) caption = 'Cards are made by the app, as its own Anki settings say.';
  else if (!settings.anki) caption = 'Anki cards are off.';
  else if (!anki?.connected) caption = 'Anki’s plain look, for now. Once AnkiConnect answers, the card’s drawn as the note type has it.';
  else if (!real) caption = 'Choose a note type, and the card’s drawn as it has it.';
  else {
    const more = real.templates.length > 1 ? ` The first of its ${real.templates.length} cards.` : '';
    const scripted = real.templates[0] && /<script/i.test(real.templates[0].front + real.templates[0].back);
    const scripts = scripted && !canRunScripts() ? ' Its scripts don’t run in this browser’s preview.' : '';
    caption = `As Anki shows it, with ${settings.noteType}’s own look.${more}${scripts} `
      + (settings.warnDuplicates ? 'If the deck has the word already, it asks first.' : 'It’s made even if the deck has the word already.');
  }
  return { element: frame('A card', caption, card) };
}

/** Whether the page is drawn dark, for Anki's night mode. */
function isDark(): boolean {
  const [r, g, b] = (getComputedStyle(document.body).backgroundColor.match(/\d+/g) ?? ['255', '255', '255']).map(Number);
  return 0.299 * r + 0.587 * g + 0.114 * b < 128;
}

// ---- Keyboard: the player's keys ---------------------------------------------------------------

const ROWS = [
  ['Escape', 'Q', 'W', 'E', 'R', 'T', 'Y', 'U', 'I', 'O', 'P'],
  ['A', 'S', 'D', 'F', 'G', 'H', 'J', 'K', 'L', ';'],
  ['Z', 'X', 'C', 'V', 'B', 'N', 'M', ',', '.', '/'],
];

function keyboard(state: PreviewState): Preview {
  // what each key does, and the row each action's on
  const does = new Map<string, { title: string; cap: string }[]>();
  const add = (key: string, title: string, cap: string) => does.set(key, [...(does.get(key) ?? []), { title, cap }]);
  for (const { name, label } of state.actions) {
    for (const binding of state.player.keys[name] ?? []) add(binding.split('+').pop() || '+', label, capLabel(binding));
  }
  for (const { caps, action } of state.fixed) {
    for (const cap of caps) add(cap === 'Esc' ? 'Escape' : cap, action, cap);
  }

  const board = h('div', 'pv-keyboard');
  const keys = new Map<string, HTMLElement>();
  const cap = (key: string, wide = '') => {
    const el = h('div', `pv-key${wide}`, key === 'Escape' ? 'esc' : key === 'Space' ? '' : capLabel(key));
    const jobs = does.get(key);
    if (jobs) {
      el.classList.add('bound');
      if (jobs.every((j) => j.cap.length > 1 && /[⇧⌃⌥⌘]|Shift|Ctrl|Alt/.test(j.cap))) el.classList.add('with-modifier');
    }
    el.addEventListener('mouseenter', () => say(key));
    el.addEventListener('mouseleave', () => say(null));
    keys.set(key, el);
    return el;
  };
  ROWS.forEach((row, i) => {
    const line = h('div', 'pv-keys');
    line.style.paddingLeft = `${[0, 14, 28][i]}px`;
    line.append(...row.map((key) => cap(key)));
    board.append(line);
  });
  const bottom = h('div', 'pv-keys pv-bottom');
  const arrows = h('div', 'pv-arrows');
  const upRow = h('div', 'pv-keys');
  upRow.append(cap('Up'));
  const downRow = h('div', 'pv-keys');
  downRow.append(cap('Left'), cap('Down'), cap('Right'));
  arrows.append(upRow, downRow);
  bottom.append(cap('Space', ' space'), arrows);
  board.append(bottom);

  const caption = h('div', 'pv-caption pv-says');
  const idle = 'Keys lit do something in the player. Point at one, or at a row, to see which.';
  caption.textContent = idle;
  const say = (key: string | null) => {
    for (const el of keys.values()) el.classList.remove('lit');
    document.querySelectorAll('.row.key.pv-lit').forEach((r) => r.classList.remove('pv-lit'));
    if (!key) {
      caption.textContent = idle;
      return;
    }
    keys.get(key)?.classList.add('lit');
    const jobs = does.get(key);
    caption.textContent = jobs ? jobs.map((j) => `${j.cap}: ${j.title}`).join(' · ') : `${capLabel(key)} isn’t used`;
    // and the rows those are, in the pane
    for (const job of jobs ?? []) {
      document.querySelector(`.row.key[data-k="r:${CSS.escape(job.title)}"]`)?.classList.add('pv-lit');
    }
  };
  const point = (title: string | null) => {
    for (const el of keys.values()) el.classList.remove('lit');
    if (!title) {
      caption.textContent = idle;
      return;
    }
    const lit: string[] = [];
    for (const [key, jobs] of does) {
      const job = jobs.find((j) => j.title === title);
      if (job) {
        keys.get(key)?.classList.add('lit');
        lit.push(job.cap);
      }
    }
    caption.textContent = lit.length ? `${lit.join(' or ')}: ${title}` : `${title}: no key yet`;
  };

  const block = frame('The keys', null, board);
  block.append(caption);
  return { element: block, point };
}

function append(parent: HTMLElement, ...children: HTMLElement[]): HTMLElement {
  parent.append(...children);
  return parent;
}

/** The pane's preview, if it has one. */
export function preview(state: PreviewState): Preview | null {
  switch (state.pane) {
    case 'General': return general(state);
    case 'Dictionaries': return dictionaries(state);
    case 'Playback': return playback(state);
    case 'Appearance': return appearance(state);
    case 'Anki': return ankiCard(state);
    case 'Keyboard': return keyboard(state);
    default: return null;
  }
}
