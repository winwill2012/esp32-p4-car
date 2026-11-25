const viewerKey = location.hash.slice(1);
const video = document.querySelector('#video');
const speaker = document.querySelector('#speaker');
const audioButton = document.querySelector('#audio');
const micButton = document.querySelector('#mic');
const audioStatus = document.querySelector('#audio-status');
const status = document.querySelector('#connection');
const placeholder = document.querySelector('#placeholder');
const driveView = document.querySelector('#drive-view');
const fullscreenButton = document.querySelector('#fullscreen');
const steering = document.querySelector('#steering');
const steeringHint = document.querySelector('#steering-hint');
const throttle = document.querySelector('#throttle');
const throttleValue = document.querySelector('#throttle-value');
const validKey = /^[0-9a-f]{64}$/.test(viewerKey);

let signal, call, micStream, retry, stopped = false;
let wantsAudio = false;

function sendSteering(position) {
  if (!call?.steering || signal?.readyState !== WebSocket.OPEN || signal.bufferedAmount > 1024) return;
  send({ type: 'steer', id: call.id, position });
}

function createJoystick(element, { vertical = false, sendPosition, readout }) {
  const knob = element.querySelector('.steering-knob');
  const [negativeKey, positiveKey] = vertical ? ['ArrowDown', 'ArrowUp'] : ['ArrowLeft', 'ArrowRight'];
  const [negativeLabel, positiveLabel, neutralLabel] = vertical ? ['后退', '前进', '停止'] : ['向左', '向右', '回中'];
  let position = 0;
  let pointer, key, interval;

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
    const delta = vertical ? bounds.top + bounds.height / 2 - event.clientY :
                            event.clientX - bounds.left - bounds.width / 2;
    draw(Math.round(Math.max(-1, Math.min(1, delta / halfTravel)) * 100));
  }

  function start() {
    if (!sendPosition) return;
    sendPosition(position);
    interval ??= setInterval(() => sendPosition(position), 80);
  }

  function release() {
    const releasedPointer = pointer;
    const held = releasedPointer !== undefined || key !== undefined;
    pointer = undefined;
    key = undefined;
    clearInterval(interval);
    interval = undefined;
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

const releaseSteering = createJoystick(steering, { sendPosition: sendSteering });
const releaseThrottle = createJoystick(throttle, { vertical: true, readout: throttleValue });

function releaseControls() {
  releaseSteering();
  releaseThrottle();
}

function enableSteering(enabled) {
  if (!enabled) releaseSteering();
  steering.setAttribute('aria-disabled', String(!enabled));
  steeringHint.textContent = enabled ? '左右拖动，松手回中' : '等待转向连接';
}

addEventListener('blur', releaseControls);
document.addEventListener('visibilitychange', () => {
  if (document.hidden) releaseControls();
});

function updateFullscreenButton() {
  const expanded = document.fullscreenElement === driveView || driveView.hasAttribute('data-expanded');
  fullscreenButton.textContent = expanded ? '退出全屏' : '全屏';
}

function setExpandedVideo(expanded) {
  driveView.toggleAttribute('data-expanded', expanded);
  document.body.classList.toggle('video-expanded', expanded);
  updateFullscreenButton();
}

fullscreenButton.addEventListener('click', async () => {
  releaseControls();
  if (document.fullscreenElement === driveView) {
    await document.exitFullscreen();
  } else if (driveView.hasAttribute('data-expanded')) {
    setExpandedVideo(false);
  } else {
    try {
      if (document.fullscreenEnabled && driveView.requestFullscreen) {
        await driveView.requestFullscreen();
        return;
      }
    } catch {
      // Embedded and mobile browsers can deny fullscreen for a container.
    }
    setExpandedVideo(true);
  }
});
document.addEventListener('fullscreenchange', () => {
  releaseControls();
  updateFullscreenButton();
});
document.addEventListener('keydown', event => {
  if (event.key === 'Escape' && driveView.hasAttribute('data-expanded')) {
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
  button.textContent = enabled ? on : off;
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
    previous.peer.close();
  }
  stopMic();
  video.srcObject = null;
  speaker.srcObject = null;
  audioButton.disabled = true;
  micButton.disabled = true;
}

function send(message) {
  if (signal?.readyState === WebSocket.OPEN) signal.send(JSON.stringify(message));
}

function restart(active) {
  if (call !== active) return;
  closeCall();
  screen('正在重新连接', '正在重新建立视频和语音连接…');
  clearTimeout(retry);
  retry = setTimeout(() => send({ type: 'restart', id: active.id }), 1200);
}

function waitForIce(peer) {
  if (peer.iceGatheringState === 'complete') return Promise.resolve();
  return new Promise(resolve => {
    const done = () => {
      peer.removeEventListener('icegatheringstatechange', change);
      clearTimeout(timer);
      resolve();
    };
    const change = () => {
      if (peer.iceGatheringState === 'complete') done();
    };
    const timer = setTimeout(done, 6500);
    peer.addEventListener('icegatheringstatechange', change);
  });
}

function begin({ id, iceServers }) {
  closeCall();
  const peer = new RTCPeerConnection({ iceServers, bundlePolicy: 'max-bundle', iceTransportPolicy: 'relay' });
  const active = { id, peer, candidates: [], timeout: setTimeout(() => restart(active), 18000) };
  call = active;
  screen('正在连接开发板', '正在建立视频和语音连接…');

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
        audioStatus.textContent = '点击声音按钮恢复播放。';
      });
    }
  };
  peer.onconnectionstatechange = () => {
    if (call !== active) return;
    if (peer.connectionState === 'connected') {
      clearTimeout(active.timeout);
      audioButton.disabled = false;
      micButton.disabled = !active.micSender;
      enableSteering(Boolean(active.steering));
      if (video.hidden) screen('已连接', '正在等待摄像头画面…');
    } else if (peer.connectionState === 'failed') restart(active);
    else if (peer.connectionState === 'disconnected') {
      enableSteering(false);
      releaseThrottle();
      clearTimeout(active.timeout);
      active.timeout = setTimeout(() => restart(active), 4500);
    }
  };
  send({ type: 'start', id });
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
  await waitForIce(peer);
  if (call === active) send({ type: 'answer', id: active.id, sdp: peer.localDescription.sdp });
}

function connect() {
  if (stopped) return;
  const socket = new WebSocket(`wss://${location.host}/p4-intercom/rtc/browser`, ['p4-v1', viewerKey]);
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

video.onplaying = () => {
  if (call && video.srcObject) screen('实时画面');
};

audioButton.onclick = async () => {
  const desired = !wantsAudio;
  speaker.muted = !desired;
  if (desired) {
    try {
      await speaker.play();
    } catch {
      speaker.muted = true;
      audioStatus.textContent = '浏览器未能开始播放声音，请重试。';
      return;
    }
  }
  wantsAudio = desired;
  toggle(audioButton, desired, '关闭声音', '开启声音');
  audioStatus.textContent = '可分别开启声音和麦克风。';
};

micButton.onclick = async () => {
  const active = call;
  if (!active?.micSender) return;
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
      await active.micSender.replaceTrack(track);
      if (call !== active) return;
      micStream = opened;
      track.addEventListener('ended', () => {
        if (micStream !== opened) return;
        active.micSender.replaceTrack(null).catch(() => {});
        stopMic();
      }, { once: true });
      toggle(micButton, true, '关闭麦克风', '开启麦克风');
      audioStatus.textContent = '麦克风已开启。';
    }
  } catch {
    if (call === active) audioStatus.textContent = '无法使用麦克风，请检查浏览器权限。';
  } finally {
    if (opened && opened !== micStream) opened.getTracks().forEach(track => track.stop());
    if (call === active) micButton.disabled = false;
  }
};

addEventListener('pagehide', () => {
  stopped = true;
  setExpandedVideo(false);
  clearTimeout(retry);
  closeCall();
  signal?.close();
});
addEventListener('pageshow', ({ persisted }) => {
  if (persisted && validKey) {
    stopped = false;
    connect();
  }
});

if (!validKey) screen('需要访问链接', '请使用包含访问密钥的完整链接。');
else if (!window.RTCPeerConnection) screen('浏览器不支持', '请用支持 WebRTC 的浏览器打开。');
else connect();
