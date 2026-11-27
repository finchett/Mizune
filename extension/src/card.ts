// The page a card's preview is drawn in, so the card's own scripts run as they do in Anki: the
// manifest sandboxes it, so it has no origin of its own and can't reach the extension, its storage
// or the settings page around it. The settings send the card's whole page; this takes it in as
// Anki's reviewer does, its scripts run once it's in, and sends back how tall it came out and the
// links it's asked to open.

import { cardHeight, handleClicks } from './anki-card';

type Reviewer = Window & { onUpdateHook?: unknown[]; onShownHook?: unknown[]; pycmd?: () => void };

async function show(html: string) {
  const page = new DOMParser().parseFromString(html, 'text/html');
  document.head.replaceChildren(...[...page.head.childNodes].map((node) => document.importNode(node, true)));
  document.body.className = page.body.className;
  document.body.replaceChildren(...[...page.body.childNodes].map((node) => document.importNode(node, true)));

  // what Anki's reviewer gives a card's scripts to hook into, and to call
  const reviewer = window as Reviewer;
  reviewer.onUpdateHook = [];
  reviewer.onShownHook = [];
  reviewer.pycmd = () => {};

  // scripts taken in from elsewhere don't run, so each is made again, in order
  for (const old of document.querySelectorAll('script')) {
    const script = document.createElement('script');
    for (const attribute of old.attributes) script.setAttribute(attribute.name, attribute.value);
    script.textContent = old.textContent;
    const loaded = script.src ? new Promise((done) => { script.onload = script.onerror = done; }) : null;
    old.replaceWith(script);
    if (loaded) await loaded;
  }

  const fit = () => parent.postMessage({ height: cardHeight(document) }, '*');
  fit();
  new ResizeObserver(fit).observe(document.body);
  handleClicks(document, (href) => parent.postMessage({ open: href }, '*'));

  // as Anki's reviewer does once the card is in: what's to run before it shows, then after
  for (const hooks of [reviewer.onUpdateHook, reviewer.onShownHook]) {
    for (const hook of Array.isArray(hooks) ? [...hooks] : []) {
      try {
        if (typeof hook === 'function') await hook();
      } catch (error) {
        console.warn("A card's script failed", error);
      }
    }
  }
}

let shown = false;
window.addEventListener('message', (event) => {
  if (event.source !== parent || shown) return;
  const card = (event.data as { card?: unknown })?.card;
  if (typeof card !== 'string') return;
  shown = true;
  void show(card);
});
