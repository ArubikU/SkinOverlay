package dev.arubik.skinoverlay;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;
import javax.net.ssl.HttpsURLConnection;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerTextures;
import org.jetbrains.annotations.NotNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;

import dev.arubik.skinoverlay.utils.commands.SimpleSuggestionProvider;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class SkinOverlay extends JavaPlugin implements Listener {

    public final HashMap<UUID, String> skins = new HashMap<>();
    private final HashMap<UUID, List<String>> overlayHistory = new HashMap<>();
    private final HashMap<UUID, String> baseSkins = new HashMap<>();
    private final File saveFile = new File(getDataFolder(), "save.yml");
    boolean save;
    boolean allowHttp;

    @Override
    public void onEnable() {
        if (!getServer().getOnlineMode()) {
            getLogger().severe("\033[31;1mThis plugin doesn't work properly on offline-mode servers.\033[0m");
        }
        saveDefaultConfig();
        save = getConfig().getBoolean("save");
        allowHttp = getConfig().getBoolean("allow http");
        for (String resource : new String[] { "angel_wings" }) {
            if (!new File(getDataFolder(), resource + ".png").exists()) {
                saveResource(resource + ".png", false);
            }
        }
        getServer().getPluginManager().registerEvents(this, this);
        if (save) {
            loadData();
        }

        MinecraftServer.getServer().getCommands().getDispatcher().register(Commands.literal("wear")
                .requires(sender -> sender.getBukkitSender().hasPermission("skinoverlay.wear"))
                .then(Commands.argument("targets", EntityArgument.players())
                        .requires(sender -> sender.getBukkitSender().hasPermission("skinoverlay.wear.others"))
                        .then(Commands.literal("clear")
                                .requires(sender -> sender.getBukkitSender().hasPermission("skinoverlay.clear"))
                                .executes(context -> setSkin(() -> null, context.getSource().getBukkitSender(),
                                        EntityArgument.getPlayers(context, "targets").toArray(ServerPlayer[]::new))))
                        .then(Commands.literal("url")
                                .requires(sender -> sender.getBukkitSender().hasPermission("skinoverlay.wear.url"))
                                .then(Commands.argument("url", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            String url = StringArgumentType.getString(context, "url");
                                            return setSkin(() -> ImageIO.read(new ByteArrayInputStream(request(url))),
                                                    context.getSource().getBukkitSender(),
                                                    EntityArgument.getPlayers(context, "targets")
                                                            .toArray(ServerPlayer[]::new));
                                        })))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .suggests(SimpleSuggestionProvider.noTooltip("name",
                                        context -> getOverlayList().stream()
                                                .filter(overlay -> context.getSource().getBukkitSender()
                                                        .hasPermission("skinoverlay.overlay." + overlay))
                                                .toList()))
                                .executes(context -> {
                                    String overlay = StringArgumentType.getString(context, "name");
                                    if (!context.getSource().getBukkitSender()
                                            .hasPermission("skinoverlay.overlay." + overlay)) {
                                        context.getSource().getBukkitSender().sendMessage(message("no permission"));
                                        return 0;
                                    }
                                    return setSkin(() -> ImageIO.read(new File(getDataFolder(), overlay + ".png")),
                                            context.getSource().getBukkitSender(),
                                            EntityArgument.getPlayers(context, "targets")
                                                    .toArray(ServerPlayer[]::new));
                                })))
                .then(Commands.literal("clear")
                        .requires(sender -> sender.getBukkitSender() instanceof Player
                                && sender.getBukkitSender().hasPermission("skinoverlay.clear"))
                        .executes(context -> setSkin(() -> null, context.getSource().getBukkitSender(),
                                context.getSource().getPlayerOrException())))
                .then(Commands.literal("url")
                        .requires(sender -> sender.getBukkitSender() instanceof Player
                                && sender.getBukkitSender().hasPermission("skinoverlay.wear.url"))
                        .then(Commands.argument("url", StringArgumentType.greedyString())
                                .executes(context -> {
                                    String url = StringArgumentType.getString(context, "url");
                                    return setSkin(() -> ImageIO.read(new ByteArrayInputStream(request(url))),
                                            context.getSource().getBukkitSender(),
                                            context.getSource().getPlayerOrException());
                                })))
                .then(Commands.argument("name", StringArgumentType.string())
                        .requires(sender -> sender.getBukkitSender() instanceof Player)
                        .suggests(SimpleSuggestionProvider.noTooltip("name",
                                context -> getOverlayList().stream()
                                        .filter(overlay -> context.getSource().getBukkitSender()
                                                .hasPermission("skinoverlay.overlay." + overlay))
                                        .toList()))
                        .executes(context -> {
                            String overlay = StringArgumentType.getString(context, "name");
                            if (!context.getSource().getBukkitSender()
                                    .hasPermission("skinoverlay.overlay." + overlay)) {
                                context.getSource().getBukkitSender().sendMessage(message("no permission"));
                                return 0;
                            }
                            return setSkin(() -> ImageIO.read(new File(getDataFolder(), overlay + ".png")),
                                    context.getSource().getBukkitSender(),
                                    context.getSource().getPlayerOrException());
                        }))
                .then(Commands.literal("history")
                        .requires(sender -> sender.getBukkitSender().hasPermission("skinoverlay.history"))
                        .executes(context -> showHistory(context.getSource().getBukkitSender())))
                .then(Commands.literal("clearhistory")
                        .requires(sender -> sender.getBukkitSender().hasPermission("skinoverlay.clearhistory"))
                        .executes(context -> clearHistory(context.getSource().getBukkitSender())))
                .then(Commands.literal("reset")
                        .requires(sender -> sender.getBukkitSender().hasPermission("skinoverlay.reset"))
                        .executes(context -> resetSkin(context.getSource().getBukkitSender()))));

        getServer().getPluginManager().registerEvents(new SkinRestorerListener(), this);
    }

    @Override
    public void onDisable() {
        if (save) {
            saveData();
        }
    }

    private void loadData() {
        try {
            if (!saveFile.exists()) {
                saveFile.createNewFile();
            }
            YamlConfiguration save = YamlConfiguration.loadConfiguration(saveFile);
            save.getConfigurationSection("skins").getValues(false).forEach((uuid, property) -> {
                skins.put(UUID.fromString(uuid), (String) property);
            });
            save.getConfigurationSection("baseSkins").getValues(false).forEach((uuid, property) -> {
                baseSkins.put(UUID.fromString(uuid), (String) property);
            });
            save.getConfigurationSection("overlayHistory").getValues(false).forEach((uuid, historyList) -> {
                overlayHistory.put(UUID.fromString(uuid), (List<String>) historyList);
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveData() {
        try {
            if (!saveFile.exists()) {
                saveFile.createNewFile();
            }
            YamlConfiguration save = new YamlConfiguration();
            skins.forEach((uuid, skin) -> {
                save.set("skins." + uuid.toString(), skin);
            });
            baseSkins.forEach((uuid, skin) -> {
                save.set("baseSkins." + uuid.toString(), skin);
            });
            overlayHistory.forEach((uuid, history) -> {
                save.set("overlayHistory." + uuid.toString(), history);
            });
            save.save(saveFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public int setSkin(ImageSupplier imageSupplier, CommandSender sender, ServerPlayer... players) {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            Image overlay;
            try {
                overlay = imageSupplier.get();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            players: for (ServerPlayer serverPlayer : players) {
                Player target = serverPlayer.getBukkitEntity();
                try {
                    PlayerTextures textures = target.getPlayerProfile().getTextures();
                    var skin = ImageIO.read(textures.getSkin());

                    // Save base skin if it's not already saved
                    if (!baseSkins.containsKey(target.getUniqueId())) {
                        var baseStream = new ByteArrayOutputStream();
                        ImageIO.write(skin, "PNG", baseStream);
                        baseSkins.put(target.getUniqueId(),
                                Base64.getEncoder().encodeToString(baseStream.toByteArray()));
                    }

                    var image = new BufferedImage(skin.getWidth(), skin.getHeight(), BufferedImage.TYPE_INT_ARGB);
                    var canvas = image.createGraphics();
                    canvas.drawImage(skin, 0, 0, null);
                    canvas.drawImage(overlay, 0, 0, null);
                    var stream = new ByteArrayOutputStream();
                    ImageIO.write(image, "PNG", stream);
                    canvas.dispose();
                    var boundary = "*****";
                    var crlf = "\r\n";
                    var twoHyphens = "--";
                    var con = (HttpsURLConnection) new URL("https://api.mineskin.org/generate/upload?visibility=1")
                            .openConnection();
                    con.setRequestMethod("POST");
                    con.setRequestProperty("Connection", "Keep-Alive");
                    con.setRequestProperty("Cache-Control", "no-cache");
                    con.setRequestProperty("User-Agent", "SkinOverlay");
                    con.setDoOutput(true);
                    con.setRequestProperty("Content-Type", "multipart/form-data;boundary=" + boundary);
                    con.getOutputStream().write((twoHyphens + boundary + crlf).getBytes());
                    con.getOutputStream()
                            .write(("Content-Disposition: form-data; name=\"" + "file" + "\";filename=\"" + "file.png"
                                    + "\"" + crlf).getBytes());
                    con.getOutputStream().write((crlf).getBytes());
                    con.getOutputStream().write(stream.toByteArray());
                    con.getOutputStream().write(crlf.getBytes());
                    con.getOutputStream().write((twoHyphens + boundary + twoHyphens + crlf).getBytes());
                    con.getOutputStream().close();
                    var status = con.getResponseCode();
                    switch (status) {
                        case 429 -> sender.sendMessage(message("too many requests"));
                        case 200 -> {
                            var response = JsonParser.parseString(new String(con.getInputStream().readAllBytes()));
                            var texture = response.getAsJsonObject().getAsJsonObject("data")
                                    .getAsJsonObject("texture");
                            var texturesValue = texture.get("value").getAsString();
                            byte[] bytesjson = Base64.getDecoder().decode(texturesValue);
                            JsonObject jsonObject = JsonParser.parseString(new String(bytesjson, "UTF-8"))
                                    .getAsJsonObject();
                            String url = jsonObject.getAsJsonObject().get("textures").getAsJsonObject().get("SKIN")
                                    .getAsJsonObject().get("url").toString();
                            this.skins.put(target.getUniqueId(), url);
                            updateSkin(target, true);
                            if (!save)
                                skins.remove(target.getUniqueId());
                            sender.sendMessage(message("done").replaceAll("\\{minecrafttextures}",
                                    texture.get("url").getAsString()));

                            // Add overlay to history
                            UUID uuid = target.getUniqueId();
                            if (!overlayHistory.containsKey(uuid)) {
                                overlayHistory.put(uuid, new ArrayList<>());
                            }
                            overlayHistory.get(uuid).add(url);
                        }
                        default -> sender.sendMessage(message("unknown error"));
                    }

                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }
        });
        return players.length;
    }

    public void updateSkin(Player player, boolean forOthers) {
        try {
            com.destroystokyo.paper.profile.PlayerProfile profile = player.getPlayerProfile();
            PlayerTextures textures = player.getPlayerProfile().getTextures();
            textures.setSkin(new URI(skins.get(player.getUniqueId()).replaceAll("\"", "")).toURL(),
                    textures.getSkinModel());
            profile.setTextures(textures);
            getServer().getScheduler().runTask(this, () -> {
                player.setPlayerProfile(profile);
                player.hidePlayer(this, player);
                player.showPlayer(this, player);
                new SkinApplier().accept(player);
                if  (forOthers) {
                    getServer().getOnlinePlayers().stream().filter(p -> p != player).forEach(p -> {
                        p.hidePlayer(this, player);
                        p.showPlayer(this, player);
                    });
                }
            });
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
    }

    public List<String> getOverlayList() {
        return getOverlayListRecursive(getDataFolder()).stream()
                .map(t -> {
                    return t.getPath().replace(getDataFolder().getPath() + "\\", "");
                }).filter(fileName -> fileName.endsWith(".png")).collect(Collectors.toList());
    }

    private Collection<File> getOverlayListRecursive(@NotNull File dataFolder) {
        List<File> files = new ArrayList<>();
        File[] listFiles = dataFolder.listFiles();
        if (listFiles != null) {
            for (File file : listFiles) {
                if (file.isDirectory()) {
                    files.addAll(getOverlayListRecursive(file));
                } else {
                    files.add(file);
                }
            }
        }
        return files;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length > 2)
            return new ArrayList<>();
        if (args.length == 2 && !sender.hasPermission("skinoverlay.wear.others"))
            return new ArrayList<>();
        if (args.length == 2 && getServer().getPlayer(args[0]) == null)
            return Collections.singletonList("Error: player not found");
        var overlays = getOverlayList().stream().filter(overlay -> overlay.toLowerCase().startsWith(args[args.length - 1]))
                .collect(Collectors.toList());
        var players = getServer().getOnlinePlayers().stream().map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(args[0])).collect(Collectors.toList());
        return (overlays.isEmpty() && args.length == 1 && sender.hasPermission("skinoverlay.wear.others")) ? players
                : overlays;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        getServer().getScheduler().runTask(this, () -> updateSkin(event.getPlayer(), true));
    }

    private byte[] request(String address) {
        try {
            var url = new URL(address);
            if (!url.getProtocol().startsWith("https") && !(url.getProtocol().startsWith("http") && allowHttp)) {
                throw new IllegalArgumentException("Tried to use non-https protocol");
            }
            var con = (HttpsURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            con.setRequestProperty("User-Agent", "SkinOverlay");
            var status = con.getResponseCode();
            assert status == 200;
            return con.getInputStream().readAllBytes();
        } catch (Exception exception) {
            throw new IllegalStateException("Unexpected error", exception);
        }
    }

    private String message(String path) {
        return ChatColor.translateAlternateColorCodes('&',
                Objects.requireNonNull(getConfig().getString("messages." + path)));
    }

    private int showHistory(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(message("player only command"));
            return 0;
        }
        Player player = (Player) sender;
        UUID uuid = player.getUniqueId();
        if (!overlayHistory.containsKey(uuid) || overlayHistory.get(uuid).isEmpty()) {
            sender.sendMessage(message("no history"));
            return 0;
        }
        sender.sendMessage(message("history header"));
        for (int i = 0; i < overlayHistory.get(uuid).size(); i++) {
            sender.sendMessage(message("history entry").replace("{index}", String.valueOf(i + 1)).replace("{overlay}",
                    overlayHistory.get(uuid).get(i)));
        }
        return 1;
    }

    private int clearHistory(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(message("player only command"));
            return 0;
        }
        Player player = (Player) sender;
        UUID uuid = player.getUniqueId();
        if (!overlayHistory.containsKey(uuid) || overlayHistory.get(uuid).isEmpty()) {
            sender.sendMessage(message("no history"));
            return 0;
        }
        overlayHistory.get(uuid).clear();
        sender.sendMessage(message("history cleared"));
        return 1;
    }

    private int resetSkin(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(message("player only command"));
            return 0;
        }
        Player player = (Player) sender;
        UUID uuid = player.getUniqueId();
        if (baseSkins.containsKey(uuid)) {
            try {
                byte[] decodedBytes = Base64.getDecoder().decode(baseSkins.get(uuid));
                ByteArrayInputStream bis = new ByteArrayInputStream(decodedBytes);
                BufferedImage baseImage = ImageIO.read(bis);

                var stream = new ByteArrayOutputStream();
                ImageIO.write(baseImage, "PNG", stream);

                // Use the same code as in setSkin to apply the base skin
                var boundary = "*****";
                var crlf = "\r\n";
                var twoHyphens = "--";
                var con = (HttpsURLConnection) new URL("https://api.mineskin.org/generate/upload?visibility=1")
                        .openConnection();
                con.setRequestMethod("POST");
                con.setRequestProperty("Connection", "Keep-Alive");
                con.setRequestProperty("Cache-Control", "no-cache");
                con.setRequestProperty("User-Agent", "SkinOverlay");
                con.setDoOutput(true);
                con.setRequestProperty("Content-Type", "multipart/form-data;boundary=" + boundary);
                con.getOutputStream().write((twoHyphens + boundary + crlf).getBytes());
                con.getOutputStream().write(("Content-Disposition: form-data; name=\"" + "file" + "\";filename=\""
                        + "file.png" + "\"" + crlf).getBytes());
                con.getOutputStream().write((crlf).getBytes());
                con.getOutputStream().write(stream.toByteArray());
                con.getOutputStream().write(crlf.getBytes());
                con.getOutputStream().write((twoHyphens + boundary + twoHyphens + crlf).getBytes());
                con.getOutputStream().close();
                var status = con.getResponseCode();
                if (status == 200) {
                    var response = JsonParser.parseString(new String(con.getInputStream().readAllBytes()));
                    var texture = response.getAsJsonObject().getAsJsonObject("data").getAsJsonObject("texture");
                    var texturesValue = texture.get("value").getAsString();
                    byte[] bytesjson = Base64.getDecoder().decode(texturesValue);
                    JsonObject jsonObject = JsonParser.parseString(new String(bytesjson, "UTF-8")).getAsJsonObject();
                    String url = jsonObject.getAsJsonObject().get("textures").getAsJsonObject().get("SKIN")
                            .getAsJsonObject().get("url").toString();
                    this.skins.put(uuid, url);
                    updateSkin(player, true);
                    overlayHistory.get(uuid).clear();
                    sender.sendMessage(message("skin reset"));
                    return 1;
                } else {
                    sender.sendMessage(message("skin reset error"));
                    return 0;
                }
            } catch (IOException e) {
                e.printStackTrace();
                sender.sendMessage(message("skin reset error"));
                return 0;
            }
        } else {
            sender.sendMessage(message("no base skin"));
            return 0;
        }
    }

    private class SkinRestorerListener implements Listener {
        @EventHandler
        public void onSkinRestore(SkinnedPlayerApplySkinEvent event) {
            Player player = event.getPlayer();
            UUID uuid = player.getUniqueId();
            if (skins.containsKey(uuid)) {
                // Apply the overlay to the new skin
                try {
                    PlayerTextures textures = player.getPlayerProfile().getTextures();
                    textures.setSkin(new URI(skins.get(uuid).replaceAll("\"", "")).toURL(), textures.getSkinModel());
                    com.destroystokyo.paper.profile.PlayerProfile profile = player.getPlayerProfile();
                    profile.setTextures(textures);
                    player.setPlayerProfile(profile);
                } catch (Exception e) {
                    getLogger().warning("Failed to apply overlay for player " + player.getName());
                    e.printStackTrace();
                }
            }
        }
    }

    @FunctionalInterface
    private interface ImageSupplier {
        Image get() throws IOException;
    }
}