package com.teamtracker;

import java.util.Map;

import org.joml.Quaternionf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

/**
 * Client mod. Sends your position to the relay and draws a floating "Name  123m" tag in the
 * world for every other relay member who is more than minDistance blocks away. The tag is drawn
 * through walls. Very far teammates are pinned 24 blocks out along the line toward them so the
 * tag always stays readable.
 */
public class TeamTrackerClient implements ClientModInitializer {
	public static final String MOD_ID = "teamtracker";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** Relay members older than this are ignored (the relay also drops them). */
	private static final long STALE_MS = 5000;
	/** Tags farther than this are moved closer along the same direction. */
	private static final double MAX_TAG_DISTANCE = 24.0;
	/** Send our position every N client ticks (5 = 4 times a second). */
	private static final int SEND_INTERVAL_TICKS = 5;
	private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

	private static TeamTrackerConfig config = new TeamTrackerConfig();
	private static RelayClient relay;
	private int tickCounter = 0;

	public static TeamTrackerConfig config() {
		return config;
	}

	/** Null when no relay address or room is set. */
	public static RelayClient relay() {
		return relay;
	}

	/** Call after changing relayUrl or room: drops the old connection and starts a new one if configured. */
	public static void applyConfig() {
		if (relay != null) {
			relay.close();
			relay = null;
		}
		if (config.hasRelay() && config.hasRoom()) {
			relay = new RelayClient(config.relayUrl, config.room);
		}
	}

	@Override
	public void onInitializeClient() {
		config = TeamTrackerConfig.load();
		applyConfig();
		if (!config.hasRelay() || !config.hasRoom()) {
			LOGGER.info("Team Tracker idle. In game: /tt relay <ws-url>, then /tt create or /tt join <code>");
		}
		TeamCommands.register();

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			ClientPlayerEntity self = mc.player;
			RelayClient relay = TeamTrackerClient.relay;
			if (self == null || mc.world == null || relay == null) {
				return;
			}
			relay.ensureConnected();
			if (++tickCounter >= SEND_INTERVAL_TICKS) {
				tickCounter = 0;
				relay.sendPosition(
						self.getUuid(),
						self.getName().getString(),
						self.getX(), self.getY(), self.getZ(),
						mc.world.getRegistryKey().getValue().toString(),
						serverId(mc));
			}
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			if (relay != null) {
				relay.close();
			}
		});

		WorldRenderEvents.AFTER_ENTITIES.register(TeamTrackerClient::renderTags);
	}

	private static String serverId(MinecraftClient mc) {
		ServerInfo info = mc.getCurrentServerEntry();
		return info == null ? "singleplayer" : info.address;
	}

	private static void renderTags(WorldRenderContext ctx) {
		MinecraftClient mc = MinecraftClient.getInstance();
		ClientPlayerEntity self = mc.player;
		if (self == null || mc.world == null || relay == null) {
			return;
		}
		Map<java.util.UUID, RelayClient.Member> members = relay.members();
		if (members.isEmpty()) {
			return;
		}
		MatrixStack matrices = ctx.matrices();
		VertexConsumerProvider consumers = ctx.consumers();
		if (matrices == null || consumers == null) {
			return;
		}

		float tickProgress = mc.getRenderTickCounter().getTickProgress(false);
		Vec3d selfPos = self.getLerpedPos(tickProgress);
		double camX = selfPos.x;
		double camY = selfPos.y + self.getEyeHeight(self.getPose());
		double camZ = selfPos.z;

		// Same rotation vanilla uses for the camera, so tags always face the screen.
		Quaternionf facing = new Quaternionf().rotationYXZ(
				(float) Math.PI - self.getYaw(tickProgress) * DEG_TO_RAD,
				-self.getPitch(tickProgress) * DEG_TO_RAD,
				0.0f);

		String dim = mc.world.getRegistryKey().getValue().toString();
		String server = serverId(mc);
		long now = System.currentTimeMillis();
		TextRenderer font = mc.textRenderer;

		for (RelayClient.Member m : members.values()) {
			if (m.id().equals(self.getUuid())
					|| now - m.receivedAt() > STALE_MS
					|| !m.dim().equals(dim)
					|| !m.server().equals(server)) {
				continue;
			}

			double tx = m.x();
			double ty = m.y();
			double tz = m.z();
			// If they are inside our render distance, use their smooth live position.
			PlayerEntity live = mc.world.getPlayerByUuid(m.id());
			if (live != null && live != self) {
				Vec3d p = live.getLerpedPos(tickProgress);
				tx = p.x;
				ty = p.y;
				tz = p.z;
			}

			double distance = Math.sqrt(
					sq(tx - camX) + sq(ty + 1.0 - camY) + sq(tz - camZ));
			if (distance <= config.minDistance) {
				continue;
			}

			// Tag sits above their head, pulled in along the line of sight if very far away.
			double dx = tx - camX;
			double dy = (ty + 2.3) - camY;
			double dz = tz - camZ;
			double tagDistance = Math.sqrt(dx * dx + dy * dy + dz * dz);
			double pull = tagDistance > MAX_TAG_DISTANCE ? MAX_TAG_DISTANCE / tagDistance : 1.0;
			double shownDistance = Math.min(tagDistance, MAX_TAG_DISTANCE);
			float scale = (float) (0.0035 * Math.max(shownDistance, 7.0));

			Text label = Text.literal(m.name() + "  " + Math.round(distance) + "m");

			matrices.push();
			matrices.translate(dx * pull, dy * pull, dz * pull);
			matrices.multiply(facing);
			// If the text ever looks upside down or mirrored, flip the sign of the Y (or X) scale here.
			matrices.scale(scale, -scale, scale);
			float x = -font.getWidth(label) / 2.0f;
			font.draw(
					label, x, 0.0f, 0xFFFFFFFF, false,
					matrices.peek().getPositionMatrix(),
					consumers,
					TextRenderer.TextLayerType.SEE_THROUGH,
					0x66000000,
					0xF000F0);
			matrices.pop();
		}
	}

	private static double sq(double v) {
		return v * v;
	}
}
