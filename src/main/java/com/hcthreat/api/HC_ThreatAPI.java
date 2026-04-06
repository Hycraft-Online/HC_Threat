package com.hcthreat.api;

import com.hcthreat.HC_ThreatPlugin;
import com.hcthreat.ThreatManager;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Static API for other plugins to interact with the threat system.
 *
 * Usage:
 *   if (HC_ThreatAPI.isAvailable()) {
 *       HC_ThreatAPI.addThreat(npcUuid, playerUuid, 50f);
 *       UUID topThreat = HC_ThreatAPI.getHighestThreatPlayer(npcUuid);
 *   }
 */
public final class HC_ThreatAPI {

    private HC_ThreatAPI() {} // Static-only

    /**
     * Check if the threat system is loaded and available.
     */
    public static boolean isAvailable() {
        return HC_ThreatPlugin.getInstance() != null
            && HC_ThreatPlugin.getInstance().getThreatManager() != null;
    }

    /**
     * Add threat from a player to an NPC.
     */
    public static void addThreat(UUID npcUuid, UUID playerUuid, float amount) {
        ThreatManager tm = getManager();
        if (tm != null) tm.addThreat(npcUuid, playerUuid, amount);
    }

    /**
     * Get the current threat value for a player on an NPC.
     */
    public static float getThreat(UUID npcUuid, UUID playerUuid) {
        ThreatManager tm = getManager();
        return tm != null ? tm.getThreat(npcUuid, playerUuid) : 0f;
    }

    /**
     * Get the player UUID with the highest threat on an NPC.
     */
    @Nullable
    public static UUID getHighestThreatPlayer(UUID npcUuid) {
        ThreatManager tm = getManager();
        return tm != null ? tm.getHighestThreatPlayer(npcUuid) : null;
    }

    /**
     * Check if an NPC has any active threat entries.
     */
    public static boolean hasThreat(UUID npcUuid) {
        ThreatManager tm = getManager();
        return tm != null && tm.hasThreat(npcUuid);
    }

    /**
     * Clear all threat entries for an NPC.
     */
    public static void clearNPC(UUID npcUuid) {
        ThreatManager tm = getManager();
        if (tm != null) tm.clearNPC(npcUuid);
    }

    @Nullable
    private static ThreatManager getManager() {
        HC_ThreatPlugin instance = HC_ThreatPlugin.getInstance();
        return instance != null ? instance.getThreatManager() : null;
    }
}
