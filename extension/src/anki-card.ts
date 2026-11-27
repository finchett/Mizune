// A card as Anki shows it, for the settings' preview: the note type's own templates and CSS,
// filled with a sample word's fields, drawn in a frame of its own (card.ts) where its scripts run
// cut off from the extension.
// Only as much of Anki's template language as cards use: fields, sections, FrontSide, and the
// text, furigana, kana, kanji, hint and type filters.

import { sceneImage } from './scene';

export interface CardLook {
  /** The note type's cards, in Anki's order. */
  templates: { name: string; front: string; back: string }[];
  css: string;
  /** Each field's HTML, as a card of the sample word would have it. */
  fields: Record<string, string>;
}

/** The media a sample card names (LocalEngine.kt), for which the preview puts in its own. */
const SAMPLE_AUDIO = 'mizune-sample.ogg';
const SAMPLE_IMAGE = 'mizune-sample.webp';

/** Anki's own look for a new note type ("Basic"), for while there's no note type to ask about. */
export const PLAIN_LOOK: CardLook = {
  templates: [{
    name: 'Card 1',
    front: '{{Sentence}}',
    back: '{{FrontSide}}\n\n<hr id=answer>\n\n{{furigana:Word}}<br>{{Meaning}}<br>{{Picture}}<br>{{Audio}}',
  }],
  css: '.card {\n  font-family: arial;\n  font-size: 20px;\n  text-align: center;\n  color: black;\n  background-color: white;\n}\n',
  fields: {
    Sentence: '今日はいい<b>天気</b>ですね',
    Word: '天気[てんき]',
    Meaning: 'weather; the elements',
    Picture: `<img alt="snapshot" src="${SAMPLE_IMAGE}">`,
    Audio: `[sound:${SAMPLE_AUDIO}]`,
  },
};

const stripHtml = (html: string) => html.replace(/<[^>]*>/g, '').replace(/&nbsp;/g, ' ');
const isEmpty = (html: string) => !stripHtml(html).trim() && !/<img/i.test(html);

// Anki's furigana: "天気[てんき]", a space ending the run before it
const RUBY = / ?([^ >]+?)\[(.+?)\]/g;

const FILTERS: Record<string, (text: string, field: string) => string> = {
  text: (text) => stripHtml(text),
  furigana: (text) => text.replace(RUBY, '<ruby><rb>$1</rb><rt>$2</rt></ruby>'),
  kana: (text) => text.replace(RUBY, '$2'),
  kanji: (text) => text.replace(RUBY, '$1'),
  // Anki's own markup, so the card's CSS takes it as it would there; its script is handleClicks
  hint: (text, field) => (text ? `<a class="hint" href="#">Show ${field}</a><div class="hint" style="display: none">${text}</div>` : ''),
  type: () => '<input type="text" id="typeans" disabled>',
  tts: () => '',
};

interface Note {
  fields: Record<string, string>;
  /** What Anki fills its own fields with: Tags, Deck, Type, Card. */
  own: Record<string, string>;
}

function fieldValue(name: string, note: Note): string {
  return note.fields[name] ?? note.own[name] ?? '';
}

/** [template] filled from [note], as Anki renders a side of a card. */
function render(template: string, note: Note, frontSide: string): string {
  let out = template;
  // sections, the outermost first; what's kept is gone over again
  const section = /\{\{([#^])\s*([^}]+?)\s*\}\}([\s\S]*?)\{\{\/\s*\2\s*\}\}/;
  for (let match = section.exec(out); match; match = section.exec(out)) {
    const filled = !isEmpty(fieldValue(match[2], note));
    const keep = match[1] === '#' ? filled : !filled;
    out = out.slice(0, match.index) + (keep ? match[3] : '') + out.slice(match.index + match[0].length);
  }
  return out.replace(/\{\{([^}]+)\}\}/g, (_, inside: string) => {
    const parts = inside.split(':').map((p) => p.trim());
    const name = parts.pop()!;
    if (name === 'FrontSide') return frontSide;
    // filters apply from the field outwards
    let value = fieldValue(name, note);
    for (const filter of parts.reverse()) value = (FILTERS[filter.split(' ')[0]] ?? ((text: string) => text))(value, name);
    return value;
  });
}

/** Anki's replay button, as its reviewer draws one for a sound. */
const REPLAY = '<span class="replay-button soundLink"><svg class="playImage" viewBox="0 0 64 64" version="1.1">'
  + '<circle cx="32" cy="32" r="29"></circle><path d="M56.502,32.301l-37.502,20.101l0.329,-40.804l37.173,20.703Z"></path></svg></span>';

/** Anki's reviewer around the card's own CSS: its margins, pictures that fit, and its replay buttons. */
const REVIEWER_CSS = `
html { overflow: hidden; }
body { margin: 0; padding: 20px; overflow-wrap: break-word; background-color: #fff; color: #000; }
body.nightMode { background-color: #2c2c2c; color: #fcfcfc; }
/* as Anki's dark mode has it: the browser's own colours, links and all, for a dark page */
:root:has(body.nightMode) { color-scheme: dark; }
img { max-width: 100%; height: auto; }
.replay-button { text-decoration: none; display: inline-flex; vertical-align: middle; margin: 3px; }
.replay-button svg { width: 40px; height: 40px; }
.replay-button svg circle { fill: #fff; stroke: #414141; }
.replay-button svg path { fill: #414141; }
.nightMode .replay-button svg circle { fill: #363636; stroke: #fcfcfc; }
.nightMode .replay-button svg path { fill: #fcfcfc; }
#typeans { width: 100%; box-sizing: border-box; font: inherit; }
`;

export interface Side { name: string; html: string }

/**
 * The first card's two sides, each a whole page: [look]'s templates over its fields, with the
 * sample's media made into a picture and a replay button.
 */
export function sides(look: CardLook, own: Record<string, string>, options: { dark: boolean; animated: boolean }): Side[] {
  const template = look.templates[0];
  if (!template) return [];
  const note: Note = { fields: look.fields, own: { ...own, Card: template.name } };
  const media = (html: string) => html
    .replace(/\[sound:[^\]]*\]/g, REPLAY)
    .replaceAll(SAMPLE_IMAGE, sceneImage(options.animated));
  const front = render(template.front, note, '');
  const back = render(template.back, note, front);
  const page = (html: string) => `<!doctype html><html><head><meta charset="utf-8"><style>${REVIEWER_CSS}</style><style>${look.css}</style></head>`
    + `<body class="card card1${options.dark ? ' nightMode night_mode' : ''}"><div id="qa">${media(html)}</div></body></html>`;
  return [{ name: 'Front', html: page(front) }, { name: 'Back', html: page(back) }];
}

/** A card's page: the body's own height, as the page's would never be less than its frame's. */
export function cardHeight(doc: Document): number {
  return Math.ceil(doc.body?.getBoundingClientRect().height ?? doc.documentElement.scrollHeight);
}

/**
 * Clicks in a card's page: a hint shows what it hides, as Anki's does, and hides it again on a
 * click to go back; a link never takes the frame anywhere, and one to a site goes to [open].
 */
export function handleClicks(doc: Document, open: (href: string) => void) {
  doc.addEventListener('click', (event) => {
    const target = event.target as Element | null;
    const hint = target?.closest?.('a.hint, div.hint') as HTMLElement | null;
    const pair = hint?.matches('a') ? hint.nextElementSibling : hint?.previousElementSibling;
    if (hint && pair?.classList.contains('hint')) {
      event.preventDefault();
      hint.style.display = 'none';
      (pair as HTMLElement).style.display = pair.matches('a') ? '' : 'block';
      return;
    }
    const link = target?.closest?.('a');
    if (!link) return;
    event.preventDefault();
    const href = link.getAttribute('href') ?? '';
    if (/^https?:\/\//i.test(href)) open(href);
  });
}

const openTab = (href: string) => {
  if (/^https?:\/\//i.test(href)) window.open(href, '_blank', 'noopener');
};

/** How tall each side came out, so a side drawn again starts at its height rather than jumping. */
const heights = new Map<string, number>();

/** The frames showing cards with their scripts, by the page in them, for what those pages say. */
const scripted = new Set<HTMLIFrameElement>();
let listening = false;

function listen() {
  if (listening) return;
  listening = true;
  window.addEventListener('message', (event) => {
    for (const frame of scripted) {
      if (!frame.isConnected) {
        scripted.delete(frame);
        continue;
      }
      if (event.source !== frame.contentWindow) continue;
      const data = event.data as { height?: unknown; open?: unknown };
      if (typeof data?.height === 'number') {
        heights.set(frame.dataset.card ?? '', data.height);
        sized.get(frame)?.(data.height);
      }
      if (typeof data?.open === 'string') openTab(data.open);
    }
  });
}

/** Chrome runs a card's scripts in a page of its own, cut off from the extension; Firefox can't. */
export const canRunScripts = () => {
  try {
    return !!(chrome.runtime.getManifest() as { sandbox?: { pages?: string[] } }).sandbox?.pages?.includes('card.html');
  } catch {
    return false;
  }
};

/** Each frame's way to take a new height, for what its page says. */
const sized = new WeakMap<HTMLIFrameElement, (height: number) => void>();

/**
 * How wide a card's laid out: about a phone's screen, as narrow as Anki's reviewer gets. With
 * less room than that a side is shown smaller, as a picture of it, rather than squeezed.
 */
const CARD_WIDTH = 420;

/**
 * A side in a frame of its own, as tall as what's in it, in a box that fits it to the room there
 * is. In Chrome the card's scripts run, as in Anki, in a sandboxed page that can't reach the
 * extension; elsewhere none of them do.
 */
export function sideFrame(side: Side): HTMLElement {
  const box = document.createElement('div');
  box.className = 'pv-card-side';
  const frame = document.createElement('iframe');
  frame.className = 'pv-card-page';
  frame.title = `The card's ${side.name.toLowerCase()}`;
  box.append(frame);

  let height = heights.get(side.html) ?? 120;
  const place = () => {
    const room = box.clientWidth;
    if (!room) return;
    const width = Math.max(room, CARD_WIDTH);
    const scale = room / width;
    frame.style.width = `${width}px`;
    frame.style.height = `${height}px`;
    frame.style.transform = scale < 1 ? `scale(${scale})` : '';
    box.style.height = `${Math.ceil(height * scale)}px`;
  };
  const setHeight = (to: number) => {
    height = to;
    place();
  };
  new ResizeObserver(place).observe(box);

  if (canRunScripts()) {
    listen();
    frame.dataset.card = side.html;
    frame.setAttribute('sandbox', 'allow-scripts');
    frame.src = 'card.html';
    scripted.add(frame);
    sized.set(frame, setHeight);
    frame.addEventListener('load', () => frame.contentWindow?.postMessage({ card: side.html }, '*'));
    return box;
  }
  // the page reaches in only to measure it; scripts in the templates stay off
  frame.setAttribute('sandbox', 'allow-same-origin');
  frame.srcdoc = side.html;
  const fit = () => {
    const doc = frame.contentDocument;
    if (!doc?.documentElement) return;
    const measured = cardHeight(doc);
    heights.set(side.html, measured);
    setHeight(measured);
  };
  frame.addEventListener('load', () => {
    fit();
    const doc = frame.contentDocument;
    if (!doc?.body) return;
    new ResizeObserver(fit).observe(doc.body);
    handleClicks(doc, openTab);
  });
  return box;
}
