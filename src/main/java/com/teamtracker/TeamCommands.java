package com.teamtracker;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import java.security.SecureRandom;
import java.util.Locale;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * In-game commands (also available as /tt):
 *   /teamtracker create        make a new random room and join it
 *   /teamtracker join <code>   join a room someone else made
 *   /teamtracker leave         leave your room
 *   /teamtracker code          show your room code and connection status
 *   /teamtracker relay <url>   set the relay server address (ws:// or wss://)
 */
public final class TeamCommands {
	/** No 0/O/1/I so codes are easy to read out loud. */
	private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
	private static final SecureRandom RANDOM = new SecureRandom();

	private TeamCommands() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			LiteralCommandNode<FabricClientCommandSource> root = dispatcher.register(
					literal("teamtracker")
							.then(literal("create").executes(TeamCommands::create))
							.then(literal("join")
									.then(argument("code", StringArgumentType.word()).executes(TeamCommands::join)))
							.then(literal("leave").executes(TeamCommands::leave))
							.then(literal("code").executes(TeamCommands::status))
							.then(literal("relay")
									.then(argument("url", StringArgumentType.greedyString()).executes(TeamCommands::relay))));
			dispatcher.register(literal("tt").redirect(root));
		});
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

	private static int create(CommandContext<FabricClientCommandSource> ctx) {
		TeamTrackerConfig config = TeamTrackerClient.config();
		if (!config.hasRelay()) {
			return needRelay(ctx);
		}
		String code = newCode();
		setRoom(code);
		Text copyable = Text.literal(code).styled(s -> s
				.withColor(Formatting.YELLOW)
				.withClickEvent(new ClickEvent.CopyToClipboard(code)));
		ctx.getSource().sendFeedback(Text.literal("Room created: ").formatted(Formatting.GREEN)
				.append(copyable)
				.append(Text.literal("  (click to copy, then share it. Others use /tt join <code>)").formatted(Formatting.GRAY)));
		return 1;
	}

	private static int join(CommandContext<FabricClientCommandSource> ctx) {
		TeamTrackerConfig config = TeamTrackerClient.config();
		if (!config.hasRelay()) {
			return needRelay(ctx);
		}
		String code = StringArgumentType.getString(ctx, "code").toUpperCase(Locale.ROOT);
		if (code.length() < 8 || code.length() > 64) {
			ctx.getSource().sendFeedback(Text.literal("That doesn't look like a room code (needs 8 to 64 characters).")
					.formatted(Formatting.RED));
			return 0;
		}
		setRoom(code);
		ctx.getSource().sendFeedback(Text.literal("Joined room " + code).formatted(Formatting.GREEN));
		return 1;
	}

	private static int leave(CommandContext<FabricClientCommandSource> ctx) {
		if (!TeamTrackerClient.config().hasRoom()) {
			ctx.getSource().sendFeedback(Text.literal("You're not in a room.").formatted(Formatting.YELLOW));
			return 0;
		}
		setRoom("");
		ctx.getSource().sendFeedback(Text.literal("Left the room.").formatted(Formatting.GREEN));
		return 1;
	}

	private static int status(CommandContext<FabricClientCommandSource> ctx) {
		TeamTrackerConfig config = TeamTrackerClient.config();
		RelayClient relay = TeamTrackerClient.relay();
		FabricClientCommandSource src = ctx.getSource();

		src.sendFeedback(Text.literal("Relay: ").formatted(Formatting.GRAY)
				.append(Text.literal(config.hasRelay() ? config.relayUrl : "not set (use /tt relay <url>)")
						.formatted(Formatting.WHITE)));
		if (!config.hasRoom()) {
			src.sendFeedback(Text.literal("Room: none. Use /tt create or /tt join <code>.").formatted(Formatting.GRAY));
			return 1;
		}
		String code = config.room;
		Text copyable = Text.literal(code).styled(s -> s
				.withColor(Formatting.YELLOW)
				.withClickEvent(new ClickEvent.CopyToClipboard(code)));
		src.sendFeedback(Text.literal("Room: ").formatted(Formatting.GRAY).append(copyable)
				.append(Text.literal("  (click to copy)").formatted(Formatting.GRAY)));
		boolean connected = relay != null && relay.isOpen();
		int others = relay == null ? 0 : relay.members().size();
		src.sendFeedback(Text.literal(connected
				? "Connected. Teammates online: " + others
				: "Not connected to the relay yet (it keeps retrying).")
				.formatted(connected ? Formatting.GREEN : Formatting.RED));
		return 1;
	}

	private static int relay(CommandContext<FabricClientCommandSource> ctx) {
		String url = StringArgumentType.getString(ctx, "url").trim();
		if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
			ctx.getSource().sendFeedback(Text.literal("The relay address must start with ws:// or wss://")
					.formatted(Formatting.RED));
			return 0;
		}
		TeamTrackerConfig config = TeamTrackerClient.config();
		config.relayUrl = url;
		config.save();
		TeamTrackerClient.applyConfig();
		ctx.getSource().sendFeedback(Text.literal("Relay set to " + url).formatted(Formatting.GREEN));
		return 1;
	}

	private static int needRelay(CommandContext<FabricClientCommandSource> ctx) {
		ctx.getSource().sendFeedback(Text.literal("Set the relay address first: /tt relay ws://your-server:8080")
				.formatted(Formatting.RED));
		return 0;
	}

	private static void setRoom(String code) {
		TeamTrackerConfig config = TeamTrackerClient.config();
		config.room = code;
		config.save();
		TeamTrackerClient.applyConfig();
	}
}
