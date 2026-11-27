// The app's study panel (PlayerScreen.kt's StudyPanel), a card down the right of the window beside
// the site's video: the way back and the track menu along the top, the line big enough to read and
// tap, the dictionary for the word picked in it (each line comes with one picked already), and the
// line buttons and the stop at a line's end at the bottom. Its left edge drags to make it wider.
import { Entry } from '../shared';
import { addHints, ICONS, LineStop, STOP_HINTS } from './controls';
import { LookupMessages, renderLookup, Shown } from './lookup-view';
import { ease, EASE, MOTION } from './motion';
import { h } from '../dom';

export interface PanelEvents {
  /** A character of the line was clicked. */
  point(index: number): void;
  select(start: number, end: number): void;
  /** The line is held, to peek at its translation, or let go. */
  hold(on: boolean): void;
  previousLine(): void;
  replayLine(): void;
  nextLine(): void;
  toggleStop(): void;
  add(entry: Entry): void;
  remove(entry: Entry): void;
  /** The track menu, from [anchor]. */
  trackMenu(anchor: HTMLElement): void;
  /** Its edge was dragged and let go: how much of the window it takes now. */
  resized(fraction: number): void;
}

export interface PanelLine {
  /** The line's text, or null before the first. */
  text: string | null;
  /** What to say in its place: no subtitles yet, say. */
  status: string | null;
  /** Playing now, rather than the last one, which shows fainter. */
  active: boolean;
  highlight: [number, number] | null;
  peeking: boolean;
  /** The line in the peek language, or what to say while peeking without one. */
  translation: string | null;
  noTranslation: string;
  /** The app's subtitle size, which the line shrinks from to fit. */
  size: number;
}

export const DEFAULT_FRACTION = 0.36;
/**
 * The app's cards (DesktopTheme.kt's Corners, off a Mac's window): the video and the panel each
 * stand this far in from the window's edge and from each other, with rounded corners.
 */
export const CARD_GAP = 6;
export const CARD_RADIUS = 12;
const MIN_FRACTION = 0.2;
const MAX_FRACTION = 0.6;
/** As Compose's long press. */
const HOLD_MS = 500;

const MESSAGES: LookupMessages = {
  noDictionaries: 'No dictionaries yet. Import a Yomitan dictionary in Mizune, under Settings › Dictionaries.',
  noLocalDictionaries: 'No dictionaries yet. Add one in the extension’s options.',
  nothingFound: 'Nothing to look up here. Click or drag across a word to try another spot.',
};

export const clampFraction = (f: number) => Math.min(MAX_FRACTION, Math.max(MIN_FRACTION, Number.isFinite(f) ? f : DEFAULT_FRACTION));

export class Panel {
  readonly element = h('div', 'panel');
  private grip = h('div', 'grip');
  private offset = h('span', 'offset');
  private anki = h('span', 'anki');
  private ankiTimer = 0;
  private cog = h('button', 'icon cog');
  private current = h('div', 'current');
  private stack = h('div', 'lines');
  private ja = h('div', 'ja');
  private en = h('div', 'en');
  private status = h('div', 'status');
  private dict = h('div', 'dict');
  private previous = h('button', 'icon');
  private replay = h('button', 'icon');
  private next = h('button', 'icon');
  private stop = h('button', 'icon stop');
  private hint = h('div', 'hint');
  private fraction = DEFAULT_FRACTION;
  private chars: HTMLElement[] = [];
  private text: string | null = null;
  private fitted = '';
  private anchor = -1;
  private dragged = false;
  private holding = false;
  private holdTimer = 0;

  constructor(private events: PanelEvents) {
    this.cog.innerHTML = ICONS.cog();
    this.cog.dataset.hint = 'Audio, subtitles and picture';
    this.cog.addEventListener('click', () => this.events.trackMenu(this.cog));
    this.stack.append(this.ja, this.en);
    this.current.append(this.stack, this.status);
    const footer = h('div', 'footer');
    this.previous.innerHTML = ICONS.lineSkip(false);
    this.replay.innerHTML = ICONS.replay();
    this.next.innerHTML = ICONS.lineSkip(true);
    this.stop.innerHTML = ICONS.lineStop();
    // then how adding a card went and the subtitle offset, if it isn't nothing; and the track menu at
    // the far end, in line with the video's own controls
    const notes = h('div', 'notes');
    notes.append(this.anki, this.offset);
    footer.append(this.previous, this.replay, this.next, this.stop, notes, this.cog, this.hint);
    // a card inset from the window's edge, as the app's; the grip is the gap beside it
    const card = h('div', 'sheet');
    card.append(this.current, h('div', 'divider'), this.dict, h('div', 'divider'), footer);
    this.element.append(this.grip, card);
    this.element.hidden = true;
    addHints([this.previous, this.replay, this.next, this.stop, this.cog], this.hint, footer);

    this.previous.addEventListener('click', () => this.events.previousLine());
    this.replay.addEventListener('click', () => this.events.replayLine());
    this.next.addEventListener('click', () => this.events.nextLine());
    this.stop.addEventListener('click', () => this.events.toggleStop());
    // the page's player doesn't hear what happens in here
    for (const type of ['pointerdown', 'mousedown', 'mouseup', 'click', 'dblclick', 'wheel', 'contextmenu']) {
      this.element.addEventListener(type, (e) => e.stopPropagation());
    }
    // nor do its buttons keep the focus, which Space would press again
    this.element.addEventListener('mousedown', (e) => {
      if ((e.target as HTMLElement).closest?.('button')) e.preventDefault();
    });
    this.lineEvents();
    this.gripEvents();
  }

  // ---- placement ----------------------------------------------------------------------------

  private open = false;
  /** How far out it is, 0 to 1, as it slides. */
  private out = 0;
  private slideFrom = 0;
  private slideStart = 0;

  /** Slides it out or away, as the app's (or at once, the first time and when Mizune stops). */
  show(on: boolean, slide = true) {
    if (on === this.open) return;
    this.open = on;
    this.slideFrom = this.out;
    this.slideStart = slide ? performance.now() : -Infinity;
    this.step();
  }

  get shown() {
    return this.open;
  }

  /** Moves it on with the slide; every frame. */
  step() {
    const t = Math.min(1, (performance.now() - this.slideStart) / MOTION.panel);
    const target = this.open ? 1 : 0;
    this.out = this.slideFrom + (target - this.slideFrom) * ease(t);
    this.element.hidden = this.out === 0;
    this.element.style.transform = this.out === 1 ? '' : `translateX(${((1 - this.out) * 100).toFixed(2)}%)`;
  }

  /** How much of the window's width it takes, in pixels, as it slides; 0 while it's put away. */
  get width() {
    return Math.round(innerWidth * this.fraction * this.out);
  }

  /** As the app's Rounded corners: a card on the gutter, or flat against the video with a hairline between. */
  setRounded(on: boolean) {
    this.element.classList.toggle('flat', !on);
  }

  setFraction(fraction: number) {
    this.fraction = clampFraction(fraction);
    this.element.style.width = `${this.fraction * 100}vw`;
  }

  private gripEvents() {
    let dragging = false;
    this.grip.addEventListener('pointerdown', (e) => {
      dragging = true;
      this.grip.setPointerCapture(e.pointerId);
      this.element.classList.add('resizing');
    });
    this.grip.addEventListener('pointermove', (e) => {
      if (dragging) this.setFraction((innerWidth - e.clientX) / innerWidth);
    });
    const end = () => {
      if (!dragging) return;
      dragging = false;
      this.element.classList.remove('resizing');
      this.events.resized(this.fraction);
    };
    this.grip.addEventListener('pointerup', end);
    this.grip.addEventListener('pointercancel', end);
  }

  // ---- the footer ---------------------------------------------------------------------------

  setOffset(offset: number) {
    this.offset.textContent = offset ? `${offset > 0 ? '+' : '-'}${Math.abs(offset).toFixed(1)} s` : '';
  }

  /**
   * What adding a card is doing, or how it went, as the app says it; done or failed, it
   * fades out a few seconds after.
   */
  setStatus(text: string, state: 'working' | 'done' | 'failed') {
    clearTimeout(this.ankiTimer);
    this.anki.textContent = text;
    this.anki.title = text;
    this.anki.classList.toggle('failed', state === 'failed');
    this.anki.classList.add('shown');
    if (state !== 'working') this.ankiTimer = window.setTimeout(() => this.anki.classList.remove('shown'), 4000);
  }

  clearStatus() {
    clearTimeout(this.ankiTimer);
    this.anki.classList.remove('shown');
  }

  setFooter(stop: LineStop, keys: Record<string, string[]>) {
    const lineKeys = (move: string, stopKey: string) => {
      const k = keys[move]?.[0], s = keys[stopKey]?.[0];
      const stopping = s ? `${s} to stop at its end` : undefined;
      return k && stopping ? `${k}, or ${stopping}` : k ?? stopping ?? '';
    };
    this.previous.dataset.hint = 'Previous line';
    this.previous.dataset.key = lineKeys('PreviousLine', 'PreviousLineStop');
    this.replay.dataset.hint = 'Replay this line';
    this.replay.dataset.key = keys.ReplayLine?.[0] ?? '';
    this.next.dataset.hint = 'Next line';
    this.next.dataset.key = lineKeys('NextLine', 'NextLineStop');
    const className = `icon stop ${stop}`;
    if (this.stop.className !== className) this.stop.className = className;
    this.stop.dataset.hint = STOP_HINTS[stop];
    this.stop.dataset.key = keys.StopEveryLine?.[0] ?? '';
  }

  // ---- the line -----------------------------------------------------------------------------

  setLine(line: PanelLine) {
    this.status.hidden = line.status == null && line.text != null;
    this.stack.hidden = !this.status.hidden;
    if (!this.status.hidden) {
      this.status.textContent = line.status ?? '…';
      this.text = null;
      return;
    }
    if (line.text !== this.text) {
      this.text = line.text;
      this.chars = [...line.text!].map((ch, i) => {
        if (ch === '\n') return h('br') as unknown as HTMLElement;
        const span = h('span', 'c', ch);
        span.dataset.i = String(i);
        return span;
      });
      this.ja.replaceChildren(...this.chars);
    }
    const highlight = line.highlight;
    this.chars.forEach((c, i) => {
      const lit = !!highlight && i >= highlight[0] && i < highlight[1];
      c.classList.toggle('hl', lit);
      c.classList.toggle('first', lit && i === highlight![0]);
      c.classList.toggle('last', lit && i === highlight![1] - 1);
    });
    this.ja.classList.toggle('faint', !line.active);
    // both are laid out all the time, so the box fits the bigger and peeking only fades between them
    const english = line.translation ?? (line.peeking ? line.noTranslation : '');
    if (this.en.textContent !== english) this.en.textContent = english;
    this.en.classList.toggle('none', line.translation == null);
    this.stack.classList.toggle('peeking', line.peeking);
    this.fit(line.size);
  }

  /** The app's size, smaller until it fits in its share of the panel (as TextAutoSize). */
  private fit(size: number) {
    const key = `${this.text}|${this.en.textContent}|${size}|${this.element.clientWidth}|${this.element.clientHeight}`;
    if (key === this.fitted) return;
    this.fitted = key;
    const room = this.element.clientHeight * 0.4 - 24;
    for (let px = size; px >= 14; px--) {
      this.ja.style.fontSize = `${px}px`;
      if (this.stack.scrollHeight <= room) break;
    }
  }

  private charAt(e: PointerEvent): number {
    const root = this.element.getRootNode() as ShadowRoot;
    const target = root.elementFromPoint?.(e.clientX, e.clientY) as HTMLElement | null;
    const index = target?.dataset?.i ?? (e.target as HTMLElement)?.dataset?.i;
    return index == null ? -1 : Number(index);
  }

  /** As the app's TappableText: a click looks up, a drag picks a span, a hold peeks. */
  private lineEvents() {
    const box = this.current;
    box.addEventListener('pointerdown', (e) => {
      if (this.stack.hidden || e.button !== 0) return;
      this.anchor = this.charAt(e);
      this.dragged = false;
      box.setPointerCapture(e.pointerId);
      clearTimeout(this.holdTimer);
      this.holdTimer = window.setTimeout(() => {
        this.holding = true;
        this.events.hold(true);
      }, HOLD_MS);
    });
    box.addEventListener('pointermove', (e) => {
      if (this.anchor < 0 || this.holding) return;
      const index = this.charAt(e);
      if (index >= 0 && index !== this.anchor) {
        this.dragged = true;
        clearTimeout(this.holdTimer);
      }
      const [a, b] = [Math.min(this.anchor, index), Math.max(this.anchor, index)];
      this.chars.forEach((c, i) => c.classList.toggle('sel', this.dragged && index >= 0 && i >= a && i <= b));
    });
    const end = (e: PointerEvent, cancelled: boolean) => {
      clearTimeout(this.holdTimer);
      const from = this.anchor;
      this.anchor = -1;
      this.chars.forEach((c) => c.classList.remove('sel'));
      if (this.holding) {
        this.holding = false;
        this.events.hold(false);
        return;
      }
      if (cancelled || from < 0) return;
      const to = this.charAt(e);
      if (this.dragged && to >= 0 && to !== from) this.events.select(Math.min(from, to), Math.max(from, to) + 1);
      else this.events.point(from);
    };
    box.addEventListener('pointerup', (e) => end(e, false));
    box.addEventListener('pointercancel', (e) => end(e, true));
  }

  // ---- the dictionary -----------------------------------------------------------------------

  showLookup(result: Shown, isAdded: (entry: Entry) => boolean, cardsOn: boolean, busy: boolean) {
    renderLookup(this.dict, result, MESSAGES, isAdded, cardsOn, busy, this.events);
  }

  clearLookup() {
    this.dict.replaceChildren();
    this.dict.scrollTop = 0;
  }
}

export const PANEL_STYLES = `
.panel {
  position: fixed; top: 0; right: 0; bottom: 0; width: 36vw; z-index: 2147483646;
  /* the gap on its left is the video card's, which stands in from its own edge */
  display: flex; padding: ${CARD_GAP}px ${CARD_GAP}px ${CARD_GAP}px 0; background: var(--gutter); color: var(--onSurface);
  font-family: "Hiragino Sans", "Hiragino Kaku Gothic ProN", "Noto Sans JP", "Yu Gothic UI", Meiryo, system-ui, sans-serif;
  -webkit-font-smoothing: antialiased;
}
/* the app's card beside the video's (PlayerScreen.kt): the theme's surface, rounded, on the gutter */
.panel > .sheet { flex: 1; min-width: 0; display: flex; flex-direction: column; border-radius: ${CARD_RADIUS}px; overflow: hidden; background: var(--surface); }
/* without rounded corners, as the app's: against the video, a hairline between */
.panel.flat { padding: 0; background: var(--surface); box-shadow: inset 1px 0 0 color-mix(in srgb, var(--onSurface) 14%, transparent); }
.panel.flat > .sheet { border-radius: 0; background: transparent; }
.panel[hidden] { display: none; }
.panel.resizing, .panel.resizing * { cursor: ew-resize !important; user-select: none; }
.panel .grip { position: absolute; left: -3px; top: 0; bottom: 0; width: 12px; cursor: ew-resize; z-index: 1; }
.panel .divider { height: 1px; flex: none; background: color-mix(in srgb, var(--onSurface) 14%, transparent); }
.panel button {
  border: none; background: transparent; color: var(--onSurfaceVariant); cursor: pointer; border-radius: 6px;
  font: inherit; display: flex; align-items: center; justify-content: center; flex: none; padding: 5px;
}
.panel button:hover { color: var(--onSurface); background: color-mix(in srgb, var(--onSurface) 8%, transparent); }

/* the line, big; the one before the next shows fainter */
.panel .current {
  flex: none; min-height: 120px; max-height: 40%; overflow: hidden; padding: 12px 16px;
  display: flex; align-items: center; justify-content: center; user-select: none; cursor: default;
}
.panel .lines { display: grid; width: 100%; }
.panel .lines[hidden], .panel .status[hidden] { display: none; }
.panel .lines > * { grid-area: 1 / 1; align-self: center; text-align: center; transition: opacity 0.2s ${EASE}; }
.panel .ja { line-height: 1.35; white-space: pre-wrap; overflow-wrap: anywhere; }
.panel .ja.faint { color: color-mix(in srgb, var(--onSurface) 75%, transparent); }
.panel .en { font-size: 17px; line-height: 1.3; opacity: 0; pointer-events: none; white-space: pre-wrap; }
.panel .en.none { color: var(--onSurfaceVariant); font-size: 15px; }
.panel .lines.peeking .ja { opacity: 0; }
.panel .lines.peeking .en { opacity: 1; }
.panel .status { color: var(--onSurfaceVariant); text-align: center; font-size: 14px; line-height: 1.5; padding: 8px; }
.panel .c { border-radius: 0; padding: 1px 0; }
.panel .c.hl { background: color-mix(in srgb, var(--primary) 30%, transparent); }
.panel .c.sel { background: color-mix(in srgb, var(--tertiary) 34%, transparent); }
.panel .c.first { border-top-left-radius: 8px; border-bottom-left-radius: 8px; padding-left: 5px; margin-left: -5px; }
.panel .c.last { border-top-right-radius: 8px; border-bottom-right-radius: 8px; padding-right: 5px; margin-right: -5px; }

/* the dictionary, as the popup's */
.panel .dict {
  flex: 1; min-height: 0; overflow-y: auto; overscroll-behavior: contain; scrollbar-width: none;
}
.panel .dict::-webkit-scrollbar { display: none; }

/* the line buttons, and the stop at a line's end */
.panel .footer { position: relative; flex: none; display: flex; align-items: center; padding: 6px 8px; }
.panel .footer .notes {
  flex: 0 1 auto; min-width: 0; margin-left: auto; display: flex; align-items: center; gap: 8px; padding: 0 8px;
  font: 500 12px/1.2 system-ui, -apple-system, "Segoe UI", sans-serif;
}
.panel .footer .offset { flex: none; color: var(--tertiary); }
.panel .footer .anki {
  flex: 0 1 auto; min-width: 0; color: var(--onSurfaceVariant); white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  opacity: 0; transition: opacity 0.22s ${EASE};
}
.panel .footer .anki.shown { opacity: 1; }
.panel .footer .anki.failed { color: var(--error); }

.panel button.stop.every { color: var(--primary); background: color-mix(in srgb, var(--primary) 16%, transparent); }
.panel button.stop.this { color: var(--primary); }
.panel button.stop.held { color: color-mix(in srgb, var(--primary) 50%, transparent); }
.panel button.stop.every:hover, .panel button.stop.this:hover, .panel button.stop.held:hover { color: var(--primary); }
.panel .hint {
  position: absolute; bottom: calc(100% + 2px); padding: 5px 8px; border-radius: 6px; white-space: nowrap; pointer-events: none;
  background: var(--onSurface); color: var(--surface); box-shadow: 0 4px 12px rgba(0, 0, 0, 0.35);
  font: 500 12px/1 system-ui, -apple-system, "Segoe UI", sans-serif;
}
.panel .hint[hidden] { display: none; }
.panel .hint .key { opacity: 0.65; white-space: pre; }
`;
