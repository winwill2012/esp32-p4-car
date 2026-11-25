import { timingSafeEqual } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { WebSocket, WebSocketServer } from 'ws';

const base = '/p4-intercom/';
const deviceKey = process.env.DEVICE_KEY;
const viewerKey = process.env.VIEWER_KEY;
const origin = process.env.PUBLIC_ORIGIN;
if (!/^[a-f0-9]{64}$/.test(deviceKey ?? '') || !/^[a-f0-9]{64}$/.test(viewerKey ?? '') || !origin) {
  throw new Error('Missing relay configuration');
}

const assets = new Map([
  ['', ['index.html', 'text/html; charset=utf-8']],
  ['app.js', ['app.js', 'text/javascript; charset=utf-8']],
  ['audio.js', ['audio.js', 'text/javascript; charset=utf-8']],
  ['style.css', ['style.css', 'text/css; charset=utf-8']],
].map(([path, [file, type]]) => [base + path, [readFileSync(new URL('./web/' + file, import.meta.url)), type]]));

const server = createServer((request, response) => {
  const asset = assets.get(request.url);
  if (!asset || !['GET', 'HEAD'].includes(request.method)) {
    response.writeHead(404).end();
    return;
  }
  const [body, type] = asset;
  response.writeHead(200, {
    'Content-Type': type,
    'Content-Length': body.length,
    'Cache-Control': 'no-store',
    'Content-Security-Policy': "default-src 'self'; connect-src 'self' " + origin.replace('https:', 'wss:') + "; img-src 'self' blob:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'",
    'Permissions-Policy': 'microphone=(self), camera=()',
    'Referrer-Policy': 'no-referrer',
    'X-Content-Type-Options': 'nosniff',
  }).end(request.method === 'HEAD' ? undefined : body);
});

const maxVideo = 512 * 1024;
const audioBytes = 640;
const browserVideo = new Set();
const devices = new Map();
let browserAudio;
let lastFrame = 0;
const socketServer = new WebSocketServer({
  noServer: true,
  maxPayload: maxVideo,
  perMessageDeflate: false,
  handleProtocols: protocols => protocols.has('p4-v1') && 'p4-v1',
});

function authorized(value, key) {
  return typeof value === 'string' && /^[a-f0-9]{64}$/.test(value) &&
    timingSafeEqual(Buffer.from(value, 'hex'), Buffer.from(key, 'hex'));
}

function send(socket, data, binary = false, limit = 4096) {
  if (socket?.readyState === WebSocket.OPEN && socket.bufferedAmount <= limit) {
    socket.send(data, { binary });
  }
}

function update(type) {
  if (type === 'video') {
    send(devices.get(type), browserVideo.size ? 'start' : 'stop');
    for (const viewer of browserVideo) send(viewer, devices.has(type) ? 'online' : 'offline');
  } else {
    send(devices.get(type), browserAudio ? 'start' : 'stop');
    send(browserAudio, devices.has(type) ? 'online' : 'offline');
  }
}

server.on('upgrade', (request, socket, head) => {
  const route = request.url.match(/^\/p4-intercom\/ws\/(device|browser)\/(video|audio)$/);
  if (!route) return socket.end('HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n');
  const [, role, type] = route;
  const protocols = (request.headers['sec-websocket-protocol'] ?? '').split(/,\s*/);
  const device = role === 'device';
  const allowed = device
    ? authorized(request.headers.authorization?.replace(/^Bearer /, ''), deviceKey)
    : request.headers.origin === origin && protocols[0] === 'p4-v1' && authorized(protocols[1], viewerKey);
  if (!allowed) return socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n');

  socketServer.handleUpgrade(request, socket, head, ws => {
    ws.on('error', () => ws.terminate());
    ws.alive = true;
    ws.on('pong', () => { ws.alive = true; });
    if (device) {
      const previous = devices.get(type);
      devices.set(type, ws);
      previous?.close(1008);
      if (type === 'video') lastFrame = 0;
    } else if (type === 'audio') {
      if (browserAudio) return ws.close(4001, 'busy');
      browserAudio = ws;
    } else {
      if (browserVideo.size >= 8) return ws.close(4001, 'busy');
      browserVideo.add(ws);
    }

    ws.on('close', () => {
      if (device) {
        if (devices.get(type) !== ws) return;
        devices.delete(type);
      } else if (type === 'audio') {
        if (browserAudio !== ws) return;
        browserAudio = undefined;
      } else {
        browserVideo.delete(ws);
      }
      update(type);
    });

    ws.on('message', (data, binary) => {
      if (type === 'audio' && binary && data.length === audioBytes) {
        if (device && devices.get(type) === ws) send(browserAudio, data, true);
        if (!device && browserAudio === ws) send(devices.get(type), data, true);
        return;
      }
      if (type === 'video' && device && devices.get(type) === ws && binary &&
          data.length >= 4 && data[0] === 0xff && data[1] === 0xd8 &&
          data.at(-2) === 0xff && data.at(-1) === 0xd9) {
        const now = Date.now();
        if (now - lastFrame >= 45) {
          lastFrame = now;
          for (const viewer of browserVideo) send(viewer, data, true, maxVideo);
        }
        return;
      }
      ws.close(1008, 'invalid media packet');
    });
    update(type);
  });
});

setInterval(() => {
  for (const ws of socketServer.clients) {
    if (!ws.alive) ws.terminate();
    else {
      ws.alive = false;
      ws.ping();
    }
  }
}, 12000).unref();

server.listen(18127, '127.0.0.1');
