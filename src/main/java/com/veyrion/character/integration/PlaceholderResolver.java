package com.veyrion.character.integration;

import com.veyrion.character.character.CharacterData;
import com.veyrion.character.economy.Money;
import com.veyrion.character.race.PopulationReport;
import java.util.Locale;
import java.util.UUID;
import java.util.function.DoubleFunction;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Turns a placeholder name into text using only the caches. Pure Java: no Bukkit, no PlaceholderAPI, no I/O,
 * so it is safe on any thread and unit-testable.
 *
 * <p>Fallbacks (character missing, not loaded yet, or no race): name = the given fallback name or empty,
 * race = {@value #NONE}, exp = 0, rank = {@value #NONE}, has_race = false. Balances are {@value #UNAVAILABLE}
 * when the economy is unavailable. Population numbers are 0 until the
 * first report is available. An unknown placeholder name returns null so PlaceholderAPI leaves it untouched.
 */
public final class PlaceholderResolver {

    public static final String NONE = "None";
    public static final String UNAVAILABLE = "N/A";
    private static final String POPULATION_PREFIX = "population_";
    private static final Pattern RACE_ID = Pattern.compile("[a-z0-9_-]{1,32}");

    private final CharacterCache characters;
    private final PopulationCache population;
    private final Function<String, String> raceDisplayName;
    private final Function<UUID, Double> balances;
    private final DoubleFunction<String> moneyFormat;

    /** @param raceDisplayName race id to display name (e.g. "dragon" to "Dragon") */
    public PlaceholderResolver(CharacterCache characters, PopulationCache population,
                               Function<String, String> raceDisplayName) {
        this(characters, population, raceDisplayName, uuid -> null, value -> Double.toString(value));
    }

    /**
     * @param balances    the player's balance from the economy provider (never blocks), or null if unavailable
     * @param moneyFormat formats an amount for display, e.g. "2,500 VYR"
     */
    public PlaceholderResolver(CharacterCache characters, PopulationCache population,
                               Function<String, String> raceDisplayName, Function<UUID, Double> balances,
                               DoubleFunction<String> moneyFormat) {
        this.characters = characters;
        this.population = population;
        this.raceDisplayName = raceDisplayName;
        this.balances = balances;
        this.moneyFormat = moneyFormat;
    }

    /**
     * @param uuid         the player, or null if PlaceholderAPI gave none
     * @param fallbackName name to show when no character data is available
     * @param params       the part after {@code %veyrioncharacter_}
     * @return the text, or null if the placeholder does not exist
     */
    public @Nullable String resolve(@Nullable UUID uuid, @Nullable String fallbackName, String params) {
        String key = params.toLowerCase(Locale.ROOT);
        if (key.startsWith(POPULATION_PREFIX)) {
            return population(key.substring(POPULATION_PREFIX.length()));
        }
        if (key.equals("balance") || key.equals("balance_formatted")) {
            return balance(uuid, key.equals("balance_formatted"));
        }
        switch (key) {
            case "name", "race", "exp", "rank", "has_race" -> { }
            default -> {
                return null;
            }
        }

        CharacterData data = null;
        if (uuid != null) {
            CharacterCache.Entry entry = characters.get(uuid);
            data = entry == null ? null : entry.data();
        }
        return switch (key) {
            case "name" -> data != null ? data.playerName() : fallbackName == null ? "" : fallbackName;
            case "race" -> data != null && data.hasRace() ? raceDisplayName.apply(data.race()) : NONE;
            case "exp" -> Long.toString(data != null ? data.adventureExp() : 0L);
            case "rank" -> data != null ? data.adventureRank() : NONE;
            default -> Boolean.toString(data != null && data.hasRace()); // has_race
        };
    }

    /** "N/A" when there is no player or the economy is unavailable/not loaded yet (never a misleading 0). */
    private String balance(@Nullable UUID uuid, boolean formatted) {
        Double value = uuid == null ? null : balances.apply(uuid);
        if (value == null || !Double.isFinite(value)) {
            return UNAVAILABLE;
        }
        return formatted ? moneyFormat.apply(value) : Money.plain(value);
    }

    private @Nullable String population(String key) {
        PopulationReport report = population.report();
        switch (key) {
            case "total" -> {
                return Long.toString(report == null ? 0 : report.total());
            }
            case "unassigned" -> {
                return Long.toString(report == null ? 0 : report.unassigned());
            }
            case "assigned" -> {
                return Long.toString(report == null ? 0 : report.assigned());
            }
            case "legacy" -> {
                return Long.toString(report == null ? 0 : report.legacy());
            }
            default -> {
                if (!RACE_ID.matcher(key).matches()) {
                    return null;
                }
                if (report == null) {
                    return "0";
                }
                for (PopulationReport.RaceRow row : report.races()) {
                    if (row.id().equals(key)) {
                        return Long.toString(row.count());
                    }
                }
                for (PopulationReport.LegacyRow row : report.legacyRaces()) {
                    if (row.id().equals(key)) {
                        return Long.toString(row.count());
                    }
                }
                return "0"; // a valid id nobody holds (e.g. removed from races.yml)
            }
        }
    }
}
