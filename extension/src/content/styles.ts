// The overlay's look: the app's line over the video, and its dictionary panel (Panels.kt).
import { EASE } from './motion';
import { CARD_RADIUS } from './panel';

export const STYLES = `
:host { all: initial; }
* { box-sizing: border-box; }
.root {
  position: fixed; left: 0; top: 0; width: 0; height: 0; z-index: 2147483646;
  pointer-events: none; font-family: "Hiragino Sans", "Hiragino Kaku Gothic ProN", "Noto Sans JP", "Yu Gothic UI", Meiryo, system-ui, sans-serif;
  color: var(--onSurface); -webkit-font-smoothing: antialiased;
}
.root.capturing { visibility: hidden; }
.root.in-card { border-radius: ${CARD_RADIUS}px; overflow: hidden; }
/* the gutter around the video's card: everything but a rounded hole over the card, which the page shows through */
.frame {
  position: fixed; z-index: 2147483646; pointer-events: none; border-radius: ${CARD_RADIUS}px;
  box-shadow: 0 0 0 100vmax var(--gutter);
}
.frame[hidden] { display: none; }
.root.no-cursor, .root.no-cursor * { cursor: none !important; }
.stack {
  position: absolute; left: 40px; right: 40px; bottom: var(--rest, 32px);
  display: flex; flex-direction: column; align-items: center; transition: bottom 0.3s ${EASE};
}
/* as the app: clear of the bar, which only moves a line resting lower than that */
.root.bar-up .stack { bottom: max(var(--rest, 32px), 56px); }
.stack > * { pointer-events: auto; }

/* the line */
.line {
  max-width: 100%; border-radius: 10px; background: rgba(0, 0, 0, 0.6);
  padding: 8px 16px; color: #fff; text-align: center; line-height: 1.3;
  text-shadow: 0 2px 6px #000; user-select: none; cursor: default; white-space: pre-wrap;
}
.line[hidden] { display: none; }
.line .c { border-radius: 0; padding: 1px 0; }
.line .c.hl { --lit: color-mix(in srgb, var(--primary) 38%, transparent); background: var(--lit); }
.line .c.sel { --lit: color-mix(in srgb, var(--tertiary) 40%, transparent); background: var(--lit); }
/* the word's rounded ends reach past it as shadows, which take no room: as padding (and a margin
   to make up for it) they can push a word onto the next row as it lights */
.line .c.first { border-top-left-radius: 8px; border-bottom-left-radius: 8px; box-shadow: -5px 0 0 var(--lit); }
.line .c.last { border-top-right-radius: 8px; border-bottom-right-radius: 8px; box-shadow: 5px 0 0 var(--lit); }
.line .c.first.last { box-shadow: -5px 0 0 var(--lit), 5px 0 0 var(--lit); }
.line .peek { font-size: 0.7em; }
.line .peek.none { opacity: 0.7; }

/* the lookup above it */
.lookup-box { width: min(460px, 100%); padding-bottom: 12px; }
.lookup-box[hidden] { display: none; }
.lookup {
  height: var(--lookup-height, 360px); overflow-y: auto; border-radius: 12px; background: var(--surface);
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.45); overscroll-behavior: contain;
  /* no scrollbar, as in the app: one would square off the right corners */
  scrollbar-width: none;
}
.lookup::-webkit-scrollbar { display: none; }
.message { height: 100%; display: flex; align-items: center; justify-content: center; padding: 24px; text-align: center; color: var(--onSurfaceVariant); font-size: 14px; line-height: 1.5; }
.term { padding: 12px; display: flex; flex-direction: column; gap: 6px; border-bottom: 1px solid color-mix(in srgb, var(--onSurface) 12%, transparent); }
.term:last-child { border-bottom: none; }
.head { display: flex; align-items: flex-end; gap: 8px; }
.head .words { flex: 1; min-width: 0; }
.reading { color: var(--onSurfaceVariant); font-size: 14px; font-weight: 500; }
.expression { font-size: 28px; line-height: 1.25; color: var(--onSurface); }
.row { display: flex; flex-wrap: wrap; gap: 4px 6px; align-items: center; }
.reasons { color: var(--onSurfaceVariant); font-size: 12px; font-weight: 500; }
.chip { font-size: 11px; font-weight: 500; border-radius: 4px; padding: 2px 6px; line-height: 1.35; }
.chip.primary { color: var(--primary); background: color-mix(in srgb, var(--primary) 14%, transparent); }
.chip.secondary { color: var(--secondary); background: color-mix(in srgb, var(--secondary) 14%, transparent); }
.chip.tertiary { color: var(--tertiary); background: color-mix(in srgb, var(--tertiary) 14%, transparent); }
.dictionary { display: flex; flex-direction: column; gap: 4px; }
.sense { display: flex; gap: 6px; }
.sense .number { width: 18px; flex: none; color: var(--primary); font-size: 14px; font-weight: 500; }
.sense .body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.glossary { font-size: 15px; line-height: 1.45; white-space: pre-wrap; color: var(--onSurface); overflow-wrap: anywhere; }
.glossary .g-rt { font-size: 0.7em; color: var(--onSurfaceVariant); }
.glossary .g-link { color: var(--primary); }
.glossary .g-tag { font-size: 0.8em; font-weight: 700; color: var(--secondary); }
.glossary .g-example { color: var(--onSurfaceVariant); font-style: italic; }
.glossary .g-quiet { font-size: 0.85em; color: var(--onSurfaceVariant); }
.forms { font-size: 12px; color: var(--onSurfaceVariant); }

/* the card button: a round + that becomes a − once the card is in Anki */
.card {
  flex: none; width: 24px; height: 24px; border-radius: 50%; border: none; padding: 0; cursor: pointer;
  background: color-mix(in srgb, var(--onSurface) 8%, transparent); color: var(--onSurfaceVariant);
  display: flex; align-items: center; justify-content: center; font: 500 17px/1 system-ui, sans-serif;
}
.card:hover { background: color-mix(in srgb, var(--primary) 26%, transparent); color: var(--primary); }
.card.added { background: var(--primary); color: var(--onPrimary); }
.card:disabled { opacity: 0.35; cursor: default; }

/* what a key or a card just did */
.notice {
  position: absolute; top: 24px; left: 50%; transform: translateX(-50%); max-width: calc(100% - 48px);
  display: flex; align-items: center; gap: 10px; padding: 7px 14px; border-radius: 999px;
  background: rgba(0, 0, 0, 0.72); color: #fff; font-size: 14px; font-weight: 500; text-shadow: 0 1px 4px #000;
  transition: opacity 0.12s ${EASE}; pointer-events: auto;
}
.notice[hidden] { display: none; }
.notice.fading { opacity: 0; transition: opacity 0.4s ${EASE}; }
.notice.error { color: color-mix(in srgb, var(--error) 70%, #fff); }
.notice button {
  border: none; border-radius: 999px; padding: 3px 10px; cursor: pointer; font: inherit; font-size: 13px;
  background: var(--primary); color: var(--onPrimary); text-shadow: none;
}

/* asking for a subtitle file, or saying what to set up */
.prompt {
  position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%); width: min(420px, calc(100% - 48px));
  background: var(--surface); color: var(--onSurface); border-radius: 14px; padding: 18px 20px;
  box-shadow: 0 10px 36px rgba(0, 0, 0, 0.5); pointer-events: auto; font-size: 14px; line-height: 1.5;
}
.prompt[hidden] { display: none; }
.prompt h2 { margin: 0 0 6px; font-size: 16px; font-weight: 600; }
.prompt p { margin: 0 0 14px; color: var(--onSurfaceVariant); }
.prompt .buttons { display: flex; gap: 8px; justify-content: flex-end; }
.prompt button, .prompt a.button {
  border: none; border-radius: 8px; padding: 6px 14px; cursor: pointer; font: inherit; font-weight: 500; text-decoration: none;
  background: color-mix(in srgb, var(--onSurface) 9%, transparent); color: var(--onSurface);
}
.prompt button.main, .prompt a.button.main { background: var(--primary); color: var(--onPrimary); }
.drop {
  position: absolute; inset: 0; border: 3px dashed var(--primary); border-radius: 12px;
  background: color-mix(in srgb, var(--primary) 18%, transparent); pointer-events: none;
}
.drop[hidden] { display: none; }
`;
