import { createHmac, randomUUID, timingSafeEqual } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { WebSocket, WebSocketServer } from 'ws';

const base = '/p4-intercom/rtc/';
const { DEVICE_KEY, VIEWER_KEY, PUBLIC_ORIGIN, TURN_SECRET, TURN_HOST } = process.env;
if (![DEVICE_KEY, VIEWER_KEY, TURN_SECRET].every(key => /^[a-f0-9]{64}$/.test(key ?? '')) ||
    !PUBLIC_ORIGIN || !TURN_HOST) throw new Error('Missing WebRTC service configuration');

const assetList = [
  ['', './web/rtc-index.html', 'text/html; charset=utf-8'],
  ['rtc.js', './web/rtc.js', 'text/javascript; charset=utf-8'],
  ['style.css', './web/style.css', 'text/css; charset=utf-8'],
];
const assets = new Map(assetList.map(([route, file, type]) => [
  base + route, [readFileSync(new URL(file, import.meta.url)), type],
]));

const server = createServer((request, response) => {
  const asset = assets.get(request.url);
  if (!asset || !['GET', 'HEAD'].includes(request.method)) return response.writeHead(404).end();
  const [body, type] = asset;
  response.writeHead(200, {
    'Content-Type': type,
    'Content-Length': body.length,
    'Cache-Control': 'no-store',
    'Content-Security-Policy': "default-src 'self'; connect-src 'self' " +
      PUBLIC_ORIGIN.replace('https:', 'wss:') +
      "; media-src 'self' blob:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'",
    'Permissions-Policy': 'microphone=(self), camera=()',
    'Referrer-Policy': 'no-referrer',
    'X-Content-Type-Options': 'nosniff',
  }).end(request.method === 'HEAD' ? undefined : body);
});

const wss = new WebSocketServer({
  noServer: true, maxPayload: 16384, handleProtocols: protocols => protocols.has('p4-v1') && 'p4-v1',
});
let device, viewer, session;

function authorized(value, secret) {
  return typeof value === 'string' && /^[a-f0-9]{64}$/.test(value) &&
    timingSafeEqual(Buffer.from(value, 'hex'), Buffer.from(secret, 'hex'));
}

function send(socket, message) {
  if (socket?.readyState === WebSocket.OPEN && socket.bufferedAmount < 32768) socket.send(message);
}

function credentials(sessionId, who) {
  const username = `${Math.floor(Date.now() / 1000) + 3600}:${sessionId}-${who}`;
  return { username, credential: createHmac('sha1', TURN_SECRET).update(username).digest('base64') };
}

function reportAvailability() {
  if (!device) {
    session = undefined;
    send(viewer, JSON.stringify({ type: 'offline' }));
  } else if (viewer?.readyState === WebSocket.OPEN && !session) {
    const id = randomUUID();
    session = { id, started: false };
    send(viewer, JSON.stringify({
      type: 'ready', id,
      iceServers: [{
        urls: [`turn:${TURN_HOST}:3478?transport=udp`, `turn:${TURN_HOST}:3478?transport=tcp`],
        ...credentials(id, 'browser'),
      }],
    }));
  }
}

function fromViewer(ws, raw) {
  if (viewer !== ws || !session || !device) return;
  let message;
  try { message = JSON.parse(raw); } catch { return ws.close(1008); }
  if (message?.id !== session.id) return;
  if (message.type === 'start' && !session.started) {
    session.started = true;
    const { username, credential } = credentials(session.id, 'device');
    send(device, `start ${session.id}\nturn:${TURN_HOST}:3478\n${username}\n${credential}`);
  } else if (message.type === 'answer' && session.started && typeof message.sdp === 'string' &&
             message.sdp.startsWith('v=0') && message.sdp.length <= 13500) {
    send(device, `answer ${session.id}\n${message.sdp}`);
  } else if (message.type === 'steer' && session.steering) {
    const { position } = message;
    if (!Number.isInteger(position) || position < -100 || position > 100) return;
    const now = Date.now();
    if (position !== 0 && (now - (session.lastSteeringAt ?? 0) < 30 || device.bufferedAmount > 1024)) return;
    session.lastSteeringAt = now;
    send(device, `steer ${session.id}\n${position} ${now}`);
  } else if (message.type === 'restart') {
    send(device, `stop ${session.id}`);
    session = undefined;
    reportAvailability();
  }
}

function fromDevice(ws, message) {
  if (device !== ws || !session?.started) return;
  const split = message.indexOf('\n');
  const [type, id] = (split < 0 ? message : message.slice(0, split)).split(' ');
  if (id !== session.id) return;
  if (type === 'offer' && split > 0) {
    send(viewer, JSON.stringify({ type, id, sdp: message.slice(split + 1) }));
  } else if (type === 'candidate' && split > 0) {
    const candidate = message.slice(split + 1);
    if (candidate.startsWith('candidate:')) send(viewer, JSON.stringify({ type, id, candidate }));
  } else if (type === 'steering-ready') {
    session.steering = true;
    send(viewer, JSON.stringify({ type, id }));
  } else if (type === 'failed') {
    send(viewer, JSON.stringify({ type, id }));
  }
}

server.on('upgrade', (request, socket, head) => {
  const isDevice = request.url === base + 'device';
  if (!isDevice && request.url !== base + 'browser') {
    return socket.end('HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n');
  }
  const protocols = (request.headers['sec-websocket-protocol'] ?? '').split(/,\s*/);
  const allowed = isDevice
    ? authorized(request.headers.authorization?.replace(/^Bearer /, ''), DEVICE_KEY)
    : request.headers.origin === PUBLIC_ORIGIN && protocols[0] === 'p4-v1' && authorized(protocols[1], VIEWER_KEY);
  if (!allowed) return socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n');
  wss.handleUpgrade(request, socket, head, ws => {
    ws.alive = true;
    ws.on('error', () => ws.terminate());
    ws.on('pong', () => { ws.alive = true; });
    if (isDevice) {
      const old = device;
      device = ws;
      session = undefined;
      old?.close(1008);
    } else {
      if (viewer?.readyState === WebSocket.OPEN) return ws.close(4001, 'busy');
      viewer = ws;
    }
    ws.on('message', (data, binary) => {
      if (binary) return ws.close(1008);
      if (isDevice) fromDevice(ws, data.toString());
      else fromViewer(ws, data.toString());
    });
    ws.on('close', () => {
      if (isDevice && device === ws) {
        device = undefined;
        reportAvailability();
      } else if (!isDevice && viewer === ws) {
        viewer = undefined;
        if (session) send(device, `stop ${session.id}`);
        session = undefined;
      }
    });
    reportAvailability();
  });
});

setInterval(() => {
  for (const socket of wss.clients) {
    if (socket.alive) {
      socket.alive = false;
      socket.ping();
    } else socket.terminate();
  }
}, 12000).unref();

server.listen(18128, '127.0.0.1');
