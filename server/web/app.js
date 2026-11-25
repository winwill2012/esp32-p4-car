const key = location.hash.slice(1);
const status = document.querySelector('#connection');
const placeholder = document.querySelector('#placeholder');
const video = document.querySelector('#video');
const canvas = video.getContext('2d');
const audioButton = document.querySelector('#audio');
const micButton = document.querySelector('#mic');
const audioStatus = document.querySelector('#audio-status');
const validKey = /^[0-9a-f]{64}$/.test(key);
let videoSocket, videoTimer, frameTimer, audioSocket, audioTimer;
let context, bridge, stream, micInput;
let rendering = false;
let stopping = false;

function connect(type) {
  const endpoint = new URL('ws/browser/' + type, location.href);
  endpoint.protocol = 'wss:';
  const socket = new WebSocket(endpoint, ['p4-v1', key]);
  socket.binaryType = 'arraybuffer';
  return socket;
}

function videoStatus(label, detail) {
  status.textContent = label;
  status.toggleAttribute('data-live', !detail);
  placeholder.textContent = detail || '';
  placeholder.hidden = !detail;
  video.hidden = Boolean(detail);
}

function openVideo() {
  const socket = connect('video');
  videoSocket = socket;
  socket.onmessage = async ({ data }) => {
    if (data === 'offline') {
      clearTimeout(frameTimer);
      videoStatus('开发板离线', '正在等待开发板上线。');
    } else if (data === 'online') {
      if (video.hidden) videoStatus('开发板已连接', '正在等待摄像头画面…');
    } else if (data instanceof ArrayBuffer && !rendering) {
      rendering = true;
      const url = URL.createObjectURL(new Blob([data], { type: 'image/jpeg' }));
      try {
        const frame = new Image();
        frame.src = url;
        await frame.decode();
        if (socket !== videoSocket || socket.readyState !== WebSocket.OPEN) return;
        if (video.width !== frame.naturalWidth || video.height !== frame.naturalHeight) {
          video.width = frame.naturalWidth;
          video.height = frame.naturalHeight;
        }
        canvas.drawImage(frame, 0, 0);
        videoStatus('实时画面', '');
        clearTimeout(frameTimer);
        frameTimer = setTimeout(() => videoStatus('等待画面', '正在等待摄像头画面…'), 4500);
      } catch {
        // Skip damaged frames and keep receiving.
      } finally {
        URL.revokeObjectURL(url);
        rendering = false;
      }
    }
  };
  socket.onclose = ({ code }) => {
    clearTimeout(frameTimer);
    if (stopping) return;
    videoStatus('等待连接', code === 4001 ? '观看人数已满，正在重试。' : '正在重新连接…');
    videoTimer = setTimeout(openVideo, 2500);
  };
}

function openAudio() {
  const socket = connect('audio');
  audioSocket = socket;
  audioStatus.textContent = '正在连接语音…';
  socket.onmessage = ({ data }) => {
    if (socket !== audioSocket) return;
    if (typeof data === 'string') {
      audioStatus.textContent = data === 'online' ? '语音已连接。' : '正在等待开发板语音上线。';
    } else if (bridge) {
      bridge.port.postMessage({ pcm: data }, [data]);
    }
  };
  socket.onclose = ({ code }) => {
    if (socket !== audioSocket || stopping || !context) return;
    if (code === 4001) {
      closeAudio();
      audioStatus.textContent = '对讲正在被另一位用户使用。';
      return;
    }
    audioStatus.textContent = '正在重新连接语音…';
    audioTimer = setTimeout(openAudio, 2000);
  };
}

function setToggle(button, active, onText, offText) {
  button.setAttribute('aria-pressed', active);
  button.textContent = active ? onText : offText;
}

function closeMic() {
  micInput?.disconnect();
  micInput = undefined;
  stream?.getTracks().forEach(track => track.stop());
  stream = undefined;
  bridge?.port.postMessage({ capture: false });
  setToggle(micButton, false, '关闭麦克风', '开启麦克风');
}

function closeAudio() {
  closeMic();
  micButton.disabled = true;
  clearTimeout(audioTimer);
  const socket = audioSocket;
  audioSocket = undefined;
  socket?.close();
  bridge?.disconnect();
  bridge = undefined;
  context?.close().catch(() => {});
  context = undefined;
  setToggle(audioButton, false, '关闭声音', '开启声音');
  audioStatus.textContent = '开启声音后即可收听和对讲。';
}

audioButton.onclick = async () => {
  if (context) {
    closeAudio();
    return;
  }
  audioButton.disabled = true;
  try {
    context = new AudioContext({ latencyHint: 'interactive' });
    await Promise.all([context.resume(), context.audioWorklet.addModule('audio.js')]);
    bridge = new AudioWorkletNode(context, 'p4-intercom', { outputChannelCount: [1] });
    bridge.connect(context.destination);
    bridge.port.onmessage = ({ data }) => {
      if (audioSocket?.readyState === WebSocket.OPEN && audioSocket.bufferedAmount < 2560) {
        audioSocket.send(data);
      }
    };
    setToggle(audioButton, true, '关闭声音', '开启声音');
    micButton.disabled = false;
    openAudio();
  } catch {
    closeAudio();
    audioStatus.textContent = '浏览器未能启动声音。请重试。';
  } finally {
    audioButton.disabled = false;
  }
};

micButton.onclick = async () => {
  if (stream) {
    closeMic();
    return;
  }
  const activeContext = context;
  const activeBridge = bridge;
  micButton.disabled = true;
  try {
    const opened = await navigator.mediaDevices.getUserMedia({
      audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
    });
    if (activeContext !== context || activeBridge !== bridge) {
      opened.getTracks().forEach(track => track.stop());
      return;
    }
    stream = opened;
    stream.getAudioTracks()[0].addEventListener('ended', closeMic, { once: true });
    micInput = context.createMediaStreamSource(stream);
    micInput.connect(bridge);
    bridge.port.postMessage({ capture: true });
    setToggle(micButton, true, '关闭麦克风', '开启麦克风');
  } catch {
    if (activeContext === context) audioStatus.textContent = '无法使用麦克风，请检查浏览器权限。';
  } finally {
    micButton.disabled = !context;
  }
};

addEventListener('pagehide', () => {
  stopping = true;
  clearTimeout(videoTimer);
  clearTimeout(frameTimer);
  videoSocket?.close();
  closeAudio();
});
addEventListener('pageshow', ({ persisted }) => {
  if (persisted && validKey) {
    stopping = false;
    openVideo();
  }
});

if (validKey) openVideo();
else {
  videoStatus('需要访问链接', '请使用包含访问密钥的完整链接。');
  audioButton.disabled = true;
}
