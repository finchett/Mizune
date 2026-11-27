// The app's motion (Motion.kt, and the player's own timings in PlayerScreen.kt), for the
// extension's parts to move as the app's do. Compose's tweens ease as FastOutSlowInEasing.
export const EASE = 'cubic-bezier(0.4, 0, 0.2, 1)';

/** FastOutSlowInEasing at [t] (0 to 1), for what's moved frame by frame. */
export function ease(t: number): number {
  if (t <= 0) return 0;
  if (t >= 1) return 1;
  // the curve's x(s) solved for t by bisection, then its y(s)
  const x1 = 0.4, y1 = 0, x2 = 0.2, y2 = 1;
  const at = (a: number, b: number, s: number) => 3 * a * s * (1 - s) ** 2 + 3 * b * s * s * (1 - s) + s ** 3;
  let lo = 0, hi = 1, s = t;
  for (let i = 0; i < 24; i++) {
    s = (lo + hi) / 2;
    if (at(x1, x2, s) < t) lo = s;
    else hi = s;
  }
  return at(y1, y2, s);
}

/** As the app's timings, in milliseconds. */
export const MOTION = {
  /** The study panel sliding out or away (expandHorizontally, tween(220)). */
  panel: 220,
  /** The line rising above the bar, or settling back (animateDpAsState, tween(300)). */
  lineLift: 300,
  /** A notice: how long it stays, and out (NOTICE_MS, fadeOut(tween(400))); it comes in over 120 (styles.ts). */
  noticeStays: 1500,
  noticeOut: 400,
};
