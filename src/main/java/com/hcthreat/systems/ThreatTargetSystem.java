package com.hcthreat.systems;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import com.hcthreat.ThreatManager;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * Each tick: for each NPC with active threat, find highest-threat player
 * and override the NPC's target via MarkedEntitySupport.setMarkedEntity().
 */
public class ThreatTargetSystem extends EntityTickingSystem<EntityStore> {

    private final ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
    private final ComponentType<EntityStore, UUIDComponent> uuidType = UUIDComponent.getComponentType();
    private final ComponentType<EntityStore, TransformComponent> transformType = TransformComponent.getComponentType();

    private final Query<EntityStore> query = Archetype.of(npcType, uuidType, transformType);

    private volatile ThreatManager threatManager;

    public void initialize(ThreatManager threatManager) {
        this.threatManager = threatManager;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void tick(float deltaTime, int index,
                     @Nonnull ArchetypeChunk<EntityStore> chunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {

        if (threatManager == null) return;

        Ref<EntityStore> npcRef = chunk.getReferenceTo(index);
        if (npcRef == null || !npcRef.isValid()) return;

        // Get NPC UUID for threat lookup
        UUIDComponent uuidComp = store.getComponent(npcRef, uuidType);
        if (uuidComp == null) return;
        UUID npcUuid = uuidComp.getUuid();

        // O(1) early exit for non-combat NPCs
        if (!threatManager.hasThreat(npcUuid)) return;

        // Find highest-threat player
        UUID highestPlayerUuid = threatManager.getHighestThreatPlayer(npcUuid);
        if (highestPlayerUuid == null) return;

        // Resolve player UUID to entity ref
        Ref<EntityStore> playerRef = store.getExternalData().getRefFromUUID(highestPlayerUuid);
        if (playerRef == null || !playerRef.isValid()) {
            // Player left the world or disconnected - remove from threat table
            threatManager.removePlayerFromAll(highestPlayerUuid);
            return;
        }

        // Get the NPC's Role -> MarkedEntitySupport to override targeting
        NPCEntity npcEntity = store.getComponent(npcRef, npcType);
        if (npcEntity == null) return;

        Role role = npcEntity.getRole();
        if (role == null) return;

        MarkedEntitySupport markedEntitySupport = role.getMarkedEntitySupport();
        if (markedEntitySupport == null) return;

        // Set the highest-threat player as the locked target.
        // If the NPC role has no "LockedTarget" slot, this silently no-ops.
        markedEntitySupport.setMarkedEntity(MarkedEntitySupport.DEFAULT_TARGET_SLOT, playerRef);
    }
}
