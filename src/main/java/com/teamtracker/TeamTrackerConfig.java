package com.teamtracker;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Settings, stored in config/teamtracker.json. A default file is written on first launch.
 * relayUrl and room can also be changed in game with /teamtracker commands, which save here.
 */
public final class TeamTrackerConfig {
	/** Address of your relay, e.g. wss://relay.example.com or ws://203.0.113.5:8080. */
	public String relayUrl = "";
	/** Current room code. Everyone in the same room sees each other. Empty = not in a room. */
	public String room = "";
	/** Teammates closer than this many blocks get no tag. */
	public double minDistance = 7.0;
	/** Master switch, toggled with the "Toggle Team Tracker" keybind. Off = no tags shown. */
	public boolean enabled = true;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("teamtracker.json");
	}

	public boolean hasRelay() {
		return relayUrl != null && !relayUrl.isBlank();
	}

	public boolean hasRoom() {
		return room != null && room.length() >= 8;
	}

	public void save() {
		try {
			Files.writeString(path(), GSON.toJson(this));
		} catch (IOException e) {
			TeamTrackerClient.LOGGER.error("Could not save {}", path(), e);
		}
	}

	public static TeamTrackerConfig load() {
		Path path = path();
		try {
			if (Files.exists(path)) {
				try (Reader reader = Files.newBufferedReader(path)) {
					TeamTrackerConfig loaded = GSON.fromJson(reader, TeamTrackerConfig.class);
					if (loaded != null) {
						if (loaded.relayUrl == null) loaded.relayUrl = "";
						if (loaded.room == null) loaded.room = "";
						return loaded;
					}
				}
			} else {
				TeamTrackerConfig defaults = new TeamTrackerConfig();
				defaults.save();
				return defaults;
			}
		} catch (IOException | JsonParseException e) {
			TeamTrackerClient.LOGGER.error("Could not read {}", path, e);
		}
		return new TeamTrackerConfig();
	}
}
