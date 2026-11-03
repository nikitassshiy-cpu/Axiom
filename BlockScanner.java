package dev.axiom;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Periodically scans loaded chunks for the selected blocks (nearest first, time-limited). */
public final class BlockScanner {
    public record Hit(int x, int y, int z, double dist2) {}

    private static final int MAX_HITS = 1500;
    private static final long BUDGET_NANOS = 6_000_000L;
    private static final int INTERVAL_TICKS = 8;

    private static volatile List<Hit> hits = List.of();
    private static int cooldown = 0;

    private BlockScanner() {}

    public static List<Hit> hits() {
        return hits;
    }

    public static void clear() {
        hits = List.of();
    }

    public static void invalidate() {
        cooldown = 0;
    }

    public static void tick(Minecraft mc) {
        AxiomConfig cfg = AxiomConfig.get();
        ClientLevel level = mc.level;
        Player player = mc.player;
        if (!cfg.enabled || !(cfg.outlineEnabled || cfg.tracersEnabled) || level == null || player == null) {
            hits = List.of();
            return;
        }
        Set<Block> sel = AxiomConfig.selectedBlocks();
        if (sel.isEmpty()) {
            hits = List.of();
            return;
        }
        if (--cooldown > 0) return;
        cooldown = INTERVAL_TICKS;

        double px = player.getX(), py = player.getY(), pz = player.getZ();
        int r = cfg.maxDistance;
        double r2 = (double) r * r;
        int pcx = Mth.floor(px) >> 4;
        int pcz = Mth.floor(pz) >> 4;
        int cr = (r >> 4) + 1;

        List<int[]> offsets = new ArrayList<>();
        for (int dx = -cr; dx <= cr; dx++) {
            for (int dz = -cr; dz <= cr; dz++) {
                offsets.add(new int[]{dx, dz});
            }
        }
        offsets.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));

        Predicate<BlockState> pred = s -> sel.contains(s.getBlock());
        long deadline = System.nanoTime() + BUDGET_NANOS;
        int minY = level.getMinY();
        List<Hit> found = new ArrayList<>();

        for (int[] off : offsets) {
            if (found.size() >= MAX_HITS * 4 || System.nanoTime() > deadline) break;
            int ccx = pcx + off[0], ccz = pcz + off[1];
            LevelChunk chunk = level.getChunkSource().getChunkNow(ccx, ccz);
            if (chunk == null) continue;
            LevelChunkSection[] sections = chunk.getSections();
            for (int si = 0; si < sections.length; si++) {
                LevelChunkSection sec = sections[si];
                if (sec == null || sec.hasOnlyAir()) continue;
                int baseY = minY + si * 16;
                if (baseY + 15 < py - r || baseY > py + r) continue;
                if (!sec.maybeHas(pred)) continue;
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            BlockState st = sec.getBlockState(x, y, z);
                            if (!sel.contains(st.getBlock())) continue;
                            int wx = (ccx << 4) + x, wy = baseY + y, wz = (ccz << 4) + z;
                            double dx = wx + 0.5 - px, dy = wy + 0.5 - py, dz = wz + 0.5 - pz;
                            double d2 = dx * dx + dy * dy + dz * dz;
                            if (d2 <= r2) found.add(new Hit(wx, wy, wz, d2));
                        }
                    }
                }
                if (System.nanoTime() > deadline) break;
            }
        }

        found.sort(Comparator.comparingDouble(Hit::dist2));
        if (found.size() > MAX_HITS) found = new ArrayList<>(found.subList(0, MAX_HITS));
        hits = found;
    }
}
