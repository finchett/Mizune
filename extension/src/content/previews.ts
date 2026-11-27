// The site's pictures of its video, for over Mizune's seek bar: decoded as they're wanted, the
// last few kept. Drawn on a canvas, which a site's rules for where pictures come from don't cover.
import { Bif, frameAt, parseBif } from '../bif';

const KEEP = 24;

export class Previews {
  private decoded = new Map<number, Promise<ImageBitmap | null>>();

  private constructor(private bif: Bif) {}

  static from(buffer: ArrayBuffer): Previews | null {
    const bif = parseBif(buffer);
    return bif ? new Previews(bif) : null;
  }

  /** Which frame shows [seconds]. */
  frameAt(seconds: number): number {
    return frameAt(this.bif, seconds);
  }

  /** Frame [index], decoded. */
  frame(index: number): Promise<ImageBitmap | null> {
    let frame = this.decoded.get(index);
    if (frame) {
      // the latest used goes last, so the oldest is the first to go
      this.decoded.delete(index);
    } else {
      const { buffer, offsets } = this.bif;
      const jpeg = new Blob([new Uint8Array(buffer, offsets[index], offsets[index + 1] - offsets[index])], { type: 'image/jpeg' });
      frame = createImageBitmap(jpeg).catch(() => null);
    }
    this.decoded.set(index, frame);
    if (this.decoded.size > KEEP) {
      const [oldest, gone] = this.decoded.entries().next().value!;
      this.decoded.delete(oldest);
      gone.then((b) => b?.close());
    }
    return frame;
  }

  dispose() {
    for (const frame of this.decoded.values()) frame.then((b) => b?.close());
    this.decoded.clear();
  }
}
