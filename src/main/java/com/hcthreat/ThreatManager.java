package com.hcthreat;

import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Core threat data store. Thread-safe via ConcurrentHashMap.
 *
 * NPC UUID -> Map<Player UUID, threat amount>
 * Reverse index: Player UUID -> Set<NPC UUIDs> for efficient disconnect cleanup.
 */
public class ThreatManager {

    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("HC_Threat-Manager");

    /** Threat decay rate per second (fraction of current threat removed). */
    private static final float DECAY_RATE = 0.05f;

    /** Threat entries below this threshold are removed. */
    private static final float REMOVAL_THRESHOLD = 1.0f;

    /** NPC UUID -> (Player UUID -> threat value) */
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, Float>> threatTables = new ConcurrentHashMap<>();

    /** Player UUID -> Set of NPC UUIDs (reverse index for disconnect cleanup) */
    private final ConcurrentHashMap<UUID, Set<UUID>> playerToNPCs = new ConcurrentHashMap<>();

    /** NPC UUID -> cached highest-threat player UUID. Missing = needs recompute. */
    private final ConcurrentHashMap<UUID, UUID> highestThreatCache = new ConcurrentHashMap<>();

    /**
     * Add threat from a player to an NPC. Thread-safe.
     */
    public void addThreat(UUID npcUuid, UUID playerUuid, float amount) {
        if (amount <= 0) return;

        ConcurrentHashMap<UUID, Float> npcTable = threatTables.computeIfAbsent(npcUuid, k -> new ConcurrentHashMap<>());
        float newThreat = npcTable.merge(playerUuid, amount, Float::sum);

        // Update reverse index
        playerToNPCs.computeIfAbsent(playerUuid, k -> ConcurrentHashMap.newKeySet()).add(npcUuid);

        // O(1) cache update: adding threat can only make this player the new highest
        UUID cachedHighest = highestThreatCache.get(npcUuid);
        if (cachedHighest == null) {
            highestThreatCache.put(npcUuid, playerUuid);
        } else if (!cachedHighest.equals(playerUuid)) {
            Float cachedThreat = npcTable.get(cachedHighest);
            if (cachedThreat == null || newThreat >= cachedThreat) {
                highestThreatCache.put(npcUuid, playerUuid);
            }
        }
    }

    /**
     * Get threat value for a specific player on a specific NPC.
     */
    public float getThreat(UUID npcUuid, UUID playerUuid) {
        ConcurrentHashMap<UUID, Float> npcTable = threatTables.get(npcUuid);
        if (npcTable == null) return 0f;
        return npcTable.getOrDefault(playerUuid, 0f);
    }

    /**
     * Check if an NPC has any threat entries.
     */
    public boolean hasThreat(UUID npcUuid) {
        ConcurrentHashMap<UUID, Float> npcTable = threatTables.get(npcUuid);
        return npcTable != null && !npcTable.isEmpty();
    }

    /**
     * Get the player UUID with the highest threat on an NPC.
     * Returns null if no threat entries exist.
     */
    @Nullable
    public UUID getHighestThreatPlayer(UUID npcUuid) {
        ConcurrentHashMap<UUID, Float> npcTable = threatTables.get(npcUuid);
        if (npcTable == null || npcTable.isEmpty()) return null;

        // Return cached value if available and still valid
        UUID cached = highestThreatCache.get(npcUuid);
        if (cached != null && npcTable.containsKey(cached)) {
            return cached;
        }

        // Cache miss or stale — recompute and cache
        UUID highestPlayer = null;
        float highestThreat = 0f;

        for (Map.Entry<UUID, Float> entry : npcTable.entrySet()) {
            if (entry.getValue() > highestThreat) {
                highestThreat = entry.getValue();
                highestPlayer = entry.getKey();
            }
        }
        if (highestPlayer != null) {
            highestThreatCache.put(npcUuid, highestPlayer);
        }
        return highestPlayer;
    }

    /**
     * Get the full threat table for an NPC (for debug display).
     * Returns null if NPC has no threat entries.
     */
    @Nullable
    public ConcurrentHashMap<UUID, Float> getThreatTable(UUID npcUuid) {
        return threatTables.get(npcUuid);
    }

    /**
     * Clear all threat entries for an NPC (on death/despawn).
     */
    public void clearNPC(UUID npcUuid) {
        highestThreatCache.remove(npcUuid);
        ConcurrentHashMap<UUID, Float> removed = threatTables.remove(npcUuid);
        if (removed != null) {
            // Clean up reverse index
            for (UUID playerUuid : removed.keySet()) {
                Set<UUID> npcSet = playerToNPCs.get(playerUuid);
                if (npcSet != null) {
                    npcSet.remove(npcUuid);
                    if (npcSet.isEmpty()) {
                        playerToNPCs.remove(playerUuid);
                    }
                }
            }
        }
    }

    /**
     * Remove a player from all threat tables (on disconnect).
     * Uses the reverse index for O(n) where n = NPCs the player was fighting.
     */
    public void removePlayerFromAll(UUID playerUuid) {
        Set<UUID> npcUuids = playerToNPCs.remove(playerUuid);
        if (npcUuids == null) return;

        for (UUID npcUuid : npcUuids) {
            ConcurrentHashMap<UUID, Float> npcTable = threatTables.get(npcUuid);
            if (npcTable != null) {
                npcTable.remove(playerUuid);
                if (npcTable.isEmpty()) {
                    threatTables.remove(npcUuid);
                    highestThreatCache.remove(npcUuid);
                } else if (playerUuid.equals(highestThreatCache.get(npcUuid))) {
                    // Removed player was the highest — invalidate so next access recomputes
                    highestThreatCache.remove(npcUuid);
                }
            }
        }
    }

    /**
     * Decay all threat values. Called periodically from ScheduledExecutor.
     * Removes entries that fall below the threshold.
     *
     * @param deltaSeconds time since last decay call
     */
    public void decayAll(float deltaSeconds) {
        float decayMultiplier = 1.0f - (DECAY_RATE * deltaSeconds);

        threatTables.forEach((npcUuid, npcTable) -> {
            // Track highest-threat player during iteration (free — already visiting every entry)
            UUID newHighest = null;
            float highestThreat = 0f;

            var it = npcTable.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                float newThreat = entry.getValue() * decayMultiplier;
                if (newThreat < REMOVAL_THRESHOLD) {
                    // Clean up reverse index for this player-NPC pair
                    Set<UUID> npcSet = playerToNPCs.get(entry.getKey());
                    if (npcSet != null) {
                        npcSet.remove(npcUuid);
                        if (npcSet.isEmpty()) {
                            playerToNPCs.remove(entry.getKey());
                        }
                    }
                    it.remove();
                } else {
                    entry.setValue(newThreat);
                    if (newThreat > highestThreat) {
                        highestThreat = newThreat;
                        newHighest = entry.getKey();
                    }
                }
            }

            // Update cache and remove empty tables
            if (npcTable.isEmpty()) {
                threatTables.remove(npcUuid);
                highestThreatCache.remove(npcUuid);
            } else if (newHighest != null) {
                highestThreatCache.put(npcUuid, newHighest);
            }
        });
    }

    /**
     * Clear all threat data.
     */
    public void clearAll() {
        threatTables.clear();
        playerToNPCs.clear();
        highestThreatCache.clear();
    }

    /**
     * Get total number of NPCs with active threat tables (for debug).
     */
    public int getActiveNPCCount() {
        return threatTables.size();
    }
}
