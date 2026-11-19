package com.veyrion.character.awakening;

import com.veyrion.character.core.VeyrionCharacterPlugin;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

/**
 * The four stone pillars of the Race Altar. Main thread only.
 *
 * <p>Event driven: there is no polling task. Players are tracked from move, teleport, world change, respawn,
 * join, quit and death events (each with a cheap early exit). While at least one player is within the radius the
 * pillars rise; when the last one is gone (after a short delay) they lower again. The only tasks that ever exist
 * are one animation task while the pillars move, one ambient particle task while they stand, and one optional
 * one-shot delay before lowering; all are cancelled as soon as they are not needed.
 *
 * <p>The display entities are non-persistent: after a restart or a crash they simply do not exist, and they are
 * spawned again at rest when the altar chunks load. Nothing here reads or changes the race roll.
 */
final class AltarVisual {

    private final VeyrionCharacterPlugin plugin;
    private final AwakeningService service;
    private final AltarProgress progress = new AltarProgress();
    private final Set<UUID> inside = new HashSet<>();

    private @Nullable AltarVisualSettings cfg;
    private @Nullable World world;
    private @Nullable Location centre;
    private Set<Long> footprint = Set.of();
    private List<StonePillar> pillars = List.of();

    private @Nullable BukkitTask animation;
    private @Nullable BukkitTask lowerDelay;
    private @Nullable BukkitTask ambient;

    AltarVisual(VeyrionCharacterPlugin plugin, AwakeningService service) {
        this.plugin = plugin;
        this.service = service;
    }

    // ---------------------------------------------------------------------------------------------- lifecycle

    /** (Re)builds from the current settings and altar: removes everything, spawns stumps if possible. */
    void rebuild() {
        despawn();
        inside.clear();
        cfg = service.settings().altarVisual();
        world = null;
        centre = null;
        footprint = Set.of();
        AltarStore.Altar altar = service.altars().get();
        if (!cfg.enabled() || altar == null) {
            return;
        }
        World altarWorld = altar.world();
        Location anchor = altar.anchor(cfg.baseOffsetY());
        if (altarWorld == null || anchor == null) {
            return;
        }
        world = altarWorld;
        centre = altar.anchor(0.0);
        footprint = chunkKeys(layout(anchor));
        if (ensureSpawned()) {
            rescan();
        }
    }

    /** Removes every entity and task. Safe to call repeatedly; used by disable, reload and chunk unload. */
    void despawn() {
        cancelAnimation();
        cancelLowerDelay();
        cancelAmbient();
        for (StonePillar pillar : pillars) {
            pillar.remove();
        }
        pillars = List.of();
        progress.reset();
    }

    void shutdown() {
        despawn();
        inside.clear();
        world = null;
        centre = null;
        footprint = Set.of();
    }

    // ------------------------------------------------------------------------------------------------- layout

    private record Spot(AltarVisualSettings.Stone stone, Location base, double faceX, double faceZ) {}

    private List<Spot> layout(Location anchor) {
        AltarVisualSettings settings = cfg;
        List<AltarVisualSettings.Stone> stones = settings.stones();
        List<Spot> spots = new ArrayList<>(stones.size());
        for (int i = 0; i < stones.size(); i++) {
            double angle = Math.toRadians(settings.angleOffset() + 360.0 * i / stones.size());
            double dx = Math.cos(angle) * settings.stoneDistance();
            double dz = Math.sin(angle) * settings.stoneDistance();
            Location base = anchor.clone().add(dx, 0, dz);
            spots.add(new Spot(stones.get(i), base, -dx, -dz));
        }
        return spots;
    }

    private static Set<Long> chunkKeys(List<Spot> spots) {
        Set<Long> keys = new HashSet<>();
        for (Spot spot : spots) {
            // A stone is under 2 blocks wide, so check the chunks of its four corners.
            for (int sx = -1; sx <= 1; sx += 2) {
                for (int sz = -1; sz <= 1; sz += 2) {
                    int cx = Math.floorDiv((int) Math.floor(spot.base().getX() + sx * 1.0), 16);
                    int cz = Math.floorDiv((int) Math.floor(spot.base().getZ() + sz * 1.0), 16);
                    keys.add(key(cx, cz));
                }
            }
        }
        return keys;
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    /** Spawns the stumps if they are missing and every chunk they touch is loaded. */
    private boolean ensureSpawned() {
        if (!pillars.isEmpty()) {
            if (allValid()) {
                return true;
            }
            // Something removed our entities (a kill command, another plugin): start again from rest.
            despawn();
        }
        AltarVisualSettings settings = cfg;
        AltarStore.Altar altar = service.altars().get();
        if (settings == null || !settings.enabled() || altar == null || world == null) {
            return false;
        }
        Location anchor = altar.anchor(settings.baseOffsetY());
        if (anchor == null || !anchor.getWorld().equals(world)) {
            return false;
        }
        List<Spot> spots = layout(anchor);
        for (long key : footprint) {
            if (!world.isChunkLoaded((int) (key >> 32), (int) key)) {
                return false;
            }
        }
        // Leftovers of a crashed reload: only entities carrying our tag are touched.
        for (Entity stray : world.getNearbyEntities(anchor, settings.stoneDistance() + 3, 12,
                settings.stoneDistance() + 3, e -> e instanceof Display && e.getScoreboardTags().contains(StonePillar.TAG))) {
            stray.remove();
        }
        List<StonePillar> spawned = new ArrayList<>(spots.size());
        try {
            for (Spot spot : spots) {
                spawned.add(StonePillar.spawn(spot.stone(), spot.base(), spot.faceX(), spot.faceZ(), settings));
            }
        } catch (RuntimeException e) {
            spawned.forEach(StonePillar::remove);
            plugin.getLogger().log(Level.SEVERE, "Could not spawn the altar pillars.", e);
            return false;
        }
        pillars = List.copyOf(spawned);
        progress.reset();
        return true;
    }

    private boolean allValid() {
        for (StonePillar pillar : pillars) {
            if (!pillar.valid()) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------- player tracking

    void track(Player player, Location at) {
        if (cfg == null || centre == null || !cfg.enabled()) {
            return;
        }
        UUID id = player.getUniqueId();
        boolean was = inside.contains(id);
        boolean now = !player.isDead() && within(at, was);
        if (now == was) {
            return;
        }
        if (now) {
            inside.add(id);
        } else {
            inside.remove(id);
        }
        refresh();
    }

    void untrack(UUID id) {
        if (inside.remove(id)) {
            refresh();
        }
    }

    private boolean within(@Nullable Location at, boolean wasInside) {
        if (at == null || at.getWorld() == null || !at.getWorld().equals(world)) {
            return false;
        }
        double radius = cfg.radius() + (wasInside ? cfg.exitMargin() : 0.0);
        return at.distanceSquared(centre) <= radius * radius;
    }

    /** Recomputes who is inside from the online players of the altar world (after spawn or reload). */
    private void rescan() {
        inside.clear();
        if (world == null || centre == null) {
            return;
        }
        for (Player player : world.getPlayers()) {
            if (!player.isDead() && within(player.getLocation(), false)) {
                inside.add(player.getUniqueId());
            }
        }
        refresh();
    }

    private void refresh() {
        if (inside.isEmpty()) {
            if (progress.targetsUp() && lowerDelay == null) {
                scheduleLower();
            }
            return;
        }
        cancelLowerDelay();
        if (!ensureSpawned()) {
            return;
        }
        if (!progress.targetsUp()) {
            move(true);
        }
    }

    private void scheduleLower() {
        if (cfg.lowerDelayTicks() <= 0) {
            move(false);
            return;
        }
        lowerDelay = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            lowerDelay = null;
            if (inside.isEmpty() && progress.targetsUp()) {
                move(false);
            }
        }, cfg.lowerDelayTicks());
    }

    // -------------------------------------------------------------------------------------------- animation

    private static double nowTicks() {
        return System.nanoTime() / 50_000_000.0;
    }

    /** Starts rising or lowering from wherever the pillars are right now. */
    private void move(boolean up) {
        if (pillars.isEmpty()) {
            return;
        }
        cancelAnimation();
        if (!up) {
            cancelAmbient();
        }
        double duration = progress.retarget(up ? 1.0 : 0.0, nowTicks(), up ? cfg.riseTicks() : cfg.lowerTicks());
        if (duration < 1.0) {
            snapTo(up ? 1.0 : 0.0);
            finish(up);
            return;
        }
        int keyframes = Math.max(1, Math.min(cfg.segments(), (int) Math.round(duration / 2.0)));
        int segmentTicks = Math.max(2, (int) Math.round(duration / keyframes));
        if (up) {
            cfg.riseStart().broadcast(centre, 1.0f);
        }
        keyframe(1, keyframes, segmentTicks);
        int[] step = {1};
        animation = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!allValid()) {
                // Entities vanished mid-animation: rebuild at rest and let the tracker decide again.
                cancelAnimation();
                despawn();
                if (ensureSpawned()) {
                    rescan();
                }
                return;
            }
            if (step[0] >= keyframes) {
                cancelAnimation();
                finish(up);
                return;
            }
            step[0]++;
            keyframe(step[0], keyframes, segmentTicks);
        }, segmentTicks, segmentTicks);
    }

    private void keyframe(int index, int total, int ticks) {
        double p = progress.keyframe(index / (double) total);
        for (StonePillar pillar : pillars) {
            // One tick of overlap so a late server tick never leaves the client standing still.
            pillar.apply(p, ticks + 1);
            pillar.burst(5);
        }
        cfg.motionStep().broadcast(centre, 0.9f + 0.2f * (float) p);
    }

    private void snapTo(double p) {
        for (StonePillar pillar : pillars) {
            pillar.apply(p, 0);
        }
    }

    private void finish(boolean up) {
        if (up) {
            cfg.activated().broadcast(centre, 1.0f);
            pillars.forEach(StonePillar::flare);
            startAmbient();
        } else {
            cfg.lowered().broadcast(centre, 1.0f);
            pillars.forEach(pillar -> pillar.burst(14));
        }
    }

    private void startAmbient() {
        if (ambient != null || cfg.ambientParticles() <= 0) {
            return;
        }
        int every = cfg.ambientEveryTicks();
        ambient = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!progress.targetsUp() || pillars.isEmpty()) {
                cancelAmbient();
                return;
            }
            for (StonePillar pillar : pillars) {
                pillar.pulse(cfg.ambientParticles());
            }
        }, every, every);
    }

    private void cancelAnimation() {
        if (animation != null) {
            animation.cancel();
            animation = null;
        }
    }

    private void cancelLowerDelay() {
        if (lowerDelay != null) {
            lowerDelay.cancel();
            lowerDelay = null;
        }
    }

    private void cancelAmbient() {
        if (ambient != null) {
            ambient.cancel();
            ambient = null;
        }
    }

    // ------------------------------------------------------------------------------------------ chunk events

    void onChunkLoad(World loaded, int cx, int cz) {
        if (loaded.equals(world) && footprint.contains(key(cx, cz)) && pillars.isEmpty()) {
            // Next tick: let the chunk finish loading first.
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (pillars.isEmpty() && ensureSpawned()) {
                    rescan();
                }
            });
        }
    }

    void onChunkUnload(World unloaded, int cx, int cz) {
        if (unloaded.equals(world) && footprint.contains(key(cx, cz))) {
            despawn();
            inside.clear();
        }
    }

    void onWorldUnload(World unloaded) {
        if (unloaded.equals(world)) {
            shutdown();
        }
    }

    // ------------------------------------------------------------------------------------------------ status

    /** For /raceadmin altar info. */
    String status() {
        if (cfg == null || world == null) {
            return "off";
        }
        if (pillars.isEmpty()) {
            return "not spawned";
        }
        return progress.phaseAt(nowTicks()).name().toLowerCase(java.util.Locale.ROOT) + ", players near: "
                + inside.size();
    }
}
