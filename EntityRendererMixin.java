package com.teamtracker.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.teamtracker.TagHider;

import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;

/** require = 0: if this method is named differently in a version, the mixin quietly does nothing. */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
	@Inject(method = "hasLabel", at = @At("HEAD"), cancellable = true, require = 0)
	private void teamtracker$hideVanillaTag(Entity entity, double squaredDistanceToCamera, CallbackInfoReturnable<Boolean> cir) {
		if (TagHider.shouldHideVanillaTag(entity)) {
			cir.setReturnValue(false);
		}
	}
}
