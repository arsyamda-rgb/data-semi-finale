package com.veyrion.character.core;

import com.veyrion.character.awakening.AwakeningListener;
import com.veyrion.character.awakening.AwakeningService;
import com.veyrion.character.character.CharacterRepository;
import com.veyrion.character.character.ExpResult;
import com.veyrion.character.character.ExpService;
import com.veyrion.character.command.BalTopCommand;
import com.veyrion.character.command.BalanceCommand;
import com.veyrion.character.command.CharacterCommand;
import com.veyrion.character.command.EconomyAdminCommand;
import com.veyrion.character.command.PayCommand;
import com.veyrion.character.command.QuestAdminCommand;
import com.veyrion.character.command.QuestCommand;
import com.veyrion.character.command.RaceAdminCommand;
import com.veyrion.character.command.RaceCommand;
import com.veyrion.character.config.ConfigManager;
import com.veyrion.character.config.Messages;
import com.veyrion.character.database.DatabaseException;
import com.veyrion.character.database.DatabaseManager;
import com.veyrion.character.economy.BalTop;
import com.veyrion.character.economy.EconomyBridge;
import com.veyrion.character.economy.EconomyService;
import com.veyrion.character.economy.NoEconomyBridge;
import com.veyrion.character.economy.PendingVyrRepository;
import com.veyrion.character.economy.VaultEconomyBridge;
import com.veyrion.character.economy.VyrPayouts;
import com.veyrion.character.integration.CharacterCache;
import com.veyrion.character.integration.PlaceholderApiHook;
import com.veyrion.character.integration.PlaceholderResolver;
import com.veyrion.character.integration.PopulationCache;
import com.veyrion.character.listener.EconomyListener;
import com.veyrion.character.listener.PlayerJoinListener;
import com.veyrion.character.listener.QuestListener;
import com.veyrion.character.quest.QuestRegistry;
import com.veyrion.character.quest.QuestRepository;
import com.veyrion.character.quest.QuestService;
import com.veyrion.character.race.RaceDefinition;
import com.veyrion.character.race.RaceRegistry;
import com.veyrion.character.race.RaceRepository;
import com.veyrion.character.race.RaceService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class VeyrionCharacterPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private Messages messages;
    private DatabaseManager databaseManager;
    private CharacterRepository characterRepository;
    private ExpService expService;
    private QuestRegistry questRegistry;
    private QuestService questService;
    private RaceRegistry raceRegistry;
    private RaceService raceService;
    private AwakeningService awakeningService;
    private CharacterCache characterCache;
    private PopulationCache populationCache;
    private EconomyService economyService;
    private VyrPayouts vyrPayouts;
    /** Null unless PlaceholderAPI is installed. Typed as AutoCloseable so this class never links to PlaceholderAPI. */
    private AutoCloseable placeholderHook;

    @Override
    public void onEnable() {
        configManager = new ConfigManager(this);
        configManager.load();

        messages = new Messages(this);
        messages.load(configManager.settings().language());

        raceRegistry = new RaceRegistry(this);
        raceRegistry.load();

        if (!startDatabase()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        expService = new ExpService(characterRepository, () -> configManager.settings().ranks());

        // Economy is optional: it is built even when Vault is missing, and then simply reports "unavailable".
        economyService = new EconomyService(getLogger(), () -> configManager.settings().economy(), createEconomyBridge());
        vyrPayouts = new VyrPayouts(this, economyService, new PendingVyrRepository(databaseManager));

        questRegistry = new QuestRegistry(this);
        questRegistry.reload();
        questService = new QuestService(this, questRegistry, new QuestRepository(databaseManager),
                characterRepository, expService, vyrPayouts);

        raceService = new RaceService(this, raceRegistry, new RaceRepository(databaseManager), characterRepository);

        characterCache = new CharacterCache(characterRepository::find, System::currentTimeMillis,
                error -> getLogger().log(Level.WARNING, "Could not refresh cached character data", error));
        populationCache = new PopulationCache(raceService::population,
                error -> getLogger().log(Level.WARNING, "Could not refresh cached population data", error));
        raceService.addRaceChangeListener(uuid -> {
            characterCache.refresh(uuid);
            populationCache.refresh();
        });

        awakeningService = new AwakeningService(this, raceService);
        awakeningService.start();

        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this, characterRepository, characterCache, populationCache), this);
        getServer().getPluginManager().registerEvents(new AwakeningListener(this, awakeningService), this);
        getServer().getPluginManager().registerEvents(new QuestListener(this, questService), this);
        getServer().getPluginManager().registerEvents(new EconomyListener(this, economyService, vyrPayouts), this);

        register("veyrioncharacter", new CharacterCommand(this, characterRepository, expService));
        register("quest", new QuestCommand(this, questService));
        register("questadmin", new QuestAdminCommand(this, questService));
        register("race", new RaceCommand(this, raceService));
        register("raceadmin", new RaceAdminCommand(this, raceService, awakeningService));
        BalTop balTop = new BalTop(this, economyService, characterRepository::identities);
        register("bayar", new PayCommand(this, economyService));
        register("saldo", new BalanceCommand(this, economyService));
        register("topuang", new BalTopCommand(this, economyService, balTop));
        register("veconomy", new EconomyAdminCommand(this, economyService));

        long interval = configManager.settings().quests().exploreCheckIntervalTicks();
        getServer().getScheduler().runTaskTimer(this, questService::exploreTick, interval, interval);

        // Players already online (e.g. after /reload) are not covered by the join event.
        getServer().getOnlinePlayers().forEach(questService::load);
        getServer().getOnlinePlayers().forEach(player -> {
            characterCache.track(player.getUniqueId());
            characterCache.refresh(player.getUniqueId());
        });
        populationCache.refresh();
        // Safety net for changes made outside this plugin (e.g. someone editing the database by hand).
        long safety = 20L * 60 * 5;
        getServer().getScheduler().runTaskTimer(this, populationCache::refresh, safety, safety);

        enablePlaceholderApi();
        enableEconomyTasks();

        getLogger().info("VeyrionCharacter " + getPluginMeta().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        if (placeholderHook != null) {
            try {
                placeholderHook.close();
            } catch (Exception | LinkageError e) {
                getLogger().log(Level.WARNING, "Could not unregister the PlaceholderAPI expansion", e);
            }
            placeholderHook = null;
        }
        // End rituals first: players get their movement back and the already stored race is kept.
        if (awakeningService != null) {
            awakeningService.shutdown();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    /** Registers the PlaceholderAPI expansion if (and only if) PlaceholderAPI is enabled. Never throws. */
    private void enablePlaceholderApi() {
        if (!getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            getLogger().info("PlaceholderAPI not found; placeholder integration is unavailable.");
            return;
        }
        try {
            PlaceholderResolver resolver = new PlaceholderResolver(characterCache, populationCache, id -> {
                RaceDefinition race = raceRegistry.settings().find(id);
                return race != null ? race.displayName() : id;
            }, economyService::balanceForPlaceholder, economyService::format);
            PlaceholderApiHook hook = new PlaceholderApiHook(this, resolver);
            if (hook.register()) {
                placeholderHook = hook;
                getLogger().info("PlaceholderAPI expansion registered (%veyrioncharacter_...%).");
            } else {
                getLogger().warning("PlaceholderAPI refused the 'veyrioncharacter' expansion (identifier already in use?).");
            }
        } catch (RuntimeException | LinkageError e) {
            getLogger().log(Level.WARNING, "PlaceholderAPI integration failed; continuing without it.", e);
        }
    }

    /** Vault is a soft dependency: link to it only if it is enabled, and never let a linking problem escape. */
    private EconomyBridge createEconomyBridge() {
        if (!getServer().getPluginManager().isPluginEnabled("Vault")) {
            return NoEconomyBridge.INSTANCE;
        }
        try {
            return new VaultEconomyBridge(getServer().getServicesManager());
        } catch (RuntimeException | LinkageError e) {
            getLogger().log(Level.WARNING, "Vault is installed but its economy API could not be used; "
                    + "economy features are disabled.", e);
            return NoEconomyBridge.INSTANCE;
        }
    }

    private void enableEconomyTasks() {
        // One tick later every plugin has finished enabling, so economy providers are registered and the report is true.
        getServer().getScheduler().runTask(this, () -> {
            getLogger().info("Veyra economy: " + economyService.describe());
            vyrPayouts.reportAmbiguous();
            vyrPayouts.flush(null);
        });
        // Keeps the balance copy used by async placeholder requests fresh (5 s) and retries undelivered VYR (5 min).
        getServer().getScheduler().runTaskTimer(this, economyService::refreshOnline, 100L, 100L);
        long retry = 20L * 60 * 5;
        getServer().getScheduler().runTaskTimer(this, () -> vyrPayouts.flush(null), retry, retry);
    }

    /** Re-reads config.yml and language files and re-checks the economy provider. Never touches stored data. */
    public void reloadEconomy() {
        configManager.load();
        messages.load(configManager.settings().language());
        getLogger().info("Veyra economy: " + economyService.describe());
        vyrPayouts.flush(null);
    }

    private void register(String name, TabExecutor handler) {
        var command = Objects.requireNonNull(getCommand(name), "Command '" + name + "' is missing from plugin.yml");
        command.setExecutor(handler);
        command.setTabCompleter(handler);
    }

    private boolean startDatabase() {
        var settings = configManager.settings().database();
        try {
            Path dataFolder = getDataFolder().toPath();
            Files.createDirectories(dataFolder);
            databaseManager = new DatabaseManager(getLogger(), dataFolder.resolve(settings.file()),
                    settings.busyTimeoutMs());
            databaseManager.initialize();
            characterRepository = new CharacterRepository(databaseManager);
            return true;
        } catch (IOException | DatabaseException e) {
            getLogger().log(Level.SEVERE, "Could not start the database. Disabling VeyrionCharacter.", e);
            return false;
        }
    }

    /** Re-reads config.yml, language files, races.yml and quest files. Never touches stored character data. */
    public void reloadSettings() {
        configManager.load();
        messages.load(configManager.settings().language());
        raceRegistry.load();
        questRegistry.reload();
        if (populationCache != null) {
            populationCache.refresh(); // races.yml may have added or removed races
        }
        if (awakeningService != null) {
            awakeningService.reload();
        }
        if (economyService != null) {
            getLogger().info("Veyra economy: " + economyService.describe());
        }
    }

    /** Tells an online player about gained EXP and rank-ups. Safe to call from any thread. */
    public void announceExp(UUID uuid, ExpResult result, long gained) {
        if (!isEnabled()) {
            return;
        }
        // Every EXP/rank change (command and quest rewards) is announced here, so this is the one place that
        // keeps the cached copy in step. The read happens in the background after the write has committed.
        if (characterCache != null) {
            characterCache.refresh(uuid);
        }
        getServer().getScheduler().runTask(this, () -> {
            Player player = getServer().getPlayer(uuid);
            if (player == null) {
                return;
            }
            messages.send(player, "exp.gain", Placeholder.unparsed("amount", Long.toString(gained)));
            if (result.rankedUp()) {
                messages.send(player, "rank.up", Placeholder.unparsed("rank", result.newRank()));
            }
        });
    }

    /** Quest VYR payout/reconciliation service; null only before the plugin finished enabling. */
    public VyrPayouts vyrPayouts() {
        return vyrPayouts;
    }

    public ConfigManager configManager() {
        return configManager;
    }

    public Messages messages() {
        return messages;
    }

    public RaceRegistry races() {
        return raceRegistry;
    }

    /** The Rift Awakening altar system; null only before the plugin finished enabling. */
    public AwakeningService awakening() {
        return awakeningService;
    }

    public void debug(String message) {
        if (configManager.settings().debug()) {
            getLogger().info("[DEBUG] " + message);
        }
    }
}
