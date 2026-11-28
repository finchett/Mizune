// Where Mizune's overlay puts things from: the window's corner, as it is on most sites; or, where
// Mizune's player is a box in the site's page (YouTube's), the page's own corner, so that all of
// it scrolls with the page as the site's player does, rather than catching up a frame behind.

let inPage: HTMLElement | null = null;

/** Puts things from the page's corner, by [host] (the overlay, at that corner), or from the window's again. */
export function placeInPage(host: HTMLElement | null) {
  inPage = host;
}

/**
 * Where the overlay's box is in the window: its (0, 0), and how far its right and bottom edges are
 * from that, which is what right and bottom are set from. Otherwise the window's.
 */
export function origin(): { x: number; y: number; width: number; height: number } {
  if (!inPage) return { x: 0, y: 0, width: innerWidth, height: innerHeight };
  const r = inPage.getBoundingClientRect();
  return { x: r.left, y: r.top, width: r.width, height: r.height };
}

/** Whether things are put from the page's corner. */
export const placingInPage = () => inPage != null;
