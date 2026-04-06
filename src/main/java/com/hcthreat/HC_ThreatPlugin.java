package com.hcthreat;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hcthreat.commands.ThreatCommand;
import com.hcthreat.systems.ThreatCleanupSystem;
import com.hcthreat.systems.ThreatDamageSystem;
import com.hcthreat.systems.ThreatTargetSystem;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * HC_Threat - Persistent cumulative threat table for NPC targeting.
 *
 * Adds a threat/aggro system so NPCs target the player with the most
 * cumulative damage rather than just the most recent attacker.
 * Enables tank/DPS/healer dynamics in multi-player combat.
 */
public class HC_ThreatPlugin extends JavaPlugin {

    public static final String VERSION = "1.0.0";

    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("HC_Threat");

    private static volatile HC_ThreatPlugin instance;

    private ThreatManager threatManager;
    private ScheduledFuture<?> decayTask;

    // Systems (kept for reference)
    private ThreatDamageSystem threatDamageSystem;
    private ThreatTargetSystem threatTargetSystem;
    private ThreatCleanupSystem threatCleanupSystem;

    public HC_ThreatPlugin(@NonNullDecl JavaPluginInit init) {
        super(init);
    }

    @Override
    public void setup() {
        instance = this;

        // Initialize core data
        threatManager = new ThreatManager();

        // Create and initialize systems
        threatDamageSystem = new ThreatDamageSystem();
        threatDamageSystem.initialize(threatManager);

        threatTargetSystem = new ThreatTargetSystem();
        threatTargetSystem.initialize(threatManager);

        threatCleanupSystem = new ThreatCleanupSystem();
        threatCleanupSystem.initialize(threatManager);

        // Register systems
        getEntityStoreRegistry().registerSystem(threatDamageSystem);
        getEntityStoreRegistry().registerSystem(threatTargetSystem);
        getEntityStoreRegistry().registerSystem(threatCleanupSystem);

        // Register commands
        getCommandRegistry().registerCommand(new ThreatCommand(this));

        // Register disconnect handler for cleanup
        getEventRegistry().register(PlayerDisconnectEvent.class, event -> {
            threatManager.removePlayerFromAll(event.getPlayerRef().getUuid());
        });

        LOGGER.at(Level.INFO).log("HC_Threat v%s setup complete", VERSION);
    }

    @Override
    public void start() {
        // Start decay task: runs every 1 second, decays threat by 5%/sec
        decayTask = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(
            () -> {
                try {
                    threatManager.decayAll(1.0f);
                } catch (Exception e) {
                    LOGGER.at(Level.WARNING).log("Threat decay task failed: %s", e.getMessage());
                }
            },
            1, 1, TimeUnit.SECONDS
        );

        LOGGER.at(Level.INFO).log("HC_Threat v%s started - decay task active", VERSION);
    }

    @Override
    public void shutdown() {
        // Cancel decay task
        if (decayTask != null) {
            decayTask.cancel(false);
            decayTask = null;
        }

        // Clear all data
        if (threatManager != null) {
            threatManager.clearAll();
        }

        instance = null;
        LOGGER.at(Level.INFO).log("HC_Threat v%s shutdown", VERSION);
    }

    public ThreatManager getThreatManager() {
        return threatManager;
    }

    public static HC_ThreatPlugin getInstance() {
        return instance;
    }
}
