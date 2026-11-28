// A menu from the player, as the app's (PlayerMenu.kt, built from shared-ui's Menus.kt): a compact
// card of choices in groups, a shade lighter than the panel, that stays up while choices are made
// so each shows behind it, and goes with a click elsewhere or Escape.
import { EASE } from './motion';
import { h } from '../dom';
import { origin } from './origin';

export type MenuItem =
  /** A quiet label over a group, with an optional note at the far end (a key, or how it's used). */
  | { kind: 'group'; label: string; note?: string }
  /** Muted words under a group's label, saying why its choices don't apply. */
  | { kind: 'note'; text: string }
  /** [folded]: one of a fold's, set in under it ([depth]: how many folds in, where it's more than one). */
  | { kind: 'choice'; title: string; detail?: string; selected: boolean; folded?: boolean; depth?: number; run: () => void }
  /** Two or three choices side by side, as one control. */
  | { kind: 'segments'; options: string[]; selected: number; run: (index: number) => void }
  /** Settings that are on or off, side by side on one line: each an icon and a word, lit while on. */
  | { kind: 'toggles'; toggles: { title: string; icon: string; tip: string; on: boolean; run: (on: boolean) => void }[] }
  /** A setting that's on or off, with a switch at its end. */
  | { kind: 'switch'; title: string; on: boolean; depth?: number; run: (on: boolean) => void }
  /**
   * The rest of a group's choices, folded away under [title] until it's opened: the folded choices
   * that follow it (and switches, and folds of their own, a [depth] further in), which slide open
   * under it. [id] tells it from the menu's other folds. At its end, how many there are, or
   * [detail] (the one chosen, say).
   */
  | { kind: 'fold'; id: string; title: string; count: number; open: boolean; run: () => void; detail?: string; depth?: number };

/** How many folds in [item] is. */
const depthOf = (item: MenuItem) => ('depth' in item && item.depth) || ('folded' in item && item.folded ? 1 : 0);

export class Menu {
  readonly element = h('div', 'menu');
  private anchor: HTMLElement | null = null;
  private build: (() => MenuItem[]) | null = null;
  private closing = 0;
  /** Whether each fold was open when it was last drawn, so it slides only as it opens or closes. */
  private folds = new Map<string, boolean>();

  /**
   * [within]: where it may go, where that's less than the window (the site's player, in its page,
   * as it is in the window now).
   */
  constructor(private onChange: (open: boolean) => void = () => {}, private within: () => DOMRect | null = () => null) {
    this.element.hidden = true;
    this.onOutside = this.onOutside.bind(this);
    for (const type of ['pointerdown', 'mousedown', 'mouseup', 'click', 'wheel', 'contextmenu']) {
      this.element.addEventListener(type, (e) => e.stopPropagation());
    }
    // nothing in it keeps the focus, which Space would press again
    this.element.addEventListener('mousedown', (e) => e.preventDefault());
  }

  get isOpen() {
    return this.anchor != null;
  }

  /** Opens it from [anchor] (a click there again closes it), with what [build] says is in it. */
  toggle(anchor: HTMLElement, build: () => MenuItem[]) {
    if (this.anchor === anchor) return this.close();
    this.anchor = anchor;
    this.build = build;
    clearTimeout(this.closing);
    this.element.hidden = false;
    this.refresh();
    this.place();
    this.element.classList.remove('open');
    void this.element.offsetWidth;
    this.element.classList.add('open');
    addEventListener('pointerdown', this.onOutside, true);
    this.onChange(true);
  }

  /** Draws it again, after a choice or once what it lists has come in. */
  refresh() {
    if (!this.build) return;
    const scroll = this.element.scrollTop;
    const items = this.build();
    const nodes: HTMLElement[] = [];
    const changed: { row: HTMLElement; body: HTMLElement; open: boolean }[] = [];
    // the items from [start] at least [depth] folds in, into [into]; where it stopped
    const draw = (start: number, into: HTMLElement[], depth: number): number => {
      let i = start;
      for (; i < items.length && depthOf(items[i]) >= depth; i++) {
        const item = items[i];
        if (item.kind !== 'fold') {
          into.push(this.item(item, i === 0));
          continue;
        }
        // drawn as it was, then moved on to how it is, so it slides
        const was = this.folds.get(item.id) ?? item.open;
        this.folds.set(item.id, item.open);
        const row = this.item({ ...item, open: was }, i === 0);
        const body = h('div', was ? 'fold-body open' : 'fold-body');
        const inner = h('div', 'fold-inner');
        const within: HTMLElement[] = [];
        i = draw(i + 1, within, depthOf(item) + 1) - 1;
        inner.append(...within);
        body.append(inner);
        body.inert = !item.open;
        into.push(row, body);
        if (was !== item.open) changed.push({ row, body, open: item.open });
      }
      return i;
    };
    draw(0, nodes, 0);
    this.element.replaceChildren(...nodes);
    this.element.scrollTop = scroll;
    if (changed.length) {
      void this.element.offsetHeight;
      for (const { row, body, open } of changed) {
        row.classList.toggle('open', open);
        row.setAttribute('aria-expanded', String(open));
        body.classList.toggle('open', open);
      }
      // what opens below the menu's foot is scrolled up into it as it opens, its row kept in sight
      const opened = changed.filter((c) => c.open).pop();
      if (opened) {
        const menu = this.element.getBoundingClientRect();
        const row = opened.row.getBoundingClientRect();
        const bottom = row.bottom + (opened.body.firstElementChild as HTMLElement).scrollHeight + 6;
        const by = Math.min(bottom - menu.bottom, row.top - menu.top - 6);
        if (by > 0) {
          // it can only scroll as far as the list has grown, so it follows it down as it grows
          const target = this.element.scrollTop + by;
          const until = performance.now() + 280;
          const follow = () => {
            this.element.scrollTop = Math.min(target, this.element.scrollHeight - this.element.clientHeight);
            if (performance.now() < until && this.element.scrollTop < target) requestAnimationFrame(follow);
          };
          requestAnimationFrame(follow);
        }
      }
    }
  }

  close() {
    if (!this.anchor) return;
    this.anchor = null;
    this.build = null;
    this.folds.clear();
    removeEventListener('pointerdown', this.onOutside, true);
    this.element.classList.remove('open');
    // as the app's dropdown: out in 75 ms
    this.closing = window.setTimeout(() => (this.element.hidden = true), 75);
    this.onChange(false);
  }

  /** Beside its anchor: under it near the top of the window, over it near the bottom, right edges in line. */
  private place() {
    const a = this.anchor!.getBoundingClientRect();
    const s = this.element.style;
    // in the window, and in what it's kept within (of that, what's in the window)
    const box = this.within();
    const top = Math.max(0, box?.top ?? 0), bottom = Math.min(innerHeight, box?.bottom ?? innerHeight);
    const below = a.top < (top + bottom) / 2;
    // from the overlay's corner, which (where it's in the page) is the page's; its right and bottom
    // edges are that corner's too (see origin)
    const o = origin();
    s.right = `${Math.max(8, innerWidth - a.right - 6) - (innerWidth - o.x - o.width)}px`;
    s.top = below ? `${a.bottom + 6 - o.y}px` : '';
    s.bottom = below ? '' : `${innerHeight - a.top + 6 - (innerHeight - o.y - o.height)}px`;
    s.maxHeight = `${Math.max(120, (below ? bottom - a.bottom : a.top - top) - 18)}px`;
    s.transformOrigin = below ? 'top right' : 'bottom right';
  }

  private onOutside(event: PointerEvent) {
    const path = event.composedPath();
    if (path.includes(this.element) || (this.anchor && path.includes(this.anchor))) return;
    this.close();
  }

  private item(item: MenuItem, first: boolean): HTMLElement {
    const row = this.row(item, first);
    // further in for each fold it's in, past the first (which .folded is)
    const depth = depthOf(item);
    if (depth > 1 || (depth === 1 && item.kind !== 'choice')) row.style.paddingLeft = `${10 + 12 * depth}px`;
    return row;
  }

  private row(item: MenuItem, first: boolean): HTMLElement {
    switch (item.kind) {
      case 'group': {
        const row = h('div', first ? 'group first' : 'group');
        row.append(h('span', 'label', item.label));
        if (item.note) row.append(h('span', 'note', item.note));
        return row;
      }
      case 'note':
        return h('div', 'menu-note', item.text);
      case 'choice': {
        const row = h('button', `choice${item.selected ? ' selected' : ''}${item.folded ? ' folded' : ''}`);
        row.append(h('span', 'title', item.title));
        if (item.detail) row.append(h('span', 'detail', item.detail));
        row.append(h('span', 'tick', item.selected ? '✓' : ''));
        row.addEventListener('click', () => {
          item.run();
          this.refresh();
        });
        return row;
      }
      case 'switch': {
        const row = h('button', 'choice switch-row');
        row.setAttribute('role', 'switch');
        row.setAttribute('aria-checked', String(item.on));
        row.append(h('span', 'title', item.title), h('span', item.on ? 'switch on' : 'switch'));
        row.addEventListener('click', () => {
          item.run(!item.on);
          this.refresh();
        });
        return row;
      }
      case 'fold': {
        const row = h('button', item.open ? 'choice fold open' : 'choice fold');
        row.setAttribute('aria-expanded', String(item.open));
        row.append(h('span', 'title', item.title), h('span', 'detail', item.detail ?? String(item.count)), h('span', 'chevron', '›'));
        row.addEventListener('click', () => {
          item.run();
          this.refresh();
        });
        return row;
      }
      case 'toggles': {
        const row = h('div', 'toggles');
        for (const toggle of item.toggles) {
          const button = h('button', toggle.on ? 'toggle on' : 'toggle');
          button.setAttribute('aria-pressed', String(toggle.on));
          button.title = toggle.tip;
          button.innerHTML = toggle.icon;
          button.append(h('span', 'title', toggle.title));
          button.addEventListener('click', () => {
            toggle.run(!toggle.on);
            this.refresh();
          });
          row.append(button);
        }
        return row;
      }
      case 'segments': {
        const row = h('div', 'segments');
        item.options.forEach((label, i) => {
          const segment = h('button', i === item.selected ? 'segment on' : 'segment', label);
          segment.addEventListener('click', () => {
            item.run(i);
            this.refresh();
          });
          row.append(segment);
        });
        return row;
      }
    }
  }
}

export const MENU_STYLES = `
.menu {
  position: fixed; z-index: 2147483647; width: 320px; padding: 8px 6px; box-sizing: border-box;
  overflow-y: auto; overscroll-behavior: contain; pointer-events: auto;
  background: var(--raised, var(--surface)); color: var(--onSurface); border-radius: 12px;
  border: 1px solid color-mix(in srgb, var(--onSurface) 12%, transparent);
  font: 400 14px/1.3 system-ui, -apple-system, "Segoe UI", sans-serif;
  scrollbar-width: thin; scrollbar-color: color-mix(in srgb, var(--onSurface) 30%, transparent) transparent;
  /* as the app's dropdown: out of its corner in 120 ms, away in 75 */
  opacity: 0; transform: scale(0.8); transition: opacity 75ms linear, transform 75ms ${EASE};
}
.menu[hidden] { display: none; }
.menu.open { opacity: 1; transform: none; transition: opacity 30ms linear, transform 120ms ${EASE}; }
.menu .group { display: flex; align-items: flex-end; padding: 14px 10px 6px; color: var(--onSurfaceVariant); }
.menu .group.first { padding-top: 6px; }
.menu .group .label { flex: 1; font-size: 11px; line-height: 14px; letter-spacing: 1.4px; font-weight: 500; text-transform: uppercase; }
.menu .group .note { font-size: 12px; opacity: 0.8; }
.menu .menu-note { font-size: 12px; color: var(--onSurfaceVariant); padding: 4px 10px; }
.menu button { font: inherit; color: inherit; border: none; background: transparent; cursor: pointer; }
.menu .choice {
  width: 100%; height: 32px; display: flex; align-items: center; gap: 10px; padding: 0 10px; border-radius: 8px; text-align: left;
}
.menu .choice:hover { background: color-mix(in srgb, var(--onSurface) 6%, transparent); }
.menu .choice.selected { background: color-mix(in srgb, var(--primary) 12%, transparent); }
.menu .choice .title { flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.menu .choice .detail { font-size: 12px; color: var(--onSurfaceVariant); white-space: nowrap; }
.menu .choice .tick { width: 14px; flex: none; text-align: center; font-weight: 500; color: var(--primary); }
/* a row under the segments, or the toggles, keeps clear of them */
.menu :is(.segments, .toggles) + .choice { margin-top: 6px; }
.menu .switch-row .switch {
  position: relative; width: 32px; height: 18px; flex: none; border-radius: 9px;
  background: color-mix(in srgb, var(--onSurface) 22%, transparent); transition: background-color 150ms ${EASE};
}
.menu .switch-row .switch::after {
  content: ""; position: absolute; left: 2px; top: 2px; width: 14px; height: 14px; border-radius: 50%;
  background: #fff; box-shadow: 0 1px 2px rgba(0, 0, 0, 0.25); transition: transform 150ms ${EASE};
}
.menu .switch-row .switch.on { background: var(--primary); }
.menu .switch-row .switch.on::after { transform: translateX(14px); }
/* the rest of a group's choices, folded away: they slide open under it, and closed */
.menu .fold-body {
  display: grid; grid-template-rows: 0fr; opacity: 0;
  transition: grid-template-rows 220ms ${EASE}, opacity 160ms ${EASE};
}
.menu .fold-body.open { grid-template-rows: 1fr; opacity: 1; transition: grid-template-rows 220ms ${EASE}, opacity 220ms ${EASE} 40ms; }
.menu .fold-inner { min-height: 0; overflow: hidden; }
@media (prefers-reduced-motion: reduce) { .menu .fold-body, .menu .fold .chevron { transition: none; } }
.menu .fold { color: var(--onSurfaceVariant); }
.menu .fold .chevron { width: 14px; flex: none; text-align: center; font-size: 15px; transition: transform 150ms ${EASE}; }
.menu .fold.open .chevron { transform: rotate(90deg); }
.menu .choice.folded { padding-left: 22px; }
.menu .toggles { display: flex; gap: 6px; margin: 0 4px; }
.menu .toggle {
  flex: 1; min-width: 0; height: 32px; display: flex; align-items: center; justify-content: center; gap: 7px; border-radius: 8px;
  font-weight: 500; color: var(--onSurfaceVariant); background: color-mix(in srgb, var(--onSurface) 6%, transparent);
  transition: background-color 150ms ${EASE}, color 150ms ${EASE};
}
.menu .toggle:hover { background: color-mix(in srgb, var(--onSurface) 10%, transparent); }
.menu .toggle svg { flex: none; }
.menu .toggle.on { background: color-mix(in srgb, var(--primary) 18%, transparent); color: var(--onSurface); }
.menu .toggle.on svg { color: var(--primary); }
.menu .segments {
  display: flex; height: 32px; margin: 0 4px; padding: 3px; border-radius: 8px; box-sizing: border-box;
  background: color-mix(in srgb, var(--onSurface) 6%, transparent);
}
.menu .segment { flex: 1; border-radius: 6px; font-weight: 500; color: var(--onSurfaceVariant); white-space: nowrap; }
.menu .segment:hover { background: color-mix(in srgb, var(--onSurface) 6%, transparent); }
.menu .segment.on { background: color-mix(in srgb, var(--primary) 18%, transparent); color: var(--onSurface); }
`;
