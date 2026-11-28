// What differs between sites: where the video is, what it's called, and how to move it.
import { CONTENT, isGecko, PAGE } from '../shared';

export interface Site {
  kind: 'netflix' | 'crunchyroll' | 'other';
  video(): HTMLVideoElement | null;
  /** The site's ID for what's playing, to match it with the subtitle files the page found. */
  videoId(): string | null;
  /** On a page for watching a video, rather than browsing (where Netflix plays previews). */
  watching(): boolean;
  /** The show and episode, for the card's "Episode and time". */
  title(): string | null;
  seek(seconds: number): Promise<void>;
  play(): Promise<void>;
  pause(): void;
  /** Hides the site's own subtitles while Mizune shows the line. */
  hideOwnSubtitles(hide: boolean): void;
  /** Gets the site's player to download the subtitles it has in [languages], where it can. */
  requestSubtitles?(languages: string[]): void;
  /** Gets the page to say what show this is, with its seasons and episodes, where it can. */
  requestShow?(): void;
  /** Every subtitle track and audio track the site's player has, where it says. */
  trackList?(): Promise<SiteTracks | null>;
  /** Gets the player to download one of its subtitle tracks; whether it came. */
  fetchTrack?(id: string): Promise<boolean>;
  setAudio?(id: string): Promise<boolean>;
  /** The video's length in seconds, or 0 while it isn't known. */
  duration(): number;
  /** Sets the volume (0 to 1) and whether it's muted. */
  setVolume(level: number, muted: boolean): void;
  /** What goes full screen: something Mizune's overlay can sit in, with the video. */
  fullscreenTarget(): Element | null;
  /** A site whose own controls Mizune's take the place of. */
  ownControls?: OwnControls;
  /** Fills the area with the picture, cropping what doesn't fit (pan and scan), or fits all of it. */
  fill(on: boolean): void;
  /** Narrows the site's player to leave [width] pixels at the window's right for the study panel; 0 gives it back. */
  makeRoom?(width: number): void;
  /**
   * Holds the picture in [rect] (the video's card, as the panel slides), rather than where the
   * site's player would put it; null gives it back. [besidePanel]: the study panel's out beside it.
   */
  placeVideo?(rect: DOMRect | null, besidePanel?: boolean): void;
}

/** The tracks a site's player has, for the track menu. */
export interface SiteTracks {
  subtitles: { id: string; language: string; label: string; captions: boolean }[];
  audio: { id: string; language: string; label: string; selected: boolean }[];
}

/** The site's own controls, which Mizune's replace while it's on. */
export interface OwnControls {
  /** The site's name, on the way back to it. */
  name: string;
  /** Hides them, or gives them back. */
  hide(hide: boolean): void;
  /** Hides the pointer over the player, which the site would show while it's busy. */
  hideCursor(hide: boolean): void;
  /** Leaves the video, as the site's own back button. */
  back(): void;
}

/** The biggest video showing in this frame. */
export function largestVideo(): HTMLVideoElement | null {
  let best: HTMLVideoElement | null = null;
  let bestArea = 0;
  for (const video of document.querySelectorAll('video')) {
    const r = video.getBoundingClientRect();
    const area = r.width * r.height;
    if (area > bestArea) {
      best = video;
      bestArea = area;
    }
  }
  return best;
}

function waitForSeek(video: HTMLVideoElement, target: number): Promise<void> {
  return new Promise((resolve) => {
    const done = () => {
      clearTimeout(timer);
      video.removeEventListener('seeked', done);
      resolve();
    };
    const timer = setTimeout(done, 3000);
    if (Math.abs(video.currentTime - target) < 0.05 && !video.seeking) return done();
    video.addEventListener('seeked', done);
  });
}

/** Any site: the video element itself. */
class ElementSite implements Site {
  kind: Site['kind'] = 'other';
  video = largestVideo;
  duration() {
    const d = this.video()?.duration ?? 0;
    return Number.isFinite(d) ? d : 0;
  }
  setVolume(level: number, muted: boolean) {
    const video = this.video();
    if (!video) return;
    video.volume = Math.min(1, Math.max(0, level));
    video.muted = muted;
  }
  fullscreenTarget(): Element | null {
    // the video can't hold the overlay, so its box does
    return this.video()?.parentElement ?? null;
  }
  fill(on: boolean) {
    const video = this.video();
    if (!video) return;
    const fit = on ? 'cover' : '';
    if (video.style.getPropertyValue('object-fit') !== fit) {
      if (on) video.style.setProperty('object-fit', 'cover', 'important');
      else video.style.removeProperty('object-fit');
    }
  }
  videoId(): string | null {
    return null;
  }
  watching() {
    // a site Mizune knows has its own watch pages; anywhere else, any video will do
    return this.kind === 'other' || this.videoId() !== null;
  }
  title(): string | null {
    return document.title.trim() || null;
  }
  async seek(seconds: number) {
    const video = this.video();
    if (!video) return;
    video.currentTime = Math.max(0, seconds);
    await waitForSeek(video, seconds);
  }
  async play() {
    await this.video()?.play().catch(() => {});
  }
  pause() {
    this.video()?.pause();
  }
  hideOwnSubtitles(hide: boolean) {
    // the video's own text tracks, if it has any showing
    for (const track of this.video()?.textTracks ?? []) {
      if (hide && track.mode === 'showing') {
        track.mode = 'hidden';
        (track as any).__mizuneHid = true;
      } else if (!hide && (track as any).__mizuneHid) {
        track.mode = 'showing';
        (track as any).__mizuneHid = false;
      }
    }
  }
}

class CrunchyrollSite extends ElementSite {
  kind: Site['kind'] = 'crunchyroll';
  videoId() {
    return location.pathname.match(/\/watch\/([A-Z0-9]+)/)?.[1] ?? null;
  }
  title() {
    return document.title.replace(/\s*[-|–]\s*Crunchyroll\s*$/i, '').replace(/^Watch\s+/i, '').trim() || null;
  }
}

/** Netflix: moved through its own player, by page.ts, since setting the video's time upsets it. */
class NetflixSite extends ElementSite {
  kind: Site['kind'] = 'netflix';
  private nextId = 1;
  private style: HTMLStyleElement | null = null;
  private room: HTMLStyleElement | null = null;
  private roomWidth = 0;
  /**
   * The video while its picture's held in the card: how a position set on it turns into one on
   * screen (on screen = o + s × set), and what was set last.
   */
  private holding: { video: HTMLVideoElement; fit: { ox: number; oy: number; sx: number; sy: number }; set: string } | null = null;
  /** It couldn't be held where the card is, so Netflix places it until the card goes. */
  private unplaceable = false;
  /** The picture fills its area, cut to it. */
  private filling = false;
  ownControls = new NetflixControls();

  fill(on: boolean) {
    this.filling = on;
    // held in its card, how it fills is part of where it's put (placeVideo)
    if (this.holding) return;
    super.fill(on);
    const video = this.video();
    if (!video) return;
    // were Netflix to size its video to the picture, rather than to its player, it's made bigger instead, about its middle
    let scale = 1;
    const box = document.querySelector('[data-uia="player"]');
    // held in its card, the picture's already the card's size
    if (on && !this.holding && box && video.offsetWidth && video.offsetHeight) {
      scale = Math.max(box.clientWidth / video.offsetWidth, box.clientHeight / video.offsetHeight);
      if (scale < 1.01) scale = 1;
    }
    const transform = scale === 1 ? '' : `scale(${scale.toFixed(3)})`;
    if (video.style.getPropertyValue('transform') !== transform) {
      if (transform) video.style.setProperty('transform', transform, 'important');
      else video.style.removeProperty('transform');
    }
  }

  makeRoom(width: number) {
    if (width === this.roomWidth) return;
    this.roomWidth = width;
    if (!width) {
      this.room?.remove();
      this.room = null;
    } else {
      this.room ??= document.createElement('style');
      // the whole player (its video, menus and buttons) fits in what's left
      // (by the window's width, in case one of its boxes is fixed to the window rather than inside the other)
      this.room.textContent = `.watch-video, .watch-video--player-view { width: calc(100vw - ${width}px) !important; max-width: calc(100vw - ${width}px) !important; }`;
      if (!this.room.isConnected) document.documentElement.append(this.room);
    }
    // Netflix fits its picture to its box when the window changes
    window.dispatchEvent(new Event('resize'));
  }

  placeVideo(rect: DOMRect | null, besidePanel = false) {
    const video = rect && rect.width > 0 && rect.height > 0 ? this.video() : null;
    if (!rect || !video) {
      this.release();
      this.unplaceable = false;
      return;
    }
    if (this.unplaceable) return;
    if (this.holding && this.holding.video !== video) this.release();
    // Netflix lays its picture out again by its own script as its player's box changes, which lags
    // behind the panel as it slides (when it grows, from a corner); so while the video's a card the
    // picture's held in it, as Mizune's overlay is, and Netflix's box only makes room for its menus.
    // Where a position lands depends on Netflix's boxes around the video (one moved or scaled moves
    // it too), so it's measured, and set again to make up the difference.
    const held = (this.holding ??= { video, fit: { ox: 0, oy: 0, sx: 1, sy: 1 }, set: '' });
    const fit = held.fit;
    // On whole pixels. And filling, Firefox on a Mac draws the picture a pixel one way or the other
    // as the page under the pointer changes, depending how it's cut: beside the panel, when
    // object-fit cuts it to the card; on its own, when the window's edges do. So beside the panel
    // it isn't cut: it's made as big as covers the card, and what's past the card is under the
    // panel, the gutter round the card, or out of the window. On its own, object-fit cuts it.
    // And on its own in a window, Firefox still draws a cut picture a pixel over as other things
    // in the window are drawn again, whichever way it's cut or sized: it puts the video on a layer
    // of its own, or draws it in with the rest, by turns, and the two land a pixel apart. A filter
    // keeps it drawn in with the rest, always; this one changes nothing that can be seen.
    const card = new DOMRect(Math.round(rect.left), Math.round(rect.top), Math.round(rect.width), Math.round(rect.height));
    let target = card, objectFit: string | null = null;
    const filter = isGecko && this.filling ? 'brightness(1.0001)' : null;
    if (this.filling && besidePanel && video.videoWidth && video.videoHeight) {
      const scale = Math.max(card.width / video.videoWidth, card.height / video.videoHeight);
      const width = Math.round(video.videoWidth * scale), height = Math.round(video.videoHeight * scale);
      target = new DOMRect(card.left + Math.round((card.width - width) / 2), card.top + Math.round((card.height - height) / 2), width, height);
      objectFit = 'fill';
    } else if (this.filling) {
      objectFit = 'cover';
    }
    for (let tries = 0; tries < 4; tries++) {
      const css = [(target.left - fit.ox) / fit.sx, (target.top - fit.oy) / fit.sy, target.width / fit.sx, target.height / fit.sy];
      const set = [...css.map((n) => n.toFixed(2)), objectFit, filter].join(' ');
      if (set !== held.set) {
        held.set = set;
        const values = ['fixed', `${css[0]}px`, `${css[1]}px`, `${css[2]}px`, `${css[3]}px`, 'none', 'none', 'none', objectFit, filter];
        PLACING.forEach((prop, i) => {
          const value = values[i];
          if (value) video.style.setProperty(prop, value, 'important');
          else video.style.removeProperty(prop);
        });
      }
      const r = video.getBoundingClientRect();
      if (Math.abs(r.left - target.left) <= 1 && Math.abs(r.top - target.top) <= 1
        && Math.abs(r.width - target.width) <= 1 && Math.abs(r.height - target.height) <= 1) return;
      if (!r.width || !r.height) break;
      // where it landed, for how a position set turns into one on screen
      fit.sx = r.width / css[2];
      fit.sy = r.height / css[3];
      fit.ox = r.left - fit.sx * css[0];
      fit.oy = r.top - fit.sy * css[1];
    }
    // it won't go where it's put: Netflix places it, as before, until the card's gone
    this.release();
    this.unplaceable = true;
  }

  /** Gives the picture back to Netflix to place. */
  private release() {
    if (!this.holding) return;
    for (const prop of PLACING) this.holding.video.style.removeProperty(prop);
    this.holding = null;
  }


  fullscreenTarget() {
    return document.documentElement;
  }

  setVolume(level: number, muted: boolean) {
    // through Netflix's player, which keeps its own volume; the element if that doesn't answer
    this.command('volume', { level, muted }).then(({ ok }) => ok || super.setVolume(level, muted));
  }

  videoId() {
    return location.pathname.match(/\/watch\/(\d+)/)?.[1] ?? null;
  }

  title() {
    const shown = document.querySelector('[data-uia="video-title"]');
    if (shown) {
      const parts = [...shown.querySelectorAll('h4, span')].map((e) => e.textContent?.trim()).filter(Boolean);
      const text = parts.length ? parts.join(' ') : shown.textContent?.trim();
      if (text) return text;
    }
    return document.title.replace(/\s*[-|–]\s*Netflix\s*$/i, '').trim() || null;
  }

  /** Asks page.ts to do [kind] with Netflix's player: whether it could, and what it answered. */
  private command(kind: string, data: Record<string, unknown> = {}, timeoutMs = 1000): Promise<{ ok: boolean; data?: any }> {
    const id = `n${this.nextId++}`;
    return new Promise((resolve) => {
      const onAnswer = (event: MessageEvent) => {
        if (event.source !== window || event.data?.source !== PAGE || event.data.kind !== 'answer' || event.data.id !== id) return;
        window.removeEventListener('message', onAnswer);
        clearTimeout(timer);
        resolve({ ok: !!event.data.ok, data: event.data.data });
      };
      const timer = setTimeout(() => {
        window.removeEventListener('message', onAnswer);
        resolve({ ok: false });
      }, timeoutMs);
      window.addEventListener('message', onAnswer);
      window.postMessage({ source: CONTENT, kind, id, ...data }, '*');
    });
  }

  async seek(seconds: number) {
    const video = this.video();
    if (!(await this.command('seek', { seconds: Math.max(0, seconds) })).ok) return super.seek(seconds);
    if (video) await waitForSeek(video, seconds);
  }

  async play() {
    if (!(await this.command('play')).ok) await super.play();
  }

  pause() {
    this.command('pause').then(({ ok }) => ok || super.pause());
  }

  requestSubtitles(languages: string[]) {
    this.command('subtitles', { languages });
  }

  requestShow() {
    // what it finds comes as the page's own message
    this.command('show', {}, 15000);
  }

  async trackList(): Promise<SiteTracks | null> {
    const { ok, data } = await this.command('trackList');
    return ok ? data : null;
  }

  async fetchTrack(id: string) {
    // the player waits up to 30 s for its tracks, then 8 for the file
    return (await this.command('fetchTrack', { track: id }, 40000)).ok;
  }

  async setAudio(id: string) {
    return (await this.command('audio', { track: id })).ok;
  }

  hideOwnSubtitles(hide: boolean) {
    if (hide && !this.style) {
      this.style = document.createElement('style');
      this.style.textContent = '.player-timedtext { visibility: hidden !important; }';
      document.documentElement.append(this.style);
    } else if (!hide && this.style) {
      this.style.remove();
      this.style = null;
    }
  }
}

/** What's set on Netflix's video to hold its picture in the card. */
// (and none of Netflix's centring, half the video's size back: half a pixel, when that's odd; and how
// it fills the card, which placeVideo does itself)
const PLACING = ['position', 'left', 'top', 'width', 'height', 'max-width', 'max-height', 'transform', 'object-fit', 'filter'];

/** What of Netflix's own is hidden while Mizune's controls are out: all of it. */
// The player never scrolls; but come to from the catalogue, Netflix's page can keep the
// catalogue's scrolling, a scrollbar beside the player (and the room made for the panel, in vw,
// a scrollbar's width too wide).
const NETFLIX_HIDDEN = `html, body { overflow: hidden !important; }
  .watch-video--back-container, .watch-video--flag-container, .watch-video--bottom-controls-container,
  .watch-video--evidence-overlay-container { visibility: hidden !important; pointer-events: none !important; }`;

/**
 * Netflix's controls: the back and flag buttons in the top corners, the bar along the bottom,
 * and the title and synopsis it shows over a paused video, which would cover the line at every
 * stop. Mizune has its own ways to its menus: the track menu, and the episode list.
 */
class NetflixControls implements OwnControls {
  name = 'Netflix';
  private style: HTMLStyleElement | null = null;
  private cursor: HTMLStyleElement | null = null;

  hideCursor(hide: boolean) {
    if (hide && !this.cursor) {
      this.cursor = document.createElement('style');
      this.cursor.textContent = '.watch-video, .watch-video * { cursor: none !important; }';
      document.documentElement.append(this.cursor);
    } else if (!hide && this.cursor) {
      this.cursor.remove();
      this.cursor = null;
    }
  }

  private hiding(css: string | null) {
    if (css == null) {
      this.style?.remove();
      this.style = null;
      return;
    }
    this.style ??= document.createElement('style');
    this.style.textContent = css;
    if (!this.style.isConnected) document.documentElement.append(this.style);
  }

  hide(hide: boolean) {
    this.hiding(hide ? NETFLIX_HIDDEN : null);
    // Netflix's own ways to full screen take the whole page, Mizune's overlay and panel with it (page.ts)
    window.postMessage({ source: CONTENT, kind: 'wholePageFullscreen', on: hide }, '*');
  }

  back() {
    // Netflix's own back button, which it only has while its player counts as busy
    poke();
    setTimeout(() => {
      const button = document.querySelector<HTMLElement>('[data-uia="control-nav-back"]');
      if (button) button.click();
      else if (history.length > 1) history.back();
      else location.assign('/browse');
    }, 100);
  }
}

/** Netflix brings its controls up when the pointer moves over its player: a move in its middle. */
function poke() {
  const player = document.querySelector('[data-uia="player"]');
  const r = player?.getBoundingClientRect();
  if (player && r) player.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 }));
}

export function siteFor(hostname: string): Site {
  if (hostname.endsWith('netflix.com')) return new NetflixSite();
  if (hostname.endsWith('crunchyroll.com')) return new CrunchyrollSite();
  return new ElementSite();
}
