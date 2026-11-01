// Team Tracker relay: forwards each player's position to everyone else in the same room.
// Rooms are created on demand; the room name is the shared secret, so use a long random one.
const { WebSocketServer } = require('ws');

const PORT = Number(process.env.PORT) || 8080;
const MAX_ROOM_SIZE = 32;
const STALE_MS = 6000;      // drop a member who has not sent a position for this long
const BROADCAST_MS = 250;   // how often teammates are sent to each client
const MIN_MSG_GAP_MS = 50;  // ignore clients sending faster than this

const wss = new WebSocketServer({ port: PORT, maxPayload: 2048 });
/** room name -> Set of sockets */
const rooms = new Map();

const isNum = (v) => typeof v === 'number' && Number.isFinite(v);
const isStr = (v, max) => typeof v === 'string' && v.length > 0 && v.length <= max;

function leave(ws) {
  if (!ws.room) return;
  const set = rooms.get(ws.room);
  if (set) {
    set.delete(ws);
    if (set.size === 0) rooms.delete(ws.room);
  }
  ws.room = null;
}

wss.on('connection', (ws) => {
  ws.room = null;
  ws.member = null;
  ws.lastMsg = 0;

  ws.on('message', (raw) => {
    const now = Date.now();
    if (now - ws.lastMsg < MIN_MSG_GAP_MS) return;
    ws.lastMsg = now;

    let m;
    try { m = JSON.parse(raw.toString()); } catch { return; }
    if (!m || typeof m !== 'object') return;

    if (m.type === 'join') {
      if (ws.room || !isStr(m.room, 64) || m.room.length < 8) {
        ws.close(1008, 'bad room');
        return;
      }
      let set = rooms.get(m.room);
      if (!set) { set = new Set(); rooms.set(m.room, set); }
      if (set.size >= MAX_ROOM_SIZE) { ws.close(1013, 'room full'); return; }
      set.add(ws);
      ws.room = m.room;
    } else if (m.type === 'pos' && ws.room) {
      if (!isStr(m.id, 40) || !isStr(m.name, 32) || !isStr(m.dim, 64) || !isStr(m.server, 128)
          || !isNum(m.x) || !isNum(m.y) || !isNum(m.z)) return;
      ws.member = { id: m.id, name: m.name, x: m.x, y: m.y, z: m.z, dim: m.dim, server: m.server, t: now };
    }
  });

  ws.on('close', () => leave(ws));
  ws.on('error', () => leave(ws));
});

setInterval(() => {
  const now = Date.now();
  for (const set of rooms.values()) {
    const fresh = [];
    for (const ws of set) {
      if (ws.member && now - ws.member.t <= STALE_MS) fresh.push(ws);
    }
    for (const ws of set) {
      if (ws.readyState !== ws.OPEN) continue;
      const members = fresh
        .filter((other) => other !== ws)
        .map(({ member: { t, ...rest } }) => rest);
      ws.send(JSON.stringify({ type: 'team', members }));
    }
  }
}, BROADCAST_MS);

console.log(`Team Tracker relay listening on port ${PORT}`);
