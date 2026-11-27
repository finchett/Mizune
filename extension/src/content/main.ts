// Mizune on a page with a video: finds the Japanese line for the moment, shows it over the
// video, looks words up in Mizune, and makes Anki cards with the line's audio and a picture.
// Lines move as in the app (PlayerSession.kt): previous, next, replay, and stopping at a line's end.
import { FrameCollector } from '../animated-webp';
import { ANIMATION, api, blobBase64, isOff, Colors, Cue, DEFAULT_THEME, encodeStill, Entry, FoundShow, FoundTrack, Hello, isGecko, listen, LookupResult, meanLuma, mergeShow, PAGE, StillFormat, TabStatus, TabVideo, showHas, toBase64, ToBackground, ToContent } from '../shared';
import { Controls, ICONS, LineStop } from './controls';
import { Episodes } from './episodes';
import { Menu, MenuItem } from './menu';
import { actionFor, isTyping } from './keys';
import { Overlay } from './overlay';
import { Previews } from './previews';
import { CARD_GAP, clampFraction, DEFAULT_FRACTION, Panel } from './panel';
import { NoAudio, startRecording } from './recorder';
import { largestVideo, Site, siteFor, SiteTracks } from './site';
import { Tutorial } from './tutorial';

declare global {
  interface Window {
    __mizuneContent?: boolean;
  }
}

/** Mizune's own messages need at least this; older apps are asked to update. */
const PROTOCOL = 1;
/** Pause slightly early so the next line's first syllable isn't heard. */
const END_MARGIN = 0.05;
/** Paused less than this into a line counts as at its start, where "previous" goes further back. */
const REPLAY_MARGIN = 0.5;
const SUBTITLE_FILE = /\.(srt|ass|ssa|vtt|ttml|dfxp|xml)$/i;

const background = (message: ToBackground): Promise<any> => api.runtime.sendMessage(message);
const host = (message: Record<string, unknown>): Promise<any> => background({ type: 'host', message });

interface Track {
  cues: Cue[];
  label: string;
  fromFile: boolean;
  /** Its language, and whether it's captions (see [trackKey]); none for a file of the user's. */
  key?: string;
}

/** What a track menu choice is remembered by, from one video to the next: "ja", or "ja+cc" for captions. */
/**
 * The site's pictures of each video it's played, for over the seek bar, by its ID: they come as the
 * site's player starts, which can be before Mizune does. The latest few are kept.
 */
const previews = new Map<string, Previews>();

function addPreviews(videoId: string, bif: ArrayBuffer) {
  const found = Previews.from(bif);
  if (!found) return;
  previews.get(videoId)?.dispose();
  previews.delete(videoId);
  previews.set(videoId, found);
  for (const [id, old] of previews) {
    if (previews.size <= 3) break;
    old.dispose();
    previews.delete(id);
  }
}

const trackKey = (track: { language: string; captions: boolean }) => track.language + (track.captions ? '+cc' : '');

/** The app's words for peeking with nothing to show (PlayerScreen.kt). */
const PEEK_OFF = 'Peeking is off. Choose a peek language in Mizune’s settings, or a translation track under ⚙.';

interface Lookup {
  lineIndex: number;
  text: string;
  start: number;
  length?: number;
  result: LookupResult | null;
}

class Session {
  private site: Site = siteFor(location.hostname);
  private overlay: Overlay;
  private on = false;
  private hello: Hello | null = null;
  private primary: Track | null = null;
  private secondary: Track | null = null;
  private title: string | null = null;
  private offset = 0;
  private lineIndex = -1;
  private lineActive = false;
  private stopAfterLine = -1;
  private keepPlaying = false;
  private autoPausedLine = -1;
  private autoPause = false;
  private lineHidden = false;
  private peeking = false;
  private lookup: Lookup | null = null;
  private lastLookup: Lookup | null = null;
  private lookupSerial = 0;
  private pausedForLookup = false;
  private busy = false;
  private notes = new Map<string, number>();
  private frame = 0;
  private trackKey = '';
  private parsed = new Map<string, Cue[]>();
  private dismissedPrompt = false;
  /** Turned off with the button: stays off on this page until it's clicked again. */
  stoppedByUser = false;
  private shownVideoId: string | null = null;
  /** Mizune's controls, on a site whose own it replaces (Netflix). */
  private controls: Controls | null = null;
  /** The way back and the episode list, over the video and at the head of the panel. */
  private episodes: Episodes | null = null;
  /** The video the page was last asked about its show for. */
  private askedShow: string | null = null;
  /**
   * The shows found here, kept as well as the background keeps them: it keeps only the last few,
   * and Firefox and Chrome stop it when it's idle, which forgets them.
   */
  private knownShows = new Map<string, FoundShow>();
  /** The app's study panel, beside a site's player that makes room for it (Netflix). */
  private panel: Panel | null = null;
  /** Put away with S, as the app's full view, which has the line over the video again. */
  private panelHidden = false;
  /** The line the panel last looked a word up in by itself. */
  private autoLookedUp = -1;
  /** The panel's line is held down, to peek. */
  private holding = false;
  /** Paused by a stop at a line's end, rather than by the user. */
  private stoppedAtLine = false;
  /** The panel's been out since Mizune started here, so it slides from now on. */
  private panelPlaced = false;
  private copiedLine = -1;
  /** The picture fills the area, as the app's Z. */
  private fill = false;
  /** As the app's Rounded corners setting: the video and panel as cards with a gap; off by default here. */
  private rounded = false;
  /** The track menu (⚙), from the panel's bottom row or the bar. */
  private menu: Menu;
  /** What the track menu chose, kept for the videos after: the subtitles to study, and the translation. */
  private choice: { study?: string; translation?: string } = {};
  /** The tracks the site's player has, as it last said. */
  private siteTracks: SiteTracks | null = null;
  /** Tracks the site's being asked for, by their key. */
  private fetching = new Set<string>();
  /** The first-play tutorial, the first time there are lines to study. */
  private tutorial = new Tutorial(() => {
    api.storage.local.set({ tutorialSeen: true }).catch(() => {});
    this.site.play();
  });
  private tutorialAsked = false;

  constructor() {
    this.overlay = new Overlay({
      point: (index) => this.lookUp(index),
      select: (start, end) => this.lookUp(start, end - start),
      leave: () => this.closeLookup(),
      add: (entry) => this.addCard(entry),
      remove: (entry) => this.removeCard(entry),
      file: (file) => this.useFile(file),
    });
    if (this.site.ownControls) {
      this.episodes = new Episodes({
        back: () => this.site.ownControls?.back(),
        open: (url) => background({ type: 'openPage', url }).catch(() => {}),
        listOpen: (open) => {
          if (open) this.menu.close();
          if (this.controls) this.controls.menuOpen = open || this.menu.isOpen;
        },
      });
      this.controls = new Controls({
        togglePlay: () => (this.site.video()?.paused ? this.site.play() : this.site.pause()),
        seek: (seconds) => this.site.seek(seconds),
        previousLine: () => this.previousLine(this.lineStop === 'every'),
        replayLine: () => this.replayLine(true),
        nextLine: () => this.nextLine(this.lineStop === 'every'),
        toggleStop: () => this.setAutoPause(this.lineStop !== 'every'),
        setVolume: (level) => this.site.setVolume(level, false),
        toggleMute: () => {
          const video = this.site.video();
          if (video) this.site.setVolume(video.volume, !video.muted);
        },
        fullscreen: () => this.toggleFullscreen(),
        togglePanel: () => this.togglePanel(),
        trackMenu: (anchor) => {
          this.episodes?.close();
          this.openTrackMenu(anchor);
        },
      });
      this.controls.backCorner.append(this.episodes.pill());
      this.overlay.mount(this.controls.element);
      this.overlay.mount(this.controls.backCorner);
    }
    if (this.site.makeRoom) {
      this.panel = new Panel({
        point: (index) => this.lookUp(index),
        select: (start, end) => this.lookUp(start, end - start),
        hold: (on) => (this.holding = on),
        previousLine: () => this.previousLine(this.lineStop === 'every'),
        replayLine: () => this.replayLine(true),
        nextLine: () => this.nextLine(this.lineStop === 'every'),
        toggleStop: () => this.setAutoPause(this.lineStop !== 'every'),
        add: (entry) => this.addCard(entry),
        remove: (entry) => this.removeCard(entry),
        trackMenu: (anchor) => {
          this.episodes?.close();
          this.openTrackMenu(anchor);
        },
        resized: (fraction) => this.saveView({ panelFraction: fraction }),
      });
      this.panel.setFraction(DEFAULT_FRACTION);
      this.panel.setRounded(false);
      this.overlay.mountBeside(this.panel.element);
    }
    api.storage.local.get('view').then(({ view }: any) => {
      if (!view) return;
      this.panelHidden = !!view.panelHidden;
      this.fill = !!view.fill;
      this.setRounded(!!view.rounded);
      this.choice = view.tracks ?? {};
      this.panel?.setFraction(clampFraction(view.panelFraction ?? DEFAULT_FRACTION));
    }).catch(() => {});
    this.menu = new Menu((open) => {
      if (this.controls) this.controls.menuOpen = open || !!this.episodes?.isOpen;
    });
    this.overlay.mountBeside(this.menu.element);
    if (this.episodes) this.overlay.mountBeside(this.episodes.list);
    this.overlay.mountBeside(this.tutorial.element);
    this.onPointerMove = this.onPointerMove.bind(this);
    this.onWheel = this.onWheel.bind(this);
    this.onPointerOut = this.onPointerOut.bind(this);
    this.onKeyDown = this.onKeyDown.bind(this);
    this.onKeyUp = this.onKeyUp.bind(this);
    this.onDrag = this.onDrag.bind(this);
    this.onDrop = this.onDrop.bind(this);
  }

  /** Netflix and Crunchyroll start by themselves once a Japanese file turns up. */
  get automatic() {
    return this.site.kind !== 'other';
  }

  async toggle() {
    this.stoppedByUser = this.on;
    if (this.on) this.stop();
    else await this.start(true);
  }

  get running() {
    return this.on;
  }

  get videoId() {
    return this.site.videoId();
  }

  get watching() {
    return this.site.watching();
  }

  /** Gets the site to download its Japanese subtitles and the peek language's, where it only downloads what it shows. */
  requestSubtitles() {
    const peek = this.peekLanguage;
    this.site.requestSubtitles?.(peek && peek !== 'ja' ? ['ja', peek] : ['ja']);
  }

  /** The app's peek language (null while peeking's off), or English without the app's say. */
  private get peekLanguage(): string | null {
    return this.hello?.peekLanguage === undefined ? 'en' : this.hello.peekLanguage;
  }

  async start(asked: boolean) {
    if (this.on) return;
    this.on = true;
    this.dismissedPrompt = false;
    this.overlay.attach();
    addEventListener('keydown', this.onKeyDown, true);
    addEventListener('keyup', this.onKeyUp, true);
    addEventListener('dragover', this.onDrag, true);
    addEventListener('drop', this.onDrop, true);
    addEventListener('dragleave', this.onDrag, true);
    addEventListener('pointermove', this.onPointerMove, true);
    addEventListener('wheel', this.onWheel, { capture: true, passive: false });
    document.addEventListener('pointerout', this.onPointerOut, true);
    document.addEventListener('fullscreenchange', () => this.overlay.attach());
    this.site.ownControls?.hide(true);
    this.systemDark.addEventListener('change', this.onSystemTheme);
    this.tick();

    const hello = await host({ type: 'hello' });
    if (!this.on) return;
    if (hello?.type !== 'hello') return this.notSetUp(hello?.message);
    if (hello.protocol < PROTOCOL) {
      return this.overlay.prompt('Mizune needs updating', `This extension needs a newer Mizune than ${hello.version}. Update it from its settings, or from mizune.app.`, [
        { label: 'Close', run: () => this.stop() },
      ]);
    }
    this.useHello(hello);
    if (hello.local && hello.dictionaries === 0) this.needsDictionary();
    await this.loadTracks(asked);
  }

  private useHello(hello: Hello) {
    // an app from before Z worked in the browser
    hello.keys = { FillFit: ['Z'], SeekBack: ['Y', 'Shift+Left'], SeekForward: ['O', 'Shift+Right'], ...hello.keys };
    this.hello = hello;
    this.autoPause = hello.autoPause;
    this.applyTheme();
  }

  /** The settings changed (in the options, the popup or the app): takes them up as they are now. */
  async settingsChanged() {
    // the options' own, for how the video looks
    const { view } = (await api.storage.local.get('view').catch(() => ({}))) as { view?: { fill?: boolean; rounded?: boolean } };
    this.fill = !!view?.fill;
    this.setRounded(!!view?.rounded);
    if (!this.on || !this.hello) return;
    const hello = await host({ type: 'hello' });
    if (this.on && hello?.type === 'hello') this.useHello(hello);
  }

  /** What it's doing, for the toolbar's popup. */
  status(): TabStatus {
    const cues = this.primary?.cues;
    const cue = cues?.[Math.max(0, this.lineIndex)];
    return {
      type: 'status',
      site: this.site.kind,
      watching: this.site.watching(),
      on: this.on,
      stoppedByUser: this.stoppedByUser,
      title: this.title ?? this.site.title(),
      lines: cues?.length || null,
      line: this.on && cue ? cue.text : null,
      at: Math.max(0, this.lineIndex),
    };
  }

  stop() {
    this.on = false;
    cancelAnimationFrame(this.frame);
    removeEventListener('keydown', this.onKeyDown, true);
    removeEventListener('keyup', this.onKeyUp, true);
    removeEventListener('dragover', this.onDrag, true);
    removeEventListener('drop', this.onDrop, true);
    removeEventListener('dragleave', this.onDrag, true);
    removeEventListener('pointermove', this.onPointerMove, true);
    removeEventListener('wheel', this.onWheel, true);
    document.removeEventListener('pointerout', this.onPointerOut, true);
    this.closeLookup();
    this.overlay.detach();
    this.site.hideOwnSubtitles(false);
    this.site.ownControls?.hide(false);
    this.site.ownControls?.hideCursor(false);
    this.menu.close();
    this.tutorial.close();
    this.panel?.show(false, false);
    this.panelPlaced = false;
    this.site.makeRoom?.(0);
    this.site.placeVideo?.(null);
    this.site.fill(false);
    this.systemDark.removeEventListener('change', this.onSystemTheme);
  }

  /** Stops, and forgets the video it was on: its lines, and the words looked up in them. */
  private leave() {
    this.stop();
    this.primary = null;
    this.secondary = null;
    this.trackKey = '';
    this.lastLookup = null;
    this.panel?.clearLookup();
    this.shownVideoId = null;
    this.title = null;
    this.offset = 0;
    this.lineIndex = -1;
    this.autoLookedUp = -1;
  }

  private notSetUp(message?: string) {
    if (message === 'notInstalled') {
      this.overlay.prompt(
        'Mizune isn’t set up for this browser',
        'The extension looks words up and adds cards with the Mizune app. Install Mizune, open it once, and turn on Settings › General › Browser extension. Then reload this page.',
        [{ label: 'Close', run: () => this.stop() }, { label: 'Get Mizune', main: true, href: 'https://mizune.app' }],
      );
    } else {
      this.overlay.prompt('Mizune didn’t answer', message ?? 'Something went wrong talking to Mizune.', [{ label: 'Close', run: () => this.stop() }]);
    }
  }

  /** The extension's own engine, with nothing to look words up in yet. */
  private needsDictionary() {
    this.overlay.prompt(
      'Mizune needs a dictionary',
      'Without the Mizune app, the extension looks words up in dictionaries of its own. Add one in its options: Jitendex is a good start. Or install Mizune, and the extension uses the app’s.',
      [
        { label: 'Not now', run: () => this.overlay.closePrompt() },
        { label: 'Get Mizune', href: 'https://mizune.app' },
        { label: 'Open options', main: true, run: () => { this.overlay.closePrompt(); background({ type: 'openOptions' }); } },
      ],
    );
  }

  private readonly systemDark = matchMedia('(prefers-color-scheme: dark)');
  private readonly onSystemTheme = () => this.applyTheme();

  private applyTheme() {
    if (!this.hello) return;
    const theme = this.hello.theme ?? DEFAULT_THEME;
    const dark = theme.mode === 'dark' || (theme.mode === 'auto' && this.systemDark.matches);
    const colors: Colors | undefined = dark ? theme.dark ?? theme.light : theme.light ?? theme.dark;
    if (colors) this.overlay.theme(colors);
  }

  // ---- subtitles ----------------------------------------------------------------------------

  /**
   * What show this is, with its seasons and episodes, as the page found it, for the episode list;
   * the page's asked once a video if it hasn't said.
   */
  async loadShow() {
    if (!this.episodes) return;
    const shows: FoundShow[] = await background({ type: 'shows' }).catch(() => []);
    for (const found of shows) {
      const key = `${found.site}/${found.id}`;
      const known = this.knownShows.get(key);
      this.knownShows.delete(key);
      this.knownShows.set(key, known ? mergeShow(known, found) : found);
    }
    const id = this.site.videoId();
    const show = (id && [...this.knownShows.values()].reverse().find((s) => showHas(s, id))) || null;
    this.episodes.set(this.site.ownControls?.name ?? '', show, id, this.site.title());
    if (!show && id && this.askedShow !== id) {
      this.askedShow = id;
      this.site.requestShow?.();
    }
  }

  /**
   * Picks the subtitles to study and the translation from the files the page found for this
   * video: what the track menu chose, if the site has it, else Japanese, and the peek language.
   */
  async loadTracks(asked = false) {
    this.loadShow();
    if (!this.hello || this.primary?.fromFile) return;
    const found: FoundTrack[] = await background({ type: 'tracks' });
    this.foundTracks = found;
    const id = this.site.videoId();
    const mine = found.filter((t) => !id || t.videoId === id);
    // plain subtitles before captions, which describe sounds too
    const pick = (language: string | null) =>
      mine.filter((t) => t.language === language).sort((a, b) => Number(a.captions) - Number(b.captions))[0];
    const chosen = (key: string | undefined) => (key ? mine.find((t) => trackKey(t) === key) : undefined);
    const peek = this.peekLanguage;
    // what's missing comes in as tracksChanged, if the site can get it
    if (!pick('ja') || (peek && !pick(peek))) this.requestSubtitles();
    for (const key of [this.choice.study, this.choice.translation]) if (key && key !== 'none' && !chosen(key)) this.fetchChosen(key);

    const study = chosen(this.choice.study) ?? pick('ja');
    const translation = this.choice.translation === 'none' ? undefined : chosen(this.choice.translation) ?? (peek ? pick(peek) : undefined);
    const studyKey = `${study?.videoId}/${study?.name}/${study?.captions}`;
    const key = `${studyKey}|${translation?.name}/${translation?.captions}`;
    if (key === this.trackKey && this.primary) return;
    const sameStudy = this.trackKey.split('|')[0] === studyKey && !!this.primary;
    this.trackKey = key;

    if (!sameStudy) {
      this.primary = study ? { cues: await this.parse(study), label: study.label, fromFile: false, key: trackKey(study) } : null;
      this.title = null;
      this.offset = 0;
      this.lineIndex = -1;
      this.autoLookedUp = -1;
      // the word looked up was in the last video's lines
      this.closeLookup();
      this.lastLookup = null;
      this.panel?.clearLookup();
    }
    this.secondary = translation && translation !== study
      ? { cues: await this.parse(translation), label: translation.label, fromFile: false, key: trackKey(translation) }
      : null;
    this.menu.refresh();
    if (this.primary?.cues.length) {
      this.overlay.closePrompt();
      this.site.hideOwnSubtitles(true);
      this.maybeShowTutorial();
    } else if (asked || !this.automatic) {
      this.askForFile();
    }
  }

  /** The first time there are lines: how to work the player, over it, paused until it's done. */
  private async maybeShowTutorial() {
    if (this.tutorialAsked || !this.hello) return;
    this.tutorialAsked = true;
    const { tutorialSeen } = await api.storage.local.get('tutorialSeen').catch(() => ({ tutorialSeen: true }));
    if (tutorialSeen || !this.on || !this.hello) return;
    this.menu.close();
    this.closeLookup();
    this.site.pause();
    this.tutorial.show({
      keys: this.hello.keys,
      anki: this.hello.anki,
      panel: !!this.panel,
      light: this.overlay.light,
      site: this.site.ownControls?.name ?? null,
    });
  }

  /** Asks the site for the track the menu chose, which it hasn't downloaded yet. */
  private async fetchChosen(key: string) {
    if (this.fetching.has(key) || !this.site.fetchTrack) return;
    this.siteTracks = (await this.site.trackList?.()) ?? this.siteTracks;
    const track = this.siteTracks?.subtitles.find((t) => trackKey(t) === key);
    if (!track || this.fetching.has(key)) return;
    this.fetching.add(key);
    this.menu.refresh();
    const ok = await this.site.fetchTrack(track.id);
    this.fetching.delete(key);
    this.menu.refresh();
    if (!ok) this.overlay.notice(`${this.site.ownControls?.name ?? 'The site'} didn’t send ${track.label}`, { error: true });
  }

  // ---- the track menu -----------------------------------------------------------------------

  private openTrackMenu(anchor: HTMLElement) {
    this.menu.toggle(anchor, () => this.trackMenuItems());
    if (!this.menu.isOpen) return;
    this.site.trackList?.().then((list) => {
      if (list) this.siteTracks = list;
      this.menu.refresh();
    });
  }

  /**
   * As the app's TrackMenu: the picture, the audio, the subtitles to study and the translation.
   * Choosing keeps it up, so the change shows behind it and another can follow.
   */
  private trackMenuItems(): MenuItem[] {
    const items: MenuItem[] = [
      { kind: 'group', label: 'Picture', note: this.hello?.keys.FillFit?.[0] ?? 'Z' },
      {
        kind: 'toggles',
        toggles: [
          { title: 'Fill', icon: ICONS.fill(), tip: 'Fill the area, cropping the edges, or show the whole picture', on: this.fill, run: (on) => this.setFill(on, true) },
          // only where the video's beside the panel
          ...(this.panel ? [{ title: 'Frame', icon: ICONS.frame(), tip: 'The video and panel as cards, with rounded corners and a gap', on: this.rounded, run: (on: boolean) => this.setRounded(on, true) }] : []),
        ],
      },
    ];
    const audio = this.siteTracks?.audio ?? [];
    if (audio.length) {
      items.push({ kind: 'group', label: 'Audio' });
      const choices = audio.map((track) => ({
        key: track.language ?? '',
        choice: {
          kind: 'choice', title: track.label, selected: track.selected,
          run: () => {
            audio.forEach((t) => (t.selected = t === track));
            this.site.setAudio?.(track.id);
          },
        } as MenuItem,
      }));
      // Japanese first; the dubs folded away
      this.foldedList(items, 'audio', choices, (language) => language === 'ja');
    }
    // the site's own list where it gives one, else the files found so far
    const id = this.site.videoId();
    const tracks = new Map<string, string>();
    for (const t of this.siteTracks?.subtitles ?? []) if (!tracks.has(trackKey(t))) tracks.set(trackKey(t), t.label);
    if (!tracks.size) this.foundTracks.filter((t) => !id || t.videoId === id).forEach((t) => tracks.set(trackKey(t), t.label));
    if (this.primary?.fromFile) tracks.set('file', this.primary.label);
    if (!tracks.size) return items;
    const detail = (key: string) => (this.fetching.has(key) ? 'Getting it…' : undefined);
    const language = (key: string) => key.replace(/\+cc$/, '');
    const peek = this.peekLanguage;

    items.push({ kind: 'group', label: 'Subtitles to study' });
    const study = [...tracks].map(([key, label]) => {
      const selected = key === 'file' ? !!this.primary?.fromFile : !this.primary?.fromFile && this.primary?.key === key;
      return { key, choice: { kind: 'choice', title: label, detail: detail(key), selected, run: () => key !== 'file' && this.chooseTracks({ study: key }) } as MenuItem };
    });
    // Japanese (and a file of its own) first; the rest folded away
    this.foldedList(items, 'study', study, (key) => key === 'file' || language(key) === 'ja');

    items.push({ kind: 'group', label: 'Translation', note: 'Hold to peek' });
    items.push({ kind: 'choice', title: 'None', selected: !this.secondary, run: () => this.chooseTracks({ translation: 'none' }) });
    const translations = [...tracks].filter(([key]) => key !== 'file' && key !== this.primary?.key).map(([key, label]) => ({
      key, choice: { kind: 'choice', title: label, detail: detail(key), selected: this.secondary?.key === key, run: () => this.chooseTracks({ translation: key }) } as MenuItem,
    }));
    // the peek language first, then Japanese (its captions, say); the rest folded away
    const first = (key: string) => (peek != null && language(key) === peek ? 0 : language(key) === 'ja' ? 1 : 2);
    translations.sort((a, b) => first(a.key) - first(b.key));
    this.foldedList(items, 'translation', translations, (key) => first(key) < 2);
    return items;
  }

  /** Which of the track menu's folds are open, by the group they're in. */
  private unfolded = new Set<string>();

  /**
   * [list]'s choices into [items]: those [shown] says, then the rest under Other languages, folded
   * away unless it's been opened or one of them is chosen. With only a few others, they're all shown.
   */
  private foldedList(items: MenuItem[], group: string, list: { key: string; choice: MenuItem }[], shown: (key: string) => boolean) {
    const main = list.filter((t) => shown(t.key));
    const rest = list.filter((t) => !shown(t.key));
    if (rest.length <= 2 || !main.length) {
      items.push(...list.map((t) => t.choice));
      return;
    }
    items.push(...main.map((t) => t.choice));
    const chosen = rest.some((t) => t.choice.kind === 'choice' && t.choice.selected);
    const open = chosen || this.unfolded.has(group);
    items.push({
      kind: 'fold', id: group, title: 'Other languages', count: rest.length, open,
      run: () => (this.unfolded.has(group) ? this.unfolded.delete(group) : this.unfolded.add(group)),
    });
    // there while it's folded too, so it can slide open
    items.push(...rest.map((t) => ({ ...t.choice, folded: true }) as MenuItem));
  }

  /** The files found for this tab, as the track menu lists them where the site gives no list. */
  private foundTracks: FoundTrack[] = [];

  private chooseTracks(change: { study?: string; translation?: string }) {
    this.choice = { ...this.choice, ...change };
    this.saveView({ tracks: this.choice });
    if (change.study && this.primary?.fromFile) this.primary = null;
    this.trackKey = '';
    this.loadTracks();
  }

  private async parse(track: FoundTrack): Promise<Cue[]> {
    const key = `${track.videoId}/${track.name}/${track.captions}`;
    const known = this.parsed.get(key);
    if (known) return known;
    let text = track.text;
    if (!text && track.urls?.length) {
      for (const url of track.urls) {
        text = await background({ type: 'fetchText', url }).catch(() => '');
        if (typeof text === 'string' && text) break;
      }
    }
    if (!text) return [];
    const data = toBase64(new TextEncoder().encode(text));
    const reply = await host({ type: 'subtitles', name: track.name, data });
    const cues: Cue[] = reply?.type === 'subtitles' ? reply.cues : [];
    this.parsed.set(key, cues);
    return cues;
  }

  private askForFile() {
    if (this.dismissedPrompt) return;
    const where = this.site.kind === 'other' ? 'this page' : 'this video';
    this.overlay.prompt(
      'Choose the Japanese subtitles',
      `Mizune didn’t find Japanese subtitles for ${where}. Drop a subtitle file on the page (SRT, ASS, WebVTT or TTML), or choose one. N and M shift it earlier or later.`,
      [{ label: 'Not now', run: () => { this.dismissedPrompt = true; this.overlay.closePrompt(); } }],
      true,
    );
  }

  private async useFile(file: File) {
    if (!this.hello) return;
    const data = await blobBase64(file);
    const reply = await host({ type: 'subtitles', name: file.name, data });
    if (reply?.type !== 'subtitles') return this.overlay.notice(reply?.message ?? `Mizune couldn’t read ${file.name}`, { error: true });
    this.primary = { cues: reply.cues, label: file.name, fromFile: true };
    this.title = file.name.replace(/\.[^.]+$/, '').replace(/\.(ja|jp|jpn)$/i, '');
    this.offset = 0;
    this.lineIndex = -1;
    this.overlay.closePrompt();
    this.site.hideOwnSubtitles(true);
    this.overlay.notice(`${reply.cues.length} lines from ${file.name}`);
  }

  private onDrag(event: DragEvent) {
    const files = [...(event.dataTransfer?.items ?? [])].some((i) => i.kind === 'file');
    if (!files) return;
    event.preventDefault();
    this.overlay.showDrop(event.type === 'dragover');
  }

  private onDrop(event: DragEvent) {
    const file = [...(event.dataTransfer?.files ?? [])].find((f) => SUBTITLE_FILE.test(f.name));
    this.overlay.showDrop(false);
    if (!file) return;
    event.preventDefault();
    event.stopPropagation();
    this.useFile(file);
  }

  // ---- the line, every frame ----------------------------------------------------------------

  private tick() {
    // off the video's page (Netflix's own back, say): off until the next one
    if (!this.site.watching()) return this.leave();
    this.frame = requestAnimationFrame(() => this.tick());
    const video = this.site.video();
    this.placePanel(!!video?.isConnected);
    if (!video || !video.isConnected) {
      this.updateControls(null);
      this.overlay.card(null);
      return this.overlay.hideLine();
    }
    // the tutorial's paused until it's done, even if the site starts playing by itself
    if (this.tutorial.isOpen && !video.paused) this.site.pause();
    if (!video.paused) this.stoppedAtLine = false;
    // the site's own controls have the picture as the site has it
    this.site.fill(this.fill);
    this.overlay.attach();
    // with the panel beside it, the video has the window less the panel, which follows the panel as
    // it slides, where the site only catches up with its video after; with rounded corners, as in
    // the app, it's a card in that, stood in from the edges
    const gap = this.rounded ? CARD_GAP : 0;
    // (on whole pixels, as the site holds the picture: see placeVideo)
    const area = this.panel ? new DOMRect(gap, gap, Math.round(innerWidth - this.panel.width - 2 * gap), innerHeight - 2 * gap) : null;
    this.site.placeVideo?.(area, this.panelOut);
    this.overlay.place(area ?? video.getBoundingClientRect());
    this.overlay.card(this.rounded ? area : null);
    this.updateControls(video);
    // the next episode plays in the same page
    const videoId = this.site.videoId();
    if (videoId !== this.shownVideoId) {
      this.shownVideoId = videoId;
      if (this.primary && !this.primary.fromFile) this.loadTracks();
    }
    const cues = this.primary?.cues;
    if (!cues?.length || !this.hello) {
      if (this.panelOut) this.drawPanel();
      return this.overlay.hideLine();
    }

    const time = video.currentTime - this.offset;
    const index = indexAt(cues, time);
    const active = index >= 0 && time < cues[index].end;
    this.lineIndex = index;
    this.lineActive = active;
    // as the app: each line to the clipboard as it starts, for other tools
    if (active && index !== this.copiedLine && this.hello.copyLines) {
      this.copiedLine = index;
      copyText(cues[index].text);
    }

    const stopHere = (this.autoPause && !this.keepPlaying) || index === this.stopAfterLine;
    if (stopHere && index >= 0 && index !== this.autoPausedLine && !video.paused && !this.busy) {
      const cue = cues[index];
      if (time >= cue.end - END_MARGIN && time < cue.end + 1.0) {
        this.autoPausedLine = index;
        this.stopAfterLine = -1;
        this.stoppedAtLine = true;
        this.site.pause();
      }
    }

    const cue = cues[index];
    // with the panel out, the line is in it rather than over the video
    if (this.panelOut) {
      this.drawPanel();
      return this.overlay.hideLine();
    }
    const shown = cue && !this.lineHidden && (active || video.paused || this.lookup);
    if (!shown) return this.overlay.hideLine();
    const highlight = this.lookup?.lineIndex === index && this.lookup.result && this.lookup.result.matchLength > 0
      ? [this.lookup.start, this.lookup.start + (this.lookup.length ?? this.lookup.result.matchLength)] as [number, number]
      : null;
    const peek = this.peeking ? this.translationFor(cue) ?? { none: this.noTranslation } : undefined;
    this.overlay.showLine(cue.text, this.lineSize(video), highlight, peek);
  }

  /** The app's size, a little bigger in full screen and smaller in a small player. */
  private lineSize(video: HTMLVideoElement) {
    const base = this.hello?.lineSize ?? 30;
    return Math.round(base * Math.max(0.7, Math.min(1.6, video.getBoundingClientRect().height / 720)));
  }

  // ---- the study panel ----------------------------------------------------------------------

  /**
   * Whether the study panel is out: on a site with room for it, unless it's been put away.
   */
  private get panelOut() {
    return !!this.panel && this.on && !!this.hello && !this.panelHidden;
  }

  /** Shows the panel or puts it away, and has the site's player make room for it. */
  private placePanel(video: boolean) {
    if (!this.panel) return;
    const out = this.panelOut && video;
    // it's there from the start, if it's out then; after (put away to start with, too), it slides
    if (out !== this.panel.shown) this.panel.show(out, this.panelPlaced);
    if (video && this.on && this.hello) this.panelPlaced = true;
    this.panel.step();
    this.site.makeRoom?.(this.panel.width);
  }

  private drawPanel() {
    const panel = this.panel!;
    panel.setOffset(this.offset);
    panel.setFooter(this.lineStop, this.hello?.keys ?? {});
    const cue = this.primary?.cues[this.lineIndex];
    const highlight = cue && this.lookup?.lineIndex === this.lineIndex && this.lookup.result && this.lookup.result.matchLength > 0
      ? [this.lookup.start, this.lookup.start + (this.lookup.length ?? this.lookup.result.matchLength)] as [number, number]
      : null;
    panel.setLine({
      text: cue?.text ?? null,
      status: this.primary?.cues.length ? null : this.primary ? 'No lines in these subtitles.' : 'Looking for Japanese subtitles…',
      active: this.lineActive,
      highlight,
      peeking: this.peeking || this.holding,
      translation: cue ? this.translationFor(cue) : null,
      noTranslation: this.noTranslation,
      size: this.hello?.lineSize ?? 30,
    });
    // every line comes with a word looked up: the first kanji (or next best) with an entry
    if (cue && this.lineIndex !== this.autoLookedUp && !this.busy) {
      this.autoLookedUp = this.lineIndex;
      if (this.lookup?.lineIndex !== this.lineIndex || this.lookup.text !== cue.text) this.lookUpLine(this.lineIndex, cue.text);
    }
  }

  private async lookUpLine(lineIndex: number, text: string) {
    if (!this.hello) return;
    if (this.hello.dictionaries === 0) return this.panel?.showLookup(this.hello.local ? 'noLocalDictionaries' : 'noDictionaries', () => false, false, false);
    const serial = ++this.lookupSerial;
    let reply = await host({ type: 'lookupLine', text });
    // an app from before lookupLine: its first character
    if (reply?.type !== 'lookup') reply = await host({ type: 'lookup', text, start: 0 });
    if (serial !== this.lookupSerial || !this.on || reply?.type !== 'lookup') return;
    this.lookup = { lineIndex, text, start: reply.start, result: reply };
    this.lastLookup = this.lookup;
    this.renderLookup();
  }

  private togglePanel() {
    if (!this.panel) return;
    this.panelHidden = !this.panelHidden;
    this.menu.close();
    this.saveView({ panelHidden: this.panelHidden });
    // what was looked up stays where it was looked up
    this.closeLookup();
    this.panel.clearLookup();
    this.autoLookedUp = -1;
  }

  /** How the player's laid out, kept for every video: the panel, its width, and the picture filling the area. */
  private saveView(change: { panelHidden?: boolean; panelFraction?: number; fill?: boolean; rounded?: boolean; tracks?: { study?: string; translation?: string } }) {
    api.storage.local.get('view').then(({ view }: any) =>
      api.storage.local.set({ view: { ...view, ...change } }),
    ).catch(() => {});
  }

  /** As the app's Rounded corners: the video and panel as cards with a gap, or flat beside each other. */
  private setRounded(on: boolean, save = false) {
    this.rounded = on;
    this.panel?.setRounded(on);
    if (save) this.saveView({ rounded: on });
  }

  /** As the app's Z: the picture fills the area, cropped (pan and scan), or fits whole in it. */
  private setFill(on: boolean, quietly = false) {
    if (on === this.fill) return;
    this.fill = on;
    this.saveView({ fill: on });
    if (!quietly) this.overlay.notice(on ? 'Filling the area' : 'The whole picture');
  }

  /** What peeking says with no translation to show, as the app's line does. */
  private get noTranslation() {
    if (this.secondary) return 'Nothing here in your peek language.';
    return this.peekLanguage ? 'No subtitles in your peek language. Pick a translation track under ⚙.' : PEEK_OFF;
  }

  private translationFor(cue: Cue): string | null {
    const lines = this.secondary?.cues.filter((c) => c.start < cue.end - 0.1 && c.end > cue.start + 0.1) ?? [];
    return lines.map((c) => c.text).join('\n') || null;
  }

  // ---- Mizune's controls ----------------------------------------------------------------------

  private updateControls(video: HTMLVideoElement | null) {
    if (!this.controls) return;
    const allowed = !!video && !!this.hello;
    this.controls.update({
      paused: video?.paused ?? true,
      position: video?.currentTime ?? 0,
      // HAVE_FUTURE_DATA: enough to go on with
      loading: !!video && (video.seeking || (!video.paused && video.readyState < 3)),
      duration: this.site.duration(),
      volume: video?.volume ?? 1,
      muted: video?.muted ?? false,
      lines: !!this.primary?.cues.length,
      stop: this.lineStop,
      fullscreen: !!document.fullscreenElement,
      panel: this.panelOut,
      stoppedAtLine: this.stoppedAtLine,
      keys: this.hello?.keys ?? {},
      previews: previews.get(this.site.videoId() ?? '') ?? null,
    }, allowed);
    this.overlay.raiseLine(this.controls.visible);
    // the site shows the pointer whenever its player is busy (a seek, say); the app hides it
    const hideCursor = allowed && this.controls.cursorHidden;
    this.site.ownControls?.hideCursor(hideCursor);
    this.overlay.hideCursor(hideCursor);
  }

  /** Whether playback will stop at a line's end, as the app's stop button shows it. */
  private get lineStop(): LineStop {
    if (this.autoPause) return this.keepPlaying ? 'held' : 'every';
    return this.stopAfterLine >= 0 ? 'this' : 'none';
  }

  private setAutoPause(on: boolean) {
    this.autoPause = on;
    this.keepPlaying = false;
    this.autoPausedLine = on && !this.lineActive ? this.lineIndex : -1;
    this.overlay.notice(on ? 'Stopping at the end of every line' : 'Playing on through the lines');
  }

  private toggleFullscreen() {
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    else this.site.fullscreenTarget()?.requestFullscreen().catch(() => {});
  }

  /** Where the pointer is over the video, for when the controls come up. */
  private onPointerMove(event: PointerEvent) {
    const video = this.site.video();
    if (!this.controls || !video) return;
    const r = video.getBoundingClientRect();
    const x = (event.clientX - r.left) / r.width, y = (event.clientY - r.top) / r.height;
    if (x < 0 || x > 1 || y < 0 || y > 1) this.controls.pointerGone();
    else this.controls.pointer(x, y);
  }

  /** As the app: ⌘ or Ctrl and scroll over the video, up to fill the area and down for the whole picture. */
  private onWheel(event: WheelEvent) {
    if (!(event.metaKey || event.ctrlKey) || !event.deltaY || !this.hello) return;
    const r = this.site.video()?.getBoundingClientRect();
    if (!r || event.clientX < r.left || event.clientX > r.right || event.clientY < r.top || event.clientY > r.bottom) return;
    event.preventDefault();
    event.stopPropagation();
    this.setFill(event.deltaY < 0);
  }

  /** The pointer left the page, past an edge of the video. */
  private onPointerOut(event: PointerEvent) {
    if (!event.relatedTarget) this.controls?.pointerGone();
  }

  // ---- moving between lines (as PlayerSession.kt) -------------------------------------------

  private async playLine(index: number, stopAtEnd: boolean) {
    const cue = this.primary?.cues[index];
    if (!cue) return;
    this.autoPausedLine = -1;
    this.stopAfterLine = stopAtEnd ? index : -1;
    this.keepPlaying = !stopAtEnd;
    await this.site.seek(cue.start + this.offset);
    await this.site.play();
  }

  private replayLine(stopAtEnd: boolean) {
    this.playLine(this.lineIndex >= 0 ? this.lineIndex : 0, stopAtEnd);
  }

  private previousLine(stopAtEnd: boolean) {
    const video = this.site.video();
    const cue = this.primary?.cues[this.lineIndex];
    let target: number;
    if (video?.paused && cue && video.currentTime - this.offset > cue.start + REPLAY_MARGIN) target = this.lineIndex;
    else if (this.lineIndex >= 0 && !this.lineActive) target = this.lineIndex;
    else target = this.lineIndex - 1;
    this.playLine(Math.max(0, target), stopAtEnd);
  }

  private nextLine(stopAtEnd: boolean) {
    const cues = this.primary?.cues;
    if (!cues) return;
    if (this.site.video()?.paused) {
      // the first press only carries on, so the rest of the line isn't skipped
      const ahead = this.lineActive ? this.lineIndex : this.lineIndex + 1;
      this.stopAfterLine = stopAtEnd ? ahead : -1;
      this.keepPlaying = !stopAtEnd;
      this.site.play();
      return;
    }
    this.playLine(Math.min(this.lineIndex + 1, cues.length - 1), stopAtEnd);
  }

  private shiftOffset(delta: number) {
    this.offset = Math.round((this.offset + delta) * 10) / 10;
    const said = this.offset === 0 ? 'Subtitles on time' : `Subtitles ${Math.abs(this.offset).toFixed(1)} s ${this.offset < 0 ? 'earlier' : 'later'}`;
    this.overlay.notice(said);
  }

  // ---- keys ---------------------------------------------------------------------------------

  private onKeyDown(event: KeyboardEvent) {
    if (this.tutorial.isOpen) return this.tutorial.key(event);
    if (!this.hello || isTyping(event)) return;
    if (event.code === 'Escape' && this.menu.isOpen) {
      this.menu.close();
      return this.swallow(event);
    }
    if (event.code === 'Escape' && this.episodes?.isOpen) {
      this.episodes.close();
      return this.swallow(event);
    }
    if (event.code === 'Escape' && this.lookup && !this.panelOut) {
      this.closeLookup();
      return this.swallow(event);
    }
    const action = actionFor(event, this.hello.keys);
    if (!action) return;
    if (action === 'SeekBack' || action === 'SeekForward') {
      const video = this.site.video();
      if (video) this.site.seek(Math.max(0, video.currentTime + (action === 'SeekBack' ? -5 : 5)));
      return this.swallow(event);
    }
    if (action === 'FillFit') {
      if (!event.repeat) this.setFill(!this.fill);
      return this.swallow(event);
    }
    if (action === 'Peek') {
      if (!event.repeat) this.peeking = true;
      return this.swallow(event);
    }
    // without lines, the site keeps its keys (Netflix's arrows seek)
    if (!this.primary?.cues.length) return;
    if (event.repeat && action !== 'SubtitlesEarlier' && action !== 'SubtitlesLater') return this.swallow(event);
    switch (action) {
      case 'PreviousLine': this.previousLine(false); break;
      case 'NextLine': this.nextLine(false); break;
      case 'PreviousLineStop': this.previousLine(true); break;
      case 'NextLineStop': this.nextLine(true); break;
      case 'ReplayLine': this.replayLine(true); break;
      case 'SubtitlesEarlier': this.shiftOffset(-0.1); break;
      case 'SubtitlesLater': this.shiftOffset(0.1); break;
      case 'StopEveryLine':
        this.setAutoPause(!this.autoPause);
        break;
      case 'Panel':
        if (this.panel) {
          this.togglePanel();
          break;
        }
        this.lineHidden = !this.lineHidden;
        if (this.lineHidden) this.closeLookup();
        this.overlay.notice(this.lineHidden ? 'Line hidden' : 'Line shown');
        break;
      case 'AddCard': {
        const entry = (this.lookup ?? this.lastLookup)?.result?.entries[0];
        if (entry) this.addCard(entry, this.lookup ?? this.lastLookup!);
        else this.overlay.notice('Look a word up first, then add it');
        break;
      }
      default:
        return;
    }
    this.swallow(event);
  }

  private onKeyUp(event: KeyboardEvent) {
    if (this.tutorial.isOpen) return this.tutorial.key(event);
    if (!this.hello) return;
    if (actionFor(event, this.hello.keys) === 'Peek' || (this.peeking && event.code === this.peekCode)) {
      this.peeking = false;
      this.swallow(event);
    }
  }

  private get peekCode() {
    return 'Key' + (this.hello?.keys.Peek?.[0] ?? 'I');
  }

  /** Mizune's key: the site doesn't see it (Netflix would seek on the arrows too). */
  private swallow(event: KeyboardEvent) {
    event.preventDefault();
    event.stopImmediatePropagation();
  }

  // ---- lookups ------------------------------------------------------------------------------

  private async lookUp(start: number, length?: number) {
    const cue = this.primary?.cues[this.lineIndex];
    if (!cue || !this.hello) return;
    if (this.hello.dictionaries === 0) {
      if (this.panelOut) return this.panel!.showLookup(this.hello.local ? 'noLocalDictionaries' : 'noDictionaries', () => false, false, false);
      this.overlay.showLookup(this.hello.local ? 'noLocalDictionaries' : 'noDictionaries', () => false, false, false);
      return;
    }
    const serial = ++this.lookupSerial;
    const reply = await host({ type: 'lookup', text: cue.text, start, ...(length ? { length } : {}) });
    if (serial !== this.lookupSerial || !this.on) return;
    if (reply?.type !== 'lookup') return this.overlay.notice(reply?.message ?? 'Mizune couldn’t look that up', { error: true });
    this.lookup = { lineIndex: this.lineIndex, text: cue.text, start, length, result: reply };
    this.lastLookup = this.lookup;
    if (this.hello.pauseOnLookup) {
      const video = this.site.video();
      if (video && !video.paused) {
        this.site.pause();
        // the popup plays on when it closes; the panel's lookup stays, and so does the pause
        this.pausedForLookup = !this.panelOut;
      }
    }
    this.renderLookup();
  }

  private renderLookup() {
    if (!this.lookup?.result || !this.hello) return;
    const sentence = this.lookup.text;
    const view = this.panelOut ? this.panel! : this.overlay;
    view.showLookup(this.lookup.result, (entry) => this.notes.has(noteKey(entry, sentence)), this.hello.anki, this.busy);
  }

  private closeLookup() {
    if (!this.lookup && !this.overlay.lookupOpen) return;
    this.lookupSerial++;
    this.lookup = null;
    this.overlay.closeLookup();
    if (this.pausedForLookup && !this.busy) {
      this.pausedForLookup = false;
      this.site.play();
    }
  }

  // ---- cards --------------------------------------------------------------------------------

  private async addCard(entry: Entry, from: Lookup | null = this.lookup, anyway = false) {
    if (!this.hello || !from || this.busy) return;
    if (!this.hello.anki) return this.cardStatus('Turn on Anki cards in Mizune’s settings first', 'failed');
    const cue = this.primary?.cues[from.lineIndex];
    if (!cue) return;
    const card = {
      line: { text: cue.text, start: cue.start + this.offset, end: cue.end + this.offset },
      start: from.start,
      ...(from.length ? { length: from.length } : {}),
      expression: entry.expression,
      reading: entry.reading,
      title: this.title ?? this.site.title() ?? document.title,
      translation: this.translationFor(cue) ?? '',
      anyway,
    };
    this.busy = true;
    this.renderLookup();
    try {
      this.cardStatus(`Adding ${entry.expression} to Anki…`, 'working');
      // a duplicate or a closed Anki shows before the line is played again for its audio
      const check = await host({ type: 'checkCard', ...card });
      if (check?.type === 'duplicate') return this.duplicate(entry, from, cue, check);
      if (check?.type !== 'canAdd') return this.cardStatus(check?.message ?? 'Anki refused the card', 'failed');
      const media = await this.media(cue, { audio: check.audio, image: check.image });
      if (media.needsClick) {
        this.cardStatus(`To record the line, ${media.needsClick}, then add the card again`, 'failed');
        return;
      }
      const reply = await host({
        type: 'addCard', ...card,
        audio: media.audio ?? '', audioType: media.audioType ?? '', image: media.image ?? '', imageType: media.imageType ?? '',
      });
      if (reply?.type === 'added') {
        this.notes.set(noteKey(entry, cue.text), reply.noteId);
        // the app says what the card's missing; the page knows why
        const message: string = reply.message;
        this.cardStatus(!media.note ? message
          : message.endsWith(')') ? `${message.slice(0, -1)}: ${media.note})`
          : `${message} (no audio: ${media.note})`, 'done');
      } else if (reply?.type === 'duplicate') {
        this.duplicate(entry, from, cue, reply);
      } else {
        this.cardStatus(reply?.message ?? 'Anki refused the card', 'failed');
      }
    } finally {
      this.busy = false;
      this.renderLookup();
    }
  }

  private duplicate(entry: Entry, from: Lookup, cue: Cue, reply: { noteId?: number; message: string }) {
    if (reply.noteId) this.notes.set(noteKey(entry, cue.text), reply.noteId);
    // asked over the video, where its button is
    this.panel?.clearStatus();
    this.overlay.notice(reply.message, { action: { label: 'Add anyway', run: () => this.addCard(entry, from, true) } });
  }

  /**
   * What a card is doing, or how it went: in the panel's bottom row, or over the
   * video while the panel's put away.
   */
  private cardStatus(text: string, state: 'working' | 'done' | 'failed') {
    if (this.panelOut) return this.panel!.setStatus(text, state);
    this.overlay.notice(text, { error: state === 'failed', stay: state === 'working' });
  }

  private async removeCard(entry: Entry) {
    const sentence = this.lookup?.text;
    const noteId = sentence ? this.notes.get(noteKey(entry, sentence)) : undefined;
    if (!sentence || noteId == null || this.busy) return;
    const reply = await host({ type: 'removeCard', noteId });
    if (reply?.type === 'removed') {
      this.notes.delete(noteKey(entry, sentence));
      this.cardStatus(`Removed ${entry.expression} from Anki`, 'done');
    } else {
      this.cardStatus(reply?.message ?? 'Anki refused', 'failed');
    }
    this.renderLookup();
  }

  /**
   * The picture as it is now, then the line played again from its start (with the app's padding)
   * while it's recorded, and back to where it was. With the extension's own engine, the picture
   * can move: filmed over the replay, from the video itself, or else from the tab's capture.
   */
  private async media(cue: Cue, wanted: { audio: boolean; image: boolean }): Promise<Media> {
    const video = this.site.video();
    if (!video) return { note: 'no video' };
    const still = wanted.image ? await this.still(video) : undefined;
    let image = still?.image, imageType = still?.imageType;
    const animate = !!still && this.hello?.local === true && this.hello.imageAnimated !== false;
    // where the frames come from: the video while the page lets it be read, else the tab (Chrome)
    const own = animate && still.from === 'video' ? new FrameCollector(ANIMATION.fps, this.hello?.imageHeight ?? 360, ANIMATION.quality) : null;
    const fromTab = animate && still.from === 'tab' && !isGecko;
    if (!wanted.audio && !own && !fromTab) return { image, imageType };
    if (isGecko && video.mediaKeys) return { image, imageType, note: 'Firefox can’t record video with DRM' };

    const padding = this.hello?.audioPadding ?? 0.3;
    const from = Math.max(0, cue.start + this.offset - padding);
    const to = cue.end + this.offset + padding;
    const was = { time: video.currentTime, paused: video.paused };
    let stopFilming = () => {};
    try {
      this.site.pause();
      await this.site.seek(from);
      const recorder = wanted.audio || fromTab ? await startRecording(video, fromTab ? this.tabVideo(video) : undefined) : null;
      // the tab's filmed as it's shown: without Mizune over the video
      if (fromTab) this.overlay.setCapturing(true);
      if (own) stopFilming = filmVideo(video, own);
      await this.site.play();
      await until(() => video.currentTime >= to || video.ended, (to - from) * 1000 * 3 + 5000);
      this.site.pause();
      stopFilming();
      const recording = recorder ? await recorder.stop() : null;
      const moving = own ? await own.finish(to * 1000).catch(() => null) : null;
      if (moving) [image, imageType] = [await blobBase64(moving), moving.type];
      else if (recording?.image) [image, imageType] = [recording.image, recording.imageType];
      if (!wanted.audio || !recording) return { image, imageType };
      if (recording.peak < 0.0005) return { image, imageType, note: 'the recording was silent' };
      return { image, imageType, audio: recording.audio, audioType: recording.audioType };
    } catch (e) {
      // the picture as it was, if it was only the picture being filmed
      if (!wanted.audio) return { image, imageType };
      if (e instanceof NoAudio) return e.needsClick ? { needsClick: e.message } : { image, imageType, note: e.message };
      return { image, imageType, note: String((e as Error)?.message ?? e) };
    } finally {
      stopFilming();
      if (fromTab) this.overlay.setCapturing(false);
      await this.site.seek(was.time);
      if (!was.paused) await this.site.play();
    }
  }

  /** Where [video] is in the tab, for filming it from the tab's capture. */
  private tabVideo(video: HTMLVideoElement): TabVideo {
    const r = video.getBoundingClientRect();
    return {
      rect: { x: r.x, y: r.y, width: r.width, height: r.height },
      viewportWidth: innerWidth, viewportHeight: innerHeight, pixelRatio: devicePixelRatio,
      height: this.hello?.imageHeight ?? 360,
    };
  }

  /**
   * The frame from the video itself where the page lets it be read, which needs no permission.
   * Video with DRM draws as black, so then it's a picture of the tab instead, which Chrome only
   * allows once the toolbar button has been used there.
   */
  private async still(video: HTMLVideoElement): Promise<{ image: string; imageType: string; from: 'video' | 'tab' } | undefined> {
    const format: StillFormat = this.hello?.local ? { type: 'image/webp', height: this.hello.imageHeight ?? 360 } : { type: 'image/png' };
    const own = await frame(video, format);
    if (own) return { ...own, from: 'video' };
    // only the top frame knows where it is in the tab
    if (window !== window.top) return undefined;
    this.overlay.setCapturing(true);
    await nextFrames(2);
    try {
      const rect = video.getBoundingClientRect();
      const reply = await background({ type: 'still', rect: { x: rect.x, y: rect.y, width: rect.width, height: rect.height }, viewportWidth: innerWidth, format });
      return reply?.image ? { image: reply.image, imageType: reply.imageType ?? 'image/png', from: 'tab' } : undefined;
    } catch {
      return undefined;
    } finally {
      this.overlay.setCapturing(false);
    }
  }
}

interface Media {
  image?: string;
  imageType?: string;
  audio?: string;
  audioType?: string;
  /** Why there's no audio. */
  note?: string;
  /** Chrome wants the toolbar button first; the card waits for it. */
  needsClick?: string;
}

/** The video's current frame as [format] asks, or nothing if the page won't give it up or it's black. */
async function frame(video: HTMLVideoElement, format: StillFormat): Promise<{ image: string; imageType: string } | undefined> {
  const { videoWidth: width, videoHeight: height } = video;
  if (!width || !height) return undefined;
  try {
    const canvas = new OffscreenCanvas(width, height);
    const context = canvas.getContext('2d', { willReadFrequently: true })!;
    context.drawImage(video, 0, 0, width, height);
    // throws for video from another site that didn't allow it
    const pixels = context.getImageData(0, 0, width, height).data;
    if (meanLuma(pixels) < 3) return undefined;
    const picture = await encodeStill(canvas, width, height, format);
    return { image: picture.image, imageType: picture.type };
  } catch {
    return undefined;
  }
}

/**
 * Films [video] into [frames] as it plays, a frame each time it shows a new one; what it answers
 * stops it.
 */
function filmVideo(video: HTMLVideoElement, frames: FrameCollector): () => void {
  let filming = true;
  const take = (at: number) => {
    if (!filming) return;
    frames.add(at, video.videoWidth, video.videoHeight, (context, w, h) => context.drawImage(video, 0, 0, w, h));
  };
  if ('requestVideoFrameCallback' in video) {
    const next = () => video.requestVideoFrameCallback((_, meta) => {
      take(meta.mediaTime * 1000);
      if (filming) next();
    });
    next();
  } else {
    const next = () => requestAnimationFrame(() => {
      take((video as HTMLVideoElement).currentTime * 1000);
      if (filming) next();
    });
    next();
  }
  return () => {
    filming = false;
  };
}

/** Puts [text] on the clipboard, without the click the page's own way would want (the extension may: clipboardWrite). */
function copyText(text: string) {
  navigator.clipboard.writeText(text).catch(() => {
    const area = document.createElement('textarea');
    area.value = text;
    area.style.cssText = 'position: fixed; left: -9999px; top: 0; opacity: 0;';
    document.documentElement.append(area);
    area.select();
    try { document.execCommand('copy'); } catch {}
    area.remove();
  });
}

function noteKey(entry: Entry, sentence: string) {
  return `${entry.expression}\u0000${sentence}`;
}

/** Index of the cue showing at [time], or else the last one that started before it; -1 before the first. */
function indexAt(cues: Cue[], time: number): number {
  let lo = 0, hi = cues.length - 1, found = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >>> 1;
    if (cues[mid].start <= time) {
      found = mid;
      lo = mid + 1;
    } else hi = mid - 1;
  }
  return found;
}

function until(done: () => boolean, timeoutMs: number): Promise<void> {
  const deadline = performance.now() + timeoutMs;
  return new Promise((resolve) => {
    const check = () => (done() || performance.now() > deadline ? resolve() : requestAnimationFrame(check));
    check();
  });
}

function nextFrames(count: number): Promise<void> {
  return new Promise((resolve) => {
    const step = (left: number) => (left <= 0 ? resolve() : requestAnimationFrame(() => step(left - 1)));
    step(count);
  });
}

// ---- start ----------------------------------------------------------------------------------

if (!window.__mizuneContent) {
  window.__mizuneContent = true;
  const session = new Session();
  // for tests: this is the content script's own world, which the page can't see
  (window as any).__mizuneSession = session;

  // the page's own script (page.ts) passes on the subtitle files it finds, and the seek bar's pictures
  window.addEventListener('message', (event) => {
    if (event.source !== window || event.data?.source !== PAGE) return;
    if (event.data.kind === 'track') background({ type: 'trackFound', track: event.data.track }).catch(() => {});
    else if (event.data.kind === 'show' && event.data.show && typeof event.data.show === 'object') background({ type: 'showFound', show: event.data.show }).catch(() => {});
    // (not instanceof: in Firefox the page's ArrayBuffer isn't this script's)
    else if (event.data.kind === 'previews' && typeof event.data.videoId === 'string' && Object.prototype.toString.call(event.data.bif) === '[object ArrayBuffer]') {
      addPreviews(event.data.videoId, event.data.bif);
    }
  });

  // turned off everywhere, it stays out: it goes as it's turned off, and comes back where it would by itself
  let off = false;
  const offRead = isOff().then((value) => (off = value));
  api.storage.onChanged.addListener((changes, area) => {
    if (area !== 'local' || !('off' in changes)) return;
    off = !!changes.off.newValue;
    if (off && session.running) session.stop();
    else if (!off && session.automatic) startIfJapanese();
  });

  listen<ToContent>((message) => {
    if (message.type === 'toggle') {
      // only the frame with the video answers; the first answer wins
      if (!largestVideo()) return undefined;
      return offRead.then(() => (off ? { ok: false } : session.toggle().then(() => ({ ok: true }))));
    }
    if (message.type === 'status') {
      if (!largestVideo()) return undefined;
      return Promise.resolve(session.status());
    }
    if (message.type === 'settingsChanged') {
      if (!session.running) return undefined;
      return session.settingsChanged().then(() => ({ ok: true }));
    }
    if (message.type === 'showsChanged') {
      if (!session.running) return undefined;
      session.loadShow();
      return Promise.resolve({ ok: true });
    }
    if (message.type === 'tracksChanged') {
      if (!largestVideo()) return undefined;
      return startIfJapanese().then(() => ({ ok: true }));
    }
    return undefined;
  });

  /** On Netflix and Crunchyroll, Mizune starts by itself once there's Japanese for the video. */
  async function startIfJapanese() {
    if (session.running) return session.loadTracks();
    await offRead;
    if (off || !session.automatic || session.stoppedByUser || !session.watching || !largestVideo()) return;
    const found: FoundTrack[] = await background({ type: 'tracks' }).catch(() => []);
    const id = session.videoId;
    if (found.some((t) => t.language === 'ja' && (!id || t.videoId === id))) await session.start(false);
    // Netflix only downloads the subtitles it shows: ask it for the Japanese
    else session.requestSubtitles();
  }

  // a file found before this frame had its video
  if (session.automatic) setInterval(() => { if (!session.running) startIfJapanese(); }, 2000);
}
