// A lookup's entries as the app's dictionary panel draws them (Panels.kt): the word and its
// reading, how it was deinflected, frequencies and pitch, then each dictionary's senses. The
// popup over the video and the study panel both draw them here.
import { Entry, LookupResult } from '../shared';
import { glossaryText, renderGlossary } from './glossary';
import { h } from '../dom';

export interface EntryActions {
  add(entry: Entry): void;
  remove(entry: Entry): void;
}

export interface LookupMessages {
  noDictionaries: string;
  noLocalDictionaries: string;
  nothingFound: string;
}

export type Shown = LookupResult | 'noDictionaries' | 'noLocalDictionaries';

/** Fills [box] with [result], keeping where it was scrolled to. */
export function renderLookup(
  box: HTMLElement,
  result: Shown,
  messages: LookupMessages,
  isAdded: (entry: Entry) => boolean,
  cardsOn: boolean,
  busy: boolean,
  actions: EntryActions,
) {
  const scroll = box.scrollTop;
  if (result === 'noDictionaries' || result === 'noLocalDictionaries') {
    box.replaceChildren(h('div', 'message', messages[result]));
    return;
  }
  if (!result.entries.length) {
    box.replaceChildren(h('div', 'message', messages.nothingFound));
    return;
  }
  box.replaceChildren(...result.entries.map((entry) => term(entry, isAdded(entry), cardsOn, busy, actions)));
  box.scrollTop = scroll;
}

function term(entry: Entry, added: boolean, cardsOn: boolean, busy: boolean, actions: EntryActions): HTMLElement {
  const term = h('div', 'term');
  const head = h('div', 'head');
  const words = h('div', 'words');
  if (entry.reading !== entry.expression) words.append(h('div', 'reading', entry.reading));
  words.append(h('div', 'expression', entry.expression));
  head.append(words);
  if (cardsOn) {
    const button = h('button', added ? 'card added' : 'card', added ? '−' : '+');
    button.title = added ? 'Remove from Anki' : 'Add to Anki';
    button.disabled = busy;
    button.addEventListener('click', () => (added ? actions.remove(entry) : actions.add(entry)));
    head.append(button);
  }
  term.append(head);

  if (entry.reasons.length || entry.frequencies.length || entry.pitch.length) {
    const row = h('div', 'row');
    if (entry.reasons.length) row.append(h('span', 'reasons', '« ' + entry.reasons.join(' « ')));
    entry.frequencies.forEach((f) => row.append(h('span', 'chip tertiary', `${f.dictionary} ${f.display}`)));
    if (entry.pitch.length) row.append(h('span', 'chip primary', 'pitch ' + entry.pitch.map((p) => `[${p}]`).join(' ')));
    term.append(row);
  }

  // one heading per dictionary, as the app
  const byDictionary = new Map<string, Entry['definitions']>();
  for (const d of entry.definitions) byDictionary.set(d.dictionary, [...(byDictionary.get(d.dictionary) ?? []), d]);
  for (const [title, definitions] of byDictionary) {
    const block = h('div', 'dictionary');
    const tags = h('div', 'row');
    tags.append(h('span', 'chip primary', title));
    const wordTags = [...new Set(definitions.flatMap((d) => d.termTags.split(' ')).filter(Boolean))];
    wordTags.forEach((t) => tags.append(h('span', 'chip tertiary', t)));
    block.append(tags);
    const isForms = (d: Entry['definitions'][number]) => d.definitionTags.split(' ').includes('forms');
    const senses = definitions.filter((d) => !isForms(d));
    senses.forEach((definition, index) => {
      const sense = h('div', 'sense');
      if (senses.length > 1) sense.append(h('span', 'number', String(index + 1)));
      const body = h('div', 'body');
      const senseTags = [...new Set(definition.definitionTags.split(' ').filter((t) => t && !/^\d+$/.test(t)))];
      if (senseTags.length) {
        const row = h('div', 'row');
        senseTags.forEach((t) => row.append(h('span', 'chip secondary', t)));
        body.append(row);
      }
      body.append(renderGlossary(definition.glossary));
      sense.append(body);
      block.append(sense);
    });
    definitions.filter(isForms).forEach((d) => {
      const forms = glossaryText(d.glossary).split('\n').map((l) => l.replace(/^\d+\.\s*/, '').trim()).filter(Boolean);
      block.append(h('div', 'forms', 'Other forms: ' + forms.join('、')));
    });
    term.append(block);
  }
  return term;
}
