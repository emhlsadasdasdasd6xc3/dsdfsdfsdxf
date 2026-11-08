package com.teamtracker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Talks to the relay server over a WebSocket. Sends our own position and receives
 * everyone else's in the same room. Uses the JDK's built-in WebSocket client, so no extra libraries.
 *
 * Reliability: the relay broadcasts to every client 4 times a second, so if we hear nothing for
 * SILENCE_LIMIT_MS the connection is treated as dead and replaced. This matters because a dropped
 * Wi-Fi/router connection often never produces an error on our side.
 */
public final class RelayClient {
	public record Member(UUID id, String name, double x, double y, double z, String dim, String server, long receivedAt) {
	}

	private static final HttpClient HTTP = HttpClient.newHttpClient();
	private static final Gson GSON = new Gson();
	private static final long RECONNECT_DELAY_MS = 5000;
	/** The relay talks to us 4 times a second; this much silence means the connection is dead. */
	private static final long SILENCE_LIMIT_MS = 10_000;
	/** A single outgoing message stuck this long means the connection is dead. */
	private static final long SEND_STUCK_LIMIT_MS = 5_000;
	/** A connection attempt taking this long is abandoned. */
	private static final long CONNECT_LIMIT_MS = 15_000;

	private final String url;
	private final String room;

	private volatile WebSocket socket;
	private volatile boolean open = false;
	private volatile boolean connecting = false;
	private volatile Map<UUID, Member> members = Map.of();
	private volatile CompletableFuture<?> lastSend = CompletableFuture.completedFuture(null);
	private volatile long lastSendStartedAt = 0;
	private volatile long lastReceivedAt = 0;
	private volatile long connectingSince = 0;
	/** Bumped whenever a connection is dropped, so events from an old connection are ignored. */
	private volatile int generation = 0;
	private long nextAttemptMs = 0;

	public RelayClient(String url, String room) {
		this.url = url.trim();
		this.room = room;
	}

	public Map<UUID, Member> members() {
		return members;
	}

	public boolean isOpen() {
		return open;
	}

	/** Call every tick while in a world; reconnects if the connection is missing or has gone quiet. */
	public void ensureConnected() {
		long now = System.currentTimeMillis();

		if (open) {
			boolean silent = now - lastReceivedAt > SILENCE_LIMIT_MS;
			boolean sendStuck = !lastSend.isDone() && now - lastSendStartedAt > SEND_STUCK_LIMIT_MS;
			if (!silent && !sendStuck) {
				return;
			}
			TeamTrackerClient.LOGGER.info("Relay connection went quiet, reconnecting");
			dropConnection(false);
			nextAttemptMs = 0;
		} else if (connecting && now - connectingSince > CONNECT_LIMIT_MS) {
			TeamTrackerClient.LOGGER.info("Relay connection attempt timed out, retrying");
			dropConnection(false);
			nextAttemptMs = 0;
		}

		if (connecting || now < nextAttemptMs) {
			return;
		}
		nextAttemptMs = now + RECONNECT_DELAY_MS;
		connecting = true;
		connectingSince = now;
		int gen = ++generation;
		try {
			HTTP.newWebSocketBuilder()
					.connectTimeout(Duration.ofSeconds(5))
					.buildAsync(URI.create(url), new Listener(gen))
					.whenComplete((ws, error) -> {
						if (error != null && gen == generation) {
							connecting = false;
							TeamTrackerClient.LOGGER.debug("Relay connect failed: {}", error.toString());
						}
					});
		} catch (RuntimeException e) { // e.g. malformed URL
			connecting = false;
			TeamTrackerClient.LOGGER.warn("Bad relayUrl '{}': {}", url, e.toString());
		}
	}

	public void sendPosition(UUID id, String name, double x, double y, double z, String dim, String server) {
		WebSocket ws = socket;
		if (!open || ws == null || !lastSend.isDone()) {
			return; // previous send still in flight; skip this one
		}
		JsonObject o = new JsonObject();
		o.addProperty("type", "pos");
		o.addProperty("id", id.toString());
		o.addProperty("name", name);
		o.addProperty("x", x);
		o.addProperty("y", y);
		o.addProperty("z", z);
		o.addProperty("dim", dim);
		o.addProperty("server", server);
		try {
			lastSendStartedAt = System.currentTimeMillis();
			lastSend = ws.sendText(GSON.toJson(o), true);
		} catch (RuntimeException e) {
			TeamTrackerClient.LOGGER.debug("Relay send failed: {}", e.toString());
		}
	}

	/** Politely close (leaving the world, changing room). A new connection starts on the next tick. */
	public void close() {
		dropConnection(true);
		nextAttemptMs = 0;
	}

	private void dropConnection(boolean graceful) {
		WebSocket ws = socket;
		generation++;
		open = false;
		connecting = false;
		socket = null;
		members = Map.of();
		if (ws != null) {
			try {
				if (graceful) {
					ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
				} else {
					ws.abort();
				}
			} catch (RuntimeException ignored) {
			}
		}
	}

	private final class Listener implements WebSocket.Listener {
		private final int gen;
		private final StringBuilder buffer = new StringBuilder();

		Listener(int gen) {
			this.gen = gen;
		}

		@Override
		public void onOpen(WebSocket ws) {
			if (gen != generation) { // we gave up on this attempt already
				ws.abort();
				return;
			}
			socket = ws;
			open = true;
			connecting = false;
			lastReceivedAt = System.currentTimeMillis();
			JsonObject join = new JsonObject();
			join.addProperty("type", "join");
			join.addProperty("room", room);
			lastSendStartedAt = lastReceivedAt;
			lastSend = ws.sendText(GSON.toJson(join), true);
			ws.request(1);
			TeamTrackerClient.LOGGER.info("Connected to relay");
		}

		@Override
		public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
			if (gen != generation) {
				return null;
			}
			lastReceivedAt = System.currentTimeMillis();
			buffer.append(data);
			if (last) {
				String text = buffer.toString();
				buffer.setLength(0);
				handle(text);
			}
			ws.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
			if (gen == generation) {
				markClosed();
				TeamTrackerClient.LOGGER.info("Relay closed ({} {})", statusCode, reason);
			}
			return null;
		}

		@Override
		public void onError(WebSocket ws, Throwable error) {
			if (gen == generation) {
				markClosed();
				TeamTrackerClient.LOGGER.debug("Relay error: {}", error.toString());
			}
		}

		private void markClosed() {
			open = false;
			connecting = false;
			socket = null;
			members = Map.of();
		}

		private void handle(String text) {
			try {
				JsonObject root = JsonParser.parseString(text).getAsJsonObject();
				if (!"team".equals(root.get("type").getAsString())) {
					return;
				}
				long now = System.currentTimeMillis();
				JsonArray arr = root.getAsJsonArray("members");
				Map<UUID, Member> map = new HashMap<>();
				for (JsonElement el : arr) {
					JsonObject m = el.getAsJsonObject();
					UUID id = UUID.fromString(m.get("id").getAsString());
					map.put(id, new Member(
							id,
							m.get("name").getAsString(),
							m.get("x").getAsDouble(),
							m.get("y").getAsDouble(),
							m.get("z").getAsDouble(),
							m.get("dim").getAsString(),
							m.get("server").getAsString(),
							now));
				}
				members = map;
			} catch (RuntimeException e) {
				TeamTrackerClient.LOGGER.debug("Ignoring bad relay message: {}", e.toString());
			}
		}
	}
}
