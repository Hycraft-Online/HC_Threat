package com.hcthreat.commands;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hcthreat.HC_ThreatPlugin;
import com.hcthreat.ThreatManager;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import java.awt.Color;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Debug command for inspecting and manipulating threat tables.
 *
 * /threat table - shows threat table for nearest NPC
 * /threat clear - clears all threat tables
 * /threat add <amount> - adds threat on nearest NPC (for testing)
 * /threat info - shows global threat stats
 */
public class ThreatCommand extends AbstractAsyncCommand {

    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("HC_Threat-Cmd");
    private static final double NPC_SEARCH_RADIUS = 20.0;

    private static final Query<EntityStore> NPC_QUERY = Archetype.of(
        NPCEntity.getComponentType(),
        UUIDComponent.getComponentType(),
        TransformComponent.getComponentType()
    );

    private final HC_ThreatPlugin plugin;

    public ThreatCommand(HC_ThreatPlugin plugin) {
        super("threat", "Threat table debug commands");
        this.setAllowsExtraArguments(true);
        this.requirePermission("*");
        this.plugin = plugin;
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    @NonNullDecl
    @Override
    protected CompletableFuture<Void> executeAsync(@NonNullDecl CommandContext ctx) {
        CommandSender sender = ctx.sender();

        if (!(sender instanceof Player player)) {
            sender.sendMessage(Message.raw("This command can only be used by players."));
            return CompletableFuture.completedFuture(null);
        }

        String inputString = ctx.getInputString();
        String[] parts = inputString.split("\\s+");
        String[] args = parts.length > 1 ? Arrays.copyOfRange(parts, 1, parts.length) : new String[]{};

        if (args.length == 0) {
            sendUsage(sender);
            return CompletableFuture.completedFuture(null);
        }

        String subcommand = args[0].toLowerCase();

        return switch (subcommand) {
            case "table" -> handleTable(player, sender);
            case "clear" -> handleClear(sender);
            case "add" -> handleAdd(player, sender, args);
            case "info" -> handleInfo(sender);
            default -> {
                sendUsage(sender);
                yield CompletableFuture.completedFuture(null);
            }
        };
    }

    private CompletableFuture<Void> handleTable(Player player, CommandSender sender) {
        ThreatManager tm = plugin.getThreatManager();

        Ref<EntityStore> playerEntityRef = player.getReference();
        if (playerEntityRef == null || !playerEntityRef.isValid()) {
            sender.sendMessage(Message.raw("Could not get your entity reference."));
            return CompletableFuture.completedFuture(null);
        }

        World world = player.getWorld();
        if (world == null) {
            sender.sendMessage(Message.raw("Could not get your world."));
            return CompletableFuture.completedFuture(null);
        }

        world.execute(() -> {
            Store<EntityStore> store = world.getEntityStore().getStore();

            TransformComponent playerTransform = store.getComponent(playerEntityRef, TransformComponent.getComponentType());
            if (playerTransform == null) {
                sender.sendMessage(Message.raw("Could not get your position."));
                return;
            }
            Vector3d playerPos = playerTransform.getPosition();

            // Find nearest NPC with threat
            final UUID[] nearestNpcUuid = {null};
            final String[] nearestNpcName = {null};
            final double[] nearestDist = {Double.MAX_VALUE};

            store.forEachChunk(NPC_QUERY, (chunk, cmdBuffer) -> {
                for (int i = 0; i < chunk.size(); i++) {
                    Ref<EntityStore> ref = chunk.getReferenceTo(i);
                    if (ref == null || !ref.isValid()) continue;

                    UUIDComponent uuidComp = store.getComponent(ref, UUIDComponent.getComponentType());
                    if (uuidComp == null) continue;

                    if (!tm.hasThreat(uuidComp.getUuid())) continue;

                    TransformComponent npcTransform = store.getComponent(ref, TransformComponent.getComponentType());
                    if (npcTransform == null) continue;

                    double dist = playerPos.distanceTo(npcTransform.getPosition());
                    if (dist < nearestDist[0] && dist < NPC_SEARCH_RADIUS) {
                        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
                        if (npc == null) continue;

                        nearestDist[0] = dist;
                        nearestNpcUuid[0] = uuidComp.getUuid();
                        nearestNpcName[0] = npc.getRoleName();
                    }
                }
            });

            if (nearestNpcUuid[0] == null) {
                sender.sendMessage(Message.raw("No NPC with active threat within " + (int) NPC_SEARCH_RADIUS + " blocks."));
                return;
            }

            ConcurrentHashMap<UUID, Float> table = tm.getThreatTable(nearestNpcUuid[0]);
            if (table == null || table.isEmpty()) {
                sender.sendMessage(Message.raw("Threat table is empty for " + nearestNpcName[0] + "."));
                return;
            }

            sender.sendMessage(Message.raw("--- Threat Table: " + nearestNpcName[0] + " ---").color(Color.YELLOW));

            for (Map.Entry<UUID, Float> entry : table.entrySet()) {
                PlayerRef target = Universe.get().getPlayer(entry.getKey());
                String name = target != null ? target.getUsername() : entry.getKey().toString().substring(0, 8);
                String line = String.format("  %s: %.1f", name, entry.getValue());
                sender.sendMessage(Message.raw(line));
            }

            sender.sendMessage(Message.raw("Distance: " + String.format("%.1f", nearestDist[0]) + " blocks").color(Color.GRAY));
        });

        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> handleClear(CommandSender sender) {
        plugin.getThreatManager().clearAll();
        sender.sendMessage(Message.raw("All threat tables cleared.").color(Color.GREEN));
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> handleAdd(Player player, CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Message.raw("Usage: /threat add <amount>"));
            return CompletableFuture.completedFuture(null);
        }

        float amount;
        try {
            amount = Float.parseFloat(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(Message.raw("Invalid amount: " + args[1]));
            return CompletableFuture.completedFuture(null);
        }

        Ref<EntityStore> playerEntityRef = player.getReference();
        if (playerEntityRef == null || !playerEntityRef.isValid()) {
            sender.sendMessage(Message.raw("Could not get your entity reference."));
            return CompletableFuture.completedFuture(null);
        }

        World world = player.getWorld();
        if (world == null) {
            sender.sendMessage(Message.raw("Could not get your world."));
            return CompletableFuture.completedFuture(null);
        }

        world.execute(() -> {
            Store<EntityStore> store = world.getEntityStore().getStore();

            TransformComponent playerTransform = store.getComponent(playerEntityRef, TransformComponent.getComponentType());
            if (playerTransform == null) return;
            Vector3d playerPos = playerTransform.getPosition();

            // Find nearest NPC (any NPC, not just ones with threat)
            final UUID[] nearestNpcUuid = {null};
            final String[] nearestNpcName = {null};
            final double[] nearestDist = {Double.MAX_VALUE};

            store.forEachChunk(NPC_QUERY, (chunk, cmdBuffer) -> {
                for (int i = 0; i < chunk.size(); i++) {
                    Ref<EntityStore> ref = chunk.getReferenceTo(i);
                    if (ref == null || !ref.isValid()) continue;

                    TransformComponent npcTransform = store.getComponent(ref, TransformComponent.getComponentType());
                    if (npcTransform == null) continue;

                    double dist = playerPos.distanceTo(npcTransform.getPosition());
                    if (dist < nearestDist[0] && dist < NPC_SEARCH_RADIUS) {
                        UUIDComponent uuidComp = store.getComponent(ref, UUIDComponent.getComponentType());
                        if (uuidComp == null) continue;

                        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
                        if (npc == null) continue;

                        nearestDist[0] = dist;
                        nearestNpcUuid[0] = uuidComp.getUuid();
                        nearestNpcName[0] = npc.getRoleName();
                    }
                }
            });

            if (nearestNpcUuid[0] == null) {
                sender.sendMessage(Message.raw("No NPC within " + (int) NPC_SEARCH_RADIUS + " blocks."));
                return;
            }

            plugin.getThreatManager().addThreat(nearestNpcUuid[0], player.getPlayerRef().getUuid(), amount);
            sender.sendMessage(Message.raw(
                String.format("Added %.1f threat on %s (%.1f blocks away)", amount, nearestNpcName[0], nearestDist[0])
            ).color(Color.GREEN));
        });

        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> handleInfo(CommandSender sender) {
        ThreatManager tm = plugin.getThreatManager();
        sender.sendMessage(Message.raw("--- HC_Threat Info ---").color(Color.YELLOW));
        sender.sendMessage(Message.raw("  Active NPC threat tables: " + tm.getActiveNPCCount()));
        sender.sendMessage(Message.raw("  Version: " + HC_ThreatPlugin.VERSION));
        return CompletableFuture.completedFuture(null);
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Message.raw("--- /threat commands ---").color(Color.YELLOW));
        sender.sendMessage(Message.raw("  /threat table - Show threat for nearest NPC"));
        sender.sendMessage(Message.raw("  /threat add <amount> - Add threat on nearest NPC"));
        sender.sendMessage(Message.raw("  /threat clear - Clear all threat tables"));
        sender.sendMessage(Message.raw("  /threat info - Show global threat stats"));
    }
}
