package com.teamtracker;

import java.security.SecureRandom;
import java.util.Locale;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

/**
 * Settings screen, opened from Mod Menu. Everything the old /tt commands did lives here:
 * relay address, create / join / leave a room, copy the code, and see the connection status.
 * The on/off switch is also a keybind (Options > Controls > Key Binds).
 */
public class TeamTrackerScreen extends Screen {
	/** No 0/O/1/I so codes are easy to read out loud. */
	private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final int MIN_BLOCKS = 1;
	private static final int MAX_BLOCKS = 50;

	private final Screen parent;
	private TextFieldWidget relayField;
	private TextFieldWidget roomField;
	private int fieldX;
	private int nameLabelY;
	private int relayLabelY;
	private int roomLabelY;
	private int statusY;
	private Text message = Text.empty();
	private int messageColor = 0xFFA0A0A0;

	public TeamTrackerScreen(Screen parent) {
		super(Text.literal("Team Tracker"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		TeamTrackerConfig cfg = TeamTrackerClient.config();
		int w = 240;
		int half = (w - 4) / 2;
		int x = this.width / 2 - w / 2;
		int y = Math.max(30, this.height / 2 - 136);
		fieldX = x;

		addDrawableChild(ButtonWidget.builder(enabledText(), b -> {
			cfg.enabled = !cfg.enabled;
			cfg.save();
			b.setMessage(enabledText());
		}).dimensions(x, y, w, 20).build());
		y += 24;

		addDrawableChild(new DistanceSlider(x, y, w, 20, cfg.minDistance));
		y += 28;

		nameLabelY = y;
		y += 11;
		TextFieldWidget nameField = new TextFieldWidget(this.textRenderer, x, y, w, 20, Text.literal("Tag name"));
		nameField.setMaxLength(32);
		nameField.setPlaceholder(Text.literal("Leave empty to use your Minecraft name"));
		nameField.setText(cfg.displayName == null ? "" : cfg.displayName);
		nameField.setChangedListener(text -> cfg.displayName = text.trim());
		addDrawableChild(nameField);
		y += 26;

		relayLabelY = y;
		y += 11;
		relayField = new TextFieldWidget(this.textRenderer, x, y, w, 20, Text.literal("Relay address"));
		relayField.setMaxLength(256);
		relayField.setPlaceholder(Text.literal("wss://your-relay.example.com"));
		relayField.setText(cfg.relayUrl == null ? "" : cfg.relayUrl);
		addDrawableChild(relayField);
		y += 26;

		roomLabelY = y;
		y += 11;
		roomField = new TextFieldWidget(this.textRenderer, x, y, w, 20, Text.literal("Room code"));
		roomField.setMaxLength(64);
		roomField.setPlaceholder(Text.literal("Paste a room code, or create one"));
		roomField.setText(cfg.room == null ? "" : cfg.room);
		addDrawableChild(roomField);
		y += 26;

		addDrawableChild(ButtonWidget.builder(Text.literal("Create New Room"), b -> createRoom())
				.dimensions(x, y, half, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Join / Apply"), b -> applyAndJoin())
				.dimensions(x + half + 4, y, half, 20).build());
		y += 24;

		addDrawableChild(ButtonWidget.builder(Text.literal("Copy Code"), b -> copyCode())
				.dimensions(x, y, half, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Leave Room"), b -> leaveRoom())
				.dimensions(x + half + 4, y, half, 20).build());
		y += 24;

		addDrawableChild(ButtonWidget.builder(Text.literal("Reconnect to Relay"), b -> reconnect())
				.dimensions(x, y, w, 20).build());
		y += 28;

		statusY = y;
		y += 36;

		addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
				.dimensions(x, y, w, 20).build());
	}

	private static Text enabledText() {
		return Text.literal("Tags: " + (TeamTrackerClient.config().enabled ? "ON" : "OFF"));
	}

	private static String newCode() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 12; i++) {
			if (i > 0 && i % 4 == 0) {
				sb.append('-');
			}
			sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
		}
		return sb.toString();
	}

	private void info(String text) {
		message = Text.literal(text);
		messageColor = 0xFF55FF55;
	}

	private void error(String text) {
		message = Text.literal(text);
		messageColor = 0xFFFF5555;
	}

	/** Validates the relay address field. Returns the cleaned address, or null (after showing an error). */
	private String checkedRelay() {
		String url = relayField.getText().trim();
		if (url.isEmpty()) {
			error("Enter the relay address first.");
			return null;
		}
		if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
			error("The relay address must start with ws:// or wss://");
			return null;
		}
		return url;
	}

	private void createRoom() {
		if (checkedRelay() == null) {
			return;
		}
		roomField.setText(newCode());
		if (applyAndJoin()) {
			info("Room created. Click Copy Code and share it.");
		}
	}

	private boolean applyAndJoin() {
		String url = checkedRelay();
		if (url == null) {
			return false;
		}
		String code = roomField.getText().trim().toUpperCase(Locale.ROOT);
		if (code.length() < 8 || code.length() > 64) {
			error("A room code needs 8 to 64 characters.");
			return false;
		}
		roomField.setText(code);
		TeamTrackerConfig cfg = TeamTrackerClient.config();
		cfg.relayUrl = url;
		cfg.room = code;
		cfg.save();
		TeamTrackerClient.applyConfig();
		info("Joined room " + code);
		return true;
	}

	private void copyCode() {
		String code = roomField.getText().trim();
		if (code.isEmpty()) {
			error("There is no room code to copy.");
			return;
		}
		this.client.keyboard.setClipboard(code);
		info("Copied " + code);
	}

	/** Drops the relay connection and opens a fresh one (the mod also does this by itself if it goes quiet). */
	private void reconnect() {
		TeamTrackerConfig cfg = TeamTrackerClient.config();
		if (!cfg.hasRelay() || !cfg.hasRoom()) {
			error("Set a relay address and join a room first.");
			return;
		}
		TeamTrackerClient.applyConfig();
		info("Reconnecting...");
	}

	private void leaveRoom() {
		TeamTrackerConfig cfg = TeamTrackerClient.config();
		if (!cfg.hasRoom()) {
			error("You're not in a room.");
			return;
		}
		roomField.setText("");
		cfg.room = "";
		cfg.save();
		TeamTrackerClient.applyConfig();
		info("Left the room.");
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 12, 0xFFFFFFFF);
		ctx.drawTextWithShadow(this.textRenderer, Text.literal("Your tag name (what teammates see)"), fieldX, nameLabelY, 0xFFA0A0A0);
		ctx.drawTextWithShadow(this.textRenderer, Text.literal("Relay address"), fieldX, relayLabelY, 0xFFA0A0A0);
		ctx.drawTextWithShadow(this.textRenderer, Text.literal("Room code"), fieldX, roomLabelY, 0xFFA0A0A0);

		TeamTrackerConfig cfg = TeamTrackerClient.config();
		RelayClient relay = TeamTrackerClient.relay();
		String status;
		int statusColor;
		if (!cfg.hasRelay() || !cfg.hasRoom()) {
			status = "Not connected: set a relay address and join a room.";
			statusColor = 0xFFA0A0A0;
		} else if (this.client != null && this.client.world == null) {
			status = "Saved. It connects once you are in a world.";
			statusColor = 0xFFFFFF55;
		} else if (relay != null && relay.isOpen()) {
			status = "Connected. Teammates online: " + relay.members().size();
			statusColor = 0xFF55FF55;
		} else {
			status = "Connecting to the relay... (retries every 5 seconds)";
			statusColor = 0xFFFF5555;
		}
		ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(status), this.width / 2, statusY, statusColor);
		ctx.drawCenteredTextWithShadow(this.textRenderer, message, this.width / 2, statusY + 14, messageColor);
	}

	@Override
	public void removed() {
		TeamTrackerClient.config().save();
	}

	@Override
	public void close() {
		this.client.setScreen(parent);
	}

	/** Slider for the minimum distance (in whole blocks). Saved when the screen closes. */
	private static final class DistanceSlider extends SliderWidget {
		DistanceSlider(int x, int y, int width, int height, double blocks) {
			super(x, y, width, height, Text.empty(),
					MathHelper.clamp((blocks - MIN_BLOCKS) / (MAX_BLOCKS - MIN_BLOCKS), 0.0, 1.0));
			updateMessage();
		}

		private int blocks() {
			return (int) Math.round(MIN_BLOCKS + this.value * (MAX_BLOCKS - MIN_BLOCKS));
		}

		@Override
		protected void updateMessage() {
			setMessage(Text.literal("Show tags beyond: " + blocks() + " blocks"));
		}

		@Override
		protected void applyValue() {
			TeamTrackerClient.config().minDistance = blocks();
		}
	}
}
