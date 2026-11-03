package dev.axiom;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

public final class AxiomConfig {
    private static final Logger LOG = LoggerFactory.getLogger("Axiom");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static AxiomConfig instance = new AxiomConfig();
    private static volatile Set<Block> selectedBlocks = Set.of();
    private static volatile boolean dirty = true;

    public boolean enabled = true;
    public boolean outlineEnabled = true;
    public boolean tracersEnabled = true;
    public int outlineColor = 0xFF3B3B;
    public int tracerColor = 0x3BE8FF;
    public int outlineThickness = 3;   // 1..10
    public int tracerThickness = 3;    // 1..10
    public int maxDistance = 48;       // 8..128
    public int maxTracers = 8;         // 1..32
    public Set<String> selected = new LinkedHashSet<>();

    public static AxiomConfig get() {
        return instance;
    }

    /** Set of selected Block objects (rebuilt lazily after any selection change). */
    public static Set<Block> selectedBlocks() {
        if (dirty) {
            Set<Block> set = new HashSet<>();
            for (String id : instance.selected) {
                Block b = BlockIndex.get(id);
                if (b != null) set.add(b);
            }
            selectedBlocks = set;
            dirty = false;
        }
        return selectedBlocks;
    }

    public static void markSelectionDirty() {
        dirty = true;
        BlockScanner.invalidate();
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("axiom.json");
    }

    public static void load() {
        try {
            Path p = path();
            if (Files.exists(p)) {
                AxiomConfig c = GSON.fromJson(Files.readString(p), AxiomConfig.class);
                if (c != null) {
                    c.sanitize();
                    instance = c;
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to load config, using defaults", e);
        }
        dirty = true;
    }

    public static void save() {
        try {
            Files.writeString(path(), GSON.toJson(instance));
        } catch (Exception e) {
            LOG.warn("Failed to save config", e);
        }
    }

    public void resetToDefaults() {
        AxiomConfig d = new AxiomConfig();
        enabled = d.enabled;
        outlineEnabled = d.outlineEnabled;
        tracersEnabled = d.tracersEnabled;
        outlineColor = d.outlineColor;
        tracerColor = d.tracerColor;
        outlineThickness = d.outlineThickness;
        tracerThickness = d.tracerThickness;
        maxDistance = d.maxDistance;
        maxTracers = d.maxTracers;
        selected.clear();
        markSelectionDirty();
        save();
    }

    private void sanitize() {
        if (selected == null) selected = new LinkedHashSet<>();
        outlineColor &= 0xFFFFFF;
        tracerColor &= 0xFFFFFF;
        outlineThickness = clamp(outlineThickness, 1, 10);
        tracerThickness = clamp(tracerThickness, 1, 10);
        maxDistance = clamp(maxDistance, 8, 128);
        maxTracers = clamp(maxTracers, 1, 32);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
