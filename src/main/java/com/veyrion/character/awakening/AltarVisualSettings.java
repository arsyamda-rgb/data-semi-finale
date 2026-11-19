package com.veyrion.character.awakening;

import java.util.List;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;

/**
 * The {@code altar-visual} section of awakening.yml: four stone pillars around the altar that rise when a
 * player is near. Purely visual; nothing here touches the race roll.
 *
 * @param radius           a player this close (blocks, from the altar centre) raises the pillars
 * @param exitMargin       extra blocks a player must move away before they stop counting (stops flicker)
 * @param lowerDelayTicks  wait after the last player left before the pillars start to lower
 * @param stoneDistance    distance from the altar centre to each pillar centre
 * @param angleOffset      degrees; 45 puts the pillars on the diagonals
 * @param baseOffsetY      pillar base height relative to the altar's top surface
 * @param riseTicks        ticks a full rise takes
 * @param lowerTicks       ticks a full descent takes
 * @param segments         animation keyframes per motion (more = smoother easing, more packets)
 * @param stumpHeight      shaft height of an idle stone
 * @param pillarHeight     shaft height of an active pillar
 * @param viewRange        display view range multiplier (1.0 = 64 blocks)
 * @param ambientEveryTicks ticks between ambient particle pulses while active
 * @param ambientParticles  particles per pillar per pulse (0 = none)
 */
public record AltarVisualSettings(
        boolean enabled,
        double radius,
        double exitMargin,
        int lowerDelayTicks,
        double stoneDistance,
        double angleOffset,
        double baseOffsetY,
        int riseTicks,
        int lowerTicks,
        int segments,
        double stumpHeight,
        double pillarHeight,
        float viewRange,
        int ambientEveryTicks,
        int ambientParticles,
        SoundSpec riseStart,
        SoundSpec motionStep,
        SoundSpec activated,
        SoundSpec lowered,
        List<Stone> stones) {

    /** One pillar. {@code color} tints particles; the blocks are the accent materials of rings and gem. */
    public record Stone(String raceId, TextColor color, Material accent, Material gem, String rune) {}
}
