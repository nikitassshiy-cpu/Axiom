package dev.axiom;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Cache of every registered block (vanilla and from other mods). */
public final class BlockIndex {
    public record Entry(String id, String name, Block block, String search) {}

    private static Map<String, Block> byId;
    private static List<Entry> entries;

    private BlockIndex() {}

    private static synchronized void buildIds() {
        if (byId != null) return;
        Map<String, Block> map = new HashMap<>();
        for (Block b : BuiltInRegistries.BLOCK) {
            map.put(BuiltInRegistries.BLOCK.getKey(b).toString(), b);
        }
        byId = map;
    }

    public static synchronized Block get(String id) {
        buildIds();
        return byId.get(id);
    }

    /** Full list for the GUI (needs the language to be loaded, so built lazily). */
    public static synchronized List<Entry> entries() {
        if (entries == null) {
            buildIds();
            List<Entry> list = new ArrayList<>();
            for (Map.Entry<String, Block> e : byId.entrySet()) {
                Block b = e.getValue();
                if (b.defaultBlockState().isAir()) continue;
                String name = b.getName().getString();
                list.add(new Entry(e.getKey(), name, b, (name + " " + e.getKey()).toLowerCase(Locale.ROOT)));
            }
            list.sort(Comparator.comparing((Entry x) -> x.name().toLowerCase(Locale.ROOT)).thenComparing(Entry::id));
            entries = list;
        }
        return entries;
    }
}
