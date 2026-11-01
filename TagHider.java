package com.teamtracker;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

/** Decides whether the vanilla nametag should be hidden because Team Tracker draws its own. */
public final class TagHider {
	private static final long STALE_MS = 5000;

	private TagHider() {
	}

	/** True when this player is a relay teammate for whom we are currently drawing a distance tag. */
	public static boolean shouldHideVanillaTag(Entity entity) {
		if (!(entity instanceof PlayerEntity)) {
			return false;
		}
		MinecraftClient mc = MinecraftClient.getInstance();
		ClientPlayerEntity self = mc.player;
		RelayClient relay = TeamTrackerClient.relay();
		if (self == null || mc.world == null || relay == null || entity == self) {
			return false;
		}
		RelayClient.Member member = relay.members().get(entity.getUuid());
		if (member == null || System.currentTimeMillis() - member.receivedAt() > STALE_MS) {
			return false;
		}
		ServerInfo info = mc.getCurrentServerEntry();
		String server = info == null ? "singleplayer" : info.address;
		String dim = mc.world.getRegistryKey().getValue().toString();
		if (!member.dim().equals(dim) || !member.server().equals(server)) {
			return false;
		}
		// Same rule as our own tag: only shown beyond minDistance.
		return self.distanceTo(entity) > TeamTrackerClient.config().minDistance;
	}
}
