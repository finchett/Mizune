// The pictures a streaming site's player shows over its seek bar: a BIF file (Roku's format, which
// Netflix uses), a frame every few seconds as JPEGs one after another, behind an index of when
// each one is from and where it starts.

const MAGIC = [0x89, 0x42, 0x49, 0x46, 0x0d, 0x0a, 0x1a, 0x0a];
const HEADER = 64;

export interface Bif {
  buffer: ArrayBuffer;
  /** When each frame is from, in seconds, in order. */
  times: number[];
  /** Where each frame's JPEG starts in [buffer]; one more for where the last one ends. */
  offsets: number[];
}

/** Whether [buffer] starts as a BIF file does. */
export function isBif(buffer: ArrayBuffer): boolean {
  if (buffer.byteLength < HEADER) return false;
  const head = new Uint8Array(buffer, 0, MAGIC.length);
  return MAGIC.every((b, i) => head[i] === b);
}

/** [buffer]'s frames, or null if it isn't a BIF file or doesn't hold together. */
export function parseBif(buffer: ArrayBuffer): Bif | null {
  if (!isBif(buffer)) return null;
  const view = new DataView(buffer);
  const count = view.getUint32(12, true);
  // how long each step of the index's timestamps is; 0 means a second
  const step = (view.getUint32(16, true) || 1000) / 1000;
  if (!count || HEADER + (count + 1) * 8 > buffer.byteLength) return null;
  const times: number[] = [];
  const offsets: number[] = [];
  for (let i = 0; i <= count; i++) {
    const timestamp = view.getUint32(HEADER + i * 8, true);
    const offset = view.getUint32(HEADER + i * 8 + 4, true);
    if (offset > buffer.byteLength || (offsets.length && offset < offsets[offsets.length - 1])) return null;
    offsets.push(offset);
    if (i < count) times.push(timestamp * step);
  }
  return { buffer, times, offsets };
}

/** Which of [bif]'s frames shows [seconds]: the last one from before it. */
export function frameAt(bif: Bif, seconds: number): number {
  let low = 0, high = bif.times.length - 1;
  while (low < high) {
    const mid = (low + high + 1) >> 1;
    if (bif.times[mid] <= seconds) low = mid;
    else high = mid - 1;
  }
  return low;
}
