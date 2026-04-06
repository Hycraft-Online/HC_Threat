package com.hcthreat.systems;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.RootDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hcattributes.api.HC_AttributesAPI;
import com.hcthreat.ThreatManager;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Listens for damage events on NPCs and feeds the ThreatManager.
 *
 * Runs in the InspectDamageGroup (after damage is finalized) so we capture
 * post-mitigation damage amounts from AttributeDamageModifierSystem.
 */
public class ThreatDamageSystem extends DamageEventSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("HC_Threat-Damage");
    private static final String THREAT_GENERATION_PASSIVE = "threat_generation";

    private volatile ThreatManager threatManager;

    public void initialize(ThreatManager threatManager) {
        this.threatManager = threatManager;
    }

    @Override
    public void handle(int index, @NonNullDecl ArchetypeChunk<EntityStore> archetypeChunk,
                       @NonNullDecl Store<EntityStore> store,
                       @NonNullDecl CommandBuffer<EntityStore> commandBuffer,
                       @NonNullDecl Damage damage) {

        if (threatManager == null) return;

        // Get victim ref
        Ref<EntityStore> victimRef = archetypeChunk.getReferenceTo(index);
        if (victimRef == null || !victimRef.isValid()) return;

        // Only track threat on NPCs
        NPCEntity victimNPC = store.getComponent(victimRef, NPCEntity.getComponentType());
        if (victimNPC == null) return;

        // Get NPC UUID
        UUIDComponent victimUuid = store.getComponent(victimRef, UUIDComponent.getComponentType());
        if (victimUuid == null) return;

        // Get attacker ref from damage source
        Ref<EntityStore> attackerRef = null;
        if (damage.getSource() instanceof Damage.EntitySource entitySource) {
            attackerRef = entitySource.getRef();
        } else if (damage.getSource() instanceof Damage.ProjectileSource projectileSource) {
            attackerRef = projectileSource.getRef();
        }

        if (attackerRef == null || !attackerRef.isValid()) return;

        // Only track threat from players
        PlayerRef attackerPlayerRef = commandBuffer.getComponent(attackerRef, PlayerRef.getComponentType());
        if (attackerPlayerRef == null) {
            attackerPlayerRef = store.getComponent(attackerRef, PlayerRef.getComponentType());
        }
        if (attackerPlayerRef == null) return;

        float damageAmount = damage.getAmount();
        if (damageAmount <= 0) return;

        UUID npcUuid = victimUuid.getUuid();
        UUID playerUuid = attackerPlayerRef.getUuid();

        // Check for threat generation passive (+20% per rank, max 5 ranks = +100%)
        float threatAmount = damageAmount;
        try {
            if (HC_AttributesAPI.isAvailable()) {
                int rank = HC_AttributesAPI.getPassiveRank(playerUuid, THREAT_GENERATION_PASSIVE);
                if (rank > 0) {
                    threatAmount *= 1.0f + (0.20f * rank);
                }
            }
        } catch (NoClassDefFoundError ignored) {
            // HC_Attributes not loaded
        }

        threatManager.addThreat(npcUuid, playerUuid, threatAmount);
    }

    @Nullable
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getInspectDamageGroup();
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        // Match all entities that can take damage (NPCs filtered in handle())
        return NPCEntity.getComponentType();
    }

    @NonNullDecl
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return Collections.singleton(RootDependency.first());
    }
}
