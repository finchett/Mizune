// Mizune's own controls over the site's video, as the app's full view has them (PlayerScreen.kt):
// one bar along the bottom with play, the time, the seek bar, the line buttons and the stop at a
// line's end, the volume, the study panel's button, and full screen; and top left, the way back
// and the episode list (episodes.ts), where the app's full view has its way back to the library.
// The site's own controls stay hidden while Mizune's on: the toolbar's popup turns it off. With the panel put away it
// comes up as the full view's does, while the pointer is near the video's edges (QuietArea, in
// FullLayout.kt) or over the bar, and goes two seconds after. With the panel out, as the panel
// view's: while paused (not at a line's stop), and for two seconds after the pointer moves.
// It lives in the overlay's shadow root.

import { EASE } from './motion';
import { h } from '../dom';
import { Previews } from './previews';

export type LineStop = 'every' | 'held' | 'this' | 'none';

export interface ControlEvents {
  togglePlay(): void;
  seek(seconds: number): void;
  previousLine(): void;
  replayLine(): void;
  nextLine(): void;
  toggleStop(): void;
  /** 0 to 1; it unmutes. */
  setVolume(level: number): void;
  toggleMute(): void;
  fullscreen(): void;
  togglePanel(): void;
  /** The track menu, from [anchor]. */
  trackMenu(anchor: HTMLElement): void;
}

export interface ControlState {
  paused: boolean;
  position: number;
  duration: number;
  volume: number;
  muted: boolean;
  /** Whether there are lines to move between. */
  lines: boolean;
  stop: LineStop;
  fullscreen: boolean;
  /** The study panel is out (the app's panel view), rather than put away (its full view). */
  panel: boolean;
  /** Paused by a stop at a line's end, which isn't a pause that brings the bar up. */
  stoppedAtLine: boolean;
  keys: Record<string, string[]>;
  /** The site's pictures of the video, for over the seek bar, if it has them. */
  previews: Previews | null;
  /** The video's waiting for what it's to show next: seeking, or buffering. */
  loading: boolean;
}

/** How long the seek bar keeps to where it was sent, if the video never gets there. */
const SEEK_HOLD_MS = 10000;

/** As the app: how long the bar stays after the pointer was last at an edge. */
const TIMEOUT_MS = 2000;

/** The full view's quiet area (FullLayout.kt), as fractions of the video's size: the pointer here leaves the bar alone. */
const QUIET = [[0.12, 0.26], [0.88, 0.26], [0.92, 0.895], [0.69, 0.96], [0.31, 0.96], [0.08, 0.895]];

function quiet(x: number, y: number): boolean {
  let crossings = 0;
  for (let i = 0; i < QUIET.length; i++) {
    const [ax, ay] = QUIET[i];
    const [bx, by] = QUIET[(i + 1) % QUIET.length];
    if ((ay > y) !== (by > y) && x < ax + ((y - ay) / (by - ay)) * (bx - ax)) crossings++;
  }
  return crossings % 2 === 1;
}

/** As the app's formatTime: 1:02:03, or 2:03 under an hour. */
export function formatTime(seconds: number): string {
  const total = Math.max(0, Math.floor(Number.isFinite(seconds) ? seconds : 0));
  const h = Math.floor(total / 3600), m = Math.floor((total % 3600) / 60), s = total % 60;
  const two = (n: number) => String(n).padStart(2, '0');
  return h > 0 ? `${h}:${two(m)}:${two(s)}` : `${m}:${two(s)}`;
}

// ---- the app's icons (LineIcons.kt), on a 16-unit square ----------------------------------------

const svg = (body: string) => `<svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true">${body}</svg>`;

/** The app's cog (Icons.kt): eight teeth, narrowing to their tips, round a ring. */
function cogPath(): string {
  const c = 8, outer = 7.2, body = 5.44, step = 45;
  const at = (radius: number, degrees: number) => {
    const a = (degrees * Math.PI) / 180;
    return `${(c + radius * Math.cos(a)).toFixed(3)} ${(c + radius * Math.sin(a)).toFixed(3)}`;
  };
  let d = '';
  for (let i = 0; i < 8; i++) {
    const mid = i * step - 90;
    d += `${i === 0 ? 'M' : 'L'}${at(body, mid - 12)}L${at(outer, mid - 8)}L${at(outer, mid + 8)}L${at(body, mid + 12)}`;
    d += `A${body} ${body} 0 0 1 ${at(body, mid + step - 12)}`;
  }
  return d + 'Z';
}

export const ICONS = {
  /** The picture stretched out to the screen's sides. */
  fill: () => svg(`<g fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
    <rect x="0.96" y="2.56" width="14.08" height="10.88" rx="1.92"/><path d="M4.4 8h7.2M6.1 6.1 4.2 8l1.9 1.9M9.9 6.1 11.8 8 9.9 9.9"/></g>`),
  /** The picture as a card, a gap round it inside the screen. */
  frame: () => svg(`<rect x="0.96" y="2.56" width="14.08" height="10.88" rx="1.92" fill="none" stroke="currentColor" stroke-width="1.6"/>
    <rect x="3.68" y="5.28" width="8.64" height="5.44" rx="1.6" fill="currentColor"/>`),
  cog: () => svg(`<path d="${cogPath()}" fill="none" stroke="currentColor" stroke-width="1.36" stroke-linejoin="round"/>
    <circle cx="8" cy="8" r="2.16" fill="none" stroke="currentColor" stroke-width="1.36"/>`),
  lineSkip: (forward: boolean) => svg(`<g${forward ? ' transform="matrix(-1 0 0 1 16 0)"' : ''} fill="currentColor">
    <rect x="2.56" y="2.88" width="2.24" height="10.24" rx="0.64"/><path d="M5.76 8 13.44 2.88v10.24z"/></g>`),
  replay: () => svg(`<g transform="matrix(-1 0 0 1 16 0)">
    <path d="M12.157 5.6A4.8 4.8 0 1 1 8 3.2" fill="none" stroke="currentColor" stroke-width="1.92" stroke-linecap="round"/>
    <path d="M11.2 3.2 6.72 0v6.4z" fill="currentColor"/></g>`),
  lineStop: () => svg(`<g fill="currentColor"><rect x="0.64" y="7.04" width="6.4" height="1.92" rx="0.8"/>
    <rect x="8.64" y="3.2" width="2.24" height="9.6" rx="0.8"/><rect x="12.8" y="3.2" width="2.24" height="9.6" rx="0.8"/></g>`),
  fullscreen: (full: boolean) => {
    const near = 1.92, far = 14.08, arm = 4.48;
    let d = '';
    for (const x of [near, far]) for (const y of [near, far]) {
      const dx = x === near ? arm : -arm, dy = y === near ? arm : -arm;
      d += full ? `M${x + dx} ${y}L${x + dx} ${y + dy}L${x} ${y + dy}` : `M${x + dx} ${y}L${x} ${y}L${x} ${y + dy}`;
    }
    return svg(`<path d="${d}" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/>`);
  },
  panel: (shown: boolean) => svg(`<rect x="0.96" y="2.56" width="14.08" height="10.88" rx="1.92" fill="none" stroke="currentColor" stroke-width="1.6"/>`
    + (shown ? '<rect x="9.92" y="2.56" width="5.12" height="10.88" rx="1.92" fill="currentColor"/>'
      : '<path d="M9.92 2.56V13.44" stroke="currentColor" stroke-width="1.6"/>')),
  volume: (level: number, muted: boolean) => {
    const speaker = '<path d="M0.96 5.76H3.84L7.68 2.24V13.76L3.84 10.24H0.96z" fill="currentColor"/>';
    const line = (d: string) => `<path d="${d}" fill="none" stroke="currentColor" stroke-width="1.44" stroke-linecap="round"/>`;
    if (muted || level <= 0) return svg(speaker + line('M10.24 5.76 14.72 10.24M14.72 5.76 10.24 10.24'));
    const near = line('M9.943 5.737A3.2 3.2 0 0 1 9.943 10.263');
    const far = level > 0.5 ? line('M12.205 3.475A6.4 6.4 0 0 1 12.205 12.525') : '';
    return svg(speaker + near + far);
  },
};

export const STOP_HINTS: Record<LineStop, string> = {
  every: 'Stopping at the end of each line',
  held: 'Stops held off by H or L: click to stop again',
  this: 'Stopping at the end of this line',
  none: 'Stop at the end of each line',
};

/**
 * Shows what each of [buttons] does (its data-hint) and its key (data-key) in [hint], over the
 * button within [box], half a second after the pointer comes to rest on it, while [allowed].
 */
export function addHints(buttons: HTMLElement[], hint: HTMLElement, box: HTMLElement, allowed: () => boolean = () => true) {
  let timer = 0;
  hint.hidden = true;
  const show = (button: HTMLElement) => {
    if (!allowed() || !button.dataset.hint) return;
    hint.replaceChildren(document.createTextNode(button.dataset.hint));
    if (button.dataset.key) hint.append(h('span', 'key', `  ${button.dataset.key}`));
    hint.hidden = false;
    const outer = box.getBoundingClientRect(), b = button.getBoundingClientRect();
    const width = hint.offsetWidth;
    hint.style.left = `${Math.min(outer.width - width - 6, Math.max(6, b.left - outer.left + b.width / 2 - width / 2))}px`;
  };
  for (const button of buttons) {
    button.addEventListener('pointerenter', () => {
      clearTimeout(timer);
      timer = window.setTimeout(() => show(button), 500);
    });
    button.addEventListener('pointerleave', () => {
      clearTimeout(timer);
      hint.hidden = true;
    });
  }
}

export class Controls {
  readonly element = h('div', 'bar');
  private play = h('button', 'text play');
  private timeNow = h('span', 'now');
  private timeSizer = h('span', 'sizer');
  private scrub = h('div', 'scrub');
  private scrubFill = h('div', 'fill');
  /** How far along the pointer is, on the seek bar. */
  private scrubHover = h('div', 'hover');
  /** Over the seek bar: the picture from where the pointer is, and the time there. */
  private preview = h('div', 'preview');
  private previewFrame = document.createElement('canvas');
  private previewTime = h('span', 'at');
  private lines = h('span', 'group');
  private previous = h('button', 'icon previous');
  private replay = h('button', 'icon replay');
  private next = h('button', 'icon next');
  private stop = h('button', 'icon stop');
  private volumeArea = h('span', 'volume');
  private slider = h('span', 'slider');
  private sliderFill = h('span', 'fill');
  private speaker = h('button', 'icon speaker');
  /** Top left: the way back and the episode list (episodes.ts). */
  readonly backCorner = h('div', 'corner left');
  private panel = h('button', 'icon side');
  private cog = h('button', 'icon cog');
  private menuHeld = false;
  private full = h('button', 'icon full');
  private hint = h('div', 'hint');

  private state: ControlState | null = null;
  private scrubbing: number | null = null;
  /** Where the seek bar was let go, kept to until the video's there, rather than back where it was. */
  private seekingTo: { at: number; until: number } | null = null;
  /** Where the pointer is on the seek bar, from 0 to 1, while it's over it. */
  private hoverAt: number | null = null;
  /** The frame drawn in the preview, and the one it's waiting for. */
  private previewShown = { previews: null as Previews | null, frame: -1, wanted: -1 };
  private volumeHeld = false;
  private over = false;
  private atEdge = false;
  private gone = true;
  private lastAtEdge = 0;
  private lastMove = -Infinity;
  private up = false;
  private drawn = { volume: '', full: '', stop: '', panel: '' };

  constructor(private events: ControlEvents) {
    const time = h('span', 'time');
    time.append(this.timeSizer, this.timeNow);
    const track = h('div', 'track');
    track.append(this.scrubHover, this.scrubFill);
    this.scrub.append(track);
    this.preview.append(this.previewFrame, this.previewTime);
    this.previous.innerHTML = ICONS.lineSkip(false);
    this.replay.innerHTML = ICONS.replay();
    this.next.innerHTML = ICONS.lineSkip(true);
    this.stop.innerHTML = ICONS.lineStop();
    this.lines.append(this.previous, this.replay, this.next);
    const sliderTrack = h('span', 'track');
    sliderTrack.append(this.sliderFill);
    this.slider.append(sliderTrack);
    this.volumeArea.append(this.slider, this.speaker);
    this.element.append(this.play, time, this.scrub, this.lines, this.stop, this.volumeArea, this.cog, this.panel, this.full, this.hint, this.preview);
    this.cog.innerHTML = ICONS.cog();
    this.cog.dataset.hint = 'Audio, subtitles and picture';

    this.play.addEventListener('click', () => this.events.togglePlay());
    this.previous.addEventListener('click', () => this.events.previousLine());
    this.replay.addEventListener('click', () => this.events.replayLine());
    this.next.addEventListener('click', () => this.events.nextLine());
    this.stop.addEventListener('click', () => this.events.toggleStop());
    this.speaker.addEventListener('click', () => this.events.toggleMute());
    this.panel.addEventListener('click', () => this.events.togglePanel());
    this.cog.addEventListener('click', () => this.events.trackMenu(this.cog));
    this.full.addEventListener('click', () => this.events.fullscreen());
    for (const part of [this.element, this.backCorner]) {
      part.addEventListener('pointerenter', () => (this.over = true));
      part.addEventListener('pointerleave', () => (this.over = false));
    }

    // the seek bar follows the pointer while held, and seeks when let go
    const at = (e: PointerEvent, el: HTMLElement) => {
      const r = el.getBoundingClientRect();
      return Math.min(1, Math.max(0, (e.clientX - r.left) / r.width));
    };
    this.scrub.addEventListener('pointerdown', (e) => {
      if (!this.state?.duration) return;
      this.scrub.setPointerCapture(e.pointerId);
      this.scrub.classList.add('held');
      this.scrubbing = at(e, this.scrub) * this.state.duration;
      this.hoverAt = at(e, this.scrub);
      this.drawPreview();
    });
    this.scrub.addEventListener('pointermove', (e) => {
      if (this.scrubbing != null && this.state) this.scrubbing = at(e, this.scrub) * this.state.duration;
      this.hoverAt = at(e, this.scrub);
      this.drawPreview();
    });
    // held, the pointer's still the seek bar's when it strays off it
    this.scrub.addEventListener('pointerleave', () => {
      if (this.scrubbing != null) return;
      this.hoverAt = null;
      this.drawPreview();
    });
    const endScrub = (e: PointerEvent) => {
      if (this.scrubbing == null) return;
      this.events.seek(this.scrubbing);
      this.seekingTo = { at: this.scrubbing, until: performance.now() + SEEK_HOLD_MS };
      this.scrubbing = null;
      this.scrub.classList.remove('held');
      const r = this.scrub.getBoundingClientRect();
      if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top - 4 || e.clientY > r.bottom + 4) this.hoverAt = null;
      this.drawPreview();
    };
    this.scrub.addEventListener('pointerup', endScrub);
    this.scrub.addEventListener('pointercancel', endScrub);
    // the pointer taken away mid-drag (the window left, say) lets go where it got to
    this.scrub.addEventListener('lostpointercapture', endScrub);

    // the volume: straight from the press, and on with every move
    this.slider.addEventListener('pointerdown', (e) => {
      this.slider.setPointerCapture(e.pointerId);
      this.volumeHeld = true;
      this.volumeArea.classList.add('held');
      this.events.setVolume(at(e, this.slider));
    });
    this.slider.addEventListener('pointermove', (e) => {
      if (this.volumeHeld) this.events.setVolume(at(e, this.slider));
    });
    const endVolume = () => {
      this.volumeHeld = false;
      this.volumeArea.classList.remove('held');
    };
    this.slider.addEventListener('pointerup', endVolume);
    this.slider.addEventListener('pointercancel', endVolume);
    this.volumeArea.addEventListener('wheel', (e) => {
      e.preventDefault();
      if (!this.state || !e.deltaY) return;
      const level = this.state.muted ? 0 : this.state.volume;
      this.events.setVolume(Math.min(1, Math.max(0, level + (e.deltaY < 0 ? 0.05 : -0.05))));
    }, { passive: false });

    addHints([this.play, this.previous, this.replay, this.next, this.stop, this.speaker, this.cog, this.panel, this.full],
      this.hint, this.element, () => this.up);
  }

  // ---- when it shows ------------------------------------------------------------------------

  /** A menu from the bar is open, which keeps it up. */
  get menuOpen() {
    return this.menuHeld;
  }

  /** Once the menu's gone, the bar stays a while, as if the pointer had just moved: it was in use. */
  set menuOpen(open: boolean) {
    if (this.menuHeld && !open) this.lastAtEdge = this.lastMove = performance.now();
    this.menuHeld = open;
  }

  /** The pointer moved to ([x], [y]), as fractions of the video's size. */
  pointer(x: number, y: number) {
    this.gone = false;
    this.lastMove = performance.now();
    this.atEdge = !quiet(x, y);
    if (this.atEdge) this.lastAtEdge = performance.now();
  }

  /** The pointer left the video, where it can't be seen to stop. */
  pointerGone() {
    this.gone = true;
    // as the app's Exit: it's no longer moving over the video
    this.lastMove = -Infinity;
  }

  /** Whether the bar is up, which the line moves above. */
  get visible() {
    return this.up;
  }

  /** Whether the pointer moved over the video lately, which shows it, as in the app. */
  get moving() {
    return performance.now() - this.lastMove < TIMEOUT_MS;
  }

  /** As the app: the pointer goes with the bar, unless it's moving. */
  get cursorHidden() {
    return !this.up && !this.moving;
  }

  // ---- the preview over the seek bar --------------------------------------------------------

  /**
   * Over the seek bar where the pointer is (or the seek bar is held): the site's picture from
   * then, if it has them, and the time.
   */
  private drawPreview() {
    const state = this.state;
    const at = this.scrubbing != null && state?.duration ? this.scrubbing / state.duration : this.hoverAt;
    const shown = this.previewShown;
    if (shown.previews !== (state?.previews ?? null)) {
      shown.previews = state?.previews ?? null;
      shown.frame = shown.wanted = -1;
      this.preview.classList.remove('framed');
    }
    if (at == null || !state?.duration) {
      this.preview.classList.remove('on');
      this.scrubHover.style.width = '0';
      return;
    }
    const seconds = at * state.duration;
    this.scrubHover.style.width = `${at * 100}%`;
    this.previewTime.textContent = formatTime(seconds);
    const previews = shown.previews;
    if (previews) {
      const frame = previews.frameAt(seconds);
      if (frame !== shown.frame && frame !== shown.wanted) {
        shown.wanted = frame;
        previews.frame(frame).then((bitmap) => {
          // only the latest wanted is drawn, whichever comes first
          if (!bitmap || shown.previews !== previews || shown.wanted !== frame) return;
          const canvas = this.previewFrame;
          if (canvas.width !== bitmap.width || canvas.height !== bitmap.height) {
            canvas.width = bitmap.width;
            canvas.height = bitmap.height;
          }
          canvas.getContext('2d')?.drawImage(bitmap, 0, 0);
          shown.frame = frame;
          shown.wanted = -1;
          const first = !this.preview.classList.contains('framed');
          this.preview.classList.add('framed');
          // the first picture changes its size, so it's placed again
          if (first) this.placePreview(at);
        });
      }
    }
    this.preview.classList.add('on');
    this.placePreview(at);
  }

  /** Over [at] along the seek bar, kept inside the bar's ends. */
  private placePreview(at: number) {
    const bar = this.element.getBoundingClientRect(), track = this.scrub.getBoundingClientRect();
    const half = this.preview.offsetWidth / 2;
    const x = track.left - bar.left + at * track.width;
    this.preview.style.left = `${Math.min(bar.width - half - 6, Math.max(half + 6, x))}px`;
  }

  // ---- drawing ------------------------------------------------------------------------------

  /** Brings it up to date, every frame; [allowed] is false while it mustn't show at all. */
  update(state: ControlState, allowed: boolean) {
    this.state = state;
    const held = this.over || this.menuOpen || this.scrubbing != null || this.volumeHeld;
    const up = allowed && (held || (state.panel
      ? (state.paused && !state.stoppedAtLine) || this.moving
      : performance.now() - this.lastAtEdge < TIMEOUT_MS || (this.atEdge && this.gone)));
    if (up !== this.up) {
      this.up = up;
      this.element.classList.toggle('up', up);
      if (!up) {
        this.hoverAt = null;
        this.drawPreview();
        this.hint.hidden = true;
        this.over = false;
      }
    }
    this.backCorner.classList.toggle('up', up);
    if (!up) return;

    this.play.textContent = state.paused ? '▶' : '❚❚';
    this.play.dataset.hint = state.paused ? 'Play' : 'Pause';
    this.play.dataset.key = state.keys.PlayPause?.[0] ?? 'Space';
    // until the video's caught up with where it was sent, that's where the bar stays
    const sent = this.seekingTo;
    if (sent && ((Math.abs(state.position - sent.at) < 1 && !state.loading) || performance.now() > sent.until)) this.seekingTo = null;
    const position = this.scrubbing ?? this.seekingTo?.at ?? state.position;
    this.scrub.classList.toggle('loading', this.scrubbing == null && (this.seekingTo != null || state.loading));
    const total = formatTime(state.duration);
    this.timeSizer.textContent = `${total.replace(/\d/g, '0')} / ${total}`;
    this.timeNow.textContent = `${formatTime(position)} / ${total}`;
    this.scrubFill.style.width = `${state.duration > 0 ? Math.min(100, (position / state.duration) * 100) : 0}%`;
    if (state.previews !== this.previewShown.previews) this.drawPreview();

    this.lines.hidden = !state.lines;
    // the panel has its own, under the line
    this.stop.hidden = !state.lines || state.panel;
    const lineKeys = (move: string, stop: string) => {
      const keys = state.keys[move]?.[0], stopKeys = state.keys[stop]?.[0];
      const stopping = stopKeys ? `${stopKeys} to stop at its end` : undefined;
      return keys && stopping ? `${keys}, or ${stopping}` : keys ?? stopping ?? '';
    };
    this.previous.dataset.hint = 'Previous line';
    this.previous.dataset.key = lineKeys('PreviousLine', 'PreviousLineStop');
    this.replay.dataset.hint = 'Replay this line';
    this.replay.dataset.key = state.keys.ReplayLine?.[0] ?? '';
    this.next.dataset.hint = 'Next line';
    this.next.dataset.key = lineKeys('NextLine', 'NextLineStop');
    if (this.drawn.stop !== state.stop) {
      this.drawn.stop = state.stop;
      this.stop.className = `icon stop ${state.stop}`;
    }
    this.stop.dataset.hint = STOP_HINTS[state.stop];
    this.stop.dataset.key = state.keys.StopEveryLine?.[0] ?? '';

    const volumeKey = `${state.muted}/${state.volume > 0.5 ? 2 : state.volume > 0 ? 1 : 0}`;
    if (this.drawn.volume !== volumeKey) {
      this.drawn.volume = volumeKey;
      this.speaker.innerHTML = ICONS.volume(state.volume, state.muted);
    }
    this.speaker.dataset.hint = state.muted ? 'Unmute' : 'Mute';
    this.sliderFill.style.width = `${state.muted ? 0 : Math.round(state.volume * 100)}%`;

    // the panel has its own while it's out, at the end of its bottom row
    this.cog.hidden = state.panel;
    if (this.drawn.panel !== String(state.panel)) {
      this.drawn.panel = String(state.panel);
      this.panel.innerHTML = ICONS.panel(state.panel);
    }
    this.panel.dataset.hint = state.panel ? 'Put the panel away' : 'Bring the panel back';
    this.panel.dataset.key = state.keys.Panel?.[0] ?? 'S';
    const full = String(state.fullscreen);
    if (this.drawn.full !== full) {
      this.drawn.full = full;
      this.full.innerHTML = ICONS.fullscreen(state.fullscreen);
    }
    this.full.dataset.hint = state.fullscreen ? 'Leave full screen' : 'Full screen';
    this.full.dataset.key = state.keys.FullScreen?.[0] ?? 'F';
  }
}

export const CONTROL_STYLES = `
/* the app's bar: 40 px, shaded up from the bottom, white on the video */
.bar {
  position: absolute; left: 0; right: 0; bottom: 0; height: 40px; padding: 0 10px;
  display: flex; align-items: center; gap: 8px; pointer-events: auto;
  background: linear-gradient(transparent, rgba(0, 0, 0, 0.45));
  font: 500 12px/1 system-ui, -apple-system, "Segoe UI", sans-serif; color: #fff;
  --accent: color-mix(in srgb, var(--primary) 85%, #fff);
  opacity: 0; transform: translateY(33%); visibility: hidden;
  transition: opacity 0.4s ${EASE}, transform 0.4s ${EASE}, visibility 0s 0.4s;
}
.bar.up { opacity: 1; transform: none; visibility: visible; transition: opacity 0.16s ${EASE}, transform 0.22s ${EASE}, visibility 0s; }
.bar button {
  border: none; background: transparent; color: rgba(255, 255, 255, 0.85); cursor: pointer; border-radius: 6px;
  font: inherit; display: flex; align-items: center; justify-content: center; flex: none;
}
.bar button:hover { color: #fff; background: rgba(255, 255, 255, 0.08); }
.bar button[hidden], .bar .group[hidden] { display: none; }
.bar button.icon { padding: 5px; }
.bar button.text { padding: 4px 8px; }
.bar button.play { color: #fff; font-size: 16px; min-width: 34px; }
.bar .group { display: flex; align-items: center; }
.bar .time { position: relative; font-variant-numeric: tabular-nums; color: rgba(255, 255, 255, 0.85); flex: none; }
.bar .time .sizer { visibility: hidden; }
.bar .time .now { position: absolute; left: 0; top: 0; white-space: nowrap; }

/* the seek bar: thin, thicker under the pointer */
.bar .scrub { flex: 1; min-width: 40px; height: 14px; margin: 0 6px; display: flex; align-items: center; cursor: pointer; touch-action: none; }
.bar .scrub .track { position: relative; width: 100%; height: 3px; border-radius: 3px; background: rgba(255, 255, 255, 0.28); transition: height 0.15s ${EASE}; }
.bar .scrub:hover .track, .bar .scrub.held .track { height: 6px; }
.bar .scrub .fill { position: absolute; left: 0; top: 0; bottom: 0; border-radius: 3px; background: var(--accent); }

/* waiting for the video: a light sweeping along the bar */
.bar .scrub .track { overflow: hidden; }
.bar .scrub .track::after {
  content: ""; position: absolute; top: 0; bottom: 0; left: 0; width: 30%; pointer-events: none; opacity: 0;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.55), transparent);
  transform: translateX(-100%); transition: opacity 200ms ${EASE};
}
.bar .scrub.loading .track::after { opacity: 1; animation: sweep 1.1s ${EASE} infinite; }
@keyframes sweep { from { transform: translateX(-100%); } to { transform: translateX(340%); } }
@media (prefers-reduced-motion: reduce) { .bar .scrub.loading .track::after { animation: none; transform: none; width: 100%; opacity: 0.35; } }

/* how far along the pointer is, under how far the video's played */
.bar .scrub .hover { position: absolute; left: 0; top: 0; bottom: 0; width: 0; border-radius: 3px; background: rgba(255, 255, 255, 0.22); }

/* over the seek bar: the picture from where the pointer is, and the time there */
.bar .preview {
  position: absolute; bottom: 42px; left: 0; transform: translate(-50%, 4px); pointer-events: none;
  display: flex; flex-direction: column; align-items: center; gap: 6px;
  opacity: 0; transition: opacity 0.14s ${EASE}, transform 0.18s ${EASE};
}
.bar .preview.on { opacity: 1; transform: translate(-50%, 0); }
.bar .preview canvas {
  display: none; width: 192px; height: auto; border-radius: 8px; background: #000;
  box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.2), 0 8px 24px rgba(0, 0, 0, 0.5);
}
.bar .preview.framed canvas { display: block; }
.bar .preview .at {
  padding: 4px 8px; border-radius: 6px; font-variant-numeric: tabular-nums; white-space: nowrap;
  background: var(--onSurface); color: var(--surface); box-shadow: 0 4px 12px rgba(0, 0, 0, 0.35);
}

/* stop at the line's end: lit while it will */
.bar button.stop.every { color: var(--accent); background: color-mix(in srgb, var(--accent) 16%, transparent); }
.bar button.stop.this { color: var(--accent); }
.bar button.stop.held { color: color-mix(in srgb, var(--accent) 50%, transparent); }
.bar button.stop.every:hover, .bar button.stop.this:hover, .bar button.stop.held:hover { color: var(--accent); }

/* the volume: the slider slides out to the speaker's left */
.bar .volume { display: flex; align-items: center; flex: none; }
.bar .volume .slider {
  width: 0; height: 14px; overflow: hidden; display: flex; align-items: center; cursor: pointer; touch-action: none;
  opacity: 0; transition: width 0.2s ${EASE}, opacity 0.2s ${EASE}, margin 0.2s ${EASE};
}
.bar .volume:hover .slider, .bar .volume.held .slider { width: 56px; opacity: 1; margin: 0 4px 0 2px; }
.bar .volume .track { position: relative; width: 56px; flex: none; height: 3px; border-radius: 3px; background: rgba(255, 255, 255, 0.28); transition: height 0.15s ${EASE}; }
.bar .volume .slider:hover .track, .bar .volume.held .track { height: 5px; }
.bar .volume .fill { position: absolute; left: 0; top: 0; bottom: 0; border-radius: 3px; background: #fff; }
.bar .volume .slider:hover .fill, .bar .volume.held .fill { background: var(--accent); }

/* top left, as the app's way back to the library, fading with the bar */
/* along the top, as the bar along the bottom: a fade, what's on it plain, sliding in with it; its
   buttons (24px) as far from the top as from the side */
.corner {
  position: absolute; top: 0; left: 0; right: 0; height: 40px; padding: 0 8px; box-sizing: border-box;
  display: flex; align-items: center; pointer-events: none;
  background: linear-gradient(rgba(0, 0, 0, 0.45), transparent);
  opacity: 0; transform: translateY(-33%); visibility: hidden;
  transition: opacity 0.4s ${EASE}, transform 0.4s ${EASE}, visibility 0s 0.4s;
}
.corner.up { opacity: 1; transform: none; visibility: visible; transition: opacity 0.16s ${EASE}, transform 0.22s ${EASE}, visibility 0s; }


/* what a control does, and its key */
.bar .hint {
  position: absolute; bottom: 42px; padding: 5px 8px; border-radius: 6px; white-space: nowrap; pointer-events: none;
  background: var(--onSurface); color: var(--surface); box-shadow: 0 4px 12px rgba(0, 0, 0, 0.35);
}
.bar .hint[hidden] { display: none; }
.bar .hint .key { opacity: 0.65; white-space: pre; }
`;
