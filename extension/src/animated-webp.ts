// An animated WebP made of still ones. Browsers encode WebP stills (canvas.convertToBlob) but not
// animations, so each frame is a still, and its bitstream is lifted into an animation's frame
// (the container's ANMF chunk), as libwebp's own muxer does. Anki shows animated WebP on the
// desktop, on AnkiDroid and on AnkiMobile.

export interface Frame {
  /** A still WebP, as the browser encoded it. */
  webp: Uint8Array;
  /** How long it shows, in milliseconds. */
  duration: number;
}

const text = (bytes: Uint8Array, at: number) => String.fromCharCode(bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]);
const u32 = (bytes: Uint8Array, at: number) => (bytes[at] | (bytes[at + 1] << 8) | (bytes[at + 2] << 16) | (bytes[at + 3] << 24)) >>> 0;

interface Chunk { tag: string; data: Uint8Array }

/** A still's chunks, after its RIFF header. */
function chunks(webp: Uint8Array): Chunk[] {
  if (text(webp, 0) !== 'RIFF' || text(webp, 8) !== 'WEBP') throw new Error('Not a WebP');
  const out: Chunk[] = [];
  for (let at = 12; at + 8 <= webp.length;) {
    const size = Math.min(u32(webp, at + 4), webp.length - at - 8);
    // a view made directly, not webp.subarray: in a Firefox content script, the bytes of a blob
    // belong to the page's side, which won't let subarray look up their constructor
    out.push({ tag: text(webp, at), data: new Uint8Array(webp.buffer, webp.byteOffset + at + 8, size) });
    at += 8 + size + (size & 1);
  }
  return out;
}

/** A still's width and height, from its VP8X, VP8 or VP8L chunk. */
function size(parts: Chunk[]): { width: number; height: number } {
  for (const { tag, data } of parts) {
    if (tag === 'VP8X') return { width: 1 + (data[4] | (data[5] << 8) | (data[6] << 16)), height: 1 + (data[7] | (data[8] << 8) | (data[9] << 16)) };
    // lossy: the key frame's start code, then 14 bits of each
    if (tag === 'VP8 ') return { width: (data[6] | (data[7] << 8)) & 0x3fff, height: (data[8] | (data[9] << 8)) & 0x3fff };
    // lossless: a signature byte, then 14 bits of each, less one
    if (tag === 'VP8L') {
      const bits = data[1] | (data[2] << 8) | (data[3] << 16) | (data[4] << 24);
      return { width: 1 + (bits & 0x3fff), height: 1 + ((bits >>> 14) & 0x3fff) };
    }
  }
  throw new Error('A WebP without a picture');
}

class Writer {
  private parts: number[] = [];
  bytes(values: ArrayLike<number>) {
    for (let i = 0; i < values.length; i++) this.parts.push(values[i]);
  }
  tag(name: string) {
    for (const c of name) this.parts.push(c.charCodeAt(0));
  }
  u16(value: number) {
    this.parts.push(value & 0xff, (value >>> 8) & 0xff);
  }
  u24(value: number) {
    this.parts.push(value & 0xff, (value >>> 8) & 0xff, (value >>> 16) & 0xff);
  }
  u32(value: number) {
    this.parts.push(value & 0xff, (value >>> 8) & 0xff, (value >>> 16) & 0xff, (value >>> 24) & 0xff);
  }
  chunk(name: string, data: ArrayLike<number>) {
    this.tag(name);
    this.u32(data.length);
    this.bytes(data);
    if (data.length & 1) this.parts.push(0);
  }
  get length() {
    return this.parts.length;
  }
  done() {
    return Uint8Array.from(this.parts);
  }
}

/** [frames], all the same size, as one WebP that plays them over and over. */
export function animatedWebp(frames: Frame[]): Uint8Array {
  if (!frames.length) throw new Error('No frames');
  const stills = frames.map((f) => chunks(f.webp));
  const { width, height } = size(stills[0]);
  const alpha = stills.some((parts) => parts.some((c) => c.tag === 'ALPH'));

  const body = new Writer();
  // the canvas: animated, with alpha if a frame has it
  const header = new Writer();
  header.bytes([0x02 | (alpha ? 0x10 : 0), 0, 0, 0]);
  header.u24(width - 1);
  header.u24(height - 1);
  body.chunk('VP8X', header.done());
  // a transparent background, played forever
  const anim = new Writer();
  anim.u32(0);
  anim.u16(0);
  body.chunk('ANIM', anim.done());

  stills.forEach((parts, i) => {
    const frame = new Writer();
    frame.u24(0);
    frame.u24(0);
    frame.u24(width - 1);
    frame.u24(height - 1);
    frame.u24(Math.max(1, Math.min(0xffffff, Math.round(frames[i].duration))));
    // each frame stands on its own: not blended over the one before
    frame.bytes([0x02]);
    for (const { tag, data } of parts) if (tag === 'ALPH' || tag === 'VP8 ' || tag === 'VP8L') frame.chunk(tag, data);
    body.chunk('ANMF', frame.done());
  });

  const file = new Writer();
  file.tag('RIFF');
  file.u32(4 + body.length);
  file.tag('WEBP');
  const out = file.done();
  const rest = body.done();
  const whole = new Uint8Array(out.length + rest.length);
  whole.set(out);
  whole.set(rest, out.length);
  return whole;
}

/**
 * Collects frames from a moving picture, at most [fps] a second, each drawn to [height] and
 * encoded as it comes. [draw] puts the picture on the canvas it's given, or says it can't yet.
 */
export class FrameCollector {
  private frames: { blob: Promise<Blob>; at: number }[] = [];
  private canvas: OffscreenCanvas | null = null;
  private last = -Infinity;

  constructor(private readonly fps: number, private readonly height: number, private readonly quality: number) {}

  /**
   * A frame, if it's time for one: [at] is where the picture is, in milliseconds, and [width]
   * and [height] its size, which [draw] scales into the canvas.
   */
  add(at: number, width: number, height: number, draw: (context: OffscreenCanvasRenderingContext2D, canvasWidth: number, canvasHeight: number) => void) {
    // a little early rather than a frame late: 25 frames a second give 8, not 6
    if (at - this.last < (1000 / this.fps) * 0.8 || !width || !height) return;
    this.last = at;
    if (!this.canvas) {
      const scale = Math.min(1, this.height / height);
      this.canvas = new OffscreenCanvas(Math.max(2, Math.round(width * scale)), Math.max(2, Math.round(height * scale)));
    }
    const context = this.canvas.getContext('2d')!;
    context.imageSmoothingQuality = 'high';
    draw(context, this.canvas.width, this.canvas.height);
    this.frames.push({ blob: this.canvas.convertToBlob({ type: 'image/webp', quality: this.quality }), at });
  }

  get count() {
    return this.frames.length;
  }

  /** The animation, ending at [end] (ms); null with fewer than two frames, or no WebP to make them. */
  async finish(end: number): Promise<Blob | null> {
    if (this.frames.length < 2) return null;
    const blobs = await Promise.all(this.frames.map((f) => f.blob));
    if (blobs.some((b) => b.type !== 'image/webp')) return null;
    const stills = await Promise.all(blobs.map(async (b) => new Uint8Array(await b.arrayBuffer())));
    const frames = stills.map((webp, i) => ({
      webp,
      duration: (i + 1 < this.frames.length ? this.frames[i + 1].at : Math.max(end, this.frames[i].at + 1000 / this.fps)) - this.frames[i].at,
    }));
    return new Blob([animatedWebp(frames).buffer as ArrayBuffer], { type: 'image/webp' });
  }
}
