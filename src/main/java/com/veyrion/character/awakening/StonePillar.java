package com.veyrion.character.awakening;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * One stone of the altar: a fixed group of display entities, spawned once. Rising and lowering only change the
 * transformation of existing entities (the client interpolates between keyframes), so nothing is spawned per
 * tick. Main thread only.
 *
 * <p>Geometry is in a local frame whose +Z points at the altar centre, so every pillar faces inward. Shafts are
 * stacked blocks of about one block tall instead of one stretched block, which keeps the brick texture sharp.
 */
final class StonePillar {

    static final String TAG = "veyrion_altar_visual";

    private static final double PLINTH_H = 0.30;
    private static final double PLINTH_W = 1.75;
    private static final double SHAFT_W = 0.95;
    private static final double SEG_H = 1.2;
    private static final double RING_W = 1.10;
    private static final double CAP_W = 1.30;
    private static final double CAP_H = 0.28;
    private static final double[] RING_AT = {0.35, 0.78};
    private static final double MIN = 0.001;

    private final AltarVisualSettings.Stone stone;
    private final Location base;
    private final Quaternionf yaw;
    private final double stump;
    private final double tall;
    private final List<Display> all = new ArrayList<>();
    private final BlockDisplay[] segments;
    private final double[] lastVisible;
    private final BlockDisplay[] rings = new BlockDisplay[RING_AT.length];
    private BlockDisplay cap;
    private BlockDisplay gem;
    private TextDisplay rune;
    private final Color dust;

    private StonePillar(AltarVisualSettings.Stone stone, Location base, double facingX, double facingZ,
                        AltarVisualSettings cfg) {
        this.stone = stone;
        this.base = base;
        this.yaw = new Quaternionf().rotationY((float) Math.atan2(facingX, facingZ));
        this.stump = cfg.stumpHeight();
        this.tall = cfg.pillarHeight();
        this.segments = new BlockDisplay[(int) Math.ceil(tall / SEG_H)];
        this.lastVisible = new double[segments.length];
        java.util.Arrays.fill(lastVisible, -1.0);
        this.dust = Color.fromRGB(stone.color().value());
    }

    /**
     * Spawns the stone at rest. If anything fails, the entities already spawned are removed again.
     *
     * @param base   centre of the stone's bottom
     * @param facing horizontal direction from the stone towards the altar centre (does not need to be unit)
     */
    static StonePillar spawn(AltarVisualSettings.Stone stone, Location base, double facingX, double facingZ,
                             AltarVisualSettings cfg) {
        StonePillar pillar = new StonePillar(stone, base, facingX, facingZ, cfg);
        try {
            pillar.build(cfg.viewRange());
        } catch (RuntimeException e) {
            pillar.remove();
            throw e;
        }
        return pillar;
    }

    private void build(float viewRange) {
        World world = base.getWorld();
        BlockDisplay plinth = block(world, Material.CRACKED_STONE_BRICKS, viewRange, false);
        plinth.setTransformation(box(0, 0, 0, PLINTH_W, PLINTH_H, PLINTH_W));
        for (int k = 0; k < segments.length; k++) {
            segments[k] = block(world, Material.STONE_BRICKS, viewRange, false);
        }
        for (int i = 0; i < rings.length; i++) {
            rings[i] = block(world, stone.accent(), viewRange, true);
        }
        cap = block(world, Material.CHISELED_STONE_BRICKS, viewRange, false);
        gem = block(world, stone.gem(), viewRange, true);
        rune = world.spawn(base, TextDisplay.class, d -> {
            common(d, viewRange, true);
            d.text(Component.text(stone.rune(), stone.color()));
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setDefaultBackground(false);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setShadowed(false);
            d.setSeeThrough(false);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
        });
        all.add(rune);
        apply(0.0, 0);
    }

    private BlockDisplay block(World world, Material material, float viewRange, boolean bright) {
        BlockDisplay display = world.spawn(base, BlockDisplay.class, d -> {
            d.setBlock(material.createBlockData());
            common(d, viewRange, bright);
        });
        all.add(display);
        return display;
    }

    private static void common(Display display, float viewRange, boolean bright) {
        display.setPersistent(false);
        display.addScoreboardTag(TAG);
        display.setGravity(false);
        display.setInvulnerable(true);
        display.setViewRange(viewRange);
        display.setShadowRadius(0f);
        display.setShadowStrength(0f);
        display.setBillboard(Display.Billboard.FIXED);
        display.setInterpolationDuration(0);
        display.setInterpolationDelay(0);
        if (bright) {
            display.setBrightness(new Display.Brightness(15, 15));
        }
    }

    // ----------------------------------------------------------------------------------------------- geometry

    private double height(double p) {
        return stump + (tall - stump) * p;
    }

    /** Box with its bottom centre at (ox, oy, oz) in the local frame, rotated to face the altar. */
    private Transformation box(double ox, double oy, double oz, double sx, double sy, double sz) {
        Vector3f translation = new Vector3f((float) (ox - sx / 2), (float) oy, (float) (oz - sz / 2)).rotate(yaw);
        return new Transformation(translation, new Quaternionf(yaw),
                new Vector3f((float) sx, (float) Math.max(sy, MIN), (float) sz), new Quaternionf());
    }

    private static void set(Display display, Transformation transformation, int ticks) {
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(Math.max(0, ticks));
        display.setTransformation(transformation);
    }

    /**
     * Moves every dynamic part to progress {@code p} (0 = stump, 1 = full pillar); the client interpolates
     * over {@code ticks}. Segments whose height did not change are skipped.
     */
    void apply(double p, int ticks) {
        double h = height(p);
        for (int k = 0; k < segments.length; k++) {
            double visible = Math.max(0.0, Math.min(SEG_H, h - k * SEG_H));
            if (Math.abs(visible - lastVisible[k]) < 1e-4) {
                continue;
            }
            lastVisible[k] = visible;
            set(segments[k], box(0, PLINTH_H + k * SEG_H, 0, SHAFT_W, visible, SHAFT_W), ticks);
        }
        double ringH = 0.10 + 0.06 * p;
        for (int i = 0; i < rings.length; i++) {
            double y = PLINTH_H + h * RING_AT[i] - ringH / 2;
            set(rings[i], box(0, y, 0, RING_W, ringH, RING_W), ticks);
        }
        set(cap, box(0, PLINTH_H + h, 0, CAP_W, CAP_H, CAP_W), ticks);
        set(gem, gemTransform(p, h), ticks);
        set(rune, runeTransform(p, h), ticks);
    }

    /** A small cube standing on a corner above the cap; it turns once while the pillar rises. */
    private Transformation gemTransform(double p, double h) {
        float size = (float) Math.max(MIN, 0.46 * p);
        Quaternionf rotation = new Quaternionf(yaw)
                .rotateY((float) (p * Math.PI * 2.0))
                .rotateX(0.6155f)
                .rotateZ((float) (Math.PI / 4.0));
        Vector3f centre = new Vector3f(0f, (float) (PLINTH_H + h + CAP_H + 0.55), 0f).rotate(yaw);
        Vector3f half = new Vector3f(size / 2, size / 2, size / 2).rotate(rotation);
        centre.sub(half);
        return new Transformation(centre, rotation, new Vector3f(size, size, size), new Quaternionf());
    }

    /** The race rune hangs on the inward side of the shaft and grows in during the second part of the rise. */
    private Transformation runeTransform(double p, double h) {
        float scale = (float) Math.max(MIN, 2.2 * Math.max(0.0, Math.min(1.0, (p - 0.3) / 0.7)));
        Vector3f translation = new Vector3f(0f, (float) (PLINTH_H + h * 0.55), 0.62f).rotate(yaw);
        return new Transformation(translation, new Quaternionf(), new Vector3f(scale, scale, scale),
                new Quaternionf());
    }

    // ------------------------------------------------------------------------------------------------ effects

    /** Stone dust at the foot of the pillar. */
    void burst(int count) {
        if (count <= 0) {
            return;
        }
        base.getWorld().spawnParticle(Particle.BLOCK, base.clone().add(0, 0.35, 0), count, 0.75, 0.12, 0.75, 0.05,
                Material.STONE_BRICKS.createBlockData());
    }

    /** One ambient pulse of an active pillar: a glint at the gem and a few motes along the shaft. */
    void pulse(int count) {
        if (count <= 0) {
            return;
        }
        World world = base.getWorld();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location top = base.clone().add(0, PLINTH_H + tall + CAP_H + 0.55, 0);
        world.spawnParticle(Particle.DUST, top, count, 0.25, 0.3, 0.25, 0.0, new Particle.DustOptions(dust, 1.2f));
        world.spawnParticle(Particle.END_ROD, top, 1, 0.15, 0.2, 0.15, 0.01);
        for (int i = 0; i < Math.min(count, 3); i++) {
            Location mote = base.clone().add(random.nextDouble(-0.7, 0.7), random.nextDouble(0.3, tall),
                    random.nextDouble(-0.7, 0.7));
            world.spawnParticle(Particle.ENCHANT, mote, 1, 0.1, 0.1, 0.1, 0.3);
        }
    }

    /** A flash of the race colour at the gem when the pillar finishes rising. */
    void flare() {
        Location top = base.clone().add(0, PLINTH_H + tall + CAP_H + 0.55, 0);
        base.getWorld().spawnParticle(Particle.DUST, top, 18, 0.35, 0.35, 0.35, 0.0,
                new Particle.DustOptions(dust, 1.6f));
        base.getWorld().spawnParticle(Particle.END_ROD, top, 6, 0.3, 0.3, 0.3, 0.05);
    }

    // ------------------------------------------------------------------------------------------------ lifetime

    boolean valid() {
        for (Display display : all) {
            if (!display.isValid()) {
                return false;
            }
        }
        return !all.isEmpty();
    }

    void remove() {
        for (Display display : all) {
            display.remove();
        }
        all.clear();
    }
}
