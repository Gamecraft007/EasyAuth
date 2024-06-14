package xyz.nikitacartes.easyauth.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.ClientConnection;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.stat.ServerStatHandler;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Uuids;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import xyz.nikitacartes.easyauth.event.AuthEventHandler;
import xyz.nikitacartes.easyauth.storage.PlayerCacheV0;
import xyz.nikitacartes.easyauth.utils.PlayerAuth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.io.File;
import java.net.SocketAddress;
import java.util.Optional;
import java.util.UUID;

import static xyz.nikitacartes.easyauth.EasyAuth.*;
import static xyz.nikitacartes.easyauth.utils.AuthHelper.hasValidSession;
import static xyz.nikitacartes.easyauth.utils.EasyLogger.LogDebug;
import static xyz.nikitacartes.easyauth.utils.EasyLogger.LogWarn;

@Mixin(PlayerManager.class)
public abstract class PlayerManagerMixin {

    @Unique
    private final PlayerManager playerManager = (PlayerManager) (Object) this;

    @Final
    @Shadow
    private MinecraftServer server;

    @Inject(method = "onPlayerConnect(Lnet/minecraft/network/ClientConnection;Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/server/network/ConnectedClientData;)V", at = @At("RETURN"))
    private void onPlayerConnect(ClientConnection connection, ServerPlayerEntity player, ConnectedClientData clientData, CallbackInfo ci) {
        AuthEventHandler.onPlayerJoin(player);
    }

    @ModifyVariable(method = "onPlayerConnect(Lnet/minecraft/network/ClientConnection;Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/server/network/ConnectedClientData;)V",
            at = @At("STORE"), ordinal = 0)
    private RegistryKey<World> onPlayerConnect(RegistryKey<World> world, ClientConnection connection, ServerPlayerEntity player, ConnectedClientData clientData) {
        String uuid = ((PlayerAuth) player).easyAuth$getFakeUuid();
        PlayerCacheV0 cache;
        if (!playerCacheMap.containsKey(uuid)) {
            // First join
            cache = PlayerCacheV0.fromJson(player, uuid);
            playerCacheMap.put(uuid, cache);
        }
        if (config.hidePlayerCoords && !(hasValidSession(player, connection))) {
            ((PlayerAuth) player).easyAuth$saveLastDimension(world);
            return RegistryKey.of(RegistryKeys.WORLD, Identifier.of(config.worldSpawn.dimension));
        }
        return world;
    }

    @ModifyArgs(method = "onPlayerConnect(Lnet/minecraft/network/ClientConnection;Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/server/network/ConnectedClientData;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerPlayNetworkHandler;requestTeleport(DDDFF)V"))
    private void onPlayerConnect(Args args, ClientConnection connection, ServerPlayerEntity player, ConnectedClientData clientData) {
        if (config.hidePlayerCoords && !(hasValidSession(player, connection))) {
            PlayerCacheV0 cache = playerCacheMap.get(((PlayerAuth) player).easyAuth$getFakeUuid());
            ((PlayerAuth) player).easyAuth$saveLastLocation(false);

            LogDebug(String.format("Teleporting player %s", ((PlayerAuth) player).easyAuth$getFakeUuid()));

            Optional<NbtCompound> nbtCompound = playerManager.loadPlayerData(player);
            if(nbtCompound.isPresent() && nbtCompound.get().contains("RootVehicle", 10)) {
                NbtCompound nbtCompound2 = nbtCompound.get().getCompound("RootVehicle");
                if (nbtCompound2.containsUuid("Attach")) {
                    cache.ridingEntityUUID = nbtCompound2.getUuid("Attach");
                } else {
                    cache.ridingEntityUUID = null;
                }
            }

            LogDebug(String.format("Spawn position of player %s is %s", player.getName(), config.worldSpawn));

            args.set(0, config.worldSpawn.x);
            args.set(1, config.worldSpawn.y);
            args.set(2, config.worldSpawn.z);
            args.set(3, config.worldSpawn.yaw);
            args.set(4, config.worldSpawn.pitch);
        }
    }

    @Redirect(method = "respawnPlayer",
    at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerPlayerEntity;getRespawnTarget(ZLnet/minecraft/world/TeleportTarget$PostDimensionTransition;)Lnet/minecraft/world/TeleportTarget;"))
    private TeleportTarget replaceRespawnTarget(ServerPlayerEntity player, boolean alive, TeleportTarget.PostDimensionTransition postDimensionTransition) {
        if (!alive && config.hidePlayerCoords && !((PlayerAuth) player).easyAuth$isAuthenticated()) {
            return new TeleportTarget(
                this.server.getWorld(RegistryKey.of(RegistryKeys.WORLD, Identifier.of(config.worldSpawn.dimension))),
                new Vec3d(config.worldSpawn.x, config.worldSpawn.y, config.worldSpawn.z),
                new Vec3d(0.0F, 0.0F, 0.0F), config.worldSpawn.yaw, config.worldSpawn.pitch, postDimensionTransition
            );
        }
        return player.getRespawnTarget(alive, postDimensionTransition);
    }

    @Redirect(method = "onPlayerConnect(Lnet/minecraft/network/ClientConnection;Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/server/network/ConnectedClientData;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerPlayerEntity;startRiding(Lnet/minecraft/entity/Entity;Z)Z"))
    private boolean onPlayerConnectStartRiding(ServerPlayerEntity instance, Entity entity, boolean force, ClientConnection connection, ServerPlayerEntity player, ConnectedClientData clientData) {
        if (config.hidePlayerCoords && !(hasValidSession(player, connection))) {
            return false;
        }
        return instance.startRiding(entity, force);
    }

    @Redirect(method = "onPlayerConnect(Lnet/minecraft/network/ClientConnection;Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/server/network/ConnectedClientData;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerPlayerEntity;hasVehicle()Z"))
    private boolean onPlayerConnectStartRiding(ServerPlayerEntity instance, ClientConnection connection, ServerPlayerEntity player, ConnectedClientData clientData) {
        if (config.hidePlayerCoords && !(hasValidSession(player, connection))) {
            return true;
        }
        return instance.hasVehicle();
    }

    @Inject(method = "remove(Lnet/minecraft/server/network/ServerPlayerEntity;)V", at = @At("HEAD"))
    private void onPlayerLeave(ServerPlayerEntity serverPlayerEntity, CallbackInfo ci) {
        AuthEventHandler.onPlayerLeave(serverPlayerEntity);
    }

    @Inject(method = "checkCanJoin(Ljava/net/SocketAddress;Lcom/mojang/authlib/GameProfile;)Lnet/minecraft/text/Text;", at = @At("HEAD"), cancellable = true)
    private void checkCanJoin(SocketAddress socketAddress, GameProfile profile, CallbackInfoReturnable<Text> cir) {
        // Getting the player that is trying to join the server
        Text returnText = AuthEventHandler.checkCanPlayerJoinServer(profile, playerManager);

        if (returnText != null) {
            // Canceling player joining with the returnText message
            cir.setReturnValue(returnText);
        }
    }

    @Inject(
            method = "createStatHandler(Lnet/minecraft/entity/player/PlayerEntity;)Lnet/minecraft/stat/ServerStatHandler;",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
            ),
            locals = LocalCapture.CAPTURE_FAILHARD
    )
    private void migrateOfflineStats(PlayerEntity player, CallbackInfoReturnable<ServerStatHandler> cir, UUID uUID, ServerStatHandler serverStatHandler, File serverStatsDir, File playerStatFile) {
        File onlineFile = new File(serverStatsDir, uUID + ".json");
        if (server.isOnlineMode() && !extendedConfig.forcedOfflineUuid && ((PlayerAuth) player).easyAuth$isUsingMojangAccount() && !onlineFile.exists()) {
            String playername = player.getGameProfile().getName();
            File offlineFile = new File(onlineFile.getParent(), Uuids.getOfflinePlayerUuid(playername) + ".json");
            if (!offlineFile.renameTo(onlineFile)) {
                LogWarn("Failed to migrate offline stats (" + offlineFile.getName() + ") for player " + playername + " to online stats (" + onlineFile.getName() + ")");
            } else {
                LogDebug("Migrated offline stats (" + offlineFile.getName() + ") for player " + playername + " to online stats (" + onlineFile.getName() + ")");
            }

            serverStatHandler.file = onlineFile;
        }
    }
}
