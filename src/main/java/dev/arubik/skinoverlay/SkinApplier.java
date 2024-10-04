/*
 * SkinsRestorer
 *
 * Copyright (C) 2022 SkinsRestorer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 */
package dev.arubik.skinoverlay;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import com.mojang.authlib.properties.Property;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.effect.MobEffectInstance;

public final class SkinApplier implements Consumer<Player> {
    private static void sendPacket(ServerPlayer player, Packet<?> packet) {
        player.connection.send(packet);
    }

    public static String getName(Collection<Property> map) {
        for (Property prop : map) {
            if (prop.hasSignature()) {
                return prop.name();
            }
        }
        return "";
    }

    public static String getValue(Collection<Property> map) {
        for (Property prop : map) {
            if (prop.hasSignature()) {
                return prop.value();
            }
        }
        return "";
    }

    public static String getSignature(Collection<Property> map) {
        for (Property prop : map) {
            if (prop.hasSignature()) {
                return prop.signature();
            }
        }
        return "";
    }

    public void triggerHealthUpdate(Player player) {
        extractServerPlayer(player).resetSentInfo();
    }

    @Override
    public void accept(Player player) {
        ServerPlayer entityPlayer = extractServerPlayer(player);

        // Slowly getting from object to object till we get what is needed for
        // the respawn packet
        ServerLevel world = entityPlayer.serverLevel();

        CommonPlayerSpawnInfo spawnInfo = entityPlayer.createCommonSpawnInfo(world);
        ClientboundRespawnPacket respawn = new ClientboundRespawnPacket(
                spawnInfo,
                ClientboundRespawnPacket.KEEP_ALL_DATA);

        sendPacket(entityPlayer, new ClientboundPlayerInfoRemovePacket(List.of(player.getUniqueId())));
        sendPacket(entityPlayer, ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(entityPlayer)));
        sendPacket(entityPlayer, respawn);
        entityPlayer.onUpdateAbilities();

        entityPlayer.connection.teleport(player.getLocation());

        // Send health, food, experience (food is sent together with health)
        entityPlayer.resetSentInfo();

        PlayerList playerList = entityPlayer.server.getPlayerList();
        playerList.sendPlayerPermissionLevel(entityPlayer);
        playerList.sendLevelInfo(entityPlayer, world);
        playerList.sendAllPlayerInfo(entityPlayer);

        // Resend their effects
        for (MobEffectInstance effect : entityPlayer.getActiveEffects()) {
            sendPacket(entityPlayer, new ClientboundUpdateMobEffectPacket(entityPlayer.getId(), effect, false));
        }
        triggerHealthUpdate(player);
    }

    public static ServerPlayer extractServerPlayer(Player player) {
        return (ServerPlayer) ((CraftPlayer) player).getHandle();
    }
}