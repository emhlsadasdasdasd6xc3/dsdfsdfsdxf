package com.teamtracker.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.teamtracker.TeamTrackerClient;

import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.entity.Entity;

/**
 * Hides the vanilla nametag above a teammate while our floating name + distance tag is showing.
 * Both renderers are targeted because LivingEntityRenderer overrides hasLabel. require = 0 means a
 * missing method in one of them will not crash the game.
 */
@Mixin({EntityRenderer.class, LivingEntityRenderer.class})
public abstract class EntityRendererMixin {
	@Inject(method = "hasLabel", at = @At("HEAD"), cancellable = true, require = 0)
	private void teamtracker$hideVanillaNametag(Entity entity, double squaredDistanceToCamera,
			CallbackInfoReturnable<Boolean> cir) {
		if (TeamTrackerClient.hasDistanceTag(entity)) {
			cir.setReturnValue(false);
		}
	}
}
