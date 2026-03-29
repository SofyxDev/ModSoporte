package sofyx.lux;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.StringReader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;

import java.util.*;

public class ModCommands {

    private static final Map<String, String> COLOR_MAP = new HashMap<>() {{
        put("rojo", "§c"); put("red", "§c"); put("azul", "§9"); put("blue", "§9");
        put("verde", "§a"); put("green", "§a"); put("amarillo", "§e"); put("yellow", "§e");
        put("dorado", "§6"); put("gold", "§6"); put("morado", "§5"); put("purple", "§5");
        put("aqua", "§b"); put("cyan", "§b"); put("blanco", "§f"); put("white", "§f");
        put("negro", "§0"); put("black", "§0"); put("gris", "§7"); put("gray", "§7");
    }};

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_FROG_VARIANTS = (ctx, builder) -> SharedSuggestionProvider.suggest(new String[]{"temperate", "warm", "cold"}, builder);
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_COLORS = (ctx, builder) -> SharedSuggestionProvider.suggest(COLOR_MAP.keySet(), builder);
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_WAYPOINTS = (ctx, builder) -> {
        if (SoporteMod.DATA == null || SoporteMod.DATA.waypoints == null) return builder.buildFuture();
        return SharedSuggestionProvider.suggest(SoporteMod.DATA.waypoints.stream().map(w -> w.id), builder);
    };
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_REGIONS = (ctx, builder) -> {
        if (SoporteMod.DATA == null || SoporteMod.DATA.regions == null) return builder.buildFuture();
        return SharedSuggestionProvider.suggest(SoporteMod.DATA.regions.stream().map(r -> r.name), builder);
    };
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_GAMEMODES = (ctx, builder) -> SharedSuggestionProvider.suggest(new String[]{"survival", "creative", "adventure", "spectator"}, builder);
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_AUDIO_CATEGORIES = (ctx, builder) -> SharedSuggestionProvider.suggest(new String[]{"sountracks", "dialogos", "ambiente", "bloques", "criaturas", "jugador", "master"}, builder);
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_THEMES = (ctx, builder) -> SharedSuggestionProvider.suggest(new String[]{"moderno", "neon", "clasico"}, builder);
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_PRESETS = (ctx, builder) -> SharedSuggestionProvider.suggest(new String[]{"rpg", "moderno", "limpio", "magico", "tecnologico"}, builder);
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_RUTINAS = (ctx, builder) -> {
        if (SoporteMod.DATA == null || SoporteMod.DATA.rutinas == null) return builder.buildFuture();
        return SharedSuggestionProvider.suggest(SoporteMod.DATA.rutinas.keySet(), builder);
    };
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_PROTECTION_FLAGS = (ctx, builder) -> SharedSuggestionProvider.suggest(new String[]{"pvp", "build", "break", "interact", "explosions", "mob_spawning"}, builder);

    public static String translate(String text) {
        if (text == null) return "";
        text = text.replace("&", "§");
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == '\\' && i + 1 < text.length() && text.charAt(i+1) == 'u' && i + 5 < text.length()) {
                try {
                    sb.append((char) Integer.parseInt(text.substring(i + 2, i + 6), 16));
                    i += 5;
                } catch (Exception e) { sb.append(text.charAt(i)); }
            } else { sb.append(text.charAt(i)); }
            i++;
        }
        return sb.toString();
    }

    // =========================================================================================
    // MOTORES DE BYPASS DE ARGUMENTOS (IGNORA VALIDACIONES ESTRICTAS DE MINECRAFT)
    // =========================================================================================
    private static String getRawArg(CommandContext<CommandSourceStack> ctx, String argName) {
        for (ParsedCommandNode<CommandSourceStack> node : ctx.getNodes()) {
            if (node.getNode().getName().equals(argName)) {
                return node.getRange().get(ctx.getInput());
            }
        }
        return "";
    }

    private static Collection<GameProfile> parseTargets(CommandContext<CommandSourceStack> ctx, String argName) throws CommandSyntaxException {
        String targetStr = getRawArg(ctx, argName);
        Collection<GameProfile> targets = new HashSet<>();
        if (targetStr.isEmpty()) return targets;

        // Si es un selector nativo (@a, @p, @r, @e), usamos el parseador interno exacto
        if (targetStr.startsWith("@")) {
            EntitySelector selector = EntityArgument.players().parse(new StringReader(targetStr));
            List<ServerPlayer> players = selector.findPlayers(ctx.getSource());
            for (ServerPlayer p : players) targets.add(p.getGameProfile());
        } else {
            // Si es un jugador desconectado forzamos su registro y evitamos el error del caché
            net.minecraft.server.MinecraftServer server = ctx.getSource().getServer();
            UUID premiumId = null;
            var opt = server.getProfileCache().get(targetStr);
            if (opt.isPresent()) premiumId = opt.get().getId();

            UUID offlineId = java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + targetStr).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            targets.add(new GameProfile(premiumId != null ? premiumId : offlineId, targetStr));
        }
        return targets;
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {

        dispatcher.register(Commands.literal("lux").requires(s -> s.hasPermission(2))

                // --- 1. RECURSEPACK ---
                .then(Commands.literal("recursepack")
                        .then(Commands.literal("link").then(Commands.argument("url", StringArgumentType.string()).executes(ctx -> {
                            SoporteMod.RESOURCE_PACK_URL = StringArgumentType.getString(ctx, "url"); SoporteMod.DATA.savedPackUrl = SoporteMod.RESOURCE_PACK_URL; SoporteMod.RESOURCE_PACK_HASH = ""; SoporteMod.DATA.savedPackHash = "";
                            ctx.getSource().sendSuccess(() -> Component.literal("§d[Pack] §fURL guardada."), true); return 1;
                        }).then(Commands.argument("hash", StringArgumentType.string()).executes(ctx -> {
                            SoporteMod.RESOURCE_PACK_URL = StringArgumentType.getString(ctx, "url"); SoporteMod.DATA.savedPackUrl = SoporteMod.RESOURCE_PACK_URL; SoporteMod.RESOURCE_PACK_HASH = StringArgumentType.getString(ctx, "hash"); SoporteMod.DATA.savedPackHash = SoporteMod.RESOURCE_PACK_HASH;
                            ctx.getSource().sendSuccess(() -> Component.literal("§d[Pack] §fURL y Hash guardados."), true); return 1;
                        }))))
                        .then(Commands.literal("apply").then(Commands.argument("targets", GameProfileArgument.gameProfile()).then(Commands.argument("required", BoolArgumentType.bool()).then(Commands.argument("message", StringArgumentType.greedyString()).executes(ctx -> {
                            if (SoporteMod.RESOURCE_PACK_URL == null || SoporteMod.RESOURCE_PACK_URL.isEmpty()) { ctx.getSource().sendFailure(Component.literal("§cNo hay URL configurada.")); return 0; }
                            Collection<GameProfile> targets = parseTargets(ctx, "targets");
                            boolean req = BoolArgumentType.getBool(ctx, "required"); String msg = translate(StringArgumentType.getString(ctx, "message"));
                            int sentCount = 0;
                            for (GameProfile profile : targets) {
                                ServerPlayer p = ctx.getSource().getServer().getPlayerList().getPlayerByName(profile.getName());
                                if (p != null) { p.sendTexturePack(SoporteMod.RESOURCE_PACK_URL, SoporteMod.RESOURCE_PACK_HASH == null ? "" : SoporteMod.RESOURCE_PACK_HASH, req, Component.literal(msg)); sentCount++; }
                            }
                            int finalSent = sentCount;
                            ctx.getSource().sendSuccess(() -> Component.literal("§a[Pack] §fEnviado a " + finalSent + " jugadores."), true); return 1;
                        })))))
                        .then(Commands.literal("status").executes(ctx -> { ctx.getSource().sendSuccess(() -> Component.literal("§bURL Actual: §f" + (SoporteMod.RESOURCE_PACK_URL == null || SoporteMod.RESOURCE_PACK_URL.isEmpty() ? "Ninguna" : SoporteMod.RESOURCE_PACK_URL)), false); return 1; }))
                        .then(Commands.literal("remove").executes(ctx -> { SoporteMod.RESOURCE_PACK_URL = ""; SoporteMod.DATA.savedPackUrl = ""; SoporteMod.RESOURCE_PACK_HASH = ""; SoporteMod.DATA.savedPackHash = ""; ctx.getSource().sendSuccess(() -> Component.literal("§eURL del pack eliminada."), true); return 1; }))
                )

                // --- 2. SOUNDS ---
                .then(Commands.literal("sounds")
                        .then(Commands.literal("start").then(Commands.argument("playsounds", ResourceLocationArgument.id()).suggests(net.minecraft.commands.synchronization.SuggestionProviders.AVAILABLE_SOUNDS).then(Commands.argument("categoria", StringArgumentType.word()).suggests(SUGGEST_AUDIO_CATEGORIES).then(Commands.argument("target", GameProfileArgument.gameProfile()).then(Commands.argument("volumen", FloatArgumentType.floatArg(0.0f)).then(Commands.argument("entrada_seg", IntegerArgumentType.integer(0)).then(Commands.argument("salida_seg", IntegerArgumentType.integer(0)).executes(ctx -> {
                            String id = ResourceLocationArgument.getId(ctx, "playsounds").toString(); String cat = StringArgumentType.getString(ctx, "categoria"); float vol = FloatArgumentType.getFloat(ctx, "volumen"); int fIn = IntegerArgumentType.getInteger(ctx, "entrada_seg") * 20; int fOut = IntegerArgumentType.getInteger(ctx, "salida_seg") * 20;
                            for(GameProfile profile : parseTargets(ctx, "target")) {
                                ServerPlayer p = ctx.getSource().getServer().getPlayerList().getPlayerByName(profile.getName());
                                if (p != null) SoporteMod.NETWORK.send(PacketDistributor.PLAYER.with(() -> p), new SoporteMod.SoundSyncPacket(id, cat, false, vol, fIn, fOut));
                            }
                            ctx.getSource().sendSuccess(() -> Component.literal("§a[Audio] §fReproduciendo."), true); return 1;
                        }))))))))
                        .then(Commands.literal("stop").then(Commands.argument("playsounds", ResourceLocationArgument.id()).suggests(net.minecraft.commands.synchronization.SuggestionProviders.AVAILABLE_SOUNDS).then(Commands.argument("target", GameProfileArgument.gameProfile()).executes(ctx -> {
                            String id = ResourceLocationArgument.getId(ctx, "playsounds").toString();
                            for(GameProfile profile : parseTargets(ctx, "target")) {
                                ServerPlayer p = ctx.getSource().getServer().getPlayerList().getPlayerByName(profile.getName());
                                if (p != null) SoporteMod.NETWORK.send(PacketDistributor.PLAYER.with(() -> p), new SoporteMod.SoundSyncPacket(id, "master", true, 0, 0, 40));
                            }
                            ctx.getSource().sendSuccess(() -> Component.literal("§c[Audio] §fDeteniendo '"+id+"'."), true); return 1;
                        }))))
                        .then(Commands.literal("global_play").then(Commands.argument("playsounds", ResourceLocationArgument.id()).suggests(net.minecraft.commands.synchronization.SuggestionProviders.AVAILABLE_SOUNDS).then(Commands.argument("categoria", StringArgumentType.word()).suggests(SUGGEST_AUDIO_CATEGORIES).then(Commands.argument("volumen", FloatArgumentType.floatArg(0.0f)).then(Commands.argument("entrada_seg", IntegerArgumentType.integer(0)).executes(ctx -> {
                            String id = ResourceLocationArgument.getId(ctx, "playsounds").toString(); String cat = StringArgumentType.getString(ctx, "categoria"); float vol = FloatArgumentType.getFloat(ctx, "volumen"); int fIn = IntegerArgumentType.getInteger(ctx, "entrada_seg") * 20;
                            SoporteMod.DATA.globalMusic.soundId = id; SoporteMod.DATA.globalMusic.category = cat; SoporteMod.DATA.globalMusic.vol = vol; SoporteMod.DATA.globalMusic.fadeIn = fIn;
                            SoporteMod.NETWORK.send(PacketDistributor.ALL.noArg(), new SoporteMod.SoundSyncPacket(id, cat, false, vol, fIn, 0)); ctx.getSource().sendSuccess(() -> Component.literal("§a[Global Audio] §fMúsica guardada."), true); return 1;
                        }))))))
                        .then(Commands.literal("global_stop").executes(ctx -> {
                            if (SoporteMod.DATA.globalMusic.soundId != null && !SoporteMod.DATA.globalMusic.soundId.isEmpty()) { SoporteMod.NETWORK.send(PacketDistributor.ALL.noArg(), new SoporteMod.SoundSyncPacket(SoporteMod.DATA.globalMusic.soundId, SoporteMod.DATA.globalMusic.category, true, 0, 0, 60)); SoporteMod.DATA.globalMusic.soundId = ""; ctx.getSource().sendSuccess(() -> Component.literal("§c[Global Audio] §fMúsica global detenida."), true); }
                            return 1;
                        }))
                )

                // --- 3. RUTINAS ---
                .then(Commands.literal("rutina")
                        .then(Commands.literal("save").then(Commands.argument("nombre", StringArgumentType.word()).then(Commands.argument("secuencia", StringArgumentType.greedyString()).executes(ctx -> {
                            String nombre = StringArgumentType.getString(ctx, "nombre"); String sec = StringArgumentType.getString(ctx, "secuencia"); SoporteMod.DATA.rutinas.put(nombre, sec);
                            ctx.getSource().sendSuccess(() -> Component.literal("§aRutina '" + nombre + "' guardada."), true); return 1;
                        }))))
                        .then(Commands.literal("run").then(Commands.argument("nombre", StringArgumentType.word()).suggests(SUGGEST_RUTINAS).then(Commands.argument("target", GameProfileArgument.gameProfile()).executes(ctx -> {
                            String nombre = StringArgumentType.getString(ctx, "nombre");
                            if(SoporteMod.DATA.rutinas == null || !SoporteMod.DATA.rutinas.containsKey(nombre)) { ctx.getSource().sendFailure(Component.literal("§cRutina no existe.")); return 0; }
                            String sec = SoporteMod.DATA.rutinas.get(nombre);
                            for(GameProfile profile : parseTargets(ctx, "target")) {
                                String[] pasos = sec.split("\\|");
                                for(String paso : pasos) {
                                    String[] partes = paso.split(";");
                                    if(partes.length > 0 && !partes[0].trim().isEmpty()) {
                                        ServerPlayer p = ctx.getSource().getServer().getPlayerList().getPlayerByName(profile.getName());
                                        if (p != null) SoporteMod.NETWORK.send(PacketDistributor.PLAYER.with(() -> p), new SoporteMod.SoundSyncPacket(partes[0].trim(), "master", false, 1.0f, 0, 0));
                                    }
                                    if(partes.length > 1 && !partes[1].trim().isEmpty()) {
                                        ctx.getSource().getServer().getCommands().performPrefixedCommand(ctx.getSource().getServer().createCommandSourceStack(), partes[1].trim().replace("%player%", profile.getName()));
                                    }
                                }
                            }
                            ctx.getSource().sendSuccess(() -> Component.literal("§aRutina ejecutada."), true); return 1;
                        }))))
                )

                // --- 4. SHOWTIMER ---
                .then(Commands.literal("showtimer").then(Commands.argument("target", GameProfileArgument.gameProfile()).then(Commands.argument("time", StringArgumentType.string()).then(Commands.argument("background", StringArgumentType.string()).then(Commands.argument("command", StringArgumentType.greedyString()).executes(ctx -> {
                    Collection<GameProfile> targets = parseTargets(ctx, "target");
                    String timeStr = StringArgumentType.getString(ctx, "time"); String bg = StringArgumentType.getString(ctx, "background"); String command = StringArgumentType.getString(ctx, "command");
                    String[] parts = timeStr.split(":"); int totalSeconds = 0;
                    if(parts.length == 3) totalSeconds = Integer.parseInt(parts[0])*3600 + Integer.parseInt(parts[1])*60 + Integer.parseInt(parts[2]); else { ctx.getSource().sendFailure(Component.literal("§cFormato incorrecto. Usa HH:MM:SS")); return 0; }
                    for(GameProfile profile : targets) {
                        SoporteMod.ServerTimer timer = new SoporteMod.ServerTimer(); timer.ticksRemaining = totalSeconds * 20; timer.background = bg; timer.command = command;

                        UUID id1 = profile.getId();
                        UUID id2 = java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + profile.getName()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        SoporteMod.serverTimers.put(id1, timer);
                        SoporteMod.serverTimers.put(id2, timer);

                        ServerPlayer p = ctx.getSource().getServer().getPlayerList().getPlayerByName(profile.getName());
                        if (p != null) {
                            SoporteMod.serverTimers.put(p.getUUID(), timer);
                            SoporteMod.NETWORK.send(PacketDistributor.PLAYER.with(() -> p), new SoporteMod.TimerSyncPacket(timer.ticksRemaining, bg));
                        }
                    }
                    ctx.getSource().sendSuccess(() -> Component.literal("§a[Timer] §fIniciado para " + targets.size() + " jugador(es)."), true); return 1;
                }))))))

                // --- 5. TABULADOR ---
                .then(Commands.literal("tabulador")
                        .then(Commands.literal("preset").then(Commands.argument("nombre", StringArgumentType.word()).suggests(SUGGEST_PRESETS).executes(ctx -> {
                            String preset = StringArgumentType.getString(ctx, "nombre"); SoporteMod.DATA.tabHeaderLines.clear(); SoporteMod.DATA.tabFooterLines.clear();
                            if (preset.equalsIgnoreCase("rpg")) {
                                SoporteMod.DATA.tabHeaderLines.add(" "); SoporteMod.DATA.tabHeaderLines.add("&6&l✦ LUXFIRO RPG ✦"); SoporteMod.DATA.tabHeaderLines.add("&eJugadores: &f%online% &ePing: &f%ping%ms"); SoporteMod.DATA.tabHeaderLines.add(" ");
                                SoporteMod.DATA.tabFooterLines.add(" "); SoporteMod.DATA.tabFooterLines.add("&7Explora, Lucha, Conquista"); SoporteMod.DATA.tabFooterLines.add("&c⚔ &fplay.luxfiro.com &c⚔"); SoporteMod.DATA.tabFooterLines.add(" ");
                            } else if (preset.equalsIgnoreCase("moderno")) {
                                SoporteMod.DATA.tabHeaderLines.add("&8&m                                                "); SoporteMod.DATA.tabHeaderLines.add("&b&l⚡ LUXFIRO NETWORK ⚡"); SoporteMod.DATA.tabHeaderLines.add("&fBienvenido aventurero, &a%player%");
                                SoporteMod.DATA.tabFooterLines.add("&3Tienda: &btienda.luxfiro.com"); SoporteMod.DATA.tabFooterLines.add("&8&m                                                ");
                            } else if (preset.equalsIgnoreCase("magico")) {
                                SoporteMod.DATA.tabHeaderLines.add(" "); SoporteMod.DATA.tabHeaderLines.add("&d&l⚚ REINO DE LUXFIRO ⚚"); SoporteMod.DATA.tabHeaderLines.add("&5Tus aventuras mágicas comienzan aquí"); SoporteMod.DATA.tabHeaderLines.add(" ");
                                SoporteMod.DATA.tabFooterLines.add(" "); SoporteMod.DATA.tabFooterLines.add("&b☄ &fDiscord: discord.gg/luxfiro &b☄"); SoporteMod.DATA.tabFooterLines.add(" ");
                            } else if (preset.equalsIgnoreCase("tecnologico")) {
                                SoporteMod.DATA.tabHeaderLines.add("&b&l[ ☢ LUXFIRO CORE ☢ ]"); SoporteMod.DATA.tabHeaderLines.add("&3Sistemas operativos: &f%online%"); SoporteMod.DATA.tabFooterLines.add("&8[ &fLatencia de red: %ping%ms &8]");
                            } else if (preset.equalsIgnoreCase("limpio")) {
                                SoporteMod.DATA.tabHeaderLines.add("&aLuxfiro Network"); SoporteMod.DATA.tabFooterLines.add("&7Online: &a%online%");
                            }
                            updateTabListGlobal(ctx.getSource()); ctx.getSource().sendSuccess(() -> Component.literal("§a[Tabulador] §fPreset aplicado."), true); return 1;
                        })))
                        .then(Commands.literal("header")
                                .then(Commands.literal("add").then(Commands.argument("texto", StringArgumentType.greedyString()).executes(ctx -> { SoporteMod.DATA.tabHeaderLines.add(StringArgumentType.getString(ctx, "texto")); updateTabListGlobal(ctx.getSource()); ctx.getSource().sendSuccess(() -> Component.literal("§aAñadido."), true); return 1; })))
                                .then(Commands.literal("insert").then(Commands.argument("linea", IntegerArgumentType.integer(0)).then(Commands.argument("texto", StringArgumentType.greedyString()).executes(ctx -> { int line = IntegerArgumentType.getInteger(ctx, "linea"); if(line >= 0 && line <= SoporteMod.DATA.tabHeaderLines.size()) { SoporteMod.DATA.tabHeaderLines.add(line, StringArgumentType.getString(ctx, "texto")); updateTabListGlobal(ctx.getSource()); ctx.getSource().sendSuccess(() -> Component.literal("§aInsertado."), true); } return 1; }))))
                                .then(Commands.literal("set").then(Commands.argument("linea", IntegerArgumentType.integer(0)).then(Commands.argument("texto", StringArgumentType.greedyString()).executes(ctx -> { int line = IntegerArgumentType.getInteger(ctx, "linea"); if(line >= 0 && line < SoporteMod.DATA.tabHeaderLines.size()) { SoporteMod.DATA.tabHeaderLines.set(line, StringArgumentType.getString(ctx, "texto")); updateTabListGlobal(ctx.getSource()); ctx.getSource().sendSuccess(() -> Component.literal("§aModificado."), true); } return 1; }))))
                                .then(Commands.literal("remove").then(Commands.argument("linea", IntegerArgumentType.integer(0)).executes(ctx -> { int line = IntegerArgumentType.getInteger(ctx, "linea"); if(line >= 0 && line < SoporteMod.DATA.tabHeaderLines.size()) { SoporteMod.DATA.tabHeaderLines.remove(line); updateTabListGlobal(ctx.getSource()); ctx.getSource().sendSuccess(() -> Component.literal("§cEliminado."), true); } return 1; })))
                                .then(Commands.literal("clear").executes(ctx -> { SoporteMod.DATA.tabHeaderLines.clear(); updateTabListGlobal(ctx.getSource()); ctx.getSource().sendSuccess(() -> Component.literal("§cLimpiado."), true); return 1; }))
                        )
                        .then(Commands.literal("footer")
                                .then(Commands.literal("add").then(Commands.argument("texto", StringArgumentType.greedyString()).executes(ctx -> { SoporteMod.DATA.tabFooterLines.add(StringArgumentType.getString(ctx, "texto")); updateTabListGlobal(ctx.getSource()); ctx.getSource().sendSuccess(() -> Component.literal("§aAñadido."), true); return 1; })))
                                .then(Commands.literal("insert").then(Commands.argument("linea", IntegerArgumentType.integer(0)).then(Commands.argument("texto", StringArgumentType.greedyString()).executes(ctx -> { int line = IntegerArgumentType.getInteger(ctx, "linea"); if(line >= 0 && line <= SoporteMod.DATA.tabFooterLines.size()) { SoporteMod.DATA.tabFooterLines.add(line, StringArgumentType.getString(ctx, "texto")); updateTabListGlobal(ctx.getSource()); ctx.getSource().sendSuccess(() -> Component.literal("§aInsertado."), true); } return 1; }))))
                                .then(Commands.literal("set").then(Commands.argument("linea", IntegerArgumentType.integer(0)).then(Commands.argument("texto", StringArgumentType.greedyString()).executes(ctx -> { int line = IntegerArgumentType.getInteger(ctx, "linea"); if(line >= 0 && line < SoporteMod.DATA.tabFooterLines.size()) { SoporteMod.DATA.tabFooterLines.set(line, StringArgumentType.getString(ctx, "texto")); updateTabListGlobal(ctx.getSource()); ctx.getSource().sendSuccess(() -> Component.literal("§aModificado."), true); } return 1; }))))
                                .then(Commands.literal("remove").then(Commands.argument("linea", IntegerArgumentType.integer(0)).executes(ctx -> { int line = IntegerArgumentType.getInteger(ctx, "linea"); if(line >= 0 && line < SoporteMod.DATA.tabFooterLines.size()) { SoporteMod.DATA.tabFooterLines.remove(line); updateTabListGlobal(ctx.getSource()); ctx.getSource().sendSuccess(() -> Component.literal("§cEliminado."), true); } return 1; })))
                                .then(Commands.literal("clear").executes(ctx -> { SoporteMod.DATA.tabFooterLines.clear(); updateTabListGlobal(ctx.getSource()); ctx.getSource().sendSuccess(() -> Component.literal("§cLimpiado."), true); return 1; }))
                        )
                )

                // --- 6. WAYPOINTS ---
                .then(Commands.literal("waypoint")
                        .then(Commands.literal("list").executes(ctx -> {
                            if (SoporteMod.DATA == null || SoporteMod.DATA.waypoints.isEmpty()) {
                                ctx.getSource().sendSuccess(() -> Component.literal("§cNo hay waypoints registrados."), false);
                                return 0;
                            }
                            ctx.getSource().sendSuccess(() -> Component.literal("§6--- Lista de Waypoints ---"), false);
                            for (SoporteMod.Waypoint wp : SoporteMod.DATA.waypoints) {
                                ctx.getSource().sendSuccess(() -> Component.literal("§eID: §f" + wp.id + " §7| §eTítulo: §f" + wp.title), false);
                            }
                            return 1;
                        }))
                        .then(Commands.literal("hud")
                                .then(Commands.argument("state", BoolArgumentType.bool())
                                        .then(Commands.argument("targets", GameProfileArgument.gameProfile())
                                                .then(Commands.argument("posicion", StringArgumentType.word())
                                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(new String[]{"top_center", "top_left", "top_right", "center_left", "center_right", "bottom_center", "bottom_left", "bottom_right"}, builder))
                                                        .executes(ctx -> {
                                                            boolean state = BoolArgumentType.getBool(ctx, "state");
                                                            String pos = StringArgumentType.getString(ctx, "posicion");
                                                            for(GameProfile profile : parseTargets(ctx, "targets")) {
                                                                UUID id1 = profile.getId();
                                                                UUID id2 = java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + profile.getName()).getBytes(java.nio.charset.StandardCharsets.UTF_8));

                                                                SoporteMod.PlayerKeySettings keys = SoporteMod.DATA.playerKeys.computeIfAbsent(id1, k -> new SoporteMod.PlayerKeySettings());
                                                                SoporteMod.DATA.playerKeys.put(id2, keys);

                                                                keys.showHud = state;
                                                                keys.hudPos = pos;

                                                                ServerPlayer p = ctx.getSource().getServer().getPlayerList().getPlayerByName(profile.getName());
                                                                if (p != null) {
                                                                    SoporteMod.DATA.playerKeys.put(p.getUUID(), keys);
                                                                    SoporteMod.NETWORK.send(PacketDistributor.PLAYER.with(() -> p), new SoporteMod.KeySyncPacket(SoporteMod.GSON.toJson(keys)));
                                                                }
                                                            }
                                                            ctx.getSource().sendSuccess(() -> Component.literal("§b[Rastreador] §fHUD actualizado exitosamente."), true); return 1;
                                                        })
                                                )
                                        )
                                )
                        )
                        .then(Commands.literal("create")
                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                        .then(Commands.argument("target", GameProfileArgument.gameProfile())
                                                .then(Commands.argument("id", StringArgumentType.word())
                                                        .then(Commands.argument("titulo", StringArgumentType.string())
                                                                .then(Commands.argument("color", StringArgumentType.string()).suggests(SUGGEST_COLORS)
                                                                        .then(Commands.argument("scale", FloatArgumentType.floatArg(0.1f, 50.0f))
                                                                                .then(Commands.argument("subtext", StringArgumentType.string())
                                                                                        .executes(ctx -> {
                                                                                            SoporteMod.Waypoint wp = new SoporteMod.Waypoint();
                                                                                            wp.id = StringArgumentType.getString(ctx, "id");

                                                                                            String rawTitle = StringArgumentType.getString(ctx, "titulo");
                                                                                            wp.title = (rawTitle.startsWith("{") || rawTitle.startsWith("[")) ? rawTitle.replace("'", "\"") : rawTitle;

                                                                                            String rawSubtext = StringArgumentType.getString(ctx, "subtext");
                                                                                            wp.subtext = (rawSubtext.startsWith("{") || rawSubtext.startsWith("[")) ? rawSubtext.replace("'", "\"") : rawSubtext;

                                                                                            Collection<GameProfile> targets = parseTargets(ctx, "target");
                                                                                            wp.target = targets.size() == 1 ? targets.iterator().next().getName() : "Grupo";
                                                                                            Vec3 pos = Vec3Argument.getVec3(ctx, "pos"); wp.x = pos.x; wp.y = pos.y; wp.z = pos.z;
                                                                                            wp.color = StringArgumentType.getString(ctx, "color").toLowerCase();
                                                                                            wp.scale = FloatArgumentType.getFloat(ctx, "scale");
                                                                                            wp.style = "moderno";

                                                                                            if (SoporteMod.DATA.waypoints == null) SoporteMod.DATA.waypoints = new ArrayList<>();
                                                                                            SoporteMod.DATA.waypoints.add(wp);
                                                                                            SoporteMod.NETWORK.send(PacketDistributor.ALL.noArg(), new SoporteMod.WaypointSyncPacket(SoporteMod.GSON.toJson(SoporteMod.DATA.waypoints)));
                                                                                            ctx.getSource().sendSuccess(() -> Component.literal("§a[Waypoint] §fCreado exitosamente. ID: §e" + wp.id), true); return 1;
                                                                                        })
                                                                                )
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                        .then(Commands.literal("modify")
                                .then(Commands.argument("id", StringArgumentType.word()).suggests(SUGGEST_WAYPOINTS)
                                        .then(Commands.literal("text").then(Commands.argument("value", StringArgumentType.greedyString()).executes(ctx -> modifyWaypoint(ctx, "text"))))
                                        .then(Commands.literal("color").then(Commands.argument("value", StringArgumentType.word()).suggests(SUGGEST_COLORS).executes(ctx -> modifyWaypoint(ctx, "color"))))
                                        .then(Commands.literal("size").then(Commands.argument("value", FloatArgumentType.floatArg(0.1f, 50.0f)).executes(ctx -> modifyWaypoint(ctx, "size"))))
                                )
                        )
                        .then(Commands.literal("tp").then(Commands.argument("id", StringArgumentType.word()).suggests(SUGGEST_WAYPOINTS).executes(ctx -> {
                            if (!ctx.getSource().isPlayer()) { ctx.getSource().sendFailure(Component.literal("§cEste comando solo puede ser ejecutado por un jugador.")); return 0; }
                            String id = StringArgumentType.getString(ctx, "id");
                            if (SoporteMod.DATA != null && SoporteMod.DATA.waypoints != null) {
                                for(SoporteMod.Waypoint wp : SoporteMod.DATA.waypoints) {
                                    if(wp.id.equals(id)) { ctx.getSource().getPlayerOrException().teleportTo(wp.x, wp.y, wp.z); ctx.getSource().sendSuccess(() -> Component.literal("§aTeletransportado."), true); return 1; }
                                }
                            }
                            ctx.getSource().sendFailure(Component.literal("§cWaypoint no encontrado.")); return 0;
                        })))
                        .then(Commands.literal("remove").then(Commands.argument("id", StringArgumentType.word()).suggests(SUGGEST_WAYPOINTS).executes(ctx -> {
                            String id = StringArgumentType.getString(ctx, "id");
                            if (SoporteMod.DATA != null && SoporteMod.DATA.waypoints != null && SoporteMod.DATA.waypoints.removeIf(wp -> wp.id.equals(id))) {
                                SoporteMod.NETWORK.send(PacketDistributor.ALL.noArg(), new SoporteMod.WaypointSyncPacket(SoporteMod.GSON.toJson(SoporteMod.DATA.waypoints)));
                                ctx.getSource().sendSuccess(() -> Component.literal("§aWaypoint '" + id + "' eliminado permanentemente."), true);
                            } else {
                                ctx.getSource().sendFailure(Component.literal("§cNo se encontró ningún waypoint con el ID: " + id));
                            }
                            return 1;
                        })))
                        .then(Commands.literal("style").then(Commands.argument("id", StringArgumentType.word()).suggests(SUGGEST_WAYPOINTS).then(Commands.argument("style", StringArgumentType.word()).suggests(SUGGEST_THEMES).executes(ctx -> {
                            String id = StringArgumentType.getString(ctx, "id"); String style = StringArgumentType.getString(ctx, "style");
                            if (SoporteMod.DATA != null && SoporteMod.DATA.waypoints != null) {
                                for (SoporteMod.Waypoint wp : SoporteMod.DATA.waypoints) {
                                    if (wp.id.equalsIgnoreCase(id)) {
                                        wp.style = style;
                                        SoporteMod.NETWORK.send(PacketDistributor.ALL.noArg(), new SoporteMod.WaypointSyncPacket(SoporteMod.GSON.toJson(SoporteMod.DATA.waypoints)));
                                        ctx.getSource().sendSuccess(() -> Component.literal("§bEstilo actualizado a " + style), false); return 1;
                                    }
                                }
                            }
                            return 0;
                        }))))
                )

                // --- 7. ZONAS Y PROTECCIÓN ---
                .then(Commands.literal("badmobs").then(Commands.literal("activate").then(Commands.argument("entity", ResourceLocationArgument.id()).suggests((ctx, builder) -> SharedSuggestionProvider.suggestResource(net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKeys(), builder)).executes(ctx -> { SoporteMod.DATA.badMobs.add(ResourceLocationArgument.getId(ctx, "entity").toString()); ctx.getSource().sendSuccess(() -> Component.literal("§cBloqueado"), true); return 1; }))).then(Commands.literal("desactivate").then(Commands.argument("entity", ResourceLocationArgument.id()).executes(ctx -> { SoporteMod.DATA.badMobs.remove(ResourceLocationArgument.getId(ctx, "entity").toString()); ctx.getSource().sendSuccess(() -> Component.literal("§aDesbloqueado"), true); return 1; }))))
                .then(Commands.literal("protection")
                        .then(Commands.literal("info").executes(ctx -> {
                            if (!ctx.getSource().isPlayer()) { ctx.getSource().sendFailure(Component.literal("§cEste comando solo puede ser ejecutado por un jugador.")); return 0; }
                            ServerPlayer p = ctx.getSource().getPlayerOrException(); SoporteMod.Region r = ModEvents.getRegionAt(p); if (r == null) { ctx.getSource().sendSuccess(() -> Component.literal("§e[Protection] §cNo te encuentras dentro de ninguna región registrada."), false); } else { ctx.getSource().sendSuccess(() -> Component.literal("§a=== REGIÓN: §f" + r.name + " §a==="), false); for (Map.Entry<String, String> flag : r.flags.entrySet()) { ctx.getSource().sendSuccess(() -> Component.literal(" §e- " + flag.getKey() + ": §b" + flag.getValue()), false); } } return 1;
                        }))
                        .then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.string()).then(Commands.argument("pos1", Vec3Argument.vec3()).then(Commands.argument("pos2", Vec3Argument.vec3()).executes(ctx -> { SoporteMod.DATA.regions.add(new SoporteMod.Region(StringArgumentType.getString(ctx, "name"), Vec3Argument.getVec3(ctx, "pos1"), Vec3Argument.getVec3(ctx, "pos2"))); ctx.getSource().sendSuccess(() -> Component.literal("§a[Protection] §fRegión creada."), true); return 1; })))))
                        .then(Commands.literal("remove").then(Commands.argument("name", StringArgumentType.string()).suggests(SUGGEST_REGIONS).executes(ctx -> { if(SoporteMod.DATA != null && SoporteMod.DATA.regions != null && SoporteMod.DATA.regions.removeIf(r -> r.name.equals(StringArgumentType.getString(ctx, "name")))) ctx.getSource().sendSuccess(() -> Component.literal("§e[Protection] §fZona eliminada."), true); return 1; })))
                        .then(Commands.literal("list").executes(ctx -> { ctx.getSource().sendSuccess(() -> Component.literal("§6Zonas Registradas:"), false); if (SoporteMod.DATA != null && SoporteMod.DATA.regions != null) { for (SoporteMod.Region r : SoporteMod.DATA.regions) ctx.getSource().sendSuccess(() -> Component.literal(" - §b" + r.name), false); } return 1; }))
                        .then(Commands.literal("modify")
                                .then(Commands.argument("name", StringArgumentType.word()).suggests(SUGGEST_REGIONS)
                                        .then(Commands.argument("flag", StringArgumentType.word()).suggests(SUGGEST_PROTECTION_FLAGS)
                                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                                        .executes(ctx -> {
                                                            String region = StringArgumentType.getString(ctx, "name"); String flag = StringArgumentType.getString(ctx, "flag"); String value = StringArgumentType.getString(ctx, "value");
                                                            if(updateRegionFlagLogic(ctx, region, flag, value)) { ctx.getSource().sendSuccess(() -> Component.literal("§e[Protection] Regla §b" + flag + " §een §a" + region + " §eactualizada a: §f" + value), true); } else { ctx.getSource().sendFailure(Component.literal("§cLa región '" + region + "' no existe.")); } return 1;
                                                        })
                                                )
                                        )
                                )
                        )
                        .then(Commands.literal("attribute").then(Commands.argument("name", StringArgumentType.string()).suggests(SUGGEST_REGIONS)
                                .then(Commands.literal("gamemode").then(Commands.argument("mode", StringArgumentType.word()).suggests(SUGGEST_GAMEMODES).executes(ctx -> { updateRegionFlagLogic(ctx, StringArgumentType.getString(ctx, "name"), "gamemode", StringArgumentType.getString(ctx, "mode")); ctx.getSource().sendSuccess(() -> Component.literal("§eGamemode actualizado."), true); return 1; })))
                                .then(Commands.literal("frog").then(Commands.argument("variant", StringArgumentType.word()).suggests(SUGGEST_FROG_VARIANTS).executes(ctx -> { updateRegionFlagLogic(ctx, StringArgumentType.getString(ctx, "name"), "frog", StringArgumentType.getString(ctx, "variant")); ctx.getSource().sendSuccess(() -> Component.literal("§eRana actualizada."), true); return 1; })))
                                .then(Commands.literal("msg").then(Commands.argument("text", StringArgumentType.greedyString()).executes(ctx -> { updateRegionFlagLogic(ctx, StringArgumentType.getString(ctx, "name"), "greeting", StringArgumentType.getString(ctx, "text")); ctx.getSource().sendSuccess(() -> Component.literal("§eMensaje actualizado."), true); return 1; })))
                                .then(Commands.literal("sound").then(Commands.argument("sound_id", ResourceLocationArgument.id()).suggests(net.minecraft.commands.synchronization.SuggestionProviders.AVAILABLE_SOUNDS).then(Commands.argument("categoria", StringArgumentType.word()).suggests(SUGGEST_AUDIO_CATEGORIES).then(Commands.argument("volumen", FloatArgumentType.floatArg(0.0f)).then(Commands.argument("entrada_seg", IntegerArgumentType.integer(0)).then(Commands.argument("salida_seg", IntegerArgumentType.integer(0)).executes(ctx -> {
                                    String id = ResourceLocationArgument.getId(ctx, "sound_id").toString(); String cat = StringArgumentType.getString(ctx, "categoria"); float vol = FloatArgumentType.getFloat(ctx, "volumen"); int in = IntegerArgumentType.getInteger(ctx, "entrada_seg") * 20; int out = IntegerArgumentType.getInteger(ctx, "salida_seg") * 20;
                                    String data = id + "," + cat + "," + vol + "," + in + "," + out; updateRegionFlagLogic(ctx, StringArgumentType.getString(ctx, "name"), "sound", data);
                                    ctx.getSource().sendSuccess(() -> Component.literal("§eSonido actualizado."), true); return 1;
                                })))))))
                        ))
                )

                // --- 8. KEYS (RESTRICCIONES) ---
                .then(Commands.literal("key")
                        .then(Commands.literal("space").then(Commands.argument("state", BoolArgumentType.bool()).then(Commands.argument("target", GameProfileArgument.gameProfile()).executes(ctx -> setKeyFlag(ctx, "space", BoolArgumentType.getBool(ctx, "state"))))))
                        .then(Commands.literal("move").then(Commands.argument("state", BoolArgumentType.bool()).then(Commands.argument("target", GameProfileArgument.gameProfile()).executes(ctx -> setKeyFlag(ctx, "move", BoolArgumentType.getBool(ctx, "state"))))))
                        .then(Commands.literal("show").then(Commands.argument("state", BoolArgumentType.bool()).then(Commands.argument("target", GameProfileArgument.gameProfile()).executes(ctx -> setKeyFlag(ctx, "show", BoolArgumentType.getBool(ctx, "state"))))))
                        .then(Commands.literal("cordenadas").then(Commands.argument("state", BoolArgumentType.bool()).then(Commands.argument("target", GameProfileArgument.gameProfile()).executes(ctx -> setKeyFlag(ctx, "cordenadas", BoolArgumentType.getBool(ctx, "state"))))))
                        .then(Commands.literal("inventario").then(Commands.argument("state", BoolArgumentType.bool()).then(Commands.argument("target", GameProfileArgument.gameProfile()).executes(ctx -> setKeyFlag(ctx, "inventario", BoolArgumentType.getBool(ctx, "state"))))))
                        .then(Commands.literal("drop").then(Commands.argument("state", BoolArgumentType.bool()).then(Commands.argument("target", GameProfileArgument.gameProfile()).executes(ctx -> setKeyFlag(ctx, "drop", BoolArgumentType.getBool(ctx, "state"))))))
                        .then(Commands.literal("attack").then(Commands.argument("state", BoolArgumentType.bool()).then(Commands.argument("target", GameProfileArgument.gameProfile()).executes(ctx -> setKeyFlag(ctx, "attack", BoolArgumentType.getBool(ctx, "state"))))))
                        .then(Commands.literal("interact").then(Commands.argument("state", BoolArgumentType.bool()).then(Commands.argument("target", GameProfileArgument.gameProfile()).executes(ctx -> setKeyFlag(ctx, "interact", BoolArgumentType.getBool(ctx, "state"))))))
                        .then(Commands.literal("sneak").then(Commands.argument("state", BoolArgumentType.bool()).then(Commands.argument("target", GameProfileArgument.gameProfile()).executes(ctx -> setKeyFlag(ctx, "sneak", BoolArgumentType.getBool(ctx, "state"))))))
                        .then(Commands.literal("sprint").then(Commands.argument("state", BoolArgumentType.bool()).then(Commands.argument("target", GameProfileArgument.gameProfile()).executes(ctx -> setKeyFlag(ctx, "sprint", BoolArgumentType.getBool(ctx, "state"))))))
                        .then(Commands.literal("first").then(Commands.argument("state", StringArgumentType.word()).then(Commands.argument("camera", IntegerArgumentType.integer(1, 3)).then(Commands.argument("target", GameProfileArgument.gameProfile()).executes(ctx -> {
                            String state = StringArgumentType.getString(ctx, "state"); int camera = IntegerArgumentType.getInteger(ctx, "camera");
                            for (GameProfile profile : parseTargets(ctx, "target")) {
                                UUID id1 = profile.getId();
                                UUID id2 = java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + profile.getName()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                SoporteMod.PlayerKeySettings keys = SoporteMod.DATA.playerKeys.computeIfAbsent(id1, k -> new SoporteMod.PlayerKeySettings());
                                SoporteMod.DATA.playerKeys.put(id2, keys);

                                keys.forceCamera = state.equalsIgnoreCase("force"); keys.cameraMode = camera;
                                ServerPlayer p = ctx.getSource().getServer().getPlayerList().getPlayerByName(profile.getName());
                                if (p != null) {
                                    SoporteMod.DATA.playerKeys.put(p.getUUID(), keys);
                                    SoporteMod.NETWORK.send(PacketDistributor.PLAYER.with(() -> p), new SoporteMod.KeySyncPacket(SoporteMod.GSON.toJson(keys)));
                                }
                            }
                            ctx.getSource().sendSuccess(() -> Component.literal("§a[Keys] §fCámara forzada."), true); return 1;
                        })))))
                        .then(Commands.literal("clear").then(Commands.argument("target", GameProfileArgument.gameProfile()).executes(ctx -> {
                            for(GameProfile profile : parseTargets(ctx, "target")) {
                                UUID id1 = profile.getId();
                                UUID id2 = java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + profile.getName()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                SoporteMod.DATA.playerKeys.remove(id1);
                                SoporteMod.DATA.playerKeys.remove(id2);

                                ServerPlayer p = ctx.getSource().getServer().getPlayerList().getPlayerByName(profile.getName());
                                if (p != null) {
                                    SoporteMod.DATA.playerKeys.remove(p.getUUID());
                                    SoporteMod.NETWORK.send(PacketDistributor.PLAYER.with(() -> p), new SoporteMod.KeySyncPacket("{}"));
                                }
                            }
                            ctx.getSource().sendSuccess(() -> Component.literal("§a[Keys] §fRestricciones limpiadas."), true); return 1;
                        })))
                )
        );

        // =========================================================================
        // COMANDO PÚBLICO PROFESIONAL: /soporte ticket <titulo> <usuario> <desc>
        // =========================================================================
        dispatcher.register(Commands.literal("soporte")
                .then(Commands.literal("ticket")
                        .then(Commands.argument("titulo", StringArgumentType.string())
                                .then(Commands.argument("usuario", StringArgumentType.word())
                                        .then(Commands.argument("descripcion", StringArgumentType.greedyString())
                                                .executes(ctx -> {
                                                    // PROTECCIÓN PARA CONSOLA
                                                    if (!ctx.getSource().isPlayer()) { ctx.getSource().sendFailure(Component.literal("Solo los jugadores pueden abrir tickets.")); return 0; }
                                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                                    String titulo = StringArgumentType.getString(ctx, "titulo");
                                                    String usuario = StringArgumentType.getString(ctx, "usuario");
                                                    String desc = StringArgumentType.getString(ctx, "descripcion");

                                                    String ticketID = UUID.randomUUID().toString().substring(0, 4).toUpperCase();
                                                    String ticketEntry = ticketID + "::" + player.getName().getString() + "::" + titulo + "::" + usuario + "::" + desc;
                                                    SoporteMod.DATA.tickets.add(ticketEntry);

                                                    player.sendSystemMessage(Component.literal("§8§m                                                "));
                                                    player.sendSystemMessage(Component.literal("§b§l   TICKET ENVIADO EXITOSAMENTE   "));
                                                    player.sendSystemMessage(Component.literal(" "));
                                                    player.sendSystemMessage(Component.literal(" §7■ §fAsunto: §e" + titulo));
                                                    player.sendSystemMessage(Component.literal(" §7■ §fReporta a: §c" + usuario));
                                                    player.sendSystemMessage(Component.literal(" §7■ §fID del Ticket: §7#" + ticketID));
                                                    player.sendSystemMessage(Component.literal("§8§m                                                "));

                                                    for (ServerPlayer p : ctx.getSource().getServer().getPlayerList().getPlayers()) {
                                                        if (p.hasPermissions(2)) {
                                                            p.sendSystemMessage(Component.literal("§8§m                                                "));
                                                            p.sendSystemMessage(Component.literal("§e§l   NUEVO TICKET DE SOPORTE   "));
                                                            p.sendSystemMessage(Component.literal(" §7Abierto por: §f" + player.getName().getString() + " §8| §7ID: §f#" + ticketID));
                                                            p.sendSystemMessage(Component.literal(" §7Asunto: §e" + titulo + " §8| §7Reporta a: §c" + usuario));
                                                            p.sendSystemMessage(Component.literal("§8§m                                                "));
                                                            p.playNotifySound(SoundEvents.NOTE_BLOCK_PLING.get(), SoundSource.PLAYERS, 1.0f, 1.0f);
                                                        }
                                                    }
                                                    return 1;
                                                })
                                        )
                                )
                        )
                )
                .then(Commands.literal("leer").requires(s -> s.hasPermission(2)).executes(ctx -> {
                    CommandSourceStack source = ctx.getSource();
                    source.sendSystemMessage(Component.literal("§8§m                                                "));
                    source.sendSystemMessage(Component.literal("§b§l     PANEL DE TICKETS - LUXFIRO NETWORK     "));
                    source.sendSystemMessage(Component.literal("§8§m                                                "));

                    if (SoporteMod.DATA.tickets.isEmpty()) {
                        source.sendSystemMessage(Component.literal("§7  No hay tickets pendientes en este momento."));
                    } else {
                        for (int i = 0; i < SoporteMod.DATA.tickets.size(); i++) {
                            String raw = SoporteMod.DATA.tickets.get(i);
                            String[] parts = raw.split("::", 5);
                            if(parts.length == 5) {
                                source.sendSystemMessage(Component.literal("§e🎫 Ticket #" + (i + 1) + " §8[§7ID: " + parts[0] + "§8]"));
                                source.sendSystemMessage(Component.literal("  §eAsunto: §f" + parts[2] + " §8| §cReporta a: §f" + parts[3]));
                                source.sendSystemMessage(Component.literal("  §7Abierto por: §b" + parts[1]));
                                source.sendSystemMessage(Component.literal("  §7Desc: §f" + parts[4]));
                                source.sendSystemMessage(Component.literal("  §8§o(Responder: /soporte responder " + parts[1] + " <msj>)"));
                            } else {
                                source.sendSystemMessage(Component.literal("§e🎫 Ticket #" + (i + 1) + " §8| §f" + raw));
                            }
                            if (i < SoporteMod.DATA.tickets.size() - 1) {
                                source.sendSystemMessage(Component.literal("§8  - - - - - - - - - - - - - - - - - - -"));
                            }
                        }
                    }
                    source.sendSystemMessage(Component.literal("§8§m                                                "));
                    return 1;
                }))
                .then(Commands.literal("borrar").requires(s -> s.hasPermission(2)).then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(ctx -> {
                    int id = IntegerArgumentType.getInteger(ctx, "id") - 1;
                    if (id >= 0 && id < SoporteMod.DATA.tickets.size()) { SoporteMod.DATA.tickets.remove(id); ctx.getSource().sendSystemMessage(Component.literal("§aTicket borrado exitosamente del registro.")); }
                    else { ctx.getSource().sendSystemMessage(Component.literal("§cID de ticket inválido.")); }
                    return 1;
                })))
                .then(Commands.literal("responder").requires(s -> s.hasPermission(2)).then(Commands.argument("jugador", StringArgumentType.word()).then(Commands.argument("mensaje", StringArgumentType.greedyString()).executes(ctx -> {
                    String jugador = StringArgumentType.getString(ctx, "jugador");
                    String mensaje = StringArgumentType.getString(ctx, "mensaje");
                    ServerPlayer target = ctx.getSource().getServer().getPlayerList().getPlayerByName(jugador);

                    if (target != null) {
                        target.sendSystemMessage(Component.literal("§8§m                                                "));
                        target.sendSystemMessage(Component.literal("§b§l  SOPORTE LUXFIRO - RESPUESTA  "));
                        target.sendSystemMessage(Component.literal(" "));
                        target.sendSystemMessage(Component.literal("§eUn administrador ha respondido a tu solicitud:"));
                        target.sendSystemMessage(Component.literal("§f> " + mensaje));
                        target.sendSystemMessage(Component.literal("§8§m                                                "));
                        target.playNotifySound(SoundEvents.NOTE_BLOCK_PLING.get(), SoundSource.PLAYERS, 1.0f, 1.0f);
                        ctx.getSource().sendSuccess(() -> Component.literal("§aRespuesta enviada correctamente a " + jugador + "."), true);
                    } else {
                        ctx.getSource().sendFailure(Component.literal("§cEl jugador " + jugador + " no está conectado."));
                    }
                    return 1;
                }))))
        );
    }

    private static int modifyWaypoint(CommandContext<CommandSourceStack> ctx, String property) {
        String id = StringArgumentType.getString(ctx, "id");
        SoporteMod.Waypoint wp = null;
        if (SoporteMod.DATA != null && SoporteMod.DATA.waypoints != null) {
            for (SoporteMod.Waypoint w : SoporteMod.DATA.waypoints) {
                if (w.id.equals(id)) { wp = w; break; }
            }
        }
        if (wp == null) {
            ctx.getSource().sendFailure(Component.literal("§cWaypoint '" + id + "' no encontrado."));
            return 0;
        }
        if (property.equals("text")) {
            String raw = StringArgumentType.getString(ctx, "value");
            wp.title = (raw.startsWith("{") || raw.startsWith("[")) ? raw.replace("'", "\"") : raw;
        } else if (property.equals("color")) {
            wp.color = StringArgumentType.getString(ctx, "value").toLowerCase();
        } else if (property.equals("size")) {
            wp.scale = FloatArgumentType.getFloat(ctx, "value");
        }
        SoporteMod.NETWORK.send(PacketDistributor.ALL.noArg(), new SoporteMod.WaypointSyncPacket(SoporteMod.GSON.toJson(SoporteMod.DATA.waypoints)));
        ctx.getSource().sendSuccess(() -> Component.literal("§aWaypoint '" + id + "' modificado correctamente."), true);
        return 1;
    }

    private static void updateTabListGlobal(CommandSourceStack source) { for (ServerPlayer player : source.getServer().getPlayerList().getPlayers()) updateTabListForPlayer(player); }

    public static void updateTabListForPlayer(ServerPlayer player) {
        if (SoporteMod.DATA == null || (SoporteMod.DATA.tabHeaderLines.isEmpty() && SoporteMod.DATA.tabFooterLines.isEmpty())) return;
        int onlineCount = player.server.getPlayerCount(); int ping = player.latency; String playerName = player.getDisplayName().getString();
        String headerStr = String.join("\n", SoporteMod.DATA.tabHeaderLines).replace("%online%", String.valueOf(onlineCount)).replace("%ping%", String.valueOf(ping)).replace("%player%", playerName);
        headerStr = translate(headerStr);
        String footerStr = String.join("\n", SoporteMod.DATA.tabFooterLines).replace("%online%", String.valueOf(onlineCount)).replace("%ping%", String.valueOf(ping)).replace("%player%", playerName);
        footerStr = translate(footerStr);
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundTabListPacket(Component.literal(headerStr), Component.literal(footerStr)));
    }

    private static int setKeyFlag(CommandContext<CommandSourceStack> ctx, String keyType, boolean state) throws CommandSyntaxException {
        Collection<GameProfile> targets = parseTargets(ctx, "target");

        for (GameProfile profile : targets) {
            UUID id1 = profile.getId();
            UUID id2 = java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + profile.getName()).getBytes(java.nio.charset.StandardCharsets.UTF_8));

            SoporteMod.PlayerKeySettings keys = SoporteMod.DATA.playerKeys.computeIfAbsent(id1, k -> new SoporteMod.PlayerKeySettings());
            SoporteMod.DATA.playerKeys.put(id2, keys); // Amarramos el UUID Local de golpe (Mata cualquier posible error)

            switch (keyType) {
                case "space": keys.disableSpace = state; break; case "move": keys.disableMove = state; break;
                case "show": keys.disableF1 = state; break; case "cordenadas": keys.disableF3 = state; break;
                case "inventario": keys.disableInventory = state; break; case "drop": keys.disableDrop = state; break;
                case "attack": keys.disableAttack = state; break; case "interact": keys.disableInteract = state; break;
                case "sneak": keys.disableSneak = state; break; case "sprint": keys.disableSprint = state; break;
            }

            ServerPlayer p = ctx.getSource().getServer().getPlayerList().getPlayerByName(profile.getName());
            if (p != null) {
                SoporteMod.DATA.playerKeys.put(p.getUUID(), keys);
                SoporteMod.NETWORK.send(PacketDistributor.PLAYER.with(() -> p), new SoporteMod.KeySyncPacket(SoporteMod.GSON.toJson(keys)));
            }
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§a[Keys] §fRegla '" + keyType + "' guardada (" + state + ")."), true);
        return 1;
    }

    private static boolean updateRegionFlagLogic(CommandContext<CommandSourceStack> ctx, String name, String flag, String value) {
        if (SoporteMod.DATA == null || SoporteMod.DATA.regions == null) return false;
        for(SoporteMod.Region r : SoporteMod.DATA.regions) {
            if(r.name.equals(name)) { r.flags.put(flag, value); return true; }
        }
        return false;
    }
}