import { createHmac, randomUUID, timingSafeEqual } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { WebSocket, WebSocketServer } from 'ws';

const base = '/p4-intercom/rtc/';
const { DEVICE_KEY, PUBLIC_ORIGIN, TURN_SECRET, TURN_HOST } = process.env;
const keyPattern = /^[a-f0-9]{64}$/;
const carIdPattern = /^[a-z0-9][a-z0-9_-]{0,31}$/;
if (![DEVICE_KEY, TURN_SECRET].every(key => keyPattern.test(key ?? '')) ||
    !PUBLIC_ORIGIN || !TURN_HOST) throw new Error('Missing WebRTC service configuration');

const cars = new Map([['default', { id: 'default', name: '小车 1', key: DEVICE_KEY }]]);
let extraCars;
try { extraCars = JSON.parse(process.env.P4_CARS || '[]'); }
catch { throw new Error('P4_CARS must be a valid JSON array'); }
if (!Array.isArray(extraCars)) throw new Error('P4_CARS must be a JSON array');
const carKeys = new Set([DEVICE_KEY]);
for (const entry of extraCars) {
  const { id, name, key } = entry ?? {};
  if (typeof id !== 'string' || !carIdPattern.test(id) || cars.has(id) ||
      typeof name !== 'string' || !name.trim() || name.trim().length > 24 ||
      typeof key !== 'string' || !keyPattern.test(key) || carKeys.has(key)) {
    throw new Error('P4_CARS requires unique ids and 64-digit hex keys, with names of up to 24 characters');
  }
  cars.set(id, { id, name: name.trim(), key });
  carKeys.add(key);
}

const assetList = [
  ['', './web/rtc-index.html', 'text/html; charset=utf-8'],
  ['rtc.js', './web/rtc.js', 'text/javascript; charset=utf-8'],
  ['style.css', './web/style.css', 'text/css; charset=utf-8'],
];
const assets = new Map(assetList.map(([route, file, type]) => [
  base + route, [readFileSync(new URL(file, import.meta.url)), type],
]));

const server = createServer((request, response) => {
  if (request.method === 'GET' && request.url === '/healthz') {
    return response.writeHead(200, { 'Content-Type': 'text/plain', 'Cache-Control': 'no-store' }).end('ok\n');
  }
  if (request.method === 'GET' && request.url === base + 'cars') {
    const list = [...cars.values()].map(({ id, name, device }) =>
      ({ id, name, online: device?.readyState === WebSocket.OPEN }));
    return response.writeHead(200, {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
    }).end(JSON.stringify({ cars: list }));
  }
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
function authorized(value, secret) {
  return typeof value === 'string' && keyPattern.test(value) &&
    timingSafeEqual(Buffer.from(value, 'hex'), Buffer.from(secret, 'hex'));
}

function send(socket, message) {
  if (socket?.readyState === WebSocket.OPEN && socket.bufferedAmount < 32768) socket.send(message);
}

function credentials(sessionId, who) {
  const username = `${Math.floor(Date.now() / 1000) + 3600}:${sessionId}-${who}`;
  return { username, credential: createHmac('sha1', TURN_SECRET).update(username).digest('base64') };
}

function reportAvailability(car) {
  const { device, viewer, session } = car;
  if (!device) {
    car.session = undefined;
    send(viewer, JSON.stringify({ type: 'offline' }));
  } else if (viewer?.readyState === WebSocket.OPEN && !session) {
    const id = randomUUID();
    car.session = { id, started: false };
    send(viewer, JSON.stringify({
      type: 'ready', id,
      stunServers: [{ urls: 'stun:' + TURN_HOST + ':3478' }],
      iceServers: [{
        urls: [`turn:${TURN_HOST}:3478?transport=udp`, `turn:${TURN_HOST}:3478?transport=tcp`],
        ...credentials(id, 'browser'),
      }],
    }));
  }
}

function fromViewer(car, ws, raw) {
  const { viewer, device, session } = car;
  if (viewer !== ws || !session || !device) return;
  let message;
  try { message = JSON.parse(raw); } catch { return ws.close(1008); }
  if (message?.id !== session.id) return;
  if (message.type === 'start' && !session.started) {
    session.started = true;
    session.direct = message.mode === 'direct';
    const quality = message.quality === 'sd' ? 'sd' : 'hd';
    if (session.direct) {
      // The board expects three ICE fields. STUN does not authenticate with TURN credentials.
      send(device, `start ${session.id}\nstun:${TURN_HOST}:3478\nstun\nstun\n${quality}`);
    } else {
      const { username, credential } = credentials(session.id, 'device');
      send(device, `start ${session.id}\nturn:${TURN_HOST}:3478\n${username}\n${credential}\n${quality}`);
    }
  } else if (message.type === 'answer' && session.started && typeof message.sdp === 'string' &&
             message.sdp.startsWith('v=0') && message.sdp.length <= 13500) {
    send(device, `answer ${session.id}\n${message.sdp}`);
  } else if ((message.type === 'steer' && session.steering) ||
             (message.type === 'throttle' && session.lights)) {
    const { position, type } = message;
    if (!Number.isInteger(position) || position < -100 || position > 100) return;
    const now = Date.now();
    const last = type === 'steer' ? 'lastSteeringAt' : 'lastThrottleAt';
    if (position !== 0 && (now - (session[last] ?? 0) < 30 || device.bufferedAmount > 1024)) return;
    session[last] = now;
    send(device, `${type} ${session.id}\n${position} ${now}`);
  } else if (message.type === 'headlight' && session.lights && typeof message.on === 'boolean') {
    send(device, `headlight ${session.id}\n${message.on ? 1 : 0}`);
  } else if (message.type === 'restart') {
    send(device, `stop ${session.id}`);
    car.session = undefined;
    reportAvailability(car);
  }
}

function fromDevice(car, ws, message) {
  const { device, viewer, session } = car;
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
  } else if (type === 'lights-ready') {
    session.lights = true;
    send(viewer, JSON.stringify({ type, id }));
  } else if (type === 'headlight-state' && session.lights && split > 0) {
    const value = message.slice(split + 1);
    if (value === '0' || value === '1') send(viewer, JSON.stringify({ type, id, on: value === '1' }));
  } else if (type === 'video-quality' && split > 0) {
    const quality = message.slice(split + 1);
    if (['sd', 'hd', 'hd-only'].includes(quality)) send(viewer, JSON.stringify({ type, id, quality }));
  } else if (type === 'failed') {
    send(viewer, JSON.stringify({ type, id }));
  }
}

server.on('upgrade', (request, socket, head) => {
  const isDevice = request.url === base + 'device';
  let target;
  try { target = new URL(request.url, 'http://localhost'); }
  catch { return socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n'); }
  if (!isDevice && target.pathname !== base + 'browser') {
    return socket.end('HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n');
  }
  const protocols = (request.headers['sec-websocket-protocol'] ?? '').split(/,\s*/);
  let car;
  if (isDevice) {
    const token = request.headers.authorization?.replace(/^Bearer /, '');
    car = [...cars.values()].find(entry => authorized(token, entry.key));
  } else if (request.headers.origin === PUBLIC_ORIGIN && protocols[0] === 'p4-v1') {
    car = cars.get(target.searchParams.get('car') ?? 'default');
  }
  if (!car) return socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n');
  wss.handleUpgrade(request, socket, head, ws => {
    ws.alive = true;
    ws.on('error', () => ws.terminate());
    ws.on('pong', () => { ws.alive = true; });
    if (isDevice) {
      const old = car.device;
      car.device = ws;
      car.session = undefined;
      old?.close(1008);
    } else {
      if (car.viewer?.readyState === WebSocket.OPEN) return ws.close(4001, 'busy');
      if (car.session) {
        send(car.device, `stop ${car.session.id}`);
        car.session = undefined;
      }
      car.viewer = ws;
    }
    ws.on('message', (data, binary) => {
      if (binary) return ws.close(1008);
      if (isDevice) fromDevice(car, ws, data.toString());
      else fromViewer(car, ws, data.toString());
    });
    ws.on('close', () => {
      if (isDevice && car.device === ws) {
        car.device = undefined;
        reportAvailability(car);
      } else if (!isDevice && car.viewer === ws) {
        car.viewer = undefined;
        if (car.session) send(car.device, `stop ${car.session.id}`);
        car.session = undefined;
      }
    });
    reportAvailability(car);
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

server.listen(18128, process.env.SIGNAL_BIND || '127.0.0.1');
