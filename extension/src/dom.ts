// Building the extension's own parts of a page: an element with its class and text, or an SVG one.

export function h<K extends keyof HTMLElementTagNameMap>(tag: K, className?: string, text?: string): HTMLElementTagNameMap[K] {
  const el = document.createElement(tag);
  if (className) el.className = className;
  if (text != null) el.textContent = text;
  return el;
}

export function svg(tag: string, attributes: Record<string, string | number> = {}): SVGElement {
  const el = document.createElementNS('http://www.w3.org/2000/svg', tag) as SVGElement;
  for (const [name, value] of Object.entries(attributes)) el.setAttribute(name, String(value));
  return el;
}
