package dev.axiom;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Draws outlines and tracers as thin geometry (boxes / ribbons) through the vanilla filled-debug
 * pipeline with depth testing disabled, so everything is visible through walls.
 */
public final class EspRenderer {
    private static final int MAX_OUTLINES = 200;   // buffer budget: 16 vertices per edge * 12 edges
    private static final double CLUSTER_DIST2 = 9.0;

    private static final RenderPipeline PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath(AxiomClient.MOD_ID, "pipeline/esp_through_walls"))
                    .withDepthStencilState(Optional.empty())
                    .build());

    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    private static final StagedVertexBuffer BUFFER =
            new StagedVertexBuffer(() -> "Axiom ESP Buffer", RenderType.SMALL_BUFFER_SIZE);

    // current colour while emitting vertices
    private static float cr, cg, cb, ca;

    private EspRenderer() {}

    public static void close() {
        BUFFER.close();
    }

    public static void render(LevelRenderContext ctx) {
        AxiomConfig cfg = AxiomConfig.get();
        if (!cfg.enabled) return;
        boolean outline = cfg.outlineEnabled, tracers = cfg.tracersEnabled;
        if (!outline && !tracers) return;
        List<BlockScanner.Hit> hits = BlockScanner.hits();
        if (hits.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        VertexFormat formatBinding = PIPELINE.getVertexFormatBinding(0);
        assert formatBinding != null;
        PrimitiveTopology primitive = PIPELINE.getPrimitiveTopology();
        StagedVertexBuffer.Draw draw = BUFFER.appendDraw(formatBinding, primitive,
                primitive == PrimitiveTopology.QUADS ? RenderSystem.getProjectionType().vertexSorting() : null);

        VertexConsumer vb = BUFFER.getVertexBuilder(draw);
        Matrix4fc pose = ctx.poseStack().last().pose();
        Vec3 cam = ctx.levelState().cameraRenderState.pos;

        if (outline) {
            setColor(cfg.outlineColor, 0.9f);
            int n = Math.min(MAX_OUTLINES, hits.size());
            for (int i = 0; i < n; i++) {
                BlockScanner.Hit h = hits.get(i);
                double dist = Math.sqrt(h.dist2());
                float half = (float) Math.min(0.08, cfg.outlineThickness * (0.006 + dist * 0.0008) * 0.5);
                float x0 = (float) (h.x() - cam.x), y0 = (float) (h.y() - cam.y), z0 = (float) (h.z() - cam.z);
                outlineBox(vb, pose, x0, y0, z0, x0 + 1, y0 + 1, z0 + 1, half);
            }
        }

        if (tracers) {
            setColor(cfg.tracerColor, 0.85f);
            float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            Vec3 look = mc.player.getViewVector(partial);
            Vec3 eye = mc.player.getEyePosition(partial);
            float sx = (float) (eye.x - cam.x + look.x * 0.6);
            float sy = (float) (eye.y - cam.y + look.y * 0.6);
            float sz = (float) (eye.z - cam.z + look.z * 0.6);

            List<BlockScanner.Hit> chosen = new ArrayList<>();
            for (BlockScanner.Hit h : hits) {
                if (chosen.size() >= cfg.maxTracers) break;
                boolean near = false;
                for (BlockScanner.Hit c : chosen) {
                    double dx = c.x() - h.x(), dy = c.y() - h.y(), dz = c.z() - h.z();
                    if (dx * dx + dy * dy + dz * dz < CLUSTER_DIST2) { near = true; break; }
                }
                if (near) continue;
                chosen.add(h);
                float ex = (float) (h.x() + 0.5 - cam.x);
                float ey = (float) (h.y() + 0.5 - cam.y);
                float ez = (float) (h.z() + 0.5 - cam.z);
                ribbon(vb, pose, sx, sy, sz, ex, ey, ez, cfg.tracerThickness * 0.0015f);
            }
        }

        BUFFER.upload();
        StagedVertexBuffer.ExecuteInfo info = BUFFER.getExecuteInfo(draw);
        if (info != null) {
            drawBuffer(mc, info);
        }
        BUFFER.endFrame();
    }

    private static void setColor(int rgb, float a) {
        cr = ((rgb >> 16) & 255) / 255f;
        cg = ((rgb >> 8) & 255) / 255f;
        cb = (rgb & 255) / 255f;
        ca = a;
    }

    private static void v(VertexConsumer b, Matrix4fc m, float x, float y, float z) {
        b.addVertex(m, x, y, z).setColor(cr, cg, cb, ca);
    }

    /** Camera-facing ribbon from s to e; width grows with depth so it stays a constant size on screen. */
    private static void ribbon(VertexConsumer b, Matrix4fc m, float sx, float sy, float sz,
                               float ex, float ey, float ez, float k) {
        float dx = ex - sx, dy = ey - sy, dz = ez - sz;
        float mx = (sx + ex) * 0.5f, my = (sy + ey) * 0.5f, mz = (sz + ez) * 0.5f;
        // side = dir x (direction to the camera, which is the origin)
        float px = dy * mz - dz * my;
        float py = dz * mx - dx * mz;
        float pz = dx * my - dy * mx;
        float len = (float) Math.sqrt(px * px + py * py + pz * pz);
        if (len < 1e-6f) return;
        px /= len; py /= len; pz /= len;
        float ws = k * (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        float we = k * (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
        float ax = sx - px * ws, ay = sy - py * ws, az = sz - pz * ws;
        float bx = sx + px * ws, by = sy + py * ws, bz = sz + pz * ws;
        float cx = ex + px * we, cy = ey + py * we, cz = ez + pz * we;
        float ddx = ex - px * we, ddy = ey - py * we, ddz = ez - pz * we;
        // both windings, so culling never hides it
        v(b, m, ax, ay, az); v(b, m, bx, by, bz); v(b, m, cx, cy, cz); v(b, m, ddx, ddy, ddz);
        v(b, m, ddx, ddy, ddz); v(b, m, cx, cy, cz); v(b, m, bx, by, bz); v(b, m, ax, ay, az);
    }

    /** 12 thin edge-boxes tracing the exact block bounds. */
    private static void outlineBox(VertexConsumer b, Matrix4fc m,
                                   float x0, float y0, float z0, float x1, float y1, float z1, float h) {
        for (int yy = 0; yy < 2; yy++) {
            float y = yy == 0 ? y0 : y1;
            for (int zz = 0; zz < 2; zz++) {
                float z = zz == 0 ? z0 : z1;
                edgeX(b, m, x0 - h, y - h, z - h, x1 + h, y + h, z + h);
            }
        }
        for (int xx = 0; xx < 2; xx++) {
            float x = xx == 0 ? x0 : x1;
            for (int zz = 0; zz < 2; zz++) {
                float z = zz == 0 ? z0 : z1;
                edgeY(b, m, x - h, y0 - h, z - h, x + h, y1 + h, z + h);
            }
        }
        for (int xx = 0; xx < 2; xx++) {
            float x = xx == 0 ? x0 : x1;
            for (int yy = 0; yy < 2; yy++) {
                float y = yy == 0 ? y0 : y1;
                edgeZ(b, m, x - h, y - h, z0 - h, x + h, y + h, z1 + h);
            }
        }
    }

    private static void edgeX(VertexConsumer b, Matrix4fc m, float a, float c, float d, float e, float f, float g) {
        front(b, m, a, c, d, e, f, g); back(b, m, a, c, d, e, f, g);
        top(b, m, a, c, d, e, f, g); bottom(b, m, a, c, d, e, f, g);
    }

    private static void edgeY(VertexConsumer b, Matrix4fc m, float a, float c, float d, float e, float f, float g) {
        front(b, m, a, c, d, e, f, g); back(b, m, a, c, d, e, f, g);
        left(b, m, a, c, d, e, f, g); right(b, m, a, c, d, e, f, g);
    }

    private static void edgeZ(VertexConsumer b, Matrix4fc m, float a, float c, float d, float e, float f, float g) {
        left(b, m, a, c, d, e, f, g); right(b, m, a, c, d, e, f, g);
        top(b, m, a, c, d, e, f, g); bottom(b, m, a, c, d, e, f, g);
    }

    private static void front(VertexConsumer b, Matrix4fc m, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        v(b, m, minX, minY, maxZ); v(b, m, maxX, minY, maxZ); v(b, m, maxX, maxY, maxZ); v(b, m, minX, maxY, maxZ);
    }

    private static void back(VertexConsumer b, Matrix4fc m, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        v(b, m, maxX, minY, minZ); v(b, m, minX, minY, minZ); v(b, m, minX, maxY, minZ); v(b, m, maxX, maxY, minZ);
    }

    private static void left(VertexConsumer b, Matrix4fc m, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        v(b, m, minX, minY, minZ); v(b, m, minX, minY, maxZ); v(b, m, minX, maxY, maxZ); v(b, m, minX, maxY, minZ);
    }

    private static void right(VertexConsumer b, Matrix4fc m, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        v(b, m, maxX, minY, maxZ); v(b, m, maxX, minY, minZ); v(b, m, maxX, maxY, minZ); v(b, m, maxX, maxY, maxZ);
    }

    private static void top(VertexConsumer b, Matrix4fc m, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        v(b, m, minX, maxY, maxZ); v(b, m, maxX, maxY, maxZ); v(b, m, maxX, maxY, minZ); v(b, m, minX, maxY, minZ);
    }

    private static void bottom(VertexConsumer b, Matrix4fc m, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        v(b, m, minX, minY, minZ); v(b, m, maxX, minY, minZ); v(b, m, maxX, minY, maxZ); v(b, m, minX, minY, maxZ);
    }

    private static void drawBuffer(Minecraft client, StagedVertexBuffer.ExecuteInfo info) {
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .writeTransform(RenderSystem.getModelViewMatrixCopy(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);

        RenderTarget mainTarget = client.gameRenderer.mainRenderTarget();
        GpuTextureView colorTexture = mainTarget.getColorTextureView();
        assert colorTexture != null;

        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> AxiomClient.MOD_ID + " esp", colorTexture, Optional.empty(),
                mainTarget.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(PIPELINE);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", dynamicTransforms);
            pass.setVertexBuffer(0, info.vertexBuffer().slice());
            pass.setIndexBuffer(info.indexBuffer(), info.indexType());
            pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
        }
    }
}
