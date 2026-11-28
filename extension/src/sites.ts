// The sites to watch on: shortcuts in the toolbar's popup and at the end of the welcome, for the
// ones chosen. Each is shown by its own logo, as it is, only to say which site a tile opens.
import { h } from './dom';

export interface WatchSite {
  id: string;
  name: string;
  url: string;
  /** The site's colour, as an accent on its tile. */
  colour: string;
  /** What Mizune does there, for where the sites are chosen. */
  note: string;
  /** The site's own icon, as the site itself serves it, in public/sites. */
  logo: string;
}

export const WATCH_SITES: WatchSite[] = [
  { id: 'netflix', name: 'Netflix', url: 'https://www.netflix.com/browse', colour: '#E50914', note: 'Comes up by itself', logo: 'sites/netflix.png' },
  { id: 'crunchyroll', name: 'Crunchyroll', url: 'https://www.crunchyroll.com/', colour: '#F47521', note: 'Comes up by itself', logo: 'sites/crunchyroll.png' },
  { id: 'youtube', name: 'YouTube', url: 'https://www.youtube.com/', colour: '#FF0033', note: 'Comes up with Japanese captions', logo: 'sites/youtube.png' },
];

export const DEFAULT_SITES = ['netflix', 'crunchyroll', 'youtube'];

/** The chosen sites, in the list's order. */
export const chosenSites = (ids: string[]) => WATCH_SITES.filter((s) => ids.includes(s.id));

/** [site]'s logo, for beside its name. */
export function siteLogo(site: WatchSite): HTMLImageElement {
  const logo = h('img', 'site-logo');
  logo.src = `/${site.logo}`;
  logo.alt = '';
  return logo;
}

// ---- the tiles ---------------------------------------------------------------------------------

/** A site's tile, which goes there: its logo alone, in the middle. */
export function siteTile(site: WatchSite, go: () => void): HTMLButtonElement {
  const tile = h('button', 'site-tile');
  tile.style.setProperty('--brand', site.colour);
  tile.append(siteLogo(site));
  tile.setAttribute('aria-label', `Open ${site.name}`);
  tile.addEventListener('click', go);
  return tile;
}

/** A site's tile for choosing it, ticked while it's chosen. */
export function pickTile(site: WatchSite, chosen: boolean, flip: (on: boolean) => void): HTMLButtonElement {
  const tile = h('button', chosen ? 'site-tile pick on' : 'site-tile pick');
  tile.style.setProperty('--brand', site.colour);
  tile.setAttribute('aria-pressed', String(chosen));
  tile.append(siteLogo(site), h('span', 'check', chosen ? '✓' : ''), h('span', 'name', site.name), h('span', 'note', site.note));
  tile.addEventListener('click', () => flip(!chosen));
  return tile;
}

/** The tiles of [sites] in a row, [go] opening one. */
export function siteRow(sites: WatchSite[], go: (site: WatchSite) => void): HTMLElement {
  const row = h('div', 'site-tiles');
  row.style.setProperty('--count', String(Math.max(2, sites.length)));
  row.append(...sites.map((site) => siteTile(site, () => go(site))));
  return row;
}
