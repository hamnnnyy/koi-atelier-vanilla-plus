package io.github.hamnnnyy.packtester.mixin;

import io.github.hamnnnyy.packtester.PackTester;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 한 프레임을 다 그린 뒤 스크린샷 (셰이더가 그리던 중간 화면이 찍히지 않게)
@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/util/Window;swapBuffers()V"))
    private void packtester$frameEnd(boolean tick, CallbackInfo ci) {
        PackTester.onFrameEnd((MinecraftClient) (Object) this);
    }
}
