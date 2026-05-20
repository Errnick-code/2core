package dev.errnicraft.twocore.mixin;

import net.fabricmc.loader.impl.game.minecraft.MinecraftGameProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.nio.file.Path;

/**
 * Патчит gameDir в MinecraftGameProvider на secondary процессе.
 */
@Mixin(value = MinecraftGameProvider.class, remap = false)
public abstract class GameProviderMixin {

    @Shadow private Path gameDir;

    @Inject(method = "locateGame", at = @At("RETURN"), remap = false)
    private void twocore$afterLocateGame(CallbackInfo ci) {
        File workDir = getWorkDir();
        if (workDir == null) return;

        Path target = workDir.toPath();
        if (this.gameDir != null && this.gameDir.equals(target)) return;

        System.out.println("[2Core] [Mixin/GameProvider] gameDir: "
            + (this.gameDir != null ? this.gameDir : "null") + " -> " + target);
        this.gameDir = target;
    }

    private static File getWorkDir() {
        if (!"secondary".equals(System.getProperty("twocore.role", "").trim().toLowerCase())) return null;
        String dir = System.getProperty("fabric.gameDir", "").trim();
        if (dir.isEmpty()) return null;
        return new File(dir);
    }
}
