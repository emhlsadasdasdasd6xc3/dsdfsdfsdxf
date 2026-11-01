package com.teamtracker.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.teamtracker.TagHider;

import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.entity.Entity;

/** Same as EntityRendererMixin, for the renderer class players actually use. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	@Inject(method = "hasLabel", at = @At("HEAD"), cancellable = true, require = 0)
	private void teamtracker$hideVanillaTag(Entity entity, double squaredDistanceToCamera, CallbackInfoReturnable<Boolean> cir) {
		if (TagHider.shouldHideVanillaTag(entity)) {
			cir.setReturnValue(false);
		}
	}
}
