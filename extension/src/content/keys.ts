// The app's key names ("Shift+Left", ";", "A") for a key pressed in the browser. The app reads
// keys by where they are on the keyboard, so this goes by KeyboardEvent.code as well.

const CODES: Record<string, string> = {
  Semicolon: ';', Quote: "'", Comma: ',', Period: '.', Slash: '/', Backslash: '\\',
  BracketLeft: '[', BracketRight: ']', Minus: '-', Equal: '=', Backquote: '`',
  Space: 'Space', Enter: 'Enter', NumpadEnter: 'Enter', Tab: 'Tab', Backspace: 'Backspace',
  Delete: 'Delete', Escape: 'Escape', ArrowLeft: 'Left', ArrowRight: 'Right', ArrowUp: 'Up',
  ArrowDown: 'Down', Home: 'Home', End: 'End', PageUp: 'PageUp', PageDown: 'PageDown',
};

function keyName(code: string): string | null {
  if (/^Key[A-Z]$/.test(code)) return code.slice(3);
  if (/^Digit\d$/.test(code)) return code.slice(5);
  if (/^F([1-9]|1[0-2])$/.test(code)) return code;
  return CODES[code] ?? null;
}

/** The pressed key as the app would write it, modifiers first, in the app's order. */
export function bindingName(event: KeyboardEvent): string | null {
  const key = keyName(event.code);
  if (!key) return null;
  return (event.ctrlKey ? 'Ctrl+' : '') + (event.altKey ? 'Alt+' : '') + (event.shiftKey ? 'Shift+' : '') + (event.metaKey ? 'Cmd+' : '') + key;
}

/** Which action [event] is bound to, given the app's key map. */
export function actionFor(event: KeyboardEvent, keys: Record<string, string[]>): string | null {
  const name = bindingName(event);
  if (!name) return null;
  for (const [action, bindings] of Object.entries(keys)) if (bindings.includes(name)) return action;
  return null;
}

/** Whether the key went to something you type into, which keeps its keys. */
export function isTyping(event: KeyboardEvent): boolean {
  const target = event.composedPath()[0] as HTMLElement | undefined;
  if (!target) return false;
  return target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName);
}

// ---- how keys read on a keycap, as the app's (KeyMap.kt) ----------------------------------

const isMac = /Mac|iPhone|iPad/.test(navigator.platform);
export const CAP_LABELS: Record<string, string> = {
  Left: '←', Right: '→', Up: '↑', Down: '↓', Enter: isMac ? 'Return' : 'Enter', Escape: 'Esc',
  Backspace: isMac ? '⌫' : 'Backspace', PageUp: 'Page Up', PageDown: 'Page Down',
};
const MODIFIERS: Record<string, string> = isMac
  ? { Ctrl: '⌃', Alt: '⌥', Shift: '⇧', Cmd: '⌘' }
  : { Ctrl: 'Ctrl', Alt: 'Alt', Shift: 'Shift', Cmd: 'Win' };

/** A key as the app writes it ("Shift+Left") as one keycap reads: "⇧←", or "Shift+←". */
export function capLabel(binding: string): string {
  const parts = binding.split('+');
  // "+" itself would be the last part, empty
  const key = parts.pop() || '+';
  const caps = [...parts.map((m) => MODIFIERS[m] ?? m), CAP_LABELS[key] ?? key];
  return caps.join(isMac ? '' : '+');
}
