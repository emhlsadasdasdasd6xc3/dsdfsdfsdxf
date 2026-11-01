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
 */
public final class RelayClient {
	public record Member(UUID id, String name, double x, double y, double z, String dim, String server, long receivedAt) {
	}

	private static final HttpClient HTTP = HttpClient.newHttpClient();
	private static final Gson GSON = new Gson();
	private static final long RECONNECT_DELAY_MS = 5000;

	private final String url;
	private final String room;

	private volatile WebSocket socket;
	private volatile boolean open = false;
	private volatile boolean connecting = false;
	private volatile Map<UUID, Member> members = Map.of();
	private volatile CompletableFuture<?> lastSend = CompletableFuture.completedFuture(null);
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

	/** Call every tick while in a world; (re)connects if needed. */
	public void ensureConnected() {
		if (open || connecting) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now < nextAttemptMs) {
			return;
		}
		nextAttemptMs = now + RECONNECT_DELAY_MS;
		connecting = true;
		try {
			HTTP.newWebSocketBuilder()
					.connectTimeout(Duration.ofSeconds(5))
					.buildAsync(URI.create(url), new Listener())
					.whenComplete((ws, error) -> {
						if (error != null) {
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
			lastSend = ws.sendText(GSON.toJson(o), true);
		} catch (RuntimeException e) {
			TeamTrackerClient.LOGGER.debug("Relay send failed: {}", e.toString());
		}
	}

	public void close() {
		WebSocket ws = socket;
		open = false;
		connecting = false;
		socket = null;
		members = Map.of();
		nextAttemptMs = 0;
		if (ws != null) {
			try {
				ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
			} catch (RuntimeException ignored) {
			}
		}
	}

	private final class Listener implements WebSocket.Listener {
		private final StringBuilder buffer = new StringBuilder();

		@Override
		public void onOpen(WebSocket ws) {
			socket = ws;
			open = true;
			connecting = false;
			JsonObject join = new JsonObject();
			join.addProperty("type", "join");
			join.addProperty("room", room);
			lastSend = ws.sendText(GSON.toJson(join), true);
			ws.request(1);
			TeamTrackerClient.LOGGER.info("Connected to relay");
		}

		@Override
		public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
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
			markClosed();
			TeamTrackerClient.LOGGER.info("Relay closed ({} {})", statusCode, reason);
			return null;
		}

		@Override
		public void onError(WebSocket ws, Throwable error) {
			markClosed();
			TeamTrackerClient.LOGGER.debug("Relay error: {}", error.toString());
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
