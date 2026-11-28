// The app's first-play tutorial (desktop PlayerTutorial.kt, its pictures from shared-ui's
// TutorialPictures.kt): short cards over the paused video, each with a little looping picture of
// what to do. The browser's has one of its own: the ⚙ menu, for the site's subtitles. → or Enter moves on, ← goes back and Esc skips; while it's up no key
// reaches the player, and the video underneath takes no clicks.
import { CAP_LABELS, capLabel } from './keys';
import { ICONS } from './controls';
import { ease, EASE } from './motion';
import { h, svg } from '../dom';
import { origin, placingInPage } from './origin';
import { sceneElement, SCENE_STYLES } from '../scene';

export interface TutorialOptions {
  /** Key names as the app writes them ("Shift+Left", ";"), by player action. */
  keys: Record<string, string[]>;
  anki: boolean;
  /** Whether there's a study panel beside the video, or the meaning opens over it. */
  panel: boolean;
  light: boolean;
  /** The site's name, whose subtitles the ⚙ menu has. */
  site: string | null;
}

type Step = 'words' | 'lines' | 'peek' | 'menu' | 'playback';


/** How far [t] is from [from] to [to], eased, held at 0 before and 1 after. */
const part = (t: number, from: number, to: number) => ease(Math.min(1, Math.max(0, (t - from) / (to - from))));
/** Up over [from]–[peak], held, then back down over [fall]–[to]. */
const swell = (t: number, from: number, peak: number, fall: number, to: number) => part(t, from, peak) * (1 - part(t, fall, to));

/** A looping picture: its element, and how it looks [t] (0 to 1) of the way through its loop. */
interface Picture {
  element: HTMLElement;
  millis: number;
  frame(t: number): void;
}

/** A key seen from the front, which sinks and takes the accent colour while it's pressed. */
function demoKey(text: string, width = 46) {
  const key = h('div', 'demo-key');
  key.style.width = `${width}px`;
  key.append(h('div', 'side'), h('div', 'top', text));
  return { element: key, press: (on: boolean) => key.classList.toggle('pressed', on) };
}

/** An arrow pointer whose tip is at its top-left corner, with a ring spreading from a click. */
function pointer() {
  const arrowSvg = svg('svg', { width: 22, height: 26, viewBox: '0 0 22 26', class: 'pointer' });
  const ring = svg('circle', { cx: 0, cy: 0, r: 0, class: 'ring' });
  const arrow = 'M0 0 L0 17 L4.5 13 L7.5 20 L10.2 18.8 L7.2 12.2 L12.8 12.2 Z';
  arrowSvg.append(ring, svg('path', { d: arrow, class: 'arrow' }));
  return {
    element: arrowSvg,
    ripple(r: number) {
      const on = r > 0.01 && r < 0.99;
      ring.setAttribute('r', on ? String(6 + 16 * r) : '0');
      ring.style.opacity = on ? String(0.5 * (1 - r)) : '0';
    },
  };
}

/** 天気 is clicked, and its entry opens under the line. */
function words(): Picture {
  const box = h('div', 'pic words');
  const column = h('div', 'column');
  const line = h('div', 'demo-line');
  const parts = ['今日は', 'いい', '天気', 'ですね'].map((text) => h('span', '', text));
  line.append(...parts);
  const entry = h('div', 'demo-entry');
  const head = h('div', 'entry-head');
  head.append(h('span', 'word', '天気'), h('span', 'reading', 'てんき'));
  entry.append(head, h('div', 'meaning', 'weather; the elements'));
  column.append(line, entry);
  const arrow = pointer();
  box.append(column, arrow.element);
  const word = parts[2];
  return {
    element: box,
    millis: 4200,
    frame(t) {
      const shown = swell(t, 0.40, 0.48, 0.88, 0.96);
      word.style.background = `color-mix(in srgb, var(--primary) ${30 * shown}%, transparent)`;
      entry.style.opacity = String(shown);
      entry.style.transform = `translateY(${6 * (1 - shown)}px)`;
      // in from the lower right, onto the word, a click, and away
      const area = box.getBoundingClientRect();
      const w = word.getBoundingClientRect();
      if (!area.width) return;
      const from = { x: area.width * 0.86, y: area.height * 0.92 };
      const to = { x: w.left - area.left + w.width * 0.55, y: w.top - area.top + w.height * 0.7 };
      const k = part(t, 0.06, 0.36);
      const scale = 1 - 0.12 * swell(t, 0.37, 0.40, 0.42, 0.46);
      arrow.element.style.transform = `translate(${from.x + (to.x - from.x) * k}px, ${from.y + (to.y - from.y) * k}px) scale(${scale})`;
      arrow.element.style.opacity = String(swell(t, 0, 0.06, 0.9, 0.98));
      arrow.ripple(part(t, 0.38, 0.56));
    },
  };
}

const LINE_HEIGHT = 38;

/** The lines moving to the next and back again by → and ←, with ; replaying the one it's on. */
function lines(keys: Record<string, string[]>): Picture {
  const box = h('div', 'pic lines');
  const list = h('div', 'demo-lines');
  const current = h('div', 'current-line');
  const rows = ['おはよう！', '今日はいい天気ですね。', '散歩に行こうか。'].map((text) => h('div', 'demo-row', text));
  const replaying = h('div', 'replaying', '↻');
  list.append(current, ...rows, replaying);
  const label = (action: string, fallback: string) => {
    const first = keys[action]?.find((k) => !k.includes('+'));
    return first ? capLabel(first) : fallback;
  };
  // the arrows, as the app's picture, if they're still bound; otherwise the first key
  const pick = (action: string, arrow: string, fallback: string) => (keys[action]?.includes(arrow) ? CAP_LABELS[arrow] : label(action, fallback));
  const back = demoKey(pick('PreviousLine', 'Left', '←'));
  const replay = demoKey(keys.ReplayLine?.includes(';') ? ';' : label('ReplayLine', ';'));
  const next = demoKey(pick('NextLine', 'Right', '→'));
  const keysRow = h('div', 'demo-keys');
  keysRow.append(back.element, replay.element, next.element);
  box.append(list, keysRow);
  let shown = -1;
  return {
    element: box,
    millis: 4400,
    frame(t) {
      const index = t >= 0.20 && t <= 0.48 ? 2 : 1;
      if (index !== shown) {
        shown = index;
        rows.forEach((row, i) => row.classList.toggle('on', i === index));
        current.style.transform = `translateY(${LINE_HEIGHT * index}px)`;
        replaying.style.transform = `translateY(${LINE_HEIGHT * index + 8}px)`;
      }
      const flash = swell(t, 0.74, 0.77, 0.80, 0.92);
      current.style.background = `color-mix(in srgb, var(--primary) ${(16 + 20 * flash).toFixed(1)}%, transparent)`;
      replaying.style.opacity = String(flash);
      back.press(swell(t, 0.46, 0.48, 0.52, 0.55) > 0.5);
      replay.press(swell(t, 0.72, 0.74, 0.78, 0.81) > 0.5);
      next.press(swell(t, 0.18, 0.20, 0.24, 0.27) > 0.5);
    },
  };
}

/** Holding I brings the translation up under the line. */
function peek(keys: Record<string, string[]>): Picture {
  const box = h('div', 'pic peek');
  const column = h('div', 'column');
  const line = h('div', 'demo-peek-line', '今日はいい天気ですね。');
  const translation = h('div', 'demo-translation', 'Nice weather today, isn’t it?');
  const row = h('div', 'demo-hold');
  const key = demoKey(keys.Peek?.[0] ? capLabel(keys.Peek[0]) : 'I');
  const word = h('span', 'hold-word', 'hold');
  row.append(key.element, word);
  column.append(line, translation, row);
  box.append(column);
  return {
    element: box,
    millis: 3800,
    frame(t) {
      const held = t >= 0.22 && t <= 0.72;
      const shown = swell(t, 0.22, 0.30, 0.72, 0.80);
      translation.style.opacity = String(shown);
      translation.style.transform = `translateY(${-4 * (1 - shown)}px)`;
      key.press(held);
      word.textContent = held ? 'holding…' : 'hold';
      word.classList.toggle('held', held);
    },
  };
}

/** A little video: the summer scene, as in the app's tutorial, standing still while [paused]. */
function demoVideo(className: string, paused: boolean) {
  const tile = h('div', className);
  const scene = sceneElement();
  scene.classList.toggle('ks-paused', paused);
  tile.append(scene);
  return { tile, scene };
}

/** A little video, paused, with Mizune's bar along its foot: ▶, how far it's played, and [end] at its far end. */
function demoBar(end: HTMLElement) {
  const { tile } = demoVideo('demo-video wide', true);
  const bar = h('div', 'demo-bar');
  const track = h('div', 'progress');
  track.append(h('div', 'played'));
  bar.append(h('span', 'play', '▶'), track, end);
  tile.append(bar);
  return { tile, bar };
}

/** The pointer [k] of the way from [from] to [to], in [box]'s terms, and a little press at [pressed]. */
function pointAt(arrow: ReturnType<typeof pointer>, box: HTMLElement, from: { x: number; y: number }, to: HTMLElement, k: number, pressed: number) {
  const area = box.getBoundingClientRect();
  const r = to.getBoundingClientRect();
  if (!area.width) return null;
  const at = { x: r.left - area.left + r.width * 0.5, y: r.top - area.top + r.height * 0.6 };
  const start = { x: area.width * from.x, y: area.height * from.y };
  arrow.element.style.transform = `translate(${start.x + (at.x - start.x) * k}px, ${start.y + (at.y - start.y) * k}px) scale(${1 - 0.12 * pressed})`;
  return start;
}

/** ⚙ in the bar opens the menu, and another of the site's subtitles is chosen to study. */
function menu(): Picture {
  const box = h('div', 'pic menu-pic');
  const cog = h('span', 'cog');
  cog.innerHTML = ICONS.cog();
  const { tile } = demoBar(cog);
  const card = h('div', 'demo-menu');
  const choice = (text: string) => {
    const row = h('div', 'choice');
    row.append(h('span', '', text), h('span', 'tick', '✓'));
    return row;
  };
  const japanese = choice('Japanese');
  const cc = choice('Japanese [CC]');
  const english = choice('English');
  english.classList.add('on');
  card.append(h('div', 'group', 'Subtitles to study'), japanese, cc, h('div', 'group', 'Translation'), english);
  tile.append(card);
  const arrow = pointer();
  box.append(tile, arrow.element);
  return {
    element: box,
    millis: 5600,
    frame(t) {
      // onto ⚙ and a click; the menu opens; onto [CC] and a click; the menu goes
      const first = t < 0.5;
      const k = first ? part(t, 0.04, 0.24) : part(t, 0.36, 0.52);
      const press = first ? swell(t, 0.25, 0.27, 0.29, 0.32) : swell(t, 0.53, 0.55, 0.57, 0.60);
      if (first) pointAt(arrow, box, { x: 0.86, y: 0.95 }, cog, k, press);
      else {
        // from ⚙ on to [CC]
        const area = box.getBoundingClientRect(), c = cog.getBoundingClientRect(), r = cc.getBoundingClientRect();
        if (area.width) {
          const from = { x: c.left - area.left + c.width * 0.5, y: c.top - area.top + c.height * 0.6 };
          const to = { x: r.left - area.left + r.width * 0.42, y: r.top - area.top + r.height * 0.6 };
          arrow.element.style.transform = `translate(${from.x + (to.x - from.x) * k}px, ${from.y + (to.y - from.y) * k}px) scale(${1 - 0.12 * press})`;
        }
      }
      arrow.element.style.opacity = String(swell(t, 0, 0.04, 0.86, 0.94));
      arrow.ripple(first ? part(t, 0.27, 0.42) : part(t, 0.55, 0.7));
      const open = swell(t, 0.28, 0.34, 0.84, 0.9);
      card.style.opacity = String(open);
      card.style.transform = `scale(${0.85 + 0.15 * open})`;
      cog.classList.toggle('on', open > 0.5);
      const switched = t >= 0.56;
      japanese.classList.toggle('on', !switched);
      cc.classList.toggle('on', switched);
    },
  };
}

/** A little video that Space pauses and plays again. */
function playback(): Picture {
  const box = h('div', 'pic playback');
  const column = h('div', 'column');
  const { tile, scene } = demoVideo('demo-video', false);
  const sign = svg('svg', { width: 44, height: 44, viewBox: '0 0 44 44', class: 'sign' });
  // dark, so the sign shows up on the bright sky
  const disc = svg('circle', { cx: 22, cy: 22, r: 22, fill: '#000' });
  const bars = svg('g', { fill: '#fff' });
  bars.append(svg('rect', { x: 15, y: 13, width: 5, height: 18, rx: 1.5 }), svg('rect', { x: 24, y: 13, width: 5, height: 18, rx: 1.5 }));
  const triangle = svg('path', { d: 'M17 12 L32 22 L17 32 Z', fill: '#fff' });
  sign.append(disc, bars, triangle);
  const track = h('div', 'progress');
  const played = h('div', 'played');
  track.append(played);
  tile.append(sign, track);
  const space = demoKey('Space', 150);
  column.append(tile, space.element);
  box.append(column);
  let pop = 0, last = 0;
  return {
    element: box,
    millis: 4000,
    frame(t) {
      const playing = !(t >= 0.30 && t <= 0.70);
      const went = Math.min(t, 0.30) + Math.max(0, t - 0.70);
      const pressed = swell(t, 0.28, 0.30, 0.34, 0.37) > 0.5 || swell(t, 0.68, 0.70, 0.74, 0.77) > 0.5;
      // the sign pops while Space is down, over 180 ms as the app's
      const now = performance.now();
      const step = last ? (now - last) / 180 : 0;
      last = now;
      pop = pressed ? Math.min(1, pop + step) : Math.max(0, pop - step);
      const p = ease(pop);
      sign.style.transform = `scale(${1 + 0.12 * p})`;
      disc.setAttribute('fill-opacity', String(0.32 + 0.1 * p));
      scene.classList.toggle('ks-paused', !playing);
      bars.style.display = playing ? '' : 'none';
      triangle.style.display = playing ? 'none' : '';
      played.style.width = `${Math.min(1, 0.12 + went * 0.6) * 100}%`;
      space.press(pressed);
    },
  };
}

export class Tutorial {
  readonly element = h('div', 'tutorial');
  private card = h('div', 'tutorial-card');
  private stage = h('div', 'stage');
  private dots = h('div', 'dots');
  private count = h('span', 'count');
  private words = h('div', 'step-words');
  private skip = h('button', 'text-action', 'Skip');
  private back = h('button', 'text-action', '‹  Back');
  private next = h('button', 'next');
  private step = 0;
  private picture: Picture | null = null;
  private started = 0;
  private frameId = 0;
  private options: TutorialOptions | null = null;
  /** The cards there are: the site's own controls only where it has them. */
  private steps: Step[] = [];

  constructor(private onFinish: () => void) {
    this.element.hidden = true;
    const top = h('div', 'dots-row');
    top.append(this.dots, h('span', 'gap'), this.count);
    const body = h('div', 'tutorial-body');
    body.append(top, this.words);
    const actions = h('div', 'tutorial-actions');
    actions.append(this.skip, h('span', 'gap'), this.back, this.next);
    this.card.append(this.stage, body, actions);
    this.element.append(this.card);
    this.skip.addEventListener('click', () => this.finish());
    this.back.addEventListener('click', () => this.go(this.step - 1));
    this.next.addEventListener('click', () => this.forward());
    // the player underneath takes no clicks or hovers while it's up
    for (const type of ['pointerdown', 'pointerup', 'pointermove', 'mousedown', 'mouseup', 'mousemove', 'click', 'dblclick', 'wheel', 'contextmenu']) {
      this.element.addEventListener(type, (e) => e.stopPropagation());
    }
    // nothing in it keeps the focus, which Space would press again
    this.element.addEventListener('mousedown', (e) => e.preventDefault());
  }

  get isOpen() {
    return !this.element.hidden;
  }

  /** Over the whole window, from the overlay's corner (see origin); every frame while it's up. */
  place() {
    const s = this.element.style;
    if (!placingInPage()) {
      for (const prop of ['left', 'top', 'width', 'height']) s.removeProperty(prop);
      return;
    }
    const o = origin();
    s.left = `${-o.x}px`;
    s.top = `${-o.y}px`;
    // (the window less its scrollbars)
    s.width = `${document.documentElement.clientWidth}px`;
    s.height = `${document.documentElement.clientHeight}px`;
  }

  show(options: TutorialOptions) {
    this.options = options;
    this.steps = ['words', 'lines', 'peek', 'menu', 'playback'];
    this.dots.replaceChildren(...this.steps.map(() => h('span', 'dot')));
    this.element.classList.toggle('light', options.light);
    this.element.hidden = false;
    this.element.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 220, easing: EASE });
    this.step = -1;
    this.go(0);
    this.loop();
  }

  /** The keys while it's up: → or Enter on, ← back, Esc skips; none of them reach the player. */
  key(event: KeyboardEvent) {
    if (event.type === 'keydown' && !event.repeat) {
      if (event.code === 'ArrowRight' || event.code === 'Enter' || event.code === 'NumpadEnter') this.forward();
      else if (event.code === 'ArrowLeft') this.go(this.step - 1);
      else if (event.code === 'Escape') this.finish();
    }
    event.preventDefault();
    event.stopImmediatePropagation();
  }

  close() {
    if (!this.isOpen) return;
    cancelAnimationFrame(this.frameId);
    this.element.hidden = true;
    this.stage.replaceChildren();
    this.words.replaceChildren();
    this.picture = null;
  }

  private forward() {
    if (this.step === this.steps.length - 1) this.finish();
    else this.go(this.step + 1);
  }

  private finish() {
    this.close();
    this.onFinish();
  }

  private loop() {
    this.frameId = requestAnimationFrame(() => this.loop());
    const picture = this.picture;
    if (picture) picture.frame(((performance.now() - this.started) % picture.millis) / picture.millis);
  }

  private go(to: number) {
    if (to < 0 || to >= this.steps.length || to === this.step || !this.options) return;
    const forward = to > this.step;
    const first = this.step < 0;
    this.step = to;
    const step = this.steps[to];
    const last = to === this.steps.length - 1;
    [...this.dots.children].forEach((dot, i) => {
      dot.classList.toggle('current', i === to);
      dot.classList.toggle('reached', i <= to);
    });
    this.count.textContent = `${to + 1} of ${this.steps.length}`;
    this.skip.style.visibility = last ? 'hidden' : '';
    this.back.hidden = to === 0;
    this.next.textContent = last ? 'Start watching' : 'Next  →';

    // the picture: the old one fades away as the new one comes in
    const keys = this.options.keys;
    const picture = step === 'words' ? words() : step === 'lines' ? lines(keys) : step === 'peek' ? peek(keys)
      : step === 'menu' ? menu() : playback();
    const old = [...this.stage.children] as HTMLElement[];
    for (const el of old) el.animate([{ opacity: 1 }, { opacity: 0 }], { duration: first ? 0 : 160, fill: 'forwards' }).finished.then(() => el.remove(), () => el.remove());
    this.stage.append(picture.element);
    if (!first) picture.element.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 280, delay: 80, fill: 'backwards', easing: EASE });
    this.picture = picture;
    this.started = performance.now();
    picture.frame(0);

    // the words slide a little the way it's going
    const text = this.stepText(step);
    const sign = forward ? 1 : -1;
    const width = this.words.offsetWidth || 444;
    for (const el of [...this.words.children] as HTMLElement[]) {
      el.classList.add('leaving');
      el.animate([{ opacity: 1, transform: 'none' }, { opacity: 0, transform: `translateX(${-sign * width / 10}px)` }], { duration: 300, easing: EASE, fill: 'forwards' })
        .finished.then(() => el.remove(), () => el.remove());
    }
    this.words.append(text);
    if (!first) {
      text.animate([{ transform: `translateX(${sign * width / 10}px)` }, { transform: 'none' }], { duration: 300, easing: EASE });
      text.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 240, delay: 60, fill: 'backwards', easing: EASE });
    }
  }

  private stepText(step: Step): HTMLElement {
    const { keys, anki, panel, site } = this.options!;
    const caps = (action: string) => (keys[action]?.[0] ? [capLabel(keys[action][0])] : []);
    const second = (action: string) => (keys[action]?.[1] ? [capLabel(keys[action][1])] : []);
    const peekKey = keys.Peek?.[0] ? capLabel(keys.Peek[0]) : null;
    const [title, body] = {
      words: ['Look up any word',
        `Click a word in the line and its meaning opens ${panel ? 'beside' : 'over'} the video. Drag across a few ` +
        'characters to look up exactly those.' + (panel ? ' Every new line arrives with a word looked up already.' : '')],
      lines: ['Move line by line', 'Missed something? Jump straight back to the line instead of hunting for it.'],
      peek: ['Peek at the translation',
        (peekKey ? `Hold ${peekKey}, or press and hold the line` : 'Press and hold the line') +
        ', and the translation appears under the Japanese. Let go and it’s gone, so you only look when you need to.'],
      menu: ['Choose the subtitles',
        `⚙ opens ${site ? `${site}’s` : 'the site’s'} subtitles: the Japanese to study, and the language to peek in. ` +
        (panel ? 'Fill and Frame, for the picture, are there too.' : 'Fill, for the picture, is there too.')],
      playback: ['Play, pause and the rest',
        'A few more you’ll reach for. Every shortcut is in the extension’s options.'],
    }[step];
    const box = h('div', 'step-text');
    box.append(h('div', 'step-title', title), h('div', 'step-body', body));
    const hints = h('div', 'hints');
    const hint = (into: HTMLElement, keyCaps: string[], what: string) => {
      if (!keyCaps.length) return;
      const row = h('div', 'hint-row');
      const capsBox = h('div', 'caps');
      capsBox.append(...keyCaps.map((c) => h('span', 'keycap', c)));
      row.append(capsBox, h('span', '', what));
      into.append(row);
    };
    switch (step) {
      case 'words':
        if (anki) hint(hints, caps('AddCard'), 'Save the word as an Anki card');
        break;
      case 'lines':
        hint(hints, [...caps('PreviousLine'), ...caps('NextLine')], 'The previous or next line');
        hint(hints, [...second('PreviousLine'), ...second('NextLine')], 'The same');
        hint(hints, [...caps('PreviousLineStop'), ...caps('NextLineStop')], 'Previous or next, stopping when it ends');
        hint(hints, caps('ReplayLine'), 'Hear this line again');
        break;
      case 'peek':
        hint(hints, caps('Peek'), 'Hold to peek, let go to hide');
        break;
      case 'playback': {
        hints.classList.add('two');
        const left = h('div', 'hints'), right = h('div', 'hints');
        hint(left, ['Space'], 'Play or pause');
        hint(left, [...caps('SeekBack'), ...caps('SeekForward')], '5 s back or forward');
        hint(left, caps('StopEveryLine'), 'Stop after every line');
        hint(right, caps('Panel'), panel ? 'Put the panel away' : 'Hide the line');
        // the site's own key, as the options' Always list has it
        hint(right, ['F'], 'Full screen');
        hint(right, caps('FillFit'), 'Fill the area, or fit it all');
        hints.append(left, right);
        break;
      }
    }
    box.append(hints);
    return box;
  }
}

export const TUTORIAL_STYLES = `
.tutorial {
  position: fixed; inset: 0; z-index: 2147483647; pointer-events: auto; cursor: default;
  display: flex; align-items: center; justify-content: center; background: rgba(0, 0, 0, 0.55);
  font: 400 13px/19px system-ui, -apple-system, "Segoe UI", sans-serif; color: var(--onSurface);
}
.tutorial[hidden] { display: none; }
.tutorial button { font: inherit; border: none; background: transparent; cursor: pointer; color: inherit; }
.tutorial-card {
  width: 500px; max-width: calc(100vw - 32px); border-radius: 20px; overflow: hidden; background: var(--surface);
  box-shadow: 0 14px 40px rgba(0, 0, 0, 0.35), 0 4px 12px rgba(0, 0, 0, 0.2);
}
.tutorial .stage {
  position: relative; height: 200px; overflow: hidden;
  background: color-mix(in srgb, var(--primary) 8%, var(--surface));
}
.tutorial .pic { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; }
.tutorial .column { display: flex; flex-direction: column; align-items: center; }
.tutorial-body { padding: 22px 28px 0; }
.tutorial .dots-row { display: flex; align-items: center; }
.tutorial .gap { flex: 1; }
.tutorial .dots { display: flex; gap: 6px; align-items: center; }
.tutorial .dot {
  width: 7px; height: 7px; border-radius: 4px; background: color-mix(in srgb, var(--onSurfaceVariant) 22%, transparent);
  transition: width 300ms ${EASE}, background-color 300ms ${EASE};
}
.tutorial .dot.reached { background: var(--primary); }
.tutorial .dot.current { width: 22px; }
.tutorial .count { font-size: 12px; line-height: 16px; font-weight: 500; color: var(--onSurfaceVariant); }
.tutorial .step-words { position: relative; margin-top: 14px; min-height: 214px; }
.tutorial .step-text.leaving { position: absolute; inset: 0 0 auto 0; }
.tutorial .step-title { font-size: 20px; line-height: 26px; font-weight: 600; }
.tutorial .step-body { margin-top: 8px; line-height: 21px; color: var(--onSurfaceVariant); }
.tutorial .hints { display: flex; flex-direction: column; gap: 9px; }
.tutorial .step-text > .hints { margin-top: 16px; }
.tutorial .hints.two { flex-direction: row; gap: 16px; }
.tutorial .hints.two > .hints { flex: 1; }
.tutorial .hint-row { display: flex; align-items: center; gap: 10px; }
.tutorial .caps { width: 52px; flex: none; display: flex; gap: 4px; }
.tutorial .keycap {
  min-width: 22px; box-sizing: border-box; padding: 3px 6px; border-radius: 5px; text-align: center; white-space: nowrap;
  font-size: 11px; line-height: 14px; font-weight: 500;
  background: color-mix(in srgb, var(--onSurface) 7%, var(--surface));
  border: 1px solid color-mix(in srgb, var(--onSurface) 14%, transparent);
}
.tutorial.light .keycap { background: color-mix(in srgb, #fff 75%, var(--surface)); }
.tutorial-actions { display: flex; align-items: center; padding: 18px 20px; }
.tutorial .text-action { font-size: 13px; line-height: 18px; font-weight: 500; color: var(--onSurfaceVariant); padding: 8px; white-space: pre; }
.tutorial .text-action:hover { color: var(--onSurface); }
.tutorial .text-action:first-child { margin-left: 0; }
.tutorial-actions .text-action + .next, .tutorial-actions .gap + .text-action { margin-right: 6px; }
.tutorial .text-action[hidden] { display: none; }
.tutorial .next {
  height: 40px; padding: 0 30px; border-radius: 10px; white-space: pre;
  background: var(--primary); color: var(--onPrimary); font-size: 13px; line-height: 18px; font-weight: 500;
}
.tutorial .next:hover { background: color-mix(in srgb, var(--onPrimary) 8%, var(--primary)); }

/* the pictures */
.tutorial .demo-video.wide { width: 300px; height: 156px; align-items: flex-end; overflow: visible; }
.tutorial .demo-bar {
  position: absolute; left: 0; right: 0; bottom: 0; height: 30px; padding: 0 10px; border-radius: 0 0 12px 12px;
  display: flex; align-items: center; gap: 9px; color: #fff; background: linear-gradient(to top, rgba(0, 0, 0, 0.55), transparent);
}
.tutorial .demo-bar .play { font-size: 10px; }
.tutorial .demo-video .demo-bar .progress { position: static; flex: 1; }
.tutorial .demo-video .demo-bar .played { width: 34%; }
.tutorial .demo-bar .cog { display: flex; padding: 3px; border-radius: 5px; transition: background-color 120ms linear; }
.tutorial .demo-bar .cog svg { width: 13px; height: 13px; }
.tutorial .demo-bar .cog.on { background: rgba(255, 255, 255, 0.2); }
.tutorial .demo-menu {
  position: absolute; right: 6px; bottom: 34px; width: 150px; padding: 5px 4px; border-radius: 9px; transform-origin: bottom right;
  background: var(--surface); border: 1px solid color-mix(in srgb, var(--onSurface) 12%, transparent);
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.3); font-size: 11px; line-height: 14px;
}
.tutorial .demo-menu .group { padding: 5px 6px 3px; font-size: 8px; line-height: 10px; letter-spacing: 1px; text-transform: uppercase; color: var(--onSurfaceVariant); }
.tutorial .demo-menu .choice { display: flex; align-items: center; height: 20px; padding: 0 6px; border-radius: 5px; transition: background-color 120ms linear; }
.tutorial .demo-menu .choice span:first-child { flex: 1; }
.tutorial .demo-menu .choice .tick { color: var(--primary); font-weight: 600; opacity: 0; }
.tutorial .demo-menu .choice.on { background: color-mix(in srgb, var(--primary) 14%, transparent); }
.tutorial .demo-menu .choice.on .tick { opacity: 1; }
.tutorial .demo-line { display: flex; font-size: 26px; line-height: 34px; }
.tutorial .demo-line span { border-radius: 6px; padding: 0 1px; }
.tutorial .demo-entry {
  margin-top: 14px; padding: 10px 16px; border-radius: 10px; background: var(--surface);
  border: 1px solid color-mix(in srgb, var(--onSurface) 11%, transparent); font-size: 12px; line-height: 17px;
}
.tutorial .entry-head { display: flex; align-items: baseline; gap: 8px; }
.tutorial .entry-head .word { font-size: 19px; line-height: 25px; font-weight: 600; }
.tutorial .entry-head .reading { color: var(--onSurfaceVariant); }
.tutorial .pointer { position: absolute; left: 0; top: 0; overflow: visible; transform-origin: 11px 13px; }
.tutorial .pointer .arrow { fill: #fff; stroke: #1c1c1c; stroke-width: 1.3; }
.tutorial .pointer .ring { fill: var(--primary); }
.tutorial .pic.lines { gap: 30px; }
.tutorial .demo-lines { position: relative; width: 230px; }
.tutorial .current-line {
  position: absolute; left: 0; right: 0; top: 0; height: ${LINE_HEIGHT}px; border-radius: 9px; box-sizing: border-box;
  border: 1px solid color-mix(in srgb, var(--primary) 35%, transparent); transition: transform 260ms ${EASE};
}
.tutorial .demo-row {
  position: relative; height: ${LINE_HEIGHT}px; padding: 0 12px; display: flex; align-items: center; font-size: 16px;
  color: color-mix(in srgb, var(--onSurfaceVariant) 65%, transparent); transition: color 120ms linear;
}
.tutorial .demo-row.on { color: var(--onSurface); font-weight: 500; }
.tutorial .replaying {
  position: absolute; top: 0; right: 10px; font-size: 16px; line-height: 21px; color: var(--primary);
  transition: transform 260ms ${EASE};
}
.tutorial .demo-keys { display: flex; gap: 8px; }
.tutorial .demo-key { position: relative; height: 49px; flex: none; }
.tutorial .demo-key .side, .tutorial .demo-key .top { position: absolute; left: 0; right: 0; height: 45px; border-radius: 9px; box-sizing: border-box; }
.tutorial .demo-key .side { top: 4px; background: color-mix(in srgb, var(--onSurface) 30%, transparent); }
.tutorial .demo-key .top {
  top: 0; display: flex; align-items: center; justify-content: center; font-size: 16px; font-weight: 500;
  background: color-mix(in srgb, var(--onSurface) 12%, var(--surface)); color: var(--onSurface);
  border: 1px solid color-mix(in srgb, var(--onSurface) 30%, transparent);
  transition: transform 90ms ${EASE}, background-color 90ms ${EASE}, border-color 90ms ${EASE}, color 90ms ${EASE};
}
.tutorial.light .demo-key .side { background: color-mix(in srgb, var(--onSurface) 16%, transparent); }
.tutorial.light .demo-key .top { background: #fff; border-color: color-mix(in srgb, var(--onSurface) 16%, transparent); }
.tutorial .demo-key.pressed .top { transform: translateY(3px); background: var(--primary); border-color: var(--primary); color: var(--onPrimary); }
.tutorial .demo-peek-line { font-size: 25px; line-height: 32px; }
.tutorial .demo-translation { margin-top: 8px; font-size: 14px; line-height: 21px; color: var(--onSurfaceVariant); }
.tutorial .demo-hold { margin-top: 20px; display: flex; align-items: center; gap: 10px; }
.tutorial .hold-word { width: 64px; font-size: 12px; line-height: 16px; font-weight: 500; color: var(--onSurfaceVariant); }
.tutorial .hold-word.held { color: var(--primary); }
.tutorial .demo-video {
  position: relative; width: 184px; height: 98px; border-radius: 12px; overflow: hidden;
  background: linear-gradient(to bottom right, #2E4A42, #16231F); display: flex; align-items: center; justify-content: center;
}
.tutorial .demo-video > .ks { position: absolute; inset: 0; width: 100%; height: 100%; }
.tutorial .demo-video .sign { position: relative; transform-origin: 22px 22px; }
.tutorial .demo-video .progress {
  position: absolute; left: 10px; right: 10px; bottom: 10px; height: 3px; border-radius: 2px; overflow: hidden;
  background: rgba(255, 255, 255, 0.25);
}
.tutorial .demo-video .played { height: 3px; background: color-mix(in srgb, var(--primary) 90%, #fff); }
.tutorial .pic.playback .demo-key { margin-top: 14px; }
${SCENE_STYLES}
`;
