# Team Tracker (Fabric, Minecraft 1.21.11)

Floating tags in the world showing each teammate's **name and distance**, for anyone more than
7 blocks away, including players far outside your render distance. Tags show through walls. Very far
teammates are drawn 24 blocks out in their direction so the tag stays readable.

It is a client-only mod. Because a normal Minecraft client is never told where far-away players are,
everyone's mod reports its position to a small **relay** server, and the relay hands each player the
positions of the others in the same room. This works on any server, including ones with no mods.

## 1. Get the jar from GitHub
1. Push this folder to a GitHub repo.
2. Open the repo's **Actions** tab. The `build` workflow runs on every push. Open the run and download the
   `team-tracker-jar` artifact (unzip it for the `.jar`).
3. Or create a release: `git tag v1.0.0 && git push --tags`. The jar is attached to the release automatically.

## 2. Run the relay (once, for the whole group)
The relay is `relay/server.js` (Node 18+). Host it anywhere that stays online, such as a VPS, Fly.io, Render,
Railway, or a home machine with a port forwarded.

```
cd relay
npm install
PORT=8080 npm start
```
A `Dockerfile` is included. Put it behind TLS if you can (most hosts give you `wss://` automatically).

## 3. Set up each player (all in game)
1. Install Fabric Loader 0.18.1+, Fabric API, and this jar in `mods/`.
2. In game, point the mod at your relay (once per player):
   `/tt relay wss://your-relay.example.com` (or `ws://IP:8080`)
3. One person makes a room: `/tt create`. It prints a code like `K7Q2-M9XD-4PLA`. Click it to copy and share it.
4. Everyone else joins: `/tt join K7Q2-M9XD-4PLA`

| Command | What it does |
| --- | --- |
| `/tt create` | Make a new random room and join it |
| `/tt join <code>` | Join a room (codes are not case sensitive) |
| `/tt leave` | Leave your room |
| `/tt code` | Show relay, room code and connection status |
| `/tt relay <url>` | Set the relay address (`ws://` or `wss://`) |

`/tt` is short for `/teamtracker`. Your relay address and room are saved in `config/teamtracker.json`,
so they persist between launches (you can also edit that file by hand). The room code is the password,
so only share it with people you trust. Players only see each other if they are on the same server and
in the same dimension.

## Notes
- The relay sees player names, UUIDs, coordinates, dimension and server address. Only share the room with people you trust.
- Constants: tag distance cap and update rate are at the top of `TeamTrackerClient.java`.
- If a tag looks mirrored or upside down, flip the sign in the `matrices.scale(...)` line.
