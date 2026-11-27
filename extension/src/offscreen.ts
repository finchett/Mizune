// Chrome only: runs the extension's own engine in a worker (a service worker can't start one),
// and records the tab's audio while a line replays, with the video's part of the tab filmed for
// an animated picture where the page won't let its video be read. Capturing a tab takes its
// sound away from the speakers, so it's played back to them as well.
import { FrameCollector } from './animated-webp';
import { ANIMATION, blobBase64, TabVideo } from './shared';

new Worker('engine.js', { type: 'module' });

let recording: {
  stream: MediaStream; context: AudioContext; recorder: MediaRecorder; chunks: Blob[]; peak: () => number;
  /** The picture, while it's filmed: stopped, the animation. */
  film?: () => Promise<Blob | null>;
} | null = null;

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (message?.target !== 'offscreen') return false;
  const work = message.type === 'start' ? start(message.streamId, message.video) : stop();
  work.then(sendResponse, (e) => sendResponse({ type: 'error', message: String(e?.message ?? e) }));
  return true;
});

async function start(streamId: string, video?: TabVideo) {
  if (recording) await stop();
  const tab = { chromeMediaSource: 'tab', chromeMediaSourceId: streamId };
  // the tab at its full size, so the video's part of it is as sharp as it's shown
  const width = video && Math.round(video.viewportWidth * video.pixelRatio);
  const height = video && Math.round(video.viewportHeight * video.pixelRatio);
  const stream = await navigator.mediaDevices.getUserMedia({
    audio: { mandatory: tab } as any,
    video: video ? { mandatory: { ...tab, minWidth: width, maxWidth: width, minHeight: height, maxHeight: height, maxFrameRate: 30 } } as any : false,
  });
  const context = new AudioContext();
  const source = context.createMediaStreamSource(stream);
  source.connect(context.destination);
  const analyser = context.createAnalyser();
  analyser.fftSize = 2048;
  source.connect(analyser);
  const buffer = new Float32Array(analyser.fftSize);
  let peak = 0;
  const timer = setInterval(() => {
    analyser.getFloatTimeDomainData(buffer);
    for (const v of buffer) peak = Math.max(peak, Math.abs(v));
  }, 50);

  const recorder = new MediaRecorder(new MediaStream(stream.getAudioTracks()), { mimeType: 'audio/webm;codecs=opus' });
  const chunks: Blob[] = [];
  recorder.ondataavailable = (e) => chunks.push(e.data);
  recorder.start();
  recording = { stream, context, recorder, chunks, peak: () => (clearInterval(timer), peak) };
  if (video && stream.getVideoTracks().length) recording.film = await film(stream, video);
  return { type: 'recording' };
}

async function stop() {
  const current = recording;
  recording = null;
  if (!current) return { type: 'error', message: 'Nothing was being recorded' };
  await new Promise<void>((resolve) => {
    current.recorder.onstop = () => resolve();
    current.recorder.stop();
  });
  const picture = await current.film?.().catch(() => null);
  current.stream.getTracks().forEach((t) => t.stop());
  await current.context.close();
  const blob = new Blob(current.chunks, { type: current.recorder.mimeType });
  return {
    type: 'recorded',
    audio: await blobBase64(blob),
    audioType: current.recorder.mimeType,
    peak: current.peak(),
    ...(picture ? { image: await blobBase64(picture), imageType: picture.type } : {}),
  };
}

/**
 * Films [video]'s part of the tab's capture, a frame at a time as they come; what it answers
 * stops it and makes the animation.
 */
async function film(stream: MediaStream, video: TabVideo): Promise<() => Promise<Blob | null>> {
  const player = document.createElement('video');
  player.muted = true;
  player.srcObject = new MediaStream(stream.getVideoTracks());
  await player.play();
  const frames = new FrameCollector(ANIMATION.fps, video.height, ANIMATION.quality);
  const started = performance.now();
  let filming = true;
  const take = () => {
    if (!filming) return;
    const { videoWidth: width, videoHeight: height } = player;
    if (width && height) {
      // the capture is the page, fitted into the size asked for: where the video is in it
      const scale = Math.min(width / video.viewportWidth, height / video.viewportHeight);
      const left = (width - video.viewportWidth * scale) / 2, top = (height - video.viewportHeight * scale) / 2;
      const r = video.rect;
      const sx = left + r.x * scale, sy = top + r.y * scale, sw = r.width * scale, sh = r.height * scale;
      frames.add(performance.now() - started, sw, sh, (context, w, h) => context.drawImage(player, sx, sy, sw, sh, 0, 0, w, h));
    }
    player.requestVideoFrameCallback(take);
  };
  player.requestVideoFrameCallback(take);
  return async () => {
    filming = false;
    const end = performance.now() - started;
    player.pause();
    player.srcObject = null;
    return frames.finish(end);
  };
}
