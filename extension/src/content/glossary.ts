// A Yomitan glossary (strings, text objects and structured content) as the app shows it: see
// Glossary.kt in shared-ui, which this follows rule for rule.

const TAG_CONTENT = new Set(['part-of-speech-info', 'misc-info', 'field-info', 'dialect-info', 'tag', 'tags', 'sense-number']);

class Out {
  readonly root = document.createElement('div');
  private stack: HTMLElement[] = [this.root];
  private last: string | null = null;
  readonly lists: number[] = [];

  append(text: string) {
    if (!text) return;
    this.stack[this.stack.length - 1].append(text);
    this.last = text[text.length - 1];
  }

  newline() {
    if (this.last !== null && this.last !== '\n') this.append('\n');
  }

  styled(className: string, block: () => void) {
    const span = document.createElement('span');
    span.className = className;
    this.stack[this.stack.length - 1].append(span);
    this.stack.push(span);
    block();
    this.stack.pop();
  }
}

const isPlain = (item: any) => typeof item === 'string' || (item && typeof item === 'object' && item.type === 'text');

export function renderGlossary(glossaryJson: string): HTMLElement {
  let items: any[];
  try {
    items = JSON.parse(glossaryJson);
    if (!Array.isArray(items)) throw new Error();
  } catch {
    const div = document.createElement('div');
    div.textContent = glossaryJson;
    return div;
  }
  const out = new Out();
  const plain = items.filter(isPlain).length;
  let number = 0;
  for (const item of items) {
    out.newline();
    if (typeof item === 'string') {
      if (plain > 1) out.append(`${++number}. `);
      out.append(item);
    } else if (item?.type === 'text') {
      if (plain > 1) out.append(`${++number}. `);
      out.append(String(item.text ?? ''));
    } else if (item?.type === 'structured-content') {
      node(item.content, out);
    }
  }
  // like the app, no trailing blank lines
  const last = out.root.lastChild;
  if (last?.nodeType === Node.TEXT_NODE) last.textContent = last.textContent!.replace(/\s+$/, '');
  out.root.className = 'glossary';
  return out.root;
}

/** Plain text of a glossary, for "Other forms". */
export function glossaryText(glossaryJson: string): string {
  return renderGlossary(glossaryJson).textContent ?? '';
}

function node(value: any, out: Out) {
  if (value == null) return;
  if (typeof value === 'string') return out.append(value);
  if (Array.isArray(value)) return value.forEach((v) => node(v, out));
  if (typeof value === 'object') return element(value, out);
  out.append(String(value));
}

function element(el: any, out: Out) {
  const content = el.content;
  const dataContent: string = el.data?.content ?? '';
  switch (el.tag) {
    case 'br':
      return out.append('\n');
    case 'rp':
    case 'img':
    case 'image':
      return;
    case 'rt':
      return out.styled('g-rt', () => {
        out.append('(');
        node(content, out);
        out.append(')');
      });
    case 'a':
      return out.styled('g-link', () => node(content, out));
    case 'ul':
    case 'ol':
      out.newline();
      out.lists.push(el.tag === 'ol' ? 0 : -1);
      node(content, out);
      out.lists.pop();
      return out.newline();
    case 'li': {
      out.newline();
      out.append('  '.repeat(Math.max(0, out.lists.length - 1)));
      const last = out.lists.length - 1;
      if (last >= 0 && out.lists[last] >= 0) out.append(`${++out.lists[last]}. `);
      else out.append('• ');
      node(content, out);
      return out.newline();
    }
    case 'div':
    case 'p':
    case 'details':
    case 'summary':
    case 'table':
    case 'thead':
    case 'tbody':
    case 'tfoot':
    case 'tr':
      out.newline();
      block(dataContent, content, out);
      return out.newline();
    case 'td':
    case 'th':
      node(content, out);
      return out.append('  ');
    default:
      return block(dataContent, content, out);
  }
}

function block(dataContent: string, content: any, out: Out) {
  if (TAG_CONTENT.has(dataContent)) {
    out.styled('g-tag', () => node(content, out));
    out.append(' ');
  } else if (dataContent.includes('example')) {
    out.styled('g-example', () => node(content, out));
  } else if (dataContent === 'attribution' || dataContent === 'forms') {
    out.styled('g-quiet', () => node(content, out));
  } else {
    node(content, out);
  }
}
