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
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;
import javax.net.ssl.HttpsURLConnection;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerTextures;
import org.jetbrains.annotations.NotNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class SkinOverlay extends JavaPlugin implements Listener {

    public final HashMap<UUID, String> skins = new HashMap<>();
    private final HashMap<UUID, List<String>> overlayHistory = new HashMap<>();
    private final HashMap<UUID, String> baseSkins = new HashMap<>();
    private final HashMap<UUID, String> tempOverlays = new HashMap<>();
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

        LifecycleEventManager<Plugin> manager = this.getLifecycleManager();
        manager.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = MinecraftServer.getServer().getCommands();
            registerCommands(commands);
        });
        getServer().getPluginManager().registerEvents(new SkinRestorerListener(), this);
    }

    @Override
    public void onDisable() {
        if (save) {
            saveData();
        }
    }

    private void registerCommands(Commands commands) {

        LiteralArgumentBuilder<CommandSourceStack> wearCommand = Commands.literal("wear")
                .requires(source -> source.getSender().hasPermission("skinoverlay.wear"));

        wearCommand
                .then(Commands.argument("target", EntityArgument.player())
                        .requires(source -> source.getSender().hasPermission("skinoverlay.wear.others"))
                        .suggests(this::suggestPlayers)
                        .then(Commands.literal("clear")
                                .requires(source -> source.getSender().hasPermission("skinoverlay.clear"))
                                .executes(this::executeClearOthers))
                        .then(Commands.literal("url")
                                .requires(source -> source.getSender().hasPermission("skinoverlay.wear.url"))
                                .then(Commands.argument("url", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            try {
                                                return executeUrlOthers(context);
                                            } catch (Exception e) {
                                                e.printStackTrace();
                                                return 0;
                                            }
                                        })))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .suggests(this::suggestOverlays)
                                .executes(context -> {
                                    try {
                                        return executeOverlayOthers(context);
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                        return 0;
                                    }
                                })))
                .then(Commands.literal("clear")
                        .requires(source -> source.getSender() instanceof ServerPlayer
                                && source.getSender().hasPermission("skinoverlay.clear"))
                        .executes(this::executeClear))
                .then(Commands.literal("url")
                        .requires(source -> source.getSender() instanceof ServerPlayer
                                && source.getSender().hasPermission("skinoverlay.wear.url"))
                        .then(Commands.argument("url", StringArgumentType.greedyString())
                                .executes(context -> {
                                    try {
                                        return executeUrl(context);
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                        return 0;
                                    }
                                })))
                .then(Commands.argument("name", StringArgumentType.string())
                        .requires(source -> source.getSender() instanceof ServerPlayer)
                        .suggests(this::suggestOverlays)
                        .executes(context -> {
                            try {
                                return executeOverlay(context);
                            } catch (Exception e) {
                                e.printStackTrace();
                                return 0;
                            }
                        }))
                .then(Commands.literal("history")
                        .requires(source -> source.getSender().hasPermission("skinoverlay.history"))
                        .executes(this::executeHistory))
                .then(Commands.literal("clearhistory")
                        .requires(source -> source.getSender().hasPermission("skinoverlay.clearhistory"))
                        .executes(this::executeClearHistory))
                .then(Commands.literal("reset")
                        .requires(source -> source.getSender().hasPermission("skinoverlay.reset"))
                        .executes(this::executeReset))
                .then(Commands.literal("removeLastOverlay")
                        .requires(source -> source.getSender().hasPermission("skinoverlay.removelast"))
                        .executes(this::executeRemoveLastOverlay))
                .then(Commands.literal("addTempOverlay")
                        .requires(source -> source.getSender().hasPermission("skinoverlay.addtemp"))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .suggests(this::suggestOverlays)
                                .executes(context -> {
                                    try {
                                        return executeAddTempOverlay(context);
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                        return 0;
                                    }
                                })))
                .then(Commands.literal("clearTempOverlay")
                        .requires(source -> source.getSender().hasPermission("skinoverlay.cleartemp"))
                        .executes(this::executeClearTempOverlay));

        commands.getDispatcher().register(wearCommand);
    }

    private CompletableFuture<Suggestions> suggestOverlays(CommandContext<CommandSourceStack> context,
            SuggestionsBuilder builder) {
        String input = builder.getRemaining().toLowerCase();
        List<String> overlays = getOverlayList().stream()
                .filter(overlay -> overlay.toLowerCase().startsWith(input))
                .filter(overlay -> context.getSource().getSender().hasPermission("skinoverlay.overlay." + overlay))
                .collect(Collectors.toList());

        overlays.forEach(builder::suggest);
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestPlayers(CommandContext<CommandSourceStack> context,
            SuggestionsBuilder builder) {
        String input = builder.getRemaining().toLowerCase();
        List<String> players = getServer().getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(input))
                .collect(Collectors.toList());

        players.forEach(builder::suggest);
        return builder.buildFuture();
    }

    private int executeClearOthers(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSender sender = context.getSource().getSender();
        ServerPlayer target = EntityArgument.getPlayer(context, "target");
        return setSkin(() -> null, sender, target);
    }

    private int executeUrlOthers(CommandContext<CommandSourceStack> context) throws Exception {
        CommandSender sender = context.getSource().getSender();
        String url = StringArgumentType.getString(context, "url");
        ServerPlayer target = EntityArgument.getPlayer(context, "target");
        return setSkin(() -> ImageIO.read(new ByteArrayInputStream(request(url))), sender, target);
    }

    private int executeOverlayOthers(CommandContext<CommandSourceStack> context) throws Exception {
        CommandSender sender = context.getSource().getSender();
        String overlay = StringArgumentType.getString(context, "name");
        if (!sender.hasPermission("skinoverlay.overlay." + overlay)) {
            sender.sendMessage(message("no permission"));
            return 0;
        }
        ServerPlayer target = EntityArgument.getPlayer(context, "target");
        return setSkin(() -> ImageIO.read(new File(getDataFolder(), overlay + ".png")), sender, target);
    }

    private int executeClear(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player)) {
            sender.sendMessage(message("player only command"));
            return 0;
        }
        return setSkin(() -> null, sender, ((CraftPlayer) (Player) sender).getHandle());
    }

    private int executeUrl(CommandContext<CommandSourceStack> context) throws Exception {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player)) {
            sender.sendMessage(message("player only command"));
            return 0;
        }
        String url = StringArgumentType.getString(context, "url");
        return setSkin(() -> ImageIO.read(new ByteArrayInputStream(request(url))), sender,
                ((CraftPlayer) (Player) sender).getHandle());
    }

    private int executeOverlay(CommandContext<CommandSourceStack> context) throws Exception {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player)) {
            sender.sendMessage(message("player only command"));
            return 0;
        }
        String overlay = StringArgumentType.getString(context, "name");
        if (!sender.hasPermission("skinoverlay.overlay." + overlay)) {
            sender.sendMessage(message("no permission"));
            return 0;
        }
        return setSkin(() -> ImageIO.read(new File(getDataFolder(), overlay + ".png")), sender,
                ((CraftPlayer) (Player) sender).getHandle());
    }

    private int executeHistory(CommandContext<CommandSourceStack> context) {
        return showHistory(context.getSource().getSender());
    }

    private int executeClearHistory(CommandContext<CommandSourceStack> context) {
        return clearHistory(context.getSource().getSender());
    }

    private int executeReset(CommandContext<CommandSourceStack> context) {
        return resetSkin(context.getSource().getSender());
    }

    private int executeRemoveLastOverlay(CommandContext<CommandSourceStack> context) {
        return removeLastOverlay(context.getSource().getSender());
    }

    private int executeAddTempOverlay(CommandContext<CommandSourceStack> context) throws Exception {
        CommandSender sender = context.getSource().getSender();
        String overlay = StringArgumentType.getString(context, "name");
        return addTempOverlay(sender, overlay);
    }

    private int executeClearTempOverlay(CommandContext<CommandSourceStack> context) {
        return clearTempOverlay(context.getSource().getSender());
    }

    private void loadData() {
        try {
            if (!saveFile.exists()) {
                saveFile.createNewFile();
            }
            YamlConfiguration save = YamlConfiguration.loadConfiguration(saveFile);
            if (save.contains("skins")) {

                save.getConfigurationSection("skins").getValues(false).forEach((uuid, property) -> {
                    skins.put(UUID.fromString(uuid), (String) property);
                });
            }
            if (save.contains("baseSkins")) {
                save.getConfigurationSection("baseSkins").getValues(false).forEach((uuid, property) -> {
                    baseSkins.put(UUID.fromString(uuid), (String) property);
                });
            }
            if (save.contains("overlayHistory")) {
                save.getConfigurationSection("overlayHistory").getValues(false).forEach((uuid, historyList) -> {
                    overlayHistory.put(UUID.fromString(uuid), (List<String>) historyList);
                });
            }
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

    private int removeLastOverlay(CommandSender sender) {
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
        List<String> history = overlayHistory.get(uuid);
        history.remove(history.size() - 1);
        if (history.isEmpty()) {
            return resetSkin(sender);
        } else {
            String lastOverlay = history.get(history.size() - 1);
            skins.put(uuid, lastOverlay);
            updateSkin(player, true);
            sender.sendMessage(message("last overlay removed"));
            return 1;
        }
    }

    private int addTempOverlay(CommandSender sender, String overlayName) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(message("player only command"));
            return 0;
        }
        Player player = (Player) sender;
        UUID uuid = player.getUniqueId();
        if (!sender.hasPermission("skinoverlay.overlay." + overlayName)) {
            sender.sendMessage(message("no permission"));
            return 0;
        }
        try {
            Image overlay = ImageIO.read(new File(getDataFolder(), overlayName + ".png"));
            PlayerTextures textures = player.getPlayerProfile().getTextures();
            var skin = ImageIO.read(textures.getSkin());

            var image = new BufferedImage(skin.getWidth(), skin.getHeight(), BufferedImage.TYPE_INT_ARGB);
            var canvas = image.createGraphics();
            canvas.drawImage(skin, 0, 0, null);
            canvas.drawImage(overlay, 0, 0, null);
            var stream = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", stream);
            canvas.dispose();

            // Use the same code as in setSkin to apply the temp overlay
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
                tempOverlays.put(uuid, url);
                updateSkin(player, true);
                sender.sendMessage(message("temp overlay added"));
                return 1;
            } else {
                sender.sendMessage(message("temp overlay error"));
                return 0;
            }
        } catch (IOException e) {
            e.printStackTrace();
            sender.sendMessage(message("temp overlay error"));
            return 0;
        }
    }

    private int clearTempOverlay(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(message("player only command"));
            return 0;
        }
        Player player = (Player) sender;
        UUID uuid = player.getUniqueId();
        if (!tempOverlays.containsKey(uuid)) {
            sender.sendMessage(message("no temp overlay"));
            return 0;
        }
        tempOverlays.remove(uuid);
        if (skins.containsKey(uuid)) {
            updateSkin(player, true);
        } else {
            resetSkin(sender);
        }
        sender.sendMessage(message("temp overlay cleared"));
        return 1;
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
            String skinUrl = tempOverlays.getOrDefault(player.getUniqueId(),
                    skins.getOrDefault(player.getUniqueId(), null));
            if (skinUrl != null) {
                textures.setSkin(new URI(skinUrl.replaceAll("\"", "")).toURL(), textures.getSkinModel());
                profile.setTextures(textures);
                getServer().getScheduler().runTask(this, () -> {
                    player.setPlayerProfile(profile);
                    player.hidePlayer(this, player);
                    player.showPlayer(this, player);
                    new SkinApplier().accept(player);
                    if (forOthers) {
                        getServer().getOnlinePlayers().stream().filter(p -> p != player).forEach(p -> {
                            p.hidePlayer(this, player);
                            p.showPlayer(this, player);
                        });
                    }
                });
            }
        } catch (MalformedURLException | URISyntaxException e) {
            e.printStackTrace();
        }
    }

    public List<String> getOverlayList() {
        return getOverlayListRecursive(getDataFolder()).stream()
                .map(t -> {
                    return t.getPath().replace(getDataFolder().getPath() + "/", "");
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
                getConfig().getString("messages." + path));
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
        public void onSkinRestorer(net.skinsrestorer.api.event.SkinApplyEvent event) {
            Player player = event.getPlayer(Player.class);
            UUID uuid = player.getUniqueId();
            if (overlayHistory.containsKey(uuid)) {
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
            baseSkins.put(uuid, player.getPlayerProfile().getTextures().getSkin().toString());
        }
    }

    @FunctionalInterface
    private interface ImageSupplier {
        Image get() throws IOException;
    }
}