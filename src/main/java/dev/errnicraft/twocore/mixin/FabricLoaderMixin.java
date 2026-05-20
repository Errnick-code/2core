package dev.errnicraft.twocore.mixin;

import net.fabricmc.loader.impl.FabricLoaderImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;
import java.nio.file.Path;

/**
 * Перехватывает FabricLoaderImpl.getGameDir() и getConfigDir() на secondary процессе.
 */
@Mixin(value = FabricLoaderImpl.class, remap = false)
public abstract class FabricLoaderMixin {

    @Inject(method = "getGameDir", at = @At("HEAD"), cancellable = true, remap = false)
    private void twocore$getGameDir(CallbackInfoReturnable<Path> cir) {
        File workDir = getWorkDir();
        if (workDir == null) return;
        cir.setReturnValue(workDir.toPath());
    }

    @Inject(method = "getConfigDir", at = @At("HEAD"), cancellable = true, remap = false)
    private void twocore$getConfigDir(CallbackInfoReturnable<Path> cir) {
        File workDir = getWorkDir();
        if (workDir == null) return;
        Path configDir = workDir.toPath().resolve("config");
        configDir.toFile().mkdirs();
        cir.setReturnValue(configDir);
    }

    private static File getWorkDir() {
        if (!"secondary".equals(System.getProperty("twocore.role", "").trim().toLowerCase())) return null;
        String dir = System.getProperty("fabric.gameDir", "").trim();
        if (dir.isEmpty()) return null;
        return new File(dir);
    }
}
