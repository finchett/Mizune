// The way out of the video, top left, and where it is in its show: "‹ Netflix", then the show
// and the episode, which opens the show's seasons and episodes to pick another from. It sits
// over the video, whether or not the study panel's out, and comes and goes with the controls. The show
// is the one the site's page described (page.ts); without one, it's the site's name alone.
import { FoundEpisode, FoundSeason, FoundShow } from '../shared';
import { EASE } from './motion';
import { h } from '../dom';
import { origin } from './origin';

export interface EpisodeEvents {
  /** Leaves the video, as the site's own back button; without it, there's no way back in the bar. */
  back?(): void;
  /** Goes to an episode on the site. */
  open(url: string): void;
  /** The list opened or closed, which keeps the controls up while it's open. */
  listOpen(open: boolean): void;
}

/** How far the open list stands out round the bar (its padding and border), and its corners. */
const WRAP = 5;
/**
 * Corners nest: one round another is the inner's radius plus the gap between them. The bar's
 * buttons are BAR_RADIUS, and the list round the bar is that and WRAP; at the bottom, it's round
 * the rows, ROW_RADIUS in ROWS_PADDING (and its 1px edge).
 */
const BAR_RADIUS = 6;
const ROW_RADIUS = 10;
const ROWS_PADDING = 6;
const TOP_RADIUS = BAR_RADIUS + WRAP;
const BOTTOM_RADIUS = ROW_RADIUS + ROWS_PADDING + 1;
const RADIUS = `${TOP_RADIUS}px ${TOP_RADIUS}px ${BOTTOM_RADIUS}px ${BOTTOM_RADIUS}px`;

/**
 * Where [element] is once what it's in has finished moving: the controls slide in as the pointer
 * comes, and a click can come while they're on their way.
 */
function settled(element: HTMLElement): DOMRect {
  const rect = element.getBoundingClientRect();
  let x = 0, y = 0;
  for (let e = element.parentElement; e; e = e.parentElement) {
    const transform = getComputedStyle(e).transform;
    if (transform && transform !== 'none') {
      const m = new DOMMatrixReadOnly(transform);
      x += m.m41;
      y += m.m42;
    }
  }
  return new DOMRect(rect.x - x, rect.y - y, rect.width, rect.height);
}

/** The list without a surface or edge, at the start of its opening and the end of its closing. */
const BARE = { backgroundColor: 'transparent', borderColor: 'transparent' };

const CHEVRON_LEFT = '<svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true"><path d="M10 3.5 5.5 8l4.5 4.5" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>';
const CHEVRON_DOWN = '<svg width="12" height="12" viewBox="0 0 12 12" aria-hidden="true"><path d="M3 4.5 6 7.5l3-3" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>';

interface Pill {
  element: HTMLElement;
  leave: HTMLButtonElement;
  show: HTMLButtonElement;
}

export class Episodes {
  /** The list, mounted beside the overlay's other menus. */
  readonly list = h('div', 'episode-list');
  private pills: Pill[] = [];
  private site = '';
  private show: FoundShow | null = null;
  private videoId: string | null = null;
  /** The show and episode as the page's title has them, while the site's own list isn't known. */
  private fallback: string | null = null;
  private anchor: HTMLElement | null = null;
  /** The season the list is showing, which starts as the one playing. */
  private season: string | null = null;
  /** The list's opening or closing, while it moves. */
  private animation: Animation | null = null;
  /** The top of the list, where the bar sits while it's open. */
  private barSlot = h('div', 'ep-bar');
  /** The bar that's in the list, and what's keeping its place. */
  private moved: { pill: Pill; placeholder: HTMLElement } | null = null;

  constructor(private events: EpisodeEvents) {
    this.list.hidden = true;
    this.onOutside = this.onOutside.bind(this);
    for (const type of ['pointerdown', 'mousedown', 'mouseup', 'click', 'wheel', 'contextmenu']) {
      this.list.addEventListener(type, (e) => e.stopPropagation());
    }
    this.list.addEventListener('mousedown', (e) => e.preventDefault());
  }

  /** The pill, to put over the video. */
  pill(): HTMLElement {
    const element = h('div', 'episode-pill');
    const leave = h('button', 'ep-leave');
    const show = h('button', 'ep-show');
    leave.addEventListener('click', () => this.events.back?.());
    show.addEventListener('click', () => this.toggle(show));
    // nothing in it keeps the focus, which Space would press again
    element.addEventListener('mousedown', (e) => e.preventDefault());
    element.append(leave, show);
    const pill = { element, leave, show };
    this.pills.push(pill);
    this.drawPill(pill);
    return element;
  }

  get isOpen() {
    return this.anchor != null;
  }

  /** What's playing: the site, the show the page described (if it did), the video, and the page's title. */
  set(site: string, show: FoundShow | null, videoId: string | null, fallback: string | null) {
    const changed = site !== this.site || show !== this.show || videoId !== this.videoId || fallback !== this.fallback;
    if (!changed) return;
    if (show?.id !== this.show?.id || videoId !== this.videoId) this.season = null;
    this.site = site;
    this.show = show;
    this.videoId = videoId;
    this.fallback = fallback;
    this.pills.forEach((p) => this.drawPill(p));
    if (this.anchor) this.drawList();
  }

  private get playing(): { season: FoundSeason; episode: FoundEpisode } | null {
    for (const season of this.show?.seasons ?? []) {
      const episode = season.episodes.find((e) => e.id === this.videoId);
      if (episode) return { season, episode };
    }
    return null;
  }

  private drawPill(pill: Pill) {
    const { element, leave, show } = pill;
    // as the show comes in, the bar grows to it, rather than jumping
    const before = element.isConnected ? element.getBoundingClientRect().width : 0;
    const hadShow = !show.hidden;
    leave.hidden = !this.events.back;
    leave.innerHTML = CHEVRON_LEFT;
    leave.append(h('span', undefined, this.site || 'Back'));
    leave.title = this.site ? `Back to ${this.site}` : 'Back';
    const playing = this.playing;
    const title = this.show?.title || null;
    show.replaceChildren();
    show.hidden = !title && !this.fallback;
    show.disabled = !this.show?.seasons.length;
    if (title) show.append(h('span', 'ep-series', title));
    if (playing) {
      const { season, episode } = playing;
      const number = episode.number ? `${this.show!.seasons.length > 1 && season.short ? `${season.short} ` : ''}E${episode.number}` : '';
      show.append(h('span', 'ep-episode', [number, episode.title].filter(Boolean).join(' · ')));
    } else if (!title && this.fallback) {
      show.append(h('span', 'ep-series', this.fallback));
    }
    if (!show.disabled) {
      const chevron = h('span', 'ep-chevron');
      chevron.innerHTML = CHEVRON_DOWN;
      show.append(chevron);
      show.title = 'Seasons and episodes';
    } else {
      show.removeAttribute('title');
    }
    const after = before ? element.getBoundingClientRect().width : 0;
    if (before && after && Math.abs(after - before) > 1) {
      element.animate([{ width: `${before}px`, overflow: 'hidden' }, { width: `${after}px`, overflow: 'hidden' }], { duration: this.ms(260), easing: EASE });
    }
    if (!hadShow && !show.hidden && before) {
      show.animate([{ opacity: 0, transform: 'translateX(-6px)' }, { opacity: 1, transform: 'none' }], { duration: this.ms(260), delay: this.ms(60), easing: EASE, fill: 'backwards' });
    }
  }

  // ---- the list -------------------------------------------------------------------------------

  /** How long a move takes: none, for anyone who's asked for less motion. */
  private ms(duration: number) {
    return matchMedia('(prefers-reduced-motion: reduce)').matches ? 0 : duration;
  }

  private toggle(anchor: HTMLElement) {
    if (this.anchor === anchor) return this.close();
    const pill = this.pills.find((p) => p.show === anchor);
    if (!pill || !this.show?.seasons.length) return;
    this.anchor = anchor;
    // it opens on the season playing
    this.season = null;
    this.animation?.cancel();
    this.returnBar();
    // the bar goes into the top of the list, which wraps round it; a gap its size keeps its place
    const bar = settled(pill.element);
    const placeholder = h('div', 'ep-placeholder');
    placeholder.style.width = `${bar.width}px`;
    placeholder.style.height = `${bar.height}px`;
    pill.element.style.width = `${bar.width}px`;
    this.keepingHover(pill, () => {
      pill.element.replaceWith(placeholder);
      this.barSlot.replaceChildren(pill.element);
    });
    this.moved = { pill, placeholder };
    this.list.hidden = false;
    this.drawList();
    this.place(bar);
    this.centreCurrent();
    // it grows out of the bar, down and round it, its surface coming in round the bar's, and what's in it follows
    const surface = this.surface();
    this.animation = this.list.animate([
      { clipPath: this.barClip(pill.element.getBoundingClientRect()), ...BARE },
      { offset: 0.35, ...surface },
      { clipPath: `inset(0 0 0 0 round ${RADIUS})` },
    ], { duration: this.ms(280), easing: EASE });
    for (const part of this.list.querySelectorAll('.ep-head, .ep-rows')) {
      part.animate([{ opacity: 0, transform: 'translateY(-8px)' }, { opacity: 1, transform: 'none' }],
        { duration: this.ms(240), delay: this.ms(70), easing: EASE, fill: 'backwards' });
    }
    addEventListener('pointerdown', this.onOutside, true);
    this.pills.forEach((p) => p.show.classList.toggle('ep-open', p.show === anchor));
    this.events.listOpen(true);
  }

  close() {
    if (!this.anchor) return;
    this.anchor = null;
    removeEventListener('pointerdown', this.onOutside, true);
    this.animation?.cancel();
    // back into the bar, which then goes back where it was
    const bar = this.moved?.pill.element.getBoundingClientRect();
    for (const part of this.list.querySelectorAll('.ep-head, .ep-rows')) {
      part.animate([{ opacity: 1 }, { opacity: 0 }], { duration: this.ms(120), easing: EASE, fill: 'forwards' });
    }
    // and the list's surface goes before it's down to the bar, which is then just the bar
    const surface = this.surface();
    const closing = this.list.animate([
      { clipPath: `inset(0 0 0 0 round ${RADIUS})`, ...surface },
      { offset: 0.6, ...surface },
      { clipPath: bar ? this.barClip(bar) : 'inset(0 0 100% 0)', ...BARE },
    ], { duration: this.ms(220), easing: EASE, fill: 'forwards' });
    this.animation = closing;
    closing.finished.then(() => {
      if (this.anchor) return;
      this.list.hidden = true;
      closing.cancel();
      this.returnBar();
      // the controls are kept up until the bar's back in them
      this.events.listOpen(false);
    }, () => {});
    this.pills.forEach((p) => p.show.classList.remove('ep-open'));
  }

  /** The bar back where it was, out of the list. */
  private returnBar() {
    if (!this.moved) return;
    const { pill, placeholder } = this.moved;
    this.moved = null;
    pill.element.style.width = '';
    this.keepingHover(pill, () => placeholder.replaceWith(pill.element));
  }

  /**
   * Moves the bar by [move], with the pointer still over it. A moved element isn't hovered until
   * the browser looks again, a frame or two later, which would flash its highlight off; so
   * whatever was hovered stays so until then.
   */
  private keepingHover(pill: Pill, move: () => void) {
    const hovered = [pill.leave, pill.show].filter((b) => b.matches(':hover'));
    move();
    for (const button of hovered) {
      button.classList.add('ep-hovered');
      setTimeout(() => button.classList.remove('ep-hovered'), 300);
    }
  }

  /** The list's own surface and edge, as it has them open. */
  private surface() {
    const style = getComputedStyle(this.list);
    return { backgroundColor: style.backgroundColor, borderColor: style.borderTopColor };
  }

  /** The list clipped to just the bar inside it, [bar] being where the bar is. */
  private barClip(bar: DOMRect) {
    const list = this.list.getBoundingClientRect();
    const right = list.right - bar.right, bottom = list.bottom - bar.bottom;
    return `inset(${bar.top - list.top}px ${right}px ${bottom}px ${bar.left - list.left}px round ${BAR_RADIUS}px)`;
  }

  /** Round the bar, as wide as it (but not too narrow), kept inside the window. */
  private place(bar: DOMRect) {
    const s = this.list.style;
    const width = Math.min(Math.max(320, bar.width + 2 * WRAP), innerWidth - 16);
    const top = bar.top - WRAP;
    s.width = `${width}px`;
    // its edge just outside the bar's, even where the bar is close to the window's
    // (from the overlay's corner: see origin)
    const o = origin();
    s.left = `${Math.max(Math.min(8, bar.left - WRAP), Math.min(bar.left - WRAP, innerWidth - width - 8)) - o.x}px`;
    s.top = `${top - o.y}px`;
    s.maxHeight = `${Math.max(200, Math.min(600, innerHeight - top - 12))}px`;
  }

  /** The episode playing, in the middle of the list, where the season shown has it. */
  private centreCurrent() {
    const rows = this.list.querySelector<HTMLElement>('.ep-rows');
    const current = rows?.querySelector<HTMLElement>('.ep-current');
    if (rows && current) rows.scrollTop = current.offsetTop - (rows.clientHeight - current.offsetHeight) / 2;
  }

  private drawList() {
    const show = this.show;
    if (!show) return;
    const shown = this.shownSeason!;
    // the bar says the show; under it, the seasons, if there's more than one
    let head: HTMLElement | null = null;
    if (show.seasons.length > 1) {
      head = h('div', 'ep-head');
      const seasons = h('div', 'ep-seasons');
      for (const season of show.seasons) {
        const chip = h('button', season === shown ? 'ep-season ep-on' : 'ep-season', season.title);
        chip.title = season.title;
        chip.addEventListener('click', () => this.showSeason(season));
        seasons.append(chip);
      }
      head.append(seasons);
    }
    this.list.replaceChildren(...[this.barSlot, head, this.rows(shown)].filter((e): e is HTMLElement => !!e));
  }

  /** The season the list is showing: the one picked, else the one playing. */
  private get shownSeason(): FoundSeason | null {
    const seasons = this.show?.seasons ?? [];
    return seasons.find((s) => s.id === this.season) ?? this.playing?.season ?? seasons[0] ?? null;
  }

  /** Another season's episodes, sliding in from the side it's on, the list growing or shrinking to them. */
  private showSeason(season: FoundSeason) {
    const seasons = this.show?.seasons ?? [];
    const was = this.shownSeason;
    if (!was || season === was) return;
    const from = seasons.indexOf(was) < seasons.indexOf(season) ? 1 : -1;
    this.season = season.id;
    this.list.querySelectorAll('.ep-season').forEach((chip, i) => chip.classList.toggle('ep-on', seasons[i] === season));
    const height = this.list.offsetHeight;
    const rows = this.rows(season);
    this.list.querySelector('.ep-rows')!.replaceWith(rows);
    if (season === this.playing?.season) this.centreCurrent();
    const now = this.list.offsetHeight;
    if (now !== height) this.list.animate([{ height: `${height}px` }, { height: `${now}px` }], { duration: this.ms(220), easing: EASE });
    rows.animate([{ opacity: 0, transform: `translateX(${28 * from}px)` }, { opacity: 1, transform: 'none' }], { duration: this.ms(240), easing: EASE });
  }

  private rows(season: FoundSeason): HTMLElement {
    const rows = h('div', 'ep-rows');
    for (const episode of season.episodes) {
      const current = episode.id === this.videoId;
      const row = h('button', current ? 'ep-row ep-current' : 'ep-row');
      const still = h('span', 'ep-still');
      if (episode.still) {
        const img = h('img');
        img.src = episode.still;
        img.alt = '';
        img.loading = 'lazy';
        img.addEventListener('error', () => img.remove());
        still.append(img);
      }
      if (episode.number) still.append(h('span', 'ep-number', episode.number));
      const words = h('span', 'ep-words');
      words.append(h('span', 'ep-name', episode.title || (episode.number ? `Episode ${episode.number}` : 'Episode')));
      const meta = [current ? 'Playing' : '', episode.minutes ? `${episode.minutes} min` : ''].filter(Boolean).join(' · ');
      if (meta) words.append(h('span', 'ep-meta', meta));
      row.append(still, words);
      if (!current) {
        row.addEventListener('click', () => {
          this.close();
          this.events.open(episode.url);
        });
      }
      rows.append(row);
    }
    return rows;
  }

  private onOutside(event: PointerEvent) {
    const path = event.composedPath();
    if (path.includes(this.list) || (this.anchor && path.includes(this.anchor))) return;
    this.close();
  }
}

export const EPISODE_STYLES = `
.episode-pill {
  display: inline-flex; align-items: center; gap: 2px; max-width: 100%; min-width: 0; pointer-events: auto;
  font: 500 12px/1 system-ui, -apple-system, "Segoe UI", sans-serif;
  /* over the video, white, as the bar along the bottom; in the open list, the list's own colours */
  --ep-text: rgba(255, 255, 255, 0.85); --ep-strong: #fff; --ep-dim: rgba(255, 255, 255, 0.6); --ep-hover: rgba(255, 255, 255, 0.08);
  color: var(--ep-text);
}
.episode-list .episode-pill {
  --ep-text: var(--onSurface); --ep-strong: var(--onSurface); --ep-dim: var(--onSurfaceVariant);
  --ep-hover: color-mix(in srgb, var(--onSurface) 9%, transparent);
}
.episode-pill button {
  font: inherit; color: inherit; border: none; background: transparent; cursor: pointer;
  height: 24px; display: flex; align-items: center; border-radius: ${BAR_RADIUS}px; white-space: nowrap;
  transition: background-color 120ms ${EASE}, color 120ms ${EASE};
}
.episode-pill button:is(:hover, .ep-hovered):not(:disabled), .episode-pill button.ep-open { background: var(--ep-hover); color: var(--ep-strong); }
/* the chevron in line with the play button under it */
.episode-pill .ep-leave { flex: none; gap: 3px; padding: 0 8px 0 11px; }
.episode-pill .ep-show { flex: 0 1 auto; min-width: 0; gap: 7px; padding: 0 8px; }
.episode-pill :is(.ep-show, .ep-leave)[hidden] { display: none; }
.episode-pill .ep-show:disabled { cursor: default; }
.episode-pill .ep-series { flex: 0 1 auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; font-size: 13px; font-weight: 600; color: var(--ep-strong); max-width: 220px; }
.episode-pill .ep-episode { flex: 0 1 auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; color: var(--ep-dim); max-width: 260px; }
.episode-pill .ep-chevron { flex: none; display: flex; color: var(--ep-dim); transition: transform 150ms ${EASE}; }
.episode-pill .ep-show.ep-open .ep-chevron { transform: rotate(180deg); }
.ep-placeholder { flex: none; }

.episode-list {
  position: fixed; z-index: 2147483647; display: flex; flex-direction: column; box-sizing: border-box; overflow: hidden;
  pointer-events: auto; color: var(--onSurface); border-radius: ${RADIUS};
  background: var(--raised, var(--surface));
  border: 1px solid color-mix(in srgb, var(--onSurface) 12%, transparent);
  font: 400 14px/1.3 system-ui, -apple-system, "Segoe UI", sans-serif;
}
.episode-list[hidden] { display: none; }
.episode-list button { font: inherit; color: inherit; border: none; background: transparent; cursor: pointer; text-align: left; }
.episode-list .ep-bar { flex: none; padding: ${WRAP - 1}px; }
/* the bar, while it's in the list, is just its top: the list is its surface */
.episode-list .ep-head { flex: none; padding: 4px 10px 10px; }
.episode-list .ep-head, .episode-list .ep-bar:has(+ .ep-rows) { border-bottom: 1px solid color-mix(in srgb, var(--onSurface) 10%, transparent); }
.episode-list .ep-seasons { display: flex; gap: 6px; overflow-x: auto; scrollbar-width: none; }
.episode-list .ep-seasons::-webkit-scrollbar { display: none; }
.episode-list .ep-season {
  flex: none; max-width: 200px; height: 28px; padding: 0 12px; border-radius: 14px; font-size: 13px; font-weight: 500;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; color: var(--onSurfaceVariant);
  background: color-mix(in srgb, var(--onSurface) 6%, transparent);
  transition: background-color 180ms ${EASE}, color 180ms ${EASE};
}
.episode-list .ep-season:hover { background: color-mix(in srgb, var(--onSurface) 10%, transparent); }
.episode-list .ep-season.ep-on { color: var(--onSurface); background: color-mix(in srgb, var(--primary) 20%, transparent); }
/* no scrollbar, as in the app */
.episode-list .ep-rows { position: relative; flex: 1; min-height: 0; overflow-y: auto; overscroll-behavior: contain; padding: ${ROWS_PADDING}px; scrollbar-width: none; }
.episode-list .ep-rows::-webkit-scrollbar { display: none; }
.episode-list .ep-row {
  width: 100%; display: flex; align-items: center; gap: 12px; padding: 6px; border-radius: ${ROW_RADIUS}px;
  transition: background-color 120ms ${EASE};
}
.episode-list .ep-row:hover { background: color-mix(in srgb, var(--onSurface) 6%, transparent); }
.episode-list .ep-row.ep-current { background: color-mix(in srgb, var(--primary) 14%, transparent); cursor: default; }
.episode-list .ep-still {
  position: relative; flex: none; width: 96px; height: 54px; border-radius: ${ROW_RADIUS - 6}px; overflow: hidden;
  background: color-mix(in srgb, var(--onSurface) 8%, transparent); display: flex; align-items: center; justify-content: center;
}
.episode-list .ep-still img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; }
.episode-list .ep-still .ep-number { position: relative; font-size: 15px; font-weight: 600; color: var(--onSurfaceVariant); }
.episode-list .ep-still img ~ .ep-number {
  position: absolute; left: 4px; bottom: 4px; font-size: 11px; line-height: 1; padding: 3px 5px; border-radius: 4px;
  color: #fff; background: rgba(0, 0, 0, 0.6);
}
.episode-list .ep-words { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.episode-list .ep-name { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; font-weight: 500; }
.episode-list .ep-meta { font-size: 12px; color: var(--onSurfaceVariant); }
.episode-list .ep-row.ep-current .ep-meta { color: var(--primary); font-weight: 500; }
`;
