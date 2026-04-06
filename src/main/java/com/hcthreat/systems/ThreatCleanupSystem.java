package com.hcthreat.systems;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hcthreat.ThreatManager;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Clears the threat table when an NPC dies.
 * Extends DeathSystems.OnDeathSystem which fires when DeathComponent is added.
 */
public class ThreatCleanupSystem extends DeathSystems.OnDeathSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("HC_Threat-Cleanup");

    private final ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
    private final ComponentType<EntityStore, UUIDComponent> uuidType = UUIDComponent.getComponentType();

    private volatile ThreatManager threatManager;

    public void initialize(ThreatManager threatManager) {
        this.threatManager = threatManager;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return DeathComponent.getComponentType();
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return Set.of(
            new SystemDependency<>(Order.AFTER, DeathSystems.DropPlayerDeathItems.class)
        );
    }

    @Override
    public void onComponentAdded(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull DeathComponent component,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        if (threatManager == null) return;

        // Only care about NPC deaths
        NPCEntity npcEntity = store.getComponent(ref, npcType);
        if (npcEntity == null) return;

        // Get NPC UUID
        UUIDComponent uuidComp = store.getComponent(ref, uuidType);
        if (uuidComp == null) return;

        UUID npcUuid = uuidComp.getUuid();
        threatManager.clearNPC(npcUuid);

        LOGGER.at(Level.FINE).log("Cleared threat table for dead NPC %s", npcUuid);
    }
}
