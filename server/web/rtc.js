const linkOptions = location.hash.slice(1).split('&');
const lightTheme = linkOptions.includes('theme=light');
const directOnly = linkOptions.includes('ice=direct');
if (lightTheme) {
  document.documentElement.classList.add('light-theme');
  document.querySelector('meta[name="theme-color"]').content = '#f3f7f7';
}
const video = document.querySelector('#video');
const speaker = document.querySelector('#speaker');
const audioButton = document.querySelector('#audio');
const micButton = document.querySelector('#mic');
const headlightButton = document.querySelector('#headlight');
const audioStatus = document.querySelector('#audio-status');
const status = document.querySelector('#connection');
const placeholder = document.querySelector('#placeholder');
const fullscreenShell = document.querySelector('#fullscreen-shell');
const driveView = document.querySelector('#drive-view');
const fullscreenButton = document.querySelector('#fullscreen');
const orientationMenu = document.querySelector('#video-orientation');
const qualityMenu = document.querySelector('#video-quality');
const qualityLabel = document.querySelector('#quality-label');
const qualityButtons = [...qualityMenu.querySelectorAll('button[data-quality]')];
const videoMenus = [orientationMenu, qualityMenu];
const flipControls = [
  { button: document.querySelector('#flip-horizontal'), attribute: 'data-flip-x', bit: 1 },
  { button: document.querySelector('#flip-vertical'), attribute: 'data-flip-y', bit: 2 },
];
const steering = document.querySelector('#steering');
const steeringOffline = document.querySelector('#steering-offline');
const steeringValue = document.querySelector('#steering-value');
const throttle = document.querySelector('#throttle');
const throttleValue = document.querySelector('#throttle-value');
const throttleMode = document.querySelector('#throttle-mode');
const portrait = matchMedia('(orientation: portrait)');
const STEERING_DEAD_ZONE = 0.06;
const STEERING_UPDATE_MS = 40;
const STEERING_HEARTBEAT_MS = 80;
const DIRECT_OFFER_TIMEOUT_MS = 6500;
const DIRECT_ICE_TIMEOUT_MS = 5500;
const DIRECT_HANDSHAKE_TIMEOUT_MS = 9500;

let signal, call, micStream, retry, stopped = false;
let wantsAudio = false;
let hostVisible = true;
let gatherAllOnRetry = false;

const ORIENTATION_KEY = 'p4-car-video-orientation-v1';
const QUALITY_KEY = 'p4-car-video-quality-v1';
let orientation = 2;
let preferredQuality = 'hd';
try {
  const stored = localStorage.getItem(ORIENTATION_KEY);
  if (/^[0-3]$/.test(stored)) orientation = Number(stored);
  if (localStorage.getItem(QUALITY_KEY) === 'sd') preferredQuality = 'sd';
} catch {
  // Orientation still works in this session when WebView storage is unavailable.
}
function applyOrientation() {
  for (const { button, attribute, bit } of flipControls) {
    const flipped = Boolean(orientation & bit);
    video.toggleAttribute(attribute, flipped);
    button.setAttribute('aria-pressed', String(flipped));
  }
}
for (const { button, bit } of flipControls) {
  button.addEventListener('click', () => {
    orientation ^= bit;
    applyOrientation();
    try {
      localStorage.setItem(ORIENTATION_KEY, String(orientation));
    } catch {}
  });
}
applyOrientation();
document.addEventListener('pointerdown', event => {
  for (const menu of videoMenus) {
    if (menu.open && !menu.contains(event.target)) menu.open = false;
  }
});

function renderQuality(quality = preferredQuality, standardSupported = true) {
  qualityLabel.textContent = quality === 'sd' ? '标清' : '高清';
  for (const button of qualityButtons) {
    button.setAttribute('aria-pressed', String(button.dataset.quality === quality));
    button.disabled = !standardSupported && button.dataset.quality === 'sd';
  }
}

function changeQuality(quality) {
  qualityMenu.open = false;
  if (quality === preferredQuality && (!call?.quality || quality === call.quality)) return;
  preferredQuality = quality;
  try {
    localStorage.setItem(QUALITY_KEY, quality);
  } catch {}
  renderQuality();
  if (!call) return;
  const active = call;
  closeCall();
  clearTimeout(retry);
  screen('正在切换清晰度', '正在等待新的画面…');
  send({ type: 'restart', id: active.id });
}

for (const button of qualityButtons) {
  button.addEventListener('click', () => changeQuality(button.dataset.quality));
}
renderQuality();

function sendSteering(position) {
  if (document.hidden && position !== 0) return false;
  if (!call?.steering || signal?.readyState !== WebSocket.OPEN || signal.bufferedAmount > 1024) return false;
  send({ type: 'steer', id: call.id, position });
  return true;
}

function sendThrottle(position) {
  const reverse = Math.min(0, position);
  if ((!hostVisible || document.hidden) && reverse !== 0) return false;
  if (!call?.connected || !call.lights || signal?.readyState !== WebSocket.OPEN ||
      (reverse !== 0 && signal.bufferedAmount > 1024)) return false;
  send({ type: 'throttle', id: call.id, position: reverse });
  return true;
}

function createJoystick(element, { vertical = false, sendPosition, readout }) {
  const knob = element.querySelector('.steering-knob');
  const [negativeKey, positiveKey] = vertical ? ['ArrowDown', 'ArrowUp'] : ['ArrowLeft', 'ArrowRight'];
  const [negativeLabel, positiveLabel, neutralLabel] = vertical ? ['后退', '前进', '停止'] : ['向左', '向右', '回中'];
  let position = 0;
  let pointer, key, interval;
  let sentPosition, sentAt;

  function travel() {
    return vertical ? (element.clientHeight - knob.offsetHeight) / 2 :
                      (element.clientWidth - knob.offsetWidth) / 2;
  }

  function draw(value) {
    position = value;
    element.style.setProperty('--stick-offset', `${Math.max(0, travel()) * position / 100}px`);
    const label = position === 0 ? neutralLabel : `${position < 0 ? negativeLabel : positiveLabel} ${Math.abs(position)}%`;
    element.setAttribute('aria-valuenow', position);
    element.setAttribute('aria-valuetext', label);
    if (readout) readout.textContent = label;
  }

  function updateFromPointer(event) {
    const halfTravel = travel();
    if (halfTravel <= 0) return;
    const bounds = element.getBoundingClientRect();
    const x = event.clientX - bounds.left - bounds.width / 2;
    const y = event.clientY - bounds.top - bounds.height / 2;
    const rotated = driveView.hasAttribute('data-rotated');
    const delta = vertical ? (rotated ? x : -y) : (rotated ? y : x);
    const displacement = Math.min(1, Math.abs(delta) / halfTravel);
    const deadZone = sendPosition ? STEERING_DEAD_ZONE : 0;
    const travelFraction = Math.max(0, (displacement - deadZone) / (1 - deadZone));
    draw(Math.round(Math.sign(delta) * travelFraction * 100));
  }

  function updateCommand() {
    const now = performance.now();
    if (position === sentPosition && (position === 0 || now - sentAt < STEERING_HEARTBEAT_MS)) return;
    if (!sendPosition(position)) return;
    sentPosition = position;
    sentAt = now;
  }

  function start() {
    if (!sendPosition) return;
    updateCommand();
    interval ??= setInterval(updateCommand, STEERING_UPDATE_MS);
  }

  function release() {
    const releasedPointer = pointer;
    const held = releasedPointer !== undefined || key !== undefined;
    pointer = undefined;
    key = undefined;
    clearInterval(interval);
    interval = undefined;
    sentPosition = undefined;
    if (releasedPointer !== undefined && element.hasPointerCapture(releasedPointer)) {
      element.releasePointerCapture(releasedPointer);
    }
    draw(0);
    if (held) sendPosition?.(0);
  }

  element.addEventListener('pointerdown', event => {
    if (element.getAttribute('aria-disabled') === 'true' || event.button !== 0 ||
        pointer !== undefined || key !== undefined) return;
    element.setPointerCapture(event.pointerId);
    pointer = event.pointerId;
    updateFromPointer(event);
    start();
  });
  element.addEventListener('pointermove', event => {
    if (pointer === event.pointerId) updateFromPointer(event);
  });
  for (const type of ['pointerup', 'pointercancel', 'lostpointercapture']) {
    element.addEventListener(type, event => {
      if (pointer === event.pointerId) release();
    });
  }
  element.addEventListener('keydown', event => {
    if (element.getAttribute('aria-disabled') === 'true' || pointer !== undefined ||
        (event.key !== negativeKey && event.key !== positiveKey)) return;
    event.preventDefault();
    if (key === event.key) return;
    key = event.key;
    draw(key === negativeKey ? -100 : 100);
    start();
  });
  element.addEventListener('keyup', event => {
    if (key === event.key) release();
  });
  element.addEventListener('blur', () => {
    if (key !== undefined) release();
  });
  new ResizeObserver(() => draw(position)).observe(element);
  return release;
}

const releaseSteering = createJoystick(steering, { sendPosition: sendSteering, readout: steeringValue });
const releaseThrottle = createJoystick(throttle, { vertical: true, sendPosition: sendThrottle, readout: throttleValue });

function releaseControls() {
  releaseSteering();
  releaseThrottle();
}

function enableSteering(enabled) {
  if (!enabled) releaseSteering();
  steering.setAttribute('aria-disabled', String(!enabled));
  steeringOffline.hidden = enabled;
  steeringValue.hidden = !enabled;
}

function renderLights() {
  const ready = Boolean(call?.connected && call.lights);
  const on = ready && call.headlight;
  headlightButton.disabled = !ready || call.headlightTimeout !== undefined;
  headlightButton.setAttribute('aria-pressed', String(Boolean(on)));
  headlightButton.setAttribute('aria-label', on ? '关闭前大灯' : '开启前大灯');
  throttleMode.textContent = ready ? '倒车灯联动' : '仅预览';
}

headlightButton.addEventListener('click', () => {
  const active = call;
  if (headlightButton.disabled || !active || signal?.readyState !== WebSocket.OPEN) return;
  send({ type: 'headlight', id: active.id, on: !active.headlight });
  active.headlightTimeout = setTimeout(() => {
    active.headlightTimeout = undefined;
    if (call === active) renderLights();
  }, 2000);
  renderLights();
});

addEventListener('blur', releaseControls);
function updatePageVisibility() {
  const active = !document.hidden && hostVisible;
  if (!active) releaseControls();
  for (const track of micStream?.getAudioTracks() ?? []) track.enabled = active;
  if (active) resumePlayback();
}

document.addEventListener('visibilitychange', updatePageVisibility);
addEventListener('p4-app-visibility', ({ detail }) => {
  hostVisible = detail === true;
  updatePageVisibility();
});

function resumePlayback() {
  if (video.srcObject && video.paused) video.play().catch(() => {});
  if (wantsAudio && speaker.srcObject && speaker.paused) speaker.play().catch(() => {});
}

function updateFullscreenLayout() {
  const rotated = driveView.hasAttribute('data-expanded') && portrait.matches;
  if (rotated !== driveView.hasAttribute('data-rotated')) releaseControls();
  driveView.toggleAttribute('data-rotated', rotated);
}

function setExpandedVideo(expanded) {
  for (const menu of videoMenus) menu.open = false;
  driveView.toggleAttribute('data-expanded', expanded);
  document.body.classList.toggle('video-expanded', expanded);
  updateFullscreenLayout();
  toggle(fullscreenButton, expanded, '退出全屏', '全屏');
}

fullscreenButton.addEventListener('click', async () => {
  releaseControls();
  if (driveView.hasAttribute('data-expanded')) {
    setExpandedVideo(false);
    if (document.fullscreenElement === fullscreenShell) {
      await document.exitFullscreen().catch(() => {});
    }
    return;
  }

  setExpandedVideo(true);
  if (document.fullscreenEnabled && fullscreenShell.requestFullscreen) {
    try {
      await fullscreenShell.requestFullscreen();
      if (document.fullscreenElement === fullscreenShell) await window.screen.orientation?.lock?.('landscape');
    } catch {
      // The rotated viewport works when WebView rejects fullscreen or orientation locking.
    }
  }
});
document.addEventListener('fullscreenchange', () => {
  releaseControls();
  if (document.fullscreenElement === fullscreenShell) {
    setExpandedVideo(true);
  } else {
    setExpandedVideo(false);
    try {
      window.screen.orientation?.unlock?.();
    } catch {
      // Some WebViews reject orientation calls outside native fullscreen.
    }
  }
});
if (portrait.addEventListener) portrait.addEventListener('change', updateFullscreenLayout);
else portrait.addListener?.(updateFullscreenLayout);
document.addEventListener('keydown', event => {
  const openMenu = videoMenus.find(menu => menu.open);
  if (event.key === 'Escape' && openMenu) {
    openMenu.open = false;
    openMenu.querySelector('summary').focus();
    return;
  }
  if (event.key === 'Escape' && !document.fullscreenElement && driveView.hasAttribute('data-expanded')) {
    releaseControls();
    setExpandedVideo(false);
  }
});

function screen(label, detail = '') {
  status.textContent = label;
  status.toggleAttribute('data-live', !detail);
  placeholder.textContent = detail;
  placeholder.hidden = !detail;
  video.hidden = Boolean(detail);
}

function toggle(button, enabled, on, off) {
  button.setAttribute('aria-pressed', enabled);
  button.querySelector('.button-label').textContent = enabled ? on : off;
}

function showAudioStatus(message = '') {
  audioStatus.textContent = message;
  audioStatus.hidden = !message;
}

function stopMic() {
  micStream?.getTracks().forEach(track => track.stop());
  micStream = undefined;
  toggle(micButton, false, '关闭麦克风', '开启麦克风');
}

function closeCall() {
  enableSteering(false);
  releaseThrottle();
  if (call) {
    const previous = call;
    call = undefined;
    clearTimeout(previous.timeout);
    clearTimeout(previous.headlightTimeout);
    previous.cancelIceWait?.();
    previous.peer.close();
  }
  stopMic();
  showAudioStatus();
  video.srcObject = null;
  speaker.srcObject = null;
  audioButton.disabled = true;
  micButton.disabled = true;
  renderLights();
}

function send(message) {
  if (signal?.readyState === WebSocket.OPEN) signal.send(JSON.stringify(message));
}

function restart(active) {
  if (call !== active) return;
  if (!active.direct && active.usedEarlyAnswer && !active.connected) gatherAllOnRetry = true;
  closeCall();
  if (active.direct) {
    screen('正在重新连接', '直连暂未建立，稍后重试。');
  } else {
    screen('正在重新连接', '正在重新建立视频和语音连接…');
  }
  clearTimeout(retry);
  const delay = active.direct ? (directOnly ? 3500 : 150) : 1200;
  retry = setTimeout(() => send({ type: 'restart', id: active.id }), delay);
}

function waitForDirectPath(active) {
  const { peer } = active;
  const iceConnected = () => ['connected', 'completed'].includes(peer.iceConnectionState);
  clearTimeout(active.timeout);
  const waitForHandshake = () => setTimeout(() => restart(active), DIRECT_HANDSHAKE_TIMEOUT_MS);
  if (peer.connectionState === 'connected') return;
  active.timeout = iceConnected() ? waitForHandshake() : setTimeout(() => {
    if (iceConnected()) active.timeout = waitForHandshake();
    else restart(active);
  }, DIRECT_ICE_TIMEOUT_MS);
}

function waitForIce(active, allowEarlyAnswer) {
  const { peer } = active;
  return new Promise(resolve => {
    let graceTimer;
    const deadline = setTimeout(() => done(false), 6500);
    function done(early) {
      peer.removeEventListener('icegatheringstatechange', check);
      peer.removeEventListener('icecandidate', check);
      clearTimeout(graceTimer);
      clearTimeout(deadline);
      active.cancelIceWait = undefined;
      resolve(early);
    }
    function check() {
      if (peer.iceGatheringState === 'complete') return done(false);
      const sdp = peer.localDescription?.sdp ?? '';
      const candidateReady = active.direct ? /^a=candidate:[^\r\n]+ typ srflx(?: |\r?$)/m :
                                             /^a=candidate:[^\r\n]+ typ relay(?: |\r?$)/m;
      if (allowEarlyAnswer && graceTimer === undefined && candidateReady.test(sdp)) {
        // Give candidates that are being gathered concurrently time to enter the SDP.
        graceTimer = setTimeout(() => done(peer.iceGatheringState !== 'complete'), 180);
      }
    }
    active.cancelIceWait = () => done(false);
    peer.addEventListener('icegatheringstatechange', check);
    peer.addEventListener('icecandidate', check);
    check();
  });
}

function begin({ id, iceServers, stunServers }) {
  closeCall();
  renderQuality();
  const hasStun = Array.isArray(stunServers) && stunServers.length > 0;
  if (directOnly && !hasStun) {
    screen('无法开启仅直连', '请更新服务器版本，启用仅直连模式。');
    return;
  }
  const direct = directOnly || !iceServers?.length;
  const peer = new RTCPeerConnection({
    iceServers: direct ? stunServers : [...(stunServers ?? []), ...iceServers],
    bundlePolicy: 'max-bundle',
    iceTransportPolicy: 'all',
  });
  const active = {
    id, peer, direct, candidates: [],
    timeout: setTimeout(() => restart(active), direct ? DIRECT_OFFER_TIMEOUT_MS : 18000),
  };
  call = active;
  screen(direct ? '正在尝试直连' : '正在协商连接路径', '正在建立视频和语音连接…');

  peer.ontrack = ({ track, receiver }) => {
    if (call !== active) return;
    if (track.kind === 'video') {
      video.hidden = false;
      video.srcObject = new MediaStream([track]);
      video.play().catch(() => {});
    } else if (track.kind === 'audio') {
      if ('jitterBufferTarget' in receiver) receiver.jitterBufferTarget = 70;
      speaker.srcObject = new MediaStream([track]);
      speaker.muted = !wantsAudio;
      if (wantsAudio) speaker.play().catch(() => {
        showAudioStatus('点击声音按钮恢复播放。');
      });
    }
  };
  peer.onconnectionstatechange = () => {
    if (call !== active) return;
    if (peer.connectionState === 'connected') {
      clearTimeout(active.timeout);
      active.connected = true;
      gatherAllOnRetry = false;
      audioButton.disabled = false;
      micButton.disabled = !active.micSender;
      enableSteering(Boolean(active.steering));
      renderLights();
      if (video.hidden) screen('已连接', '正在等待摄像头画面…');
    } else if (peer.connectionState === 'failed') restart(active);
    else if (peer.connectionState === 'disconnected') {
      enableSteering(false);
      releaseThrottle();
      active.connected = false;
      renderLights();
      clearTimeout(active.timeout);
      active.timeout = setTimeout(() => restart(active), 4500);
    }
  };
  send({ type: 'start', id, mode: direct ? 'direct' : 'relay', quality: preferredQuality });
}

async function answer(active, sdp) {
  const { peer } = active;
  await peer.setRemoteDescription({ type: 'offer', sdp });
  for (const candidate of active.candidates.splice(0)) await peer.addIceCandidate(candidate).catch(() => {});
  const audio = peer.getTransceivers().find(transceiver => transceiver.receiver.track.kind === 'audio');
  if (audio) {
    audio.direction = 'sendrecv';
    active.micSender = audio.sender;
  }
  await peer.setLocalDescription(await peer.createAnswer());
  if (call !== active) return;
  active.usedEarlyAnswer = await waitForIce(active, active.direct || !gatherAllOnRetry);
  if (call !== active) return;
  send({ type: 'answer', id: active.id, sdp: peer.localDescription.sdp });
  if (active.direct) waitForDirectPath(active);
}

function connect() {
  if (stopped) return;
  const socket = new WebSocket(`wss://${location.host}/p4-intercom/rtc/browser`, 'p4-v1');
  signal = socket;
  socket.onmessage = async ({ data }) => {
    if (signal !== socket) return;
    const message = JSON.parse(data);
    if (message.type === 'offline') {
      closeCall();
      screen('开发板离线', '正在等待开发板上线。');
    } else if (message.type === 'ready') {
      clearTimeout(retry);
      begin(message);
    } else if (call && message.id === call.id) {
      const active = call;
      if (message.type === 'offer' && !active.answering) {
        active.answering = true;
        try {
          await answer(active, message.sdp);
        } catch {
          restart(active);
        }
      } else if (message.type === 'candidate') {
        const candidate = { candidate: message.candidate, sdpMLineIndex: 0 };
        if (active.peer.remoteDescription) active.peer.addIceCandidate(candidate).catch(() => {});
        else active.candidates.push(candidate);
      } else if (message.type === 'steering-ready') {
        active.steering = true;
        enableSteering(active.peer.connectionState === 'connected');
      } else if (message.type === 'lights-ready') {
        active.lights = true;
        renderLights();
      } else if (message.type === 'headlight-state' && active.lights) {
        active.headlight = message.on === true;
        clearTimeout(active.headlightTimeout);
        active.headlightTimeout = undefined;
        renderLights();
      } else if (message.type === 'video-quality' && ['sd', 'hd', 'hd-only'].includes(message.quality)) {
        active.quality = message.quality === 'sd' ? 'sd' : 'hd';
        active.standardSupported = message.quality !== 'hd-only';
        renderQuality(active.quality, active.standardSupported);
      } else if (message.type === 'failed') restart(active);
    }
  };
  socket.onclose = ({ code }) => {
    if (signal !== socket || stopped) return;
    closeCall();
    clearTimeout(retry);
    screen('等待连接', code === 4001 ? '对讲正在被另一位用户使用，稍后重试。' : '正在重新连接…');
    retry = setTimeout(connect, code === 4001 ? 3500 : 1800);
  };
}

video.onplaying = async () => {
  if (call && video.srcObject) {
    const active = call;
    if (!active.quality && video.videoWidth > 0) {
      renderQuality(video.videoWidth === 640 && video.videoHeight === 360 ? 'sd' : 'hd');
    }
    screen(active.direct ? '实时画面 · P2P' : '实时画面');
    if (active.direct) return;
    const stats = await active.peer.getStats().catch(() => null);
    if (!stats || call !== active || !video.srcObject) return;
    for (const transport of stats.values()) {
      if (transport.type !== 'transport') continue;
      const pair = stats.get(transport.selectedCandidatePairId);
      const local = stats.get(pair?.localCandidateId);
      const remote = stats.get(pair?.remoteCandidateId);
      if (!local?.candidateType || !remote?.candidateType) continue;
      screen(local.candidateType === 'relay' || remote.candidateType === 'relay' ? '实时画面 · TURN' : '实时画面 · P2P');
      break;
    }
  }
};

video.addEventListener('resize', () => {
  if (!call || !video.srcObject || !video.videoWidth ||
      (video.videoWidth === 640 && video.videoHeight === 360)) return;
  qualityMenu.querySelector('[data-quality="hd"]').textContent = `高清 ${video.videoWidth}×${video.videoHeight}`;
});

audioButton.onclick = async () => {
  const desired = !wantsAudio;
  speaker.muted = !desired;
  if (desired) {
    try {
      await speaker.play();
    } catch {
      speaker.muted = true;
      showAudioStatus('浏览器未能开始播放声音，请重试。');
      return;
    }
  }
  wantsAudio = desired;
  toggle(audioButton, desired, '关闭声音', '开启声音');
  showAudioStatus();
};

micButton.onclick = async () => {
  const active = call;
  if (!active?.micSender) return;
  showAudioStatus();
  micButton.disabled = true;
  let opened;
  try {
    if (micStream) {
      await active.micSender.replaceTrack(null);
      stopMic();
    } else {
      opened = await navigator.mediaDevices.getUserMedia({
        audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
      });
      if (call !== active) return;
      const [track] = opened.getAudioTracks();
      track.enabled = !document.hidden && hostVisible;
      await active.micSender.replaceTrack(track);
      if (call !== active) return;
      track.enabled = !document.hidden && hostVisible;
      micStream = opened;
      track.addEventListener('ended', () => {
        if (micStream !== opened) return;
        active.micSender.replaceTrack(null).catch(() => {});
        stopMic();
      }, { once: true });
      toggle(micButton, true, '关闭麦克风', '开启麦克风');
    }
  } catch {
    if (call === active) showAudioStatus('无法使用麦克风，请检查浏览器权限。');
  } finally {
    if (opened && opened !== micStream) opened.getTracks().forEach(track => track.stop());
    if (call === active) micButton.disabled = false;
  }
};

addEventListener('pagehide', ({ persisted }) => {
  releaseControls();
  if (persisted) return;
  stopped = true;
  setExpandedVideo(false);
  clearTimeout(retry);
  closeCall();
  signal?.close();
});
addEventListener('pageshow', ({ persisted }) => {
  if (persisted) resumePlayback();
});

if (!window.RTCPeerConnection) screen('浏览器不支持', '请用支持 WebRTC 的浏览器打开。');
else connect();
