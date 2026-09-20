/**
 * 재생 상태만 관리합니다. 화면을 그리는 일은 하지 않습니다.
 *
 * 지금은 실제 오디오 대신 requestAnimationFrame 으로 경과 시간을 누적해서
 * 재생을 흉내 냅니다. 나중에 <audio> 로 바꿀 때는 이 파일의 시계 부분
 * (start/stop/frame)만 audio 의 timeupdate·play·pause 로 갈아 끼우면 되고,
 * 밖으로 드러난 함수 이름과 상태 모양은 그대로 두면 됩니다.
 */

export const SPEEDS = [1, 1.25, 1.5, 2, 0.75];

/**
 * @param {{duration:number, time?:number, playing?:boolean, speed?:number}} options
 */
export function createPlayer({ duration, time = 0, playing = false, speed = 1 }) {
  const listeners = new Set();
  const state = {
    time: clamp(time, 0, duration),
    playing,
    speed,
    duration,
  };

  let frameId = null;
  let last = 0;

  function clamp(v, min, max) {
    return Math.min(max, Math.max(min, v));
  }

  function emit() {
    for (const fn of listeners) fn(state);
  }

  function frame(now) {
    const dt = (now - last) / 1000;
    last = now;
    state.time = clamp(state.time + dt * state.speed, 0, duration);
    if (state.time >= duration) {
      state.time = duration;
      stop();
      state.playing = false;
    }
    emit();
    if (state.playing) frameId = requestAnimationFrame(frame);
  }

  function start() {
    if (frameId !== null) return;
    last = performance.now();
    frameId = requestAnimationFrame(frame);
  }

  function stop() {
    if (frameId === null) return;
    cancelAnimationFrame(frameId);
    frameId = null;
  }

  const player = {
    /** 상태가 바뀔 때마다 부릅니다. 구독을 끊는 함수를 돌려줍니다. */
    subscribe(fn) {
      listeners.add(fn);
      fn(state);
      return () => listeners.delete(fn);
    },

    get state() {
      return state;
    },

    setPlaying(next) {
      if (state.playing === next) return;
      state.playing = next;
      if (next) start();
      else stop();
      emit();
    },

    play() { player.setPlaying(true); },
    pause() { player.setPlaying(false); },
    toggle() { player.setPlaying(!state.playing); },

    /** 특정 시각으로 옮깁니다. */
    seek(seconds) {
      state.time = clamp(seconds, 0, duration);
      emit();
    },

    /** 지금 위치에서 delta 초만큼 움직입니다. */
    nudge(delta) {
      player.seek(state.time + delta);
    },

    setSpeed(next) {
      state.speed = next;
      emit();
    },

    /** 1x → 1.25x → 1.5x → 2x → 0.75x 순서로 순환합니다. */
    cycleSpeed() {
      const at = SPEEDS.indexOf(state.speed);
      player.setSpeed(SPEEDS[(at + 1) % SPEEDS.length]);
    },

    destroy() {
      stop();
      listeners.clear();
    },
  };

  if (state.playing) start();
  return player;
}
