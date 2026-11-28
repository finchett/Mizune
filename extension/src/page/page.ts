// Runs in the site's own JavaScript world, from before the site's scripts, and picks up the
// subtitle files Netflix's, Crunchyroll's and YouTube's players get, and the pictures Netflix and
// Crunchyroll show over their seek bars. What it finds goes to the content script (content/main.ts).
// On Netflix and YouTube it also works the player for it, through the site's own player: setting
// the video's time directly upsets Netflix's, and YouTube's has settings of its own.
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
/**
 * While Mizune's on, Netflix's own ways to full screen (a double click on the video, F) take the
 * whole page there, as Mizune's button does, rather than Netflix's player: the browser holds what
 * goes full screen to the whole screen, over the room the player was making for the study panel,
 * and the picture and what Netflix centres on it (its play and pause) would come apart.
 */
function wholePageFullscreen() {
  let on = false;
  const request = Element.prototype.requestFullscreen;
  Element.prototype.requestFullscreen = function (this: Element, options?: FullscreenOptions) {
    const page = document.documentElement;
    if (!on || this === page) return request.call(this, options);
    // Netflix asking again, as it would were it its player there: it's a toggle, so out
    if (document.fullscreenElement === page) return document.exitFullscreen();
    return request.call(page, options);
  };
  return (value: boolean) => (on = value);
}

function netflix() {
  const fullscreenWhole = wholePageFullscreen();
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
    if (kind === 'wholePageFullscreen') return void fullscreenWhole(!!event.data.on);
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

// ---- YouTube --------------------------------------------------------------------------------

/**
 * YouTube's captions in its own JSON (json3) as WebVTT: a line each event with words in it. Made
 * by machine (asr), YouTube shows its lines two at a time, rolling up, each until the one after
 * the next: here a line lasts until the next starts.
 */
function json3ToVtt(json: any, rolling: boolean): string {
  const events: { start: number; end: number; text: string }[] = [];
  for (const event of Array.isArray(json?.events) ? json.events : []) {
    if (!Array.isArray(event.segs) || event.aAppend) continue;
    const text = event.segs.map((s: any) => text_(s?.utf8)).join('').replace(/\n\s*\n/g, '\n').trim();
    const start = Number(event.tStartMs);
    if (!text || !Number.isFinite(start)) continue;
    events.push({ start, end: start + Number(event.dDurationMs ?? 0), text });
  }
  events.sort((a, b) => a.start - b.start);
  if (rolling) events.forEach((e, i) => (e.end = Math.min(e.end, events[i + 1]?.start ?? e.end)));
  const time = (ms: number) => {
    const t = Math.max(0, Math.round(ms));
    const pad = (n: number, width = 2) => String(n).padStart(width, '0');
    return `${pad(Math.floor(t / 3600000))}:${pad(Math.floor(t / 60000) % 60)}:${pad(Math.floor(t / 1000) % 60)}.${pad(t % 1000, 3)}`;
  };
  return 'WEBVTT\n\n' + events.filter((e) => e.end > e.start).map((e) => `${time(e.start)} --> ${time(e.end)}\n${e.text}\n`).join('\n');
}

const text_ = (value: unknown) => (typeof value === 'string' ? value : '');

/**
 * YouTube's player asks for the captions it shows with a token of its own, made where the page
 * can't, and the same address asked again gets nothing; so, as on Netflix, the files are picked
 * up as the player downloads them, and to get Japanese and the peek language the player is
 * switched to each for a moment, its own captions hidden, then back to what it was showing.
 * Machine-made captions (asr) count as captions, so a person's subtitles come first; and where
 * the video has no track in the peek language, YouTube's translation of the Japanese stands in.
 */
function youtube() {
  /** videoId/language and videoId/track ID: what's been found already. */
  const found = new Set<string>();
  const player = (): any => document.getElementById('movie_player');
  const watchId = () => (location.pathname === '/watch' ? new URLSearchParams(location.search).get('v') : null);

  /** Every caption track the video has, as the player's response lists them (machine-made too). */
  function captionTracks(p: any): any[] {
    const response = p?.getPlayerResponse?.();
    // the response can still be the last video's, just after the page moves to another
    if (!response || response.videoDetails?.videoId !== watchId()) return [];
    return response.captions?.playerCaptionsTracklistRenderer?.captionTracks ?? [];
  }
  const trackName = (t: any) => text_(t?.name?.simpleText) || (t?.name?.runs ?? []).map((r: any) => text_(r?.text)).join('') || text_(t?.languageCode);
  const isAsr = (t: any) => t?.kind === 'asr';
  const trackId = (t: any) => text_(t?.vssId ?? t?.vss_id);

  /** A track's ID with a translation into [to], as the track menu knows it. */
  const translatedId = (from: string, to: string) => `${from}>${to}`;

  /** The language names YouTube translates into, by their code. */
  function translations(p: any): Map<string, string> {
    const list = p?.getPlayerResponse?.()?.captions?.playerCaptionsTracklistRenderer?.translationLanguages ?? [];
    return new Map(list.map((l: any) => [text_(l.languageCode), text_(l.languageName?.simpleText) || (l.languageName?.runs ?? []).map((r: any) => text_(r?.text)).join('')]));
  }

  /** The Japanese track a translation's made from: a person's, else the machine's. */
  const japanese = (tracks: any[]) => tracks.filter((t) => language(t.languageCode) === 'ja').sort((a, b) => Number(isAsr(a)) - Number(isAsr(b)))[0];

  function onCaptions(url: string, body: string) {
    if (!/\/api\/timedtext\b/.test(url) || !body) return;
    const params = new URL(url, location.href).searchParams;
    const videoId = params.get('v');
    const lang = language(params.get('lang') ?? '');
    if (!videoId || !lang) return;
    let json: any;
    try {
      json = JSON.parse(body);
    } catch {
      return;
    }
    const tlang = language(params.get('tlang') ?? '');
    const asr = params.get('kind') === 'asr';
    const tracks = captionTracks(player());
    // (of two in a language, the one the address names)
    const same = tracks.filter((t) => language(t.languageCode) === lang && isAsr(t) === asr);
    const source = same.find((t) => new URL(text_(t.baseUrl), location.href).searchParams.get('name') === params.get('name')) ?? same[0];
    const id = source ? trackId(source) : `${asr ? 'a' : ''}.${lang}`;
    const label = tlang
      ? `${translations(player()).get(tlang) || tlang} (auto-translated)`
      : source ? trackName(source) : lang;
    const finalLanguage = tlang ?? lang;
    found.add(`${videoId}/${finalLanguage}`);
    found.add(`${videoId}/${tlang ? translatedId(id, tlang) : id}`);
    send({ site: 'youtube', videoId, language: finalLanguage, label, captions: asr || !!tlang, machine: asr || !!tlang, name: `youtube.${finalLanguage}.vtt`, text: json3ToVtt(json, asr) });
  }

  const open = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (this: XMLHttpRequest, ...args: any[]) {
    const url = String(args[1] ?? '');
    if (/\/api\/timedtext\b/.test(url)) {
      this.addEventListener('load', () => {
        try {
          if (this.responseType === '' || this.responseType === 'text') onCaptions(url, this.responseText);
          else if (this.responseType === 'json') onCaptions(url, JSON.stringify(this.response));
          else if (this.responseType === 'arraybuffer') onCaptions(url, new TextDecoder().decode(this.response));
        } catch {}
      });
    }
    return (open as any).apply(this, args);
  } as typeof XMLHttpRequest.prototype.open;

  const originalFetch = window.fetch;
  window.fetch = async function (this: unknown, ...args: Parameters<typeof fetch>) {
    const response = await originalFetch.apply(this, args);
    try {
      const url = response.url || String(args[0] instanceof Request ? args[0].url : args[0]);
      if (/\/api\/timedtext\b/.test(url)) response.clone().text().then((body) => onCaptions(url, body), () => {});
    } catch {}
    return response;
  };

  /** Hides YouTube's own captions while the player's switched to fetch a track. */
  let hiding: HTMLStyleElement | null = null;
  function hideCaptions(hide: boolean) {
    if (hide && !hiding) {
      hiding = document.createElement('style');
      hiding.textContent = '#movie_player .ytp-caption-window-container { visibility: hidden !important; }';
      document.documentElement.append(hiding);
    } else if (!hide) {
      hiding?.remove();
      hiding = null;
    }
  }

  /** The player once it has [videoId] and its caption tracks, after any adverts before it. */
  async function ready(videoId: string, withinMs = 30000) {
    for (let waited = 0; waited < withinMs; waited += 250) {
      const p = player();
      if (watchId() !== videoId) return null;
      const advert = p?.classList?.contains('ad-showing') || p?.classList?.contains('ad-interrupting');
      if (!advert && p?.setOption && p.getPlayerResponse?.()?.videoDetails?.videoId === videoId) return p;
      await new Promise((r) => setTimeout(r, 250));
    }
    return null;
  }

  /** A queue, so one switch of the player's captions finishes before the next. */
  let queue: Promise<unknown> = Promise.resolve();
  const inTurn = <T>(run: () => Promise<T>): Promise<T> => {
    const next = queue.then(run, run);
    queue = next.catch(() => undefined);
    return next;
  };

  /**
   * Switches the player's captions to [track] (with a translation into [to]) until its file has
   * come, then back to what it was showing (or none).
   */
  async function fetchTrack(p: any, videoId: string, track: any, to?: string) {
    const key = `${videoId}/${to ? translatedId(trackId(track), to) : trackId(track)}`;
    if (found.has(key)) return true;
    const showing = p.getOption?.('captions', 'track');
    hideCaptions(true);
    try {
      p.loadModule?.('captions');
      const option = {
        languageCode: track.languageCode, kind: track.kind ?? '', vss_id: trackId(track), name: trackName(track),
        ...(to ? { translationLanguage: { languageCode: to, languageName: translations(p).get(to) ?? to } } : {}),
      };
      // the player's own list has the track by its ID (the machine's too, when asked for: it leaves
      // them out where a person's is in the same language); asked by language alone, it picks its favourite
      const listed = (p.getOption?.('captions', 'tracklist', { includeAsr: true }) ?? []).find((t: any) => t.vss_id === option.vss_id);
      p.setOption('captions', 'track', listed ? { ...listed, ...(option.translationLanguage ? { translationLanguage: option.translationLanguage } : {}) } : option);
      for (let waited = 0; waited < 8000 && !found.has(key); waited += 100) await new Promise((r) => setTimeout(r, 100));
    } finally {
      p.setOption('captions', 'track', showing?.languageCode ? showing : {});
      // its own captions come back after a moment, for what it shows by itself
      setTimeout(() => hideCaptions(false), 400);
    }
    return found.has(key);
  }

  /** The wanted languages' files that haven't come yet, for a video with Japanese captions. */
  const asked = new Set<string>();
  async function fetchSubtitles() {
    const videoId = watchId();
    if (!videoId || asked.has(videoId)) return;
    asked.add(videoId);
    // (adverts can go on for minutes; the content script asks again until there's Japanese)
    const p = await ready(videoId, 5 * 60000);
    if (!p) return void asked.delete(videoId);
    const tracks = captionTracks(p);
    const ja = japanese(tracks);
    // a video without Japanese keeps its captions as they are
    if (!ja) return;
    for (const lang of wanted) {
      if (found.has(`${videoId}/${lang}`) || watchId() !== videoId) continue;
      // a person's subtitles before the machine's
      const own = tracks.filter((t) => language(t.languageCode) === lang).sort((a, b) => Number(isAsr(a)) - Number(isAsr(b)))[0];
      if (own && (!isAsr(own) || lang === 'ja')) await inTurn(() => fetchTrack(p, videoId, own));
      else if (lang !== 'ja' && translations(p).has(lang)) await inTurn(() => fetchTrack(p, videoId, ja, lang));
      else if (own) await inTurn(() => fetchTrack(p, videoId, own));
    }
  }

  /** What the track menu offers: the video's caption tracks, translations into the wanted languages, and its audio. */
  function trackList(p: any) {
    const tracks = captionTracks(p);
    const subtitles = tracks.map((t) => ({ id: trackId(t), language: language(t.languageCode), label: trackName(t), captions: isAsr(t), machine: isAsr(t) }));
    const ja = japanese(tracks);
    const names = translations(p);
    for (const lang of wanted) {
      if (lang === 'ja' || !ja || tracks.some((t) => language(t.languageCode) === lang && !isAsr(t)) || !names.has(lang)) continue;
      subtitles.push({ id: translatedId(trackId(ja), lang), language: lang, label: `${names.get(lang)} (auto-translated)`, captions: true, machine: true });
    }
    const current = audioId(p.getAudioTrack?.());
    const audio = (p.getAvailableAudioTracks?.() ?? []).map((t: any) => ({
      id: audioId(t), label: audioName(t), language: audioLanguage(t), selected: audioId(t) === current,
    }));
    return { subtitles, audio: audio.length > 1 ? audio : [] };
  }
  const audioId = (t: any) => text_(t?.getLanguageInfo?.()?.getId?.() ?? t?.id);
  // ("ja.4", "en-US.10")
  const audioLanguage = (t: any) => language(text_(t?.getLanguageInfo?.()?.getId?.()).split('.')[0]);
  const audioName = (t: any) => {
    const info = t?.getLanguageInfo?.();
    const name = text_(info?.getName?.()) || audioId(t);
    return info?.getIsAutoDubbed?.() ? `${name} (auto-dubbed)` : name;
  };

  async function fetchOne(id: string) {
    const videoId = watchId();
    const p = videoId ? await ready(videoId) : null;
    if (!p || !videoId) return false;
    const [from, to] = id.split('>');
    const track = captionTracks(p).find((t) => trackId(t) === from);
    return track ? inTurn(() => fetchTrack(p, videoId, track, to)) : false;
  }

  /** The player's own settings, for Mizune's menu, which takes the place of YouTube's. */
  function playback(p: any) {
    const levels: string[] = (p.getAvailableQualityLevels?.() ?? []).filter((q: string) => q !== 'auto');
    const labels: string[] = p.getAvailableQualityLabels?.() ?? [];
    const preferred = p.getUserPlaybackQualityPreference?.() ?? p.getPreferredQuality?.();
    return {
      rates: p.getAvailablePlaybackRates?.() ?? [],
      rate: p.getPlaybackRate?.() ?? 1,
      qualities: levels.map((id, i) => ({ id, label: labels[i] ?? id })),
      // "auto", or the one chosen
      quality: levels.includes(preferred) ? preferred : 'auto',
      playing: p.getPlaybackQualityLabel?.() ?? '',
      autoplay: document.querySelector('.ytp-autonav-toggle-button')?.getAttribute('aria-checked') === 'true',
      loop: !!p.getLoopVideo?.(),
    };
  }

  function setPlayback(p: any, change: any) {
    if (typeof change.rate === 'number') p.setPlaybackRate?.(change.rate);
    if (typeof change.quality === 'string') {
      if (change.quality === 'auto') p.setPlaybackQualityRange?.('auto', 'auto');
      else p.setPlaybackQualityRange?.(change.quality, change.quality);
      p.setPlaybackQuality?.(change.quality);
    }
    if (typeof change.loop === 'boolean') p.setLoopVideo?.(change.loop);
    if (typeof change.autoplay === 'boolean') {
      // as its own switch does, which keeps the choice for the next visit
      const button = document.querySelector<HTMLElement>('.ytp-autonav-toggle-button');
      if (button && (button.getAttribute('aria-checked') === 'true') !== change.autoplay) (button.closest('button') ?? button).click();
      else if (!button) p.setAutonav?.(change.autoplay);
    }
  }

  // ---- the rest of YouTube's settings menu ----

  /**
   * YouTube's settings menu has more than its player says it has (stable volume, ambient mode, a
   * sleep timer, and more as YouTube adds them), in the viewer's own language. So Mizune's menu
   * shows what YouTube's does, read from YouTube's menu, opened out of sight; and choosing one
   * chooses it there. What Mizune has its own way to (quality, speed, subtitles, audio) is left out.
   */
  const MENU_HIDDEN = '#movie_player .ytp-settings-menu { opacity: 0 !important; pointer-events: none !important; }';
  let menuQueue: Promise<unknown> = Promise.resolve();
  const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
  /** Waits until [done], for up to [ms] by the clock (a hidden tab's timers are slow, so not by counting). */
  async function until(done: () => boolean, ms = 1000) {
    for (const end = Date.now() + ms; !done() && Date.now() < end; ) await sleep(50);
    return done();
  }

  /** Runs [run] with YouTube's settings menu open (and unseen), then closes it. */
  function withMenu<T>(p: any, run: (menu: HTMLElement) => Promise<T>): Promise<T | null> {
    const next = menuQueue.then(async () => {
      const button = p.querySelector('.ytp-settings-button') as HTMLElement | null;
      // (only while it's seen: a hidden tab's timers are too slow to go through it)
      if (!button || document.hidden) return null;
      const style = document.createElement('style');
      style.textContent = MENU_HIDDEN;
      document.documentElement.append(style);
      const menu = () => p.querySelector('.ytp-settings-menu') as HTMLElement | null;
      const open = () => !!menu() && getComputedStyle(menu()!).display !== 'none';
      try {
        if (open()) {
          button.click();
          await sleep(250);
        }
        button.click();
        return (await until(() => open() && panelItems(menu()!).length > 0)) ? await run(menu()!) : null;
      } finally {
        if (open()) button.click();
        // (it fades out)
        setTimeout(() => style.remove(), 400);
      }
    });
    menuQueue = next.catch(() => undefined);
    return next.catch(() => null);
  }

  /** The items of the menu's panel showing now. */
  const panelItems = (menu: HTMLElement) => {
    const panels = menu.querySelectorAll('.ytp-panel');
    return [...(panels[panels.length - 1]?.querySelectorAll<HTMLElement>('.ytp-menuitem') ?? [])];
  };
  const itemLabel = (item: HTMLElement) => ((item.querySelector('.ytp-menuitem-label') as HTMLElement | null)?.innerText ?? '').replace(/\s*\n+\s*/g, ' · ').trim();

  /** Opens the submenu under [label]; its choices, once they're there. */
  async function openSubmenu(menu: HTMLElement, label: string): Promise<HTMLElement[] | null> {
    const item = panelItems(menu).find((i) => itemLabel(i) === label);
    if (!item) return null;
    const before = panelItems(menu)[0];
    item.click();
    // (the speed's has no items, only a slider)
    const opened = () => { const items = panelItems(menu); return (items.length > 0 && items[0] !== before) || !!menu.querySelector('.ytp-panel-header'); };
    return (await until(opened)) ? panelItems(menu) : null;
  }

  async function readSettings(p: any) {
    const captions = new Set(captionTracks(p).map(trackName));
    const audio = new Set((p.getAvailableAudioTracks?.() ?? []).map((t: any) => text_(t.getLanguageInfo?.()?.getName?.())));
    const qualities: string[] = [p.getPlaybackQualityLabel?.(), ...(p.getAvailableQualityLabels?.() ?? [])].filter(Boolean);
    return withMenu(p, async (menu) => {
      const settings: any[] = [];
      const top = panelItems(menu).map((item) => ({
        label: itemLabel(item), role: item.getAttribute('role'), checked: item.getAttribute('aria-checked') === 'true',
        submenu: item.getAttribute('aria-haspopup') === 'true',
        value: ((item.querySelector('.ytp-menuitem-content') as HTMLElement | null)?.innerText ?? '').trim(),
      }));
      for (const item of top) {
        if (item.role === 'menuitemcheckbox') settings.push({ kind: 'switch', label: item.label, on: item.checked });
        if (!item.submenu) continue;
        // Mizune's own: the audio, and the quality, by what they say is playing
        if (audio.has(item.value) || qualities.some((q) => item.value.includes(q))) continue;
        const items = await openSubmenu(menu, item.label);
        if (!items) break;
        const choices = items.filter((i) => i.getAttribute('role') === 'menuitemradio').map((i) => ({ label: itemLabel(i), selected: i.getAttribute('aria-checked') === 'true' }));
        const panels = menu.querySelectorAll('.ytp-panel');
        const panel = panels[panels.length - 1];
        // and the speed (a slider), and the subtitles (the video's caption tracks)
        const own = !!panel?.querySelector('[role=slider]') || choices.some((c) => captions.has(c.label) || audio.has(c.label) || qualities.includes(c.label));
        if (!own && choices.length > 1) settings.push({ kind: 'choices', label: item.label, value: item.value, choices });
        // back to the top, by closing it and opening it again
        (p.querySelector('.ytp-settings-button') as HTMLElement).click();
        await sleep(250);
        (p.querySelector('.ytp-settings-button') as HTMLElement).click();
        if (document.hidden || !(await until(() => panelItems(menu).some((i) => itemLabel(i) === item.label)))) break;
      }
      return settings;
    });
  }

  /** Flips the switch [label], or chooses [choice] under it. */
  async function chooseSetting(p: any, label: string, choice?: string) {
    return withMenu(p, async (menu) => {
      if (choice == null) {
        const item = panelItems(menu).find((i) => itemLabel(i) === label);
        item?.click();
        return !!item;
      }
      const items = await openSubmenu(menu, label);
      const item = items?.find((i) => itemLabel(i) === choice);
      item?.click();
      await sleep(100);
      return !!item;
    });
  }

  window.addEventListener('message', (event) => {
    if (event.source !== window || event.data?.source !== CONTENT) return;
    const { kind, seconds, level, muted } = event.data;
    if (kind === 'subtitles') {
      if (Array.isArray(event.data.languages)) wanted = event.data.languages;
      fetchSubtitles().catch(() => {});
      return answer(event.data.id, true);
    }
    if (kind === 'moreSettings' || kind === 'chooseSetting') {
      const p = player();
      if (!p?.getPlayerState) return answer(event.data.id, false);
      const done = kind === 'moreSettings' ? readSettings(p) : chooseSetting(p, String(event.data.label), event.data.choice);
      done.then((data) => answer(event.data.id, data != null, data), () => answer(event.data.id, false));
      return;
    }
    if (kind === 'fetchTrack') {
      fetchOne(event.data.track).then((ok) => answer(event.data.id, ok), () => answer(event.data.id, false));
      return;
    }
    const p = player();
    if (!p?.getPlayerState) return answer(event.data.id, false);
    if (kind === 'hasJapanese') {
      // only once the player has this video, past its adverts
      const videoId = watchId();
      const advert = p.classList?.contains('ad-showing') || p.classList?.contains('ad-interrupting');
      if (!videoId || advert || p.getPlayerResponse?.()?.videoDetails?.videoId !== videoId) return answer(event.data.id, false);
      return answer(event.data.id, true, !!japanese(captionTracks(p)));
    }
    if (kind === 'trackList') return answer(event.data.id, true, trackList(p));
    if (kind === 'playback') return answer(event.data.id, true, playback(p));
    if (kind === 'setPlayback') {
      setPlayback(p, event.data.change ?? {});
      return answer(event.data.id, true, playback(p));
    }
    if (kind === 'audio') {
      const track = (p.getAvailableAudioTracks?.() ?? []).find((t: any) => audioId(t) === event.data.track);
      if (!track || !p.setAudioTrack) return answer(event.data.id, false);
      p.setAudioTrack(track);
      return answer(event.data.id, true);
    }
    if (kind === 'originalAudio') {
      // YouTube can play a machine's dub by itself; Japanese is to be heard in Japanese
      const tracks: any[] = p.getAvailableAudioTracks?.() ?? [];
      const playing = p.getAudioTrack?.();
      const original = tracks.find((t) => audioLanguage(t) === 'ja' && !t.getLanguageInfo?.()?.getIsAutoDubbed?.());
      const dubbed = !!playing?.getLanguageInfo?.()?.getIsAutoDubbed?.();
      if (original && dubbed && audioId(original) !== audioId(playing)) p.setAudioTrack?.(original);
      return answer(event.data.id, !!original);
    }
    if (kind === 'seek') p.seekTo(seconds, true);
    else if (kind === 'play') p.playVideo();
    else if (kind === 'pause') p.pauseVideo();
    else if (kind === 'volume') {
      if (typeof p.setVolume !== 'function') return answer(event.data.id, false);
      p.setVolume(Math.round(level * 100));
      if (muted) p.mute?.();
      else p.unMute?.();
    }
    answer(event.data.id, true);
  });
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
  } else if (location.hostname.endsWith('youtube.com')) {
    youtube();
  }
}
