// What Mizune draws over the site's video: the line, the lookup above it, a notice, and a
// prompt. It lives in a shadow root, so the site's styles can't reach it, and sits in the
// full-screen element when there is one, so it stays in view.
import { origin, placeInPage, placingInPage } from './origin';
import { Colors, Entry } from '../shared';
import { CONTROL_STYLES } from './controls';
import { EPISODE_STYLES } from './episodes';
import { renderLookup, Shown } from './lookup-view';
import { MOTION } from './motion';
import { MENU_STYLES } from './menu';
import { PANEL_STYLES } from './panel';
import { STYLES } from './styles';
import { TUTORIAL_STYLES } from './tutorial';
import { h } from '../dom';
import { luminance } from '../themes';

export interface OverlayEvents {
  /** The pointer came onto a character of the line (or was clicked or dragged there). */
  point(index: number): void;
  select(start: number, end: number): void;
  /** The pointer left both the line and the lookup. */
  leave(): void;
  add(entry: Entry): void;
  remove(entry: Entry): void;
  file(file: File): void;
}

export interface PromptButton {
  label: string;
  main?: boolean;
  href?: string;
  run?: () => void;
}

export const POPUP_MESSAGES = {
  noDictionaries: 'No dictionaries yet. Import a Yomitan dictionary in Mizune, under Settings › Dictionaries.',
  noLocalDictionaries: 'No dictionaries yet. Add one in the extension’s options.',
  nothingFound: 'Nothing to look up here. Point at another word to try another spot.',
};

export class Overlay {
  private host = document.createElement('mizune-overlay');
  private shadow = this.host.attachShadow({ mode: 'open' });
  /** The app's gutter around the video's card, on a site whose player Mizune makes one of. */
  private frame = h('div', 'frame');
  private root = h('div', 'root');
  private stack = h('div', 'stack');
  private lookupBox = h('div', 'lookup-box');
  private lookup = h('div', 'lookup');
  private line = h('div', 'line');
  private noticeEl = h('div', 'notice');
  private promptEl = h('div', 'prompt');
  private drop = h('div', 'drop');
  private noticeTimer = 0;
  private lineText = '';
  private chars: HTMLElement[] = [];
  private anchor = -1;
  private dragged = false;
  private lastPointed = -1;
  private overStack = false;

  constructor(private events: OverlayEvents) {
    const style = document.createElement('style');
    style.textContent = STYLES + CONTROL_STYLES + PANEL_STYLES + MENU_STYLES + TUTORIAL_STYLES + EPISODE_STYLES;
    this.lookupBox.append(this.lookup);
    this.stack.append(this.lookupBox, this.line);
    this.root.append(this.stack, this.noticeEl, this.promptEl, this.drop);
    this.shadow.append(style, this.frame, this.root);
    this.frame.hidden = true;
    this.lookupBox.hidden = true;
    this.line.hidden = true;
    this.noticeEl.hidden = true;
    this.promptEl.hidden = true;
    this.drop.hidden = true;

    // clicks on Mizune's parts stay with Mizune, rather than pausing the site's player
    for (const type of ['pointerdown', 'mousedown', 'mouseup', 'click', 'dblclick', 'wheel', 'contextmenu']) {
      this.root.addEventListener(type, (e) => {
        if ((e.target as HTMLElement) !== this.root) e.stopPropagation();
      });
    }
    // a click leaves no focus on Mizune's buttons, or Space (the site's play and pause) would press one again
    this.root.addEventListener('mousedown', (e) => {
      if ((e.target as HTMLElement).closest?.('button')) e.preventDefault();
    });
    this.stack.addEventListener('pointerenter', () => (this.overStack = true));
    this.stack.addEventListener('pointerleave', () => {
      this.overStack = false;
      this.lastPointed = -1;
      this.events.leave();
    });
    this.line.addEventListener('pointermove', (e) => this.onLineMove(e));
    this.line.addEventListener('pointerdown', (e) => {
      const index = this.charAt(e);
      if (index < 0) return;
      this.anchor = index;
      this.dragged = false;
      this.line.setPointerCapture(e.pointerId);
    });
    this.line.addEventListener('pointerup', (e) => {
      const from = this.anchor;
      this.anchor = -1;
      if (from < 0) return;
      const to = this.charAt(e);
      this.chars.forEach((c) => c.classList.remove('sel'));
      if (this.dragged && to >= 0 && to !== from) this.events.select(Math.min(from, to), Math.max(from, to) + 1);
      else this.events.point(from);
    });
    this.lookup.addEventListener('wheel', (e) => e.stopPropagation(), { passive: true });
  }

  // ---- placement ----------------------------------------------------------------------------

  attach() {
    const parent = document.fullscreenElement ?? document.documentElement;
    if (this.host.parentNode !== parent) parent.append(this.host);
  }

  /**
   * Where Mizune's player is a box in the site's page (YouTube's player), everything of the
   * overlay's goes from the page's corner, and scrolls with it. Under the site's own bar across the
   * top, which stays over the page as it scrolls, and its menus (YouTube's are over 2020).
   */
  inPage(on: boolean) {
    // (a transform, so what's fixed in the overlay is fixed to it. Not cut at the page's width: a box
    // of no height cut on one side takes no pointer, in Chrome, though it's seen)
    const page = {
      position: 'absolute', left: '0', top: '0', width: '100%', height: '0', 'z-index': '2019', transform: 'translate(0, 0)',
    };
    for (const [prop, value] of Object.entries(page)) {
      if (on) this.host.style.setProperty(prop, value, 'important');
      else this.host.style.removeProperty(prop);
    }
    placeInPage(on ? this.host : null);
  }

  detach() {
    this.host.remove();
  }

  /**
   * Over the part of [video] (its box in the viewport) that's on screen, so a page scrolled
   * part way down the video still shows its line.
   */
  place(video: DOMRect) {
    // in the page, it's over all of the player's box, as that scrolls
    const inPage = placingInPage();
    const top = inPage ? video.top : Math.max(video.top, 0), bottom = inPage ? video.bottom : Math.min(video.bottom, innerHeight);
    const o = origin();
    const rect = { left: video.left - o.x, top: top - o.y, width: video.width, height: Math.max(0, bottom - top) };
    const s = this.root.style;
    s.left = `${px(rect.left)}px`;
    s.top = `${px(rect.top)}px`;
    s.width = `${px(rect.width)}px`;
    s.height = `${px(rect.height)}px`;
    // as the app: clear of the bottom, and the lookup at most half the picture
    this.root.style.setProperty('--rest', `${Math.max(32, rect.height * 0.08)}px`);
    this.root.style.setProperty('--lookup-height', `${Math.min(360, rect.height * 0.5)}px`);
  }

  /**
   * Makes the video a card, as the app's player: the overlay over [card] (the video's box, stood in
   * from its edges), rounded, in the gutter's colour all round. Null: the video as the site has it.
   */
  card(card: DOMRect | null, within: DOMRect | null = null) {
    this.frame.hidden = !card;
    this.root.classList.toggle('in-card', !!card);
    if (!card) return;
    const o = origin();
    const s = this.frame.style;
    s.left = `${px(card.left - o.x)}px`;
    s.top = `${px(card.top - o.y)}px`;
    s.width = `${px(card.width)}px`;
    s.height = `${px(card.height)}px`;
    // the gutter goes as far as [within] (the site's player, in its page), else to the window's edges
    s.clipPath = within
      ? `inset(${px(within.top - card.top)}px ${px(card.right - within.right)}px ${px(card.bottom - within.bottom)}px ${px(within.left - card.left)}px)`
      : '';
  }

  /** Puts [element] (Mizune's controls) over the video, under the notices and prompts. */
  mount(element: HTMLElement) {
    if (element.parentNode !== this.root) this.root.insertBefore(element, this.noticeEl);
  }

  /** Puts [element] (the study panel) beside the video, outside its box. */
  mountBeside(element: HTMLElement) {
    if (element.parentNode !== this.shadow) this.shadow.append(element);
  }

  /** No pointer over Mizune's parts of the video, while the app would hide it. */
  hideCursor(hide: boolean) {
    this.root.classList.toggle('no-cursor', hide);
  }

  /**
   * The line rises above the bar while that's up, as in the app, but never moves out from under
   * the pointer or from under its lookup: there it waits for them to be left.
   */
  raiseLine(up: boolean) {
    if (this.overStack || this.lookupOpen) return;
    this.root.classList.toggle('bar-up', up);
  }

  /** Whether the theme's surface is light, for what's drawn differently on one. */
  light = false;

  theme(colors: Colors) {
    // on the host, so the panel beside the video has them too
    for (const [name, value] of Object.entries(colors)) this.host.style.setProperty(`--${name}`, value);
    // a menu's card, a shade lighter than the surface: a little on a dark theme, more on a light one (PlayerMenu.kt)
    const dark = luminance(colors.surface) < 0.5;
    this.light = !dark;
    // what the panel's card sits on: black on a dark theme, as the app's gutter (PlayerScreen.kt)
    this.host.style.setProperty('--gutter', dark ? '#000' : colors.surfaceVariant);
    this.host.style.setProperty('--raised', `color-mix(in srgb, ${colors.surface} ${dark ? 95 : 40}%, #fff)`);
  }

  /** Hidden for a moment while the tab is captured for a card's picture. */
  setCapturing(on: boolean) {
    this.root.classList.toggle('capturing', on);
  }

  // ---- the line -----------------------------------------------------------------------------

  /** [peek] is the translation while peeking, or what to say for want of one. */
  showLine(text: string, size: number, highlight: [number, number] | null, peek: string | { none: string } | undefined) {
    this.line.hidden = false;
    this.line.style.fontSize = `${size}px`;
    if (peek !== undefined) {
      // peeking: the line in the peek language instead
      this.lineText = '';
      this.chars = [];
      const span = typeof peek === 'string' ? h('div', 'peek', peek) : h('div', 'peek none', peek.none);
      this.line.replaceChildren(span);
      return;
    }
    if (text !== this.lineText) {
      this.lineText = text;
      this.chars = [...text].map((ch, i) => {
        if (ch === '\n') return h('br') as unknown as HTMLElement;
        const span = h('span', 'c', ch);
        span.dataset.i = String(i);
        return span;
      });
      this.line.replaceChildren(...this.chars);
    }
    this.chars.forEach((c, i) => {
      const lit = !!highlight && i >= highlight[0] && i < highlight[1];
      c.classList.toggle('hl', lit);
      c.classList.toggle('first', lit && i === highlight![0]);
      c.classList.toggle('last', lit && i === highlight![1] - 1);
    });
  }

  hideLine() {
    this.line.hidden = true;
  }

  private charAt(e: PointerEvent): number {
    const target = this.shadow.elementFromPoint?.(e.clientX, e.clientY) as HTMLElement | null;
    const el = target?.dataset?.i != null ? target : (e.target as HTMLElement);
    const index = el?.dataset?.i;
    return index == null ? -1 : Number(index);
  }

  private onLineMove(e: PointerEvent) {
    const index = this.charAt(e);
    if (this.anchor >= 0) {
      if (index >= 0 && index !== this.anchor) this.dragged = true;
      const [a, b] = [Math.min(this.anchor, index), Math.max(this.anchor, index)];
      this.chars.forEach((c, i) => c.classList.toggle('sel', this.dragged && index >= 0 && i >= a && i <= b));
      return;
    }
    if (index >= 0 && index !== this.lastPointed) {
      this.lastPointed = index;
      this.events.point(index);
    }
  }

  // ---- the lookup ---------------------------------------------------------------------------

  showLookup(result: Shown, isAdded: (entry: Entry) => boolean, cardsOn: boolean, busy: boolean) {
    this.lookupBox.hidden = false;
    renderLookup(this.lookup, result, POPUP_MESSAGES, isAdded, cardsOn, busy, this.events);
  }

  closeLookup() {
    this.lookupBox.hidden = true;
    this.lookup.replaceChildren();
    this.lookup.scrollTop = 0;
  }

  get lookupOpen() {
    return !this.lookupBox.hidden;
  }

  // ---- notices and prompts ------------------------------------------------------------------

  /**
   * Says what a key or a card just did, as the app's notice: it fades in, stays a moment and fades
   * away. An error stays to be read, and one with a button for long enough to press it.
   */
  notice(text: string, options: { error?: boolean; action?: PromptButton; stay?: boolean } = {}) {
    clearTimeout(this.noticeTimer);
    if (this.noticeEl.hidden) {
      // from nothing, so it has something to fade in from
      this.noticeEl.classList.add('fading');
      this.noticeEl.hidden = false;
      void this.noticeEl.offsetWidth;
    }
    this.noticeEl.classList.remove('fading');
    this.noticeEl.classList.toggle('error', !!options.error);
    this.noticeEl.replaceChildren(h('span', undefined, text));
    if (options.action) {
      const button = h('button', undefined, options.action.label);
      button.addEventListener('click', () => {
        this.noticeEl.hidden = true;
        options.action!.run?.();
      });
      this.noticeEl.append(button);
    }
    if (options.stay) return;
    this.noticeTimer = window.setTimeout(() => {
      this.noticeEl.classList.add('fading');
      this.noticeTimer = window.setTimeout(() => (this.noticeEl.hidden = true), MOTION.noticeOut);
    }, options.action ? 7000 : options.error ? 4000 : MOTION.noticeStays);
  }

  prompt(title: string, body: string, buttons: PromptButton[], chooseFile = false) {
    const heading = h('h2', undefined, title);
    const text = h('p', undefined, body);
    const row = h('div', 'buttons');
    if (chooseFile) {
      const input = h('input');
      input.type = 'file';
      input.accept = '.srt,.ass,.ssa,.vtt,.ttml,.dfxp,.xml';
      input.hidden = true;
      input.addEventListener('change', () => {
        const file = input.files?.[0];
        if (file) this.events.file(file);
      });
      const choose = h('button', 'main', 'Choose a file…');
      choose.addEventListener('click', () => input.click());
      row.append(input, choose);
    }
    for (const b of buttons) {
      if (b.href) {
        const a = h('a', b.main ? 'button main' : 'button', b.label);
        a.href = b.href;
        a.target = '_blank';
        a.rel = 'noopener';
        row.append(a);
      } else {
        const button = h('button', b.main ? 'main' : undefined, b.label);
        button.addEventListener('click', () => b.run?.());
        row.append(button);
      }
    }
    this.promptEl.replaceChildren(heading, text, row);
    this.promptEl.hidden = false;
  }

  closePrompt() {
    this.promptEl.hidden = true;
  }

  showDrop(on: boolean) {
    this.drop.hidden = !on;
  }
}

/** [n] to a hundredth of a pixel, so what's set stays the same while nothing's moved. */
const px = (n: number) => Math.round(n * 100) / 100;
