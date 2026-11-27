// Recording the line for a card's audio. Chrome records the whole tab, from the background
// (background.ts and offscreen.ts). Firefox can't, so there the video element's own sound is
// recorded, which works for video without DRM: Netflix and Crunchyroll won't give it up.
import { api, blobBase64, isGecko, TabVideo, ToBackground } from '../shared';

export interface Recording {
  audio: string;
  audioType: string;
  peak: number;
  /** The tab's picture over the line, animated, when [startRecording] was asked for it. */
  image?: string;
  imageType?: string;
}

export interface Recorder {
  stop(): Promise<Recording>;
}

/** Why there's no recording, said on the card's notice. */
export class NoAudio extends Error {
  constructor(message: string, readonly needsClick = false) {
    super(message);
  }
}

const send = (message: ToBackground) => api.runtime.sendMessage(message);

/** Records the line's sound; with [tabVideo] (Chrome), the video's place in the tab is filmed too. */
export async function startRecording(video: HTMLVideoElement, tabVideo?: TabVideo): Promise<Recorder> {
  if (isGecko) return startElementRecording(video);
  const started: any = await send({ type: 'recordStart', video: tabVideo });
  if (started?.type === 'error') {
    if (started.message === 'needsClick') throw new NoAudio("click Mizune's toolbar button once on this tab so it can record", true);
    throw new NoAudio(started.message);
  }
  return {
    async stop() {
      const recorded: any = await send({ type: 'recordStop' });
      if (recorded?.type !== 'recorded') throw new NoAudio(recorded?.message ?? 'nothing was recorded');
      return recorded as Recording;
    },
  };
}

async function startElementRecording(video: HTMLVideoElement): Promise<Recorder> {
  if (video.mediaKeys) throw new NoAudio("Firefox can't record video with DRM");
  // the standard call leaves the sound playing; older Firefox only has its own, which takes it away
  const standard = typeof (video as any).captureStream === 'function';
  const capture = standard ? (video as any).captureStream : (video as any).mozCaptureStream;
  if (!capture) throw new NoAudio("this browser can't record the video");
  let stream: MediaStream;
  try {
    stream = capture.call(video);
  } catch {
    throw new NoAudio("the site doesn't let its video be recorded");
  }
  const tracks = stream.getAudioTracks();
  if (!tracks.length) throw new NoAudio('the video has no sound to record');
  const audio = new MediaStream(tracks);
  const context = new AudioContext();
  const source = context.createMediaStreamSource(audio);
  // mozCaptureStream takes the element's sound away from the speakers: play it back
  if (!standard) source.connect(context.destination);
  const analyser = context.createAnalyser();
  analyser.fftSize = 2048;
  source.connect(analyser);
  const buffer = new Float32Array(analyser.fftSize);
  let peak = 0;
  const timer = setInterval(() => {
    analyser.getFloatTimeDomainData(buffer);
    for (const v of buffer) peak = Math.max(peak, Math.abs(v));
  }, 50);
  const recorder = new MediaRecorder(audio);
  const chunks: Blob[] = [];
  recorder.ondataavailable = (e) => chunks.push(e.data);
  recorder.start();
  return {
    async stop() {
      await new Promise<void>((resolve) => {
        recorder.onstop = () => resolve();
        recorder.stop();
      });
      clearInterval(timer);
      stream.getTracks().forEach((t) => t.stop());
      await context.close();
      const blob = new Blob(chunks, { type: recorder.mimeType });
      return { audio: await blobBase64(blob), audioType: recorder.mimeType, peak };
    },
  };
}
