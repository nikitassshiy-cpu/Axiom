package dev.axiom;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

public class AxiomScreen extends Screen {
    private static final int ACCENT = 0x6C8CFF;
    private static final int TEXT = 0xE6E8EF;
    private static final int MUTED = 0x8A90A0;
    private static final int SURFACE = 0x1A1E28;
    private static final int TRACK = 0x2B3142;
    private static final int ROW = 28;
    private static final int[] PRESETS = {
            0xFF3B3B, 0xFF9F1A, 0xFFE23B, 0x3BFF6B, 0x00FFAA,
            0x3BE8FF, 0x3B7BFF, 0xA63BFF, 0xFF3BD1, 0xFFFFFF};

    private enum Tab {
        BLOCKS("Блоки"), LOOK("Внешний вид"), TRACERS("Трейсеры"), SETTINGS("Настройки");
        final String label;
        Tab(String l) { label = l; }
    }

    private record Region(int x, int y, int w, int h, IntConsumer act, boolean drag) {}

    private final AxiomConfig cfg = AxiomConfig.get();
    private final Map<String, Float> anim = new HashMap<>();
    private final List<Region> regions = new ArrayList<>();
    private Region dragging;

    private GuiGraphicsExtractor g;
    private int mx, my;
    private float dt;
    private long lastNs = System.nanoTime();
    private float openAnim = 0f;
    private float tabPos = 0f;

    private Tab tab = Tab.BLOCKS;
    private int focus = 0; // 0 none, 1 search, 2 hex
    private String search = "";
    private String hexBuf = "";
    private String filteredFor = null;
    private List<BlockIndex.Entry> filtered = new ArrayList<>();
    private float scroll = 0f;
    private int lx, ly, lw, lh; // block list rect

    public AxiomScreen() {
        super(Component.literal("Axiom"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        AxiomConfig.save();
        super.removed();
    }

    // ------------------------------------------------------------------ helpers

    private int col(int rgb, float alpha) {
        int a = Math.max(0, Math.min(255, (int) (alpha * openAnim * 255f)));
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    private static int lerpColor(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) + ((((b >> 16) & 255) - ((a >> 16) & 255)) * t));
        int gr = (int) (((a >> 8) & 255) + ((((b >> 8) & 255) - ((a >> 8) & 255)) * t));
        int bl = (int) ((a & 255) + (((b & 255) - (a & 255)) * t));
        return (r << 16) | (gr << 8) | bl;
    }

    private float anim(String id, float target) {
        float c = anim.getOrDefault(id, target);
        c += (target - c) * (1f - (float) Math.exp(-dt * 16f));
        anim.put(id, c);
        return c;
    }

    private boolean hover(int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private void reg(int x, int y, int w, int h, IntConsumer act, boolean drag) {
        regions.add(new Region(x, y, w, h, act, drag));
    }

    private void text(String s, int x, int y, int color) {
        g.text(font, Component.literal(s), x, y, color);
    }

    private void rr(int x, int y, int w, int h, int r, int color) {
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        for (int i = 0; i < r; i++) {
            double d = r - i - 0.5;
            int inset = r - (int) Math.round(Math.sqrt(Math.max(0, (double) r * r - d * d)));
            g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            g.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, color);
        }
        g.fill(x, y + r, x + w, y + h - r, color);
    }

    // ------------------------------------------------------------------ widgets

    private void toggle(String id, int x, int y, int w, String label, boolean v, Runnable flip) {
        float k = anim(id, v ? 1f : 0f);
        if (hover(x - 4, y, w + 8, 24)) rr(x - 4, y, w + 8, 24, 6, col(0xFFFFFF, 0.05f));
        text(label, x, y + 8, col(TEXT, 1f));
        int sx = x + w - 38, sy = y + 3;
        rr(sx, sy, 36, 18, 9, col(lerpColor(TRACK, ACCENT, k), 1f));
        rr(sx + 2 + Math.round(k * 18), sy + 2, 14, 14, 7, col(0xFFFFFF, 1f));
        reg(x - 4, y, w + 8, 24, m -> flip.run(), false);
    }

    private void slider(int x, int y, int w, String label, int min, int max, int val, IntConsumer set, String suffix) {
        text(label, x, y, col(0xB8BDCC, 1f));
        String vs = val + suffix;
        text(vs, x + w - font.width(vs), y, col(TEXT, 1f));
        int ty = y + 14;
        rr(x, ty, w, 6, 3, col(TRACK, 1f));
        float f = (val - min) / (float) (max - min);
        rr(x, ty, Math.max(6, Math.round(f * w)), 6, 3, col(ACCENT, 1f));
        rr(x + Math.round(f * (w - 10)), ty - 2, 10, 10, 5, col(0xFFFFFF, 1f));
        reg(x - 4, y + 8, w + 8, 22, m -> {
            float t = Mth.clamp((m - x) / (float) w, 0f, 1f);
            set.accept(min + Math.round(t * (max - min)));
        }, true);
    }

    private void button(int x, int y, int w, int h, String label, boolean accent, Runnable r) {
        boolean hov = hover(x, y, w, h);
        int base = accent ? ACCENT : 0x262B3A;
        int c = hov ? lerpColor(base, 0xFFFFFF, 0.12f) : base;
        rr(x, y, w, h, 6, col(c, 1f));
        text(label, x + (w - font.width(label)) / 2, y + (h - 9) / 2, col(0xFFFFFF, 1f));
        reg(x, y, w, h, m -> r.run(), false);
    }

    private void textField(int x, int y, int w, int h, String value, boolean focused, String placeholder, int id, Runnable onFocus) {
        rr(x - 1, y - 1, w + 2, h + 2, 7, col(focused ? ACCENT : 0x2F3547, 1f));
        rr(x, y, w, h, 6, col(0x12141B, 1f));
        boolean empty = value.isEmpty() && !focused;
        String shown = empty ? placeholder : value;
        shown = font.plainSubstrByWidth(shown, w - 14);
        text(shown, x + 7, y + (h - 9) / 2, col(empty ? MUTED : TEXT, 1f));
        if (focused && (System.currentTimeMillis() / 500) % 2 == 0) {
            int cx = x + 7 + font.width(shown);
            g.fill(cx, y + 5, cx + 1, y + h - 5, col(TEXT, 1f));
        }
        reg(x, y, w, h, m -> {
            focus = id;
            onFocus.run();
        }, false);
    }

    /** Preset swatches + RGB sliders + HEX field. Returns the y below the editor. */
    private int colorEditor(int x, int y, int w, String title, IntSupplier get, IntConsumer set, String key) {
        int cur = get.getAsInt();
        text(title, x, y, col(0xB8BDCC, 1f));
        y += 14;
        int sw = 22, gap = 6;
        int per = Math.max(1, (w + gap) / (sw + gap));
        for (int i = 0; i < PRESETS.length; i++) {
            int bx = x + (i % per) * (sw + gap);
            int by = y + (i / per) * (sw + gap);
            if (PRESETS[i] == cur) rr(bx - 2, by - 2, sw + 4, sw + 4, 8, col(0xFFFFFF, 0.9f));
            rr(bx, by, sw, sw, 6, col(PRESETS[i], 1f));
            final int p = PRESETS[i];
            reg(bx, by, sw, sw, m -> {
                set.accept(p);
                focus = 0;
            }, false);
        }
        int rows = (PRESETS.length + per - 1) / per;
        y += rows * (sw + gap) + 2;

        String[] names = {"R", "G", "B"};
        int[] shifts = {16, 8, 0};
        for (int i = 0; i < 3; i++) {
            final int sh = shifts[i];
            slider(x, y, w, names[i], 0, 255, (cur >> sh) & 255, v -> {
                int c = get.getAsInt();
                set.accept((c & ~(255 << sh)) | ((v & 255) << sh));
            }, "");
            y += 30;
        }

        text("HEX", x, y + 8, col(0xB8BDCC, 1f));
        boolean f = focus == 2;
        String val = f ? "#" + hexBuf : String.format("#%06X", cur);
        textField(x + 36, y, 110, 24, val, f, "", 2, () -> hexBuf = String.format("%06X", get.getAsInt()));
        rr(x + 158, y, 60, 24, 6, col(cur, 1f));
        return y + 34;
    }

    // ------------------------------------------------------------------ render

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        this.g = graphics;
        this.mx = mouseX;
        this.my = mouseY;
        long now = System.nanoTime();
        dt = Math.min(0.1f, (now - lastNs) / 1e9f);
        lastNs = now;
        openAnim += (1f - openAnim) * (1f - (float) Math.exp(-dt * 14f));
        regions.clear();

        g.fill(0, 0, width, height, col(0x000000, 0.45f));

        int pw = Math.min(780, width - 24);
        int ph = Math.min(470, height - 24);
        int px = (width - pw) / 2;
        int py = (height - ph) / 2 + (int) ((1f - openAnim) * 14f);
        int sideW = pw < 560 ? 104 : 132;
        int headH = 46;

        rr(px - 1, py - 1, pw + 2, ph + 2, 11, col(0xFFFFFF, 0.07f));
        rr(px, py, pw, ph, 10, col(0x12141B, 0.95f));
        rr(px, py, sideW, ph, 10, col(0x0B0D12, 0.97f));
        g.fill(px + sideW - 12, py, px + sideW, py + ph, col(0x0B0D12, 0.97f));
        g.fill(px + sideW, py + headH, px + pw, py + headH + 1, col(0xFFFFFF, 0.07f));

        // title
        Component title = Component.literal("AXIOM").withStyle(ChatFormatting.BOLD);
        g.text(font, title, px + (sideW - font.width(title)) / 2, py + 18, col(ACCENT, 1f));

        // main switch
        boolean on = cfg.enabled;
        toggle("main", px + pw - 196, py + 11, 176, "Axiom: " + (on ? "ВКЛ" : "ВЫКЛ"), on, () -> {
            cfg.enabled = !cfg.enabled;
            BlockScanner.invalidate();
            AxiomConfig.save();
        });

        // tabs
        Tab[] tabs = Tab.values();
        tabPos += (tab.ordinal() - tabPos) * (1f - (float) Math.exp(-dt * 16f));
        int ty0 = py + headH + 10;
        int hlY = ty0 + Math.round(tabPos * 34);
        rr(px + 8, hlY, sideW - 16, 28, 7, col(ACCENT, 0.2f));
        g.fill(px + 8, hlY + 6, px + 11, hlY + 22, col(ACCENT, 1f));
        for (int i = 0; i < tabs.length; i++) {
            final Tab t = tabs[i];
            int ty = ty0 + i * 34;
            boolean sel = t == tab;
            if (!sel && hover(px + 8, ty, sideW - 16, 28)) rr(px + 8, ty, sideW - 16, 28, 7, col(0xFFFFFF, 0.05f));
            text(t.label, px + 20, ty + 10, col(sel ? 0xFFFFFF : MUTED, 1f));
            reg(px + 8, ty, sideW - 16, 28, m -> {
                tab = t;
                focus = 0;
            }, false);
        }
        text("v1.0.0", px + 14, py + ph - 18, col(0x5A6072, 1f));

        int cx = px + sideW + 18;
        int cy = py + headH + 14;
        int cw = pw - sideW - 36;
        int ch = ph - headH - 28;

        switch (tab) {
            case BLOCKS -> drawBlocks(cx, cy, cw, ch);
            case LOOK -> drawLook(cx, cy, cw);
            case TRACERS -> drawTracers(cx, cy, cw);
            case SETTINGS -> drawSettings(cx, cy, cw);
        }
    }

    private void drawLook(int x, int y, int w) {
        toggle("outline", x, y, w, "Обводка: " + (cfg.outlineEnabled ? "ВКЛ" : "ВЫКЛ"), cfg.outlineEnabled, () -> {
            cfg.outlineEnabled = !cfg.outlineEnabled;
            BlockScanner.invalidate();
            AxiomConfig.save();
        });
        y += 36;
        y = colorEditor(x, y, w, "Цвет обводки", () -> cfg.outlineColor, v -> cfg.outlineColor = v & 0xFFFFFF, "o");
        slider(x, y, w, "Толщина обводки", 1, 10, cfg.outlineThickness, v -> cfg.outlineThickness = v, "");
    }

    private void drawTracers(int x, int y, int w) {
        toggle("tracers", x, y, w, "Трейсеры: " + (cfg.tracersEnabled ? "ВКЛ" : "ВЫКЛ"), cfg.tracersEnabled, () -> {
            cfg.tracersEnabled = !cfg.tracersEnabled;
            BlockScanner.invalidate();
            AxiomConfig.save();
        });
        y += 36;
        y = colorEditor(x, y, w, "Цвет трейсеров", () -> cfg.tracerColor, v -> cfg.tracerColor = v & 0xFFFFFF, "t");
        slider(x, y, w, "Толщина линии", 1, 10, cfg.tracerThickness, v -> cfg.tracerThickness = v, "");
        y += 34;
        slider(x, y, w, "Макс. трейсеров", 1, 32, cfg.maxTracers, v -> cfg.maxTracers = v, "");
    }

    private void drawSettings(int x, int y, int w) {
        slider(x, y, w, "Максимальная дистанция", 8, 128, cfg.maxDistance, v -> {
            cfg.maxDistance = v;
            BlockScanner.invalidate();
        }, " бл.");
        y += 44;
        String key = AxiomClient.OPEN_KEY == null ? "?" : AxiomClient.OPEN_KEY.getTranslatedKeyMessage().getString();
        text("Клавиша меню: " + key, x, y, col(TEXT, 1f));
        y += 14;
        text("Изменить: Настройки → Управление → Axiom", x, y, col(MUTED, 1f));
        y += 26;
        text("Обводка и трейсеры работают независимо друг от друга.", x, y, col(MUTED, 1f));
        y += 14;
        text("Рисуется не более 200 ближайших обводок.", x, y, col(MUTED, 1f));
        y += 30;
        button(x, y, 200, 26, "Сбросить все настройки", false, () -> {
            cfg.resetToDefaults();
            focus = 0;
        });
    }

    private void refilter() {
        String q = search.trim().toLowerCase(Locale.ROOT);
        List<BlockIndex.Entry> list = new ArrayList<>();
        for (BlockIndex.Entry e : BlockIndex.entries()) {
            if (q.isEmpty() || e.search().contains(q)) list.add(e);
        }
        filtered = list;
        filteredFor = search;
    }

    private void drawBlocks(int x, int y, int w, int h) {
        if (!search.equals(filteredFor)) refilter();

        textField(x, y, w, 24, search, focus == 1, "Поиск блоков...", 1, () -> {});
        y += 32;

        button(x, y, 120, 24, "Выбрать все", true, () -> {
            for (BlockIndex.Entry e : filtered) cfg.selected.add(e.id());
            AxiomConfig.markSelectionDirty();
            AxiomConfig.save();
        });
        button(x + 128, y, 110, 24, "Снять все", false, () -> {
            cfg.selected.clear();
            AxiomConfig.markSelectionDirty();
            AxiomConfig.save();
        });
        String cnt = "Выбрано: " + cfg.selected.size() + "  |  Найдено: " + filtered.size();
        text(cnt, x + w - font.width(cnt), y + 8, col(MUTED, 1f));
        y += 32;

        lx = x; ly = y; lw = w; lh = Math.max(40, h - 64);
        rr(lx, ly, lw, lh, 8, col(SURFACE, 0.9f));

        float maxScroll = Math.max(0, filtered.size() * ROW - lh);
        scroll = Mth.clamp(scroll, 0f, maxScroll);

        if (filtered.isEmpty()) {
            text("Ничего не найдено", lx + 12, ly + 12, col(MUTED, 1f));
            return;
        }

        int first = Math.max(0, (int) (scroll / ROW));
        int last = Math.min(filtered.size(), (int) ((scroll + lh) / ROW) + 2);
        g.enableScissor(lx, ly, lx + lw, ly + lh);
        for (int i = first; i < last; i++) {
            BlockIndex.Entry e = filtered.get(i);
            int ry = ly + Math.round(i * ROW - scroll);
            boolean sel = cfg.selected.contains(e.id());
            boolean inList = hover(lx, ly, lw, lh);
            if (inList && hover(lx, ry, lw, ROW)) rr(lx + 4, ry + 1, lw - 8, ROW - 2, 6, col(0xFFFFFF, 0.05f));
            int textW = lw - 80;
            text(font.plainSubstrByWidth(e.name(), textW), lx + 12, ry + 5, col(TEXT, 1f));
            text(font.plainSubstrByWidth(e.id(), textW), lx + 12, ry + 16, col(0x5F6577, 1f));
            float k = anim("b:" + e.id(), sel ? 1f : 0f);
            int sx = lx + lw - 56, sy = ry + 6;
            rr(sx, sy, 32, 16, 8, col(lerpColor(TRACK, ACCENT, k), 1f));
            rr(sx + 2 + Math.round(k * 16), sy + 2, 12, 12, 6, col(0xFFFFFF, 1f));
            int ry0 = Math.max(ry, ly), ry1 = Math.min(ry + ROW, ly + lh);
            if (ry1 > ry0) {
                final String id = e.id();
                reg(lx, ry0, lw - 10, ry1 - ry0, m -> {
                    if (!cfg.selected.remove(id)) cfg.selected.add(id);
                    AxiomConfig.markSelectionDirty();
                }, false);
            }
        }
        g.disableScissor();

        if (maxScroll > 0) {
            int bh = Math.max(24, (int) (lh * (lh / (float) (filtered.size() * ROW))));
            int by = ly + (int) ((lh - bh) * (scroll / maxScroll));
            rr(lx + lw - 6, by, 4, bh, 2, col(0xFFFFFF, 0.25f));
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 0) return super.mouseClicked(event, doubleClick);
        focus = 0;
        int cxm = (int) event.x(), cym = (int) event.y();
        for (int i = regions.size() - 1; i >= 0; i--) {
            Region r = regions.get(i);
            if (cxm >= r.x() && cxm < r.x() + r.w() && cym >= r.y() && cym < r.y() + r.h()) {
                r.act().accept(cxm);
                if (r.drag()) dragging = r;
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging != null) {
            dragging.act().accept((int) event.x());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging != null) {
            dragging = null;
            AxiomConfig.save();
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (tab == Tab.BLOCKS && mouseX >= lx && mouseX < lx + lw && mouseY >= ly && mouseY < ly + lh) {
            float maxScroll = Math.max(0, filtered.size() * ROW - lh);
            scroll = Mth.clamp(scroll - (float) scrollY * ROW * 2f, 0f, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int k = event.key();
        boolean ctrl = (event.modifiers() & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;
        if (focus != 0) {
            if (k == GLFW.GLFW_KEY_ESCAPE || k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) {
                focus = 0;
                return true;
            }
            if (k == GLFW.GLFW_KEY_BACKSPACE) {
                if (focus == 1 && !search.isEmpty()) {
                    search = search.substring(0, search.length() - 1);
                    scroll = 0f;
                } else if (focus == 2 && !hexBuf.isEmpty()) {
                    hexBuf = hexBuf.substring(0, hexBuf.length() - 1);
                }
                return true;
            }
            if (ctrl && k == GLFW.GLFW_KEY_V) {
                String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
                if (clip == null) clip = "";
                if (focus == 1) {
                    clip = clip.replaceAll("[\\p{Cntrl}]", "");
                    search = (search + clip);
                    if (search.length() > 40) search = search.substring(0, 40);
                    scroll = 0f;
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (char c : clip.toCharArray()) {
                        if (Character.digit(c, 16) >= 0 && sb.length() < 6) sb.append(Character.toUpperCase(c));
                    }
                    if (sb.length() > 0) {
                        hexBuf = sb.toString();
                        applyHex();
                    }
                }
                return true;
            }
            return true;
        }
        if (AxiomClient.OPEN_KEY != null && AxiomClient.OPEN_KEY.matches(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        int cp = event.codepoint();
        if (focus == 1) {
            if (cp >= 32 && cp != 127 && search.length() < 40) {
                search += new String(Character.toChars(cp));
                scroll = 0f;
            }
            return true;
        }
        if (focus == 2) {
            char c = (char) cp;
            if (Character.digit(c, 16) >= 0 && hexBuf.length() < 6) {
                hexBuf += Character.toUpperCase(c);
                applyHex();
            }
            return true;
        }
        return super.charTyped(event);
    }

    private void applyHex() {
        if (hexBuf.length() != 6) return;
        try {
            int v = Integer.parseInt(hexBuf, 16);
            if (tab == Tab.LOOK) cfg.outlineColor = v;
            else if (tab == Tab.TRACERS) cfg.tracerColor = v;
        } catch (NumberFormatException ignored) {
        }
    }
}
