// Runs in the site's own JavaScript world, from before the site's scripts, and picks up the
// subtitle files Netflix's and Crunchyroll's players get, and the pictures they show over their seek
// bars. What it finds goes to the content script (content/main.ts). On Netflix it also moves the
// player for it, through Netflix's own player: setting the video's time directly upsets it.
import { isBif } from '../bif';
import { CONTENT, FoundEpisode, FoundShow, FoundTrack, mergeShow, PAGE, showHas } from '../shared';

declare global {
  interface Window {
    __mizunePage?: boolean;
    netflix?: any;
  }
}

/**
 * The languages fetched by themselves: Japanese to study, and the peek language for cards'
 * translations and peeking (English until the content script says otherwise).
 */
let wanted = ['ja', 'en'];

function send(track: FoundTrack) {
  window.postMessage({ source: PAGE, kind: 'track', track }, '*');
}

function sendShow(show: FoundShow) {
  window.postMessage({ source: PAGE, kind: 'show', show }, '*');
}

/** Of a site's pictures of an episode ({url or source, w or width}), a small one, but not too small. */
function smallPicture(pictures: unknown): string | undefined {
  if (!Array.isArray(pictures)) return undefined;
  const sized = pictures
    .map((p: any) => ({ url: String(p?.url ?? p?.source ?? ''), width: Number(p?.w ?? p?.width ?? 0) }))
    .filter((p) => /^https:\/\//.test(p.url))
    .sort((a, b) => a.width - b.width);
  return (sized.find((p) => p.width >= 200) ?? sized.at(-1))?.url;
}

const text = (value: unknown) => (typeof value === 'string' ? value : value == null ? '' : String(value));

/** The show in Netflix's metadata for a video: its seasons and episodes, or a film. */
function netflixShow(json: any): FoundShow | null {
  const video = json?.video;
  if (!video || video.id == null || !video.title) return null;
  const hidden = !!video.hiddenEpisodeNumbers;
  const seasons = (Array.isArray(video.seasons) ? video.seasons : []).map((season: any, i: number) => ({
    id: text(season.id ?? i),
    title: text(season.longName ?? season.title ?? season.name) || `Season ${season.seq ?? i + 1}`,
    short: text(season.shortName) || undefined,
    episodes: (Array.isArray(season.episodes) ? season.episodes : []).map((e: any): FoundEpisode => {
      const id = text(e.episodeId ?? e.id);
      return {
        id,
        number: hidden || season.hiddenEpisodeNumbers || e.seq == null ? null : text(e.seq),
        title: text(e.title),
        url: `https://www.netflix.com/watch/${id}`,
        minutes: e.runtime ? Math.round(Number(e.runtime) / 60) : undefined,
        still: smallPicture(e.thumbs) ?? smallPicture(e.stills),
      };
    }).filter((e: FoundEpisode) => e.id),
  })).filter((s: { episodes: FoundEpisode[] }) => s.episodes.length);
  return {
    site: 'netflix', id: text(video.id), title: text(video.title), home: `https://www.netflix.com/title/${video.id}`,
    seasons, videoIds: [text(video.id)],
  };
}


/** [code]'s language without its region ("pt" for "pt-BR"), or null. */
function language(code: string | undefined): string | null {
  return (code ?? '').toLowerCase().split(/[-_]/)[0] || null;
}


// ---- Netflix --------------------------------------------------------------------------------

/**
 * Netflix's player gets its list of subtitle files encrypted, and reads it where the page can't
 * see; but it downloads the subtitle file it shows as plain TTML or WebVTT, through the page.
 * So the files are picked up as the player downloads them, and to get Japanese and English the
 * player is switched to each for a moment (see [fetchSubtitles]), then back.
 */
function netflix() {
  /** The track the player was switched to, while [fetchTrack] waits for its file. */
  let switchedTo: { language: string; captions: boolean; label: string; id: string } | null = null;
  /** videoId/language, and videoId/track ID: what's been found already. */
  const found = new Set<string>();

  const watchId = () => location.pathname.match(/\/watch\/(\d+)/)?.[1] ?? null;

  function onText(text: string) {
    const kind = subtitleKind(text);
    if (!kind) return;
    const videoId = watchId();
    if (!videoId) return;
    const showing = player()?.getTimedTextTrack?.();
    const lang = language(text.match(/<tt\b[^>]*\bxml:lang="([^"]*)"/)?.[1]) ?? switchedTo?.language ?? language(showing?.bcp47);
    if (!lang) return;
    const mine = switchedTo?.language === lang ? switchedTo : null;
    const captions = mine ? mine.captions : showing?.trackType === 'ASSISTIVE';
    found.add(`${videoId}/${lang}`);
    const id = mine?.id ?? showing?.trackId;
    if (id) found.add(`${videoId}/${id}`);
    const label = (mine ? mine.label : showing?.displayName) ?? lang;
    send({ site: 'netflix', videoId, language: lang, label, captions, name: `netflix.${lang}.${kind}`, text });
  }

  /** The shows the player's metadata described, and the address it was asked for at. */
  const shows: FoundShow[] = [];
  let metadataUrl: string | null = null;
  const isMetadata = (url: string) => /\/metadata\?/.test(url);

  function onMetadata(url: string, json: unknown) {
    const found = netflixShow(json);
    if (!found) return;
    // asked again for a show, it's asked as the player asked when it had its episodes
    if (found.seasons.length) metadataUrl = url;
    const at = shows.findIndex((s) => s.id === found.id);
    const show = at >= 0 ? mergeShow(shows.splice(at, 1)[0], found) : found;
    shows.push(show);
    sendShow(show);
  }

  /** The show [videoId]'s in, as the player had it, or else asked for as the player asks. */
  async function showFor(videoId: string | null): Promise<boolean> {
    if (!videoId) return false;
    const known = shows.find((s) => showHas(s, videoId));
    if (known) {
      sendShow(known);
      return true;
    }
    const url = new URL(metadataUrl ?? '/nq/website/memberapi/release/metadata', location.href);
    url.searchParams.set('movieid', videoId);
    const response = await originalFetch(url.href, { credentials: 'include' });
    if (!response.ok) return false;
    onMetadata(url.href, await response.json());
    return shows.some((s) => showHas(s, videoId));
  }

  const open = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (this: XMLHttpRequest, ...args: any[]) {
    const url = String(args[1] ?? '');
    this.addEventListener('load', () => {
      try {
        if (isMetadata(url)) onMetadata(url, this.responseType === 'json' ? this.response : JSON.parse(this.responseText));
        else if (this.responseType === '' || this.responseType === 'text') onText(this.responseText);
        else if (this.responseType === 'arraybuffer' && /xml|ttml|vtt/i.test(this.getResponseHeader('content-type') ?? '')) onText(new TextDecoder().decode(this.response));
      } catch {}
    });
    return (open as any).apply(this, args);
  } as typeof XMLHttpRequest.prototype.open;

  const originalFetch = window.fetch;
  window.fetch = async function (this: unknown, ...args: Parameters<typeof fetch>) {
    const response = await originalFetch.apply(this, args);
    try {
      const url = response.url || String(args[0] instanceof Request ? args[0].url : args[0]);
      if (/xml|ttml|vtt/i.test(response.headers.get('content-type') ?? '')) response.clone().text().then(onText, () => {});
      else if (isMetadata(url) && /json/.test(response.headers.get('content-type') ?? '')) response.clone().json().then((json) => onMetadata(url, json), () => {});
    } catch {}
    return response;
  };

  function player() {
    const videoPlayer = window.netflix?.appContext?.state?.playerApp?.getAPI?.()?.videoPlayer;
    const sessions: string[] = videoPlayer?.getAllPlayerSessionIds?.() ?? [];
    const id = sessions.find((s) => s.startsWith('watch')) ?? sessions.at(-1);
    return id ? videoPlayer.getVideoPlayerBySessionId(id) : null;
  }

  /** The player, once it has its subtitle tracks (the content script can ask before it does). */
  async function ready(videoId: string) {
    let p = player();
    for (let waited = 0; waited < 30000 && !(p?.getTimedTextTrackList?.()?.length); waited += 250) {
      await new Promise((r) => setTimeout(r, 250));
      p = player();
    }
    return p?.getTimedTextTrackList && p.setTimedTextTrack && watchId() === videoId ? p : null;
  }

  /** Switches the player to [track] until its file has come, then back to [showing]. */
  async function fetchTrack(p: any, videoId: string, track: any) {
    const lang = language(track.bcp47);
    if (!lang || found.has(`${videoId}/${track.trackId}`)) return;
    switchedTo = { language: lang, captions: track.trackType === 'ASSISTIVE', label: track.displayName ?? lang, id: track.trackId };
    await p.setTimedTextTrack(track);
    for (let waited = 0; waited < 8000 && !found.has(`${videoId}/${track.trackId}`); waited += 100) await new Promise((r) => setTimeout(r, 100));
  }

  const asked = new Set<string>();

  /**
   * Fetches the wanted languages' files that haven't come yet, one track at a time, and then
   * switches back to the track that was showing.
   */
  async function fetchSubtitles() {
    const videoId = watchId();
    if (!videoId || asked.has(videoId)) return;
    asked.add(videoId);
    const p = await ready(videoId);
    if (!p) {
      asked.delete(videoId);
      return;
    }
    const tracks: any[] = p.getTimedTextTrackList() ?? [];
    // a video without Japanese keeps its subtitles as they are
    if (!tracks.some((t) => language(t.bcp47) === 'ja' && !t.isImageBased)) return;
    const showing = p.getTimedTextTrack?.();
    try {
      for (const lang of wanted) {
        if (found.has(`${videoId}/${lang}`)) continue;
        // plain subtitles before captions, which describe sounds too
        const track = tracks
          .filter((t) => language(t.bcp47) === lang && !t.isImageBased)
          .sort((a, b) => Number(a.trackType === 'ASSISTIVE') - Number(b.trackType === 'ASSISTIVE'))[0];
        if (track) await fetchTrack(p, videoId, track);
      }
    } finally {
      switchedTo = null;
      if (showing) await p.setTimedTextTrack(showing);
    }
  }

  /** One track the track menu chose, by its ID. */
  async function fetchOne(id: string) {
    const videoId = watchId();
    const p = videoId ? await ready(videoId) : null;
    const track = p?.getTimedTextTrackList().find((t: any) => t.trackId === id);
    if (!p || !track || !videoId) return false;
    const showing = p.getTimedTextTrack?.();
    try {
      await fetchTrack(p, videoId, track);
    } finally {
      switchedTo = null;
      if (showing) await p.setTimedTextTrack(showing);
    }
    return found.has(`${videoId}/${id}`);
  }

  /** What the track menu offers: the subtitles Netflix has in text (not pictures), and its audio. */
  function trackList(p: any) {
    const subtitles = (p.getTimedTextTrackList?.() ?? [])
      .filter((t: any) => t.bcp47 && !t.isImageBased && !t.isNoneTrack)
      .map((t: any) => ({ id: t.trackId, language: language(t.bcp47), label: t.displayName ?? t.bcp47, captions: t.trackType === 'ASSISTIVE' }));
    const current = p.getAudioTrack?.();
    const audio = (p.getAudioTrackList?.() ?? []).map((t: any) => ({
      id: t.trackId, label: t.displayName ?? t.bcp47, language: language(t.bcp47), selected: t.trackId === current?.trackId,
    }));
    return { subtitles, audio };
  }

  window.addEventListener('message', (event) => {
    if (event.source !== window || event.data?.source !== CONTENT) return;
    const { kind, seconds, level, muted } = event.data;
    if (kind === 'subtitles') {
      if (Array.isArray(event.data.languages)) wanted = event.data.languages;
      fetchSubtitles().catch(() => {});
      return answer(event.data.id, true);
    }
    if (kind === 'fetchTrack') {
      fetchOne(event.data.track).then((ok) => answer(event.data.id, ok), () => answer(event.data.id, false));
      return;
    }
    if (kind === 'show') {
      showFor(watchId()).then((ok) => answer(event.data.id, ok), () => answer(event.data.id, false));
      return;
    }
    const p = player();
    if (!p) return answer(event.data.id, false);
    if (kind === 'trackList') return answer(event.data.id, true, trackList(p));
    if (kind === 'audio') {
      const track = p.getAudioTrackList?.()?.find((t: any) => t.trackId === event.data.track);
      if (!track || !p.setAudioTrack) return answer(event.data.id, false);
      p.setAudioTrack(track);
      return answer(event.data.id, true);
    }
    if (kind === 'seek') p.seek(Math.round(seconds * 1000));
    else if (kind === 'play') p.play();
    else if (kind === 'pause') p.pause();
    else if (kind === 'volume') {
      if (typeof p.setVolume !== 'function') return answer(event.data.id, false);
      p.setVolume(level);
      p.setMuted?.(muted);
    }
    answer(event.data.id, true);
  });
}

/** "ttml" or "vtt" if [text] is a subtitle file in one of those, else null. */
function subtitleKind(text: string): 'ttml' | 'vtt' | null {
  const head = text.slice(0, 2000).replace(/^﻿/, '');
  if (/^WEBVTT/.test(head)) return 'vtt';
  if (/^\s*(<\?xml[^>]*\?>\s*)?(<!--[\s\S]*?-->\s*)*<tt[\s>:]/.test(head) || /^\s*<\?xml[^>]*\?>\s*<tt:tt[\s>]/.test(head)) return 'ttml';
  return null;
}

function answer(id: string, ok: boolean, data?: unknown) {
  window.postMessage({ source: PAGE, kind: 'answer', id, ok, data }, '*');
}

// ---- Crunchyroll ----------------------------------------------------------------------------

function crunchyroll() {
  const seen = new Set<string>();

  /** The series whose seasons' episodes the page fetched, and each season's place among them. */
  const series = new Map<string, FoundShow>();
  const order = new Map<string, number>();
  const seasonEpisodes = (url: string) => new URL(url, location.href).pathname.match(/\/cms\/seasons\/([^/]+)\/episodes\/?$/)?.[1] ?? null;

  function onEpisodes(seasonId: string, json: any) {
    const list: any[] = Array.isArray(json?.data) ? json.data : [];
    const first = list[0];
    const seriesId = text(first?.series_id);
    if (!seriesId) return;
    const show: FoundShow = series.get(seriesId) ?? {
      site: 'crunchyroll', id: seriesId, title: text(first.series_title),
      home: `https://www.crunchyroll.com/series/${seriesId}${first.series_slug_title ? `/${first.series_slug_title}` : ''}`,
      seasons: [], videoIds: [],
    };
    order.set(seasonId, Number(first.season_sequence_number ?? first.season_number ?? 0));
    const season = {
      id: seasonId,
      title: text(first.season_title) || `Season ${first.season_number ?? show.seasons.length + 1}`,
      episodes: list.filter((e) => e?.id).map((e): FoundEpisode => ({
        id: text(e.id),
        number: text(e.episode) || (e.episode_number != null ? text(e.episode_number) : null),
        title: text(e.title),
        url: `https://www.crunchyroll.com/watch/${e.id}${e.slug_title ? `/${e.slug_title}` : ''}`,
        minutes: e.duration_ms ? Math.round(Number(e.duration_ms) / 60000) : undefined,
        still: smallPicture(e.images?.thumbnail?.[0]),
      })),
    };
    show.seasons = [...show.seasons.filter((s) => s.id !== seasonId), season].sort((a, b) => (order.get(a.id) ?? 0) - (order.get(b.id) ?? 0));
    series.set(seriesId, show);
    sendShow(show);
  }

  function inspect(url: string, json: any) {
    if (!json || typeof json !== 'object') return;
    const subtitles = json.subtitles && !Array.isArray(json.subtitles) ? json.subtitles : null;
    const captions = json.captions && !Array.isArray(json.captions) ? json.captions : null;
    if (!subtitles && !captions) return;
    // the play service's URL ends in the episode's ID: /v1/{id}/web/{browser}/play
    const videoId = new URL(url, location.href).pathname.split('/').find((part) => /^[A-Z0-9]{9,}$/.test(part)) ?? 'unknown';
    for (const [set, isCaptions] of [[subtitles, false], [captions, true]] as const) {
      for (const [locale, file] of Object.entries<any>(set ?? {})) {
        const lang = language(locale);
        if (!lang || !wanted.includes(lang) || !file?.url) continue;
        const key = `${videoId}/${lang}/${isCaptions}`;
        if (seen.has(key)) continue;
        seen.add(key);
        const format = String(file.format ?? 'ass').toLowerCase();
        fetchFirst([file.url]).then((text) => {
          if (text) send({ site: 'crunchyroll', videoId, language: lang, label: locale, captions: isCaptions, name: `crunchyroll.${lang}.${format}`, text });
          else seen.delete(key);
        });
      }
    }
  }

  const originalFetch = window.fetch;
  window.fetch = async function (input: RequestInfo | URL, init?: RequestInit) {
    const response = await originalFetch.call(this, input, init);
    try {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
      const json = (response.headers.get('content-type') ?? '').includes('json');
      const season = seasonEpisodes(url);
      if (/\/play\b|playback/i.test(url) && json) {
        response.clone().json().then((json) => inspect(url, json), () => {});
      } else if (season && json) {
        response.clone().json().then((json) => onEpisodes(season, json), () => {});
      }
    } catch {}
    return response;
  };
  const open = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (this: XMLHttpRequest, method: string, url: string | URL, ...rest: any[]) {
    const href = String(url);
    const season = seasonEpisodes(href);
    if (/\/play\b|playback/i.test(href) || season) {
      this.addEventListener('load', () => {
        try {
          const json = this.responseType === 'json' ? this.response : JSON.parse(this.responseText);
          if (season) onEpisodes(season, json);
          else inspect(href, json);
        } catch {}
      });
    }
    return (open as any).call(this, method, url, ...rest);
  } as typeof XMLHttpRequest.prototype.open;
}

async function fetchFirst(urls: string[]): Promise<string | null> {
  for (const url of urls) {
    try {
      const response = await fetch(url, { credentials: 'omit' });
      if (response.ok) return await response.text();
    } catch {}
  }
  return null;
}

// ---- the pictures over the seek bar ---------------------------------------------------------

/**
 * The site's player downloads a BIF file of frames to show over its seek bar; Mizune's seek bar,
 * which takes its place, shows them too. They're picked up as the player downloads them, by how
 * they start, and from any address of one in the play service's answer (Crunchyroll's).
 */
function previews(videoId: () => string | null) {
  const sent = new Set<string>();

  function found(buffer: ArrayBuffer) {
    const id = videoId();
    if (!id || !isBif(buffer)) return;
    // a player may fetch them in more than one size; the first does
    if (sent.has(id)) return;
    sent.add(id);
    window.postMessage({ source: PAGE, kind: 'previews', videoId: id, bif: buffer.slice(0) }, '*');
  }

  const open = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (this: XMLHttpRequest, ...args: any[]) {
    this.addEventListener('load', () => {
      try {
        // only the start's looked at, so the video's own segments cost next to nothing
        if (this.responseType === 'arraybuffer' && this.response) found(this.response);
        else if (this.responseType === 'blob' && this.response?.size > 64) {
          const blob: Blob = this.response;
          blob.slice(0, 8).arrayBuffer().then((head) => {
            if (isBif(padded(head))) blob.arrayBuffer().then(found, () => {});
          }, () => {});
        }
      } catch {}
    });
    return (open as any).apply(this, args);
  } as typeof XMLHttpRequest.prototype.open;

  const originalFetch = window.fetch;
  window.fetch = async function (this: unknown, ...args: Parameters<typeof fetch>) {
    const response = await originalFetch.apply(this, args);
    try {
      const url = response.url || String(args[0] instanceof Request ? args[0].url : args[0]);
      if (/\.bif\b|\bbifs?\b|trickplay/i.test(url) || /bif/i.test(response.headers.get('content-type') ?? '')) {
        response.clone().arrayBuffer().then(found, () => {});
      } else if (/json/.test(response.headers.get('content-type') ?? '') && /\/play\b|playback/i.test(url)) {
        response.clone().json().then(fromJson, () => {});
      }
    } catch {}
    return response;
  };

  /** Fetches the first BIF file [json] gives the address of. */
  function fromJson(json: unknown) {
    const urls: string[] = [];
    const walk = (value: unknown, depth: number) => {
      if (depth > 8 || urls.length) return;
      if (typeof value === 'string') {
        if (/^https?:\/\/[^\s]+\.bif(\?|$)/i.test(value)) urls.push(value);
      } else if (value && typeof value === 'object') {
        for (const v of Object.values(value)) walk(v, depth + 1);
      }
    };
    walk(json, 0);
    if (urls[0]) originalFetch(urls[0], { credentials: 'omit' }).then((r) => (r.ok ? r.arrayBuffer() : null)).then((b) => b && found(b), () => {});
  }
}

/** [head] grown to a BIF header's length, so [isBif] can read the magic number in it. */
function padded(head: ArrayBuffer): ArrayBuffer {
  const out = new Uint8Array(64);
  out.set(new Uint8Array(head).subarray(0, 64));
  return out.buffer;
}

if (!window.__mizunePage) {
  window.__mizunePage = true;
  if (location.hostname.endsWith('netflix.com')) {
    netflix();
    previews(() => location.pathname.match(/\/watch\/(\d+)/)?.[1] ?? null);
  } else if (location.hostname.endsWith('crunchyroll.com')) {
    crunchyroll();
    previews(() => location.pathname.match(/\/watch\/([A-Z0-9]+)/)?.[1] ?? null);
  }
}
