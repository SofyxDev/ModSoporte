package sofyx.lux;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

@Mod(SoporteMod.MODID)
public class SoporteMod {
    public static final String MODID = "luxfirosoporte";
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static DataStorage DATA = new DataStorage();
    public static String RESOURCE_PACK_URL = "";
    public static String RESOURCE_PACK_HASH = "";

    public static Map<UUID, ServerTimer> serverTimers = new HashMap<>();
    public static int clientTimerTicks = 0;
    public static int clientMaxTimerTicks = 0;
    public static String clientTimerBg = "";

    private static final Map<UUID, String> playerCurrentRegions = new HashMap<>();

    private static final String PROTOCOL_VERSION = "1";

    @SuppressWarnings("removal")
    public static final SimpleChannel NETWORK = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MODID, "network"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    public SoporteMod() {
        MinecraftForge.EVENT_BUS.register(this);
        NETWORK.registerMessage(0, TimerSyncPacket.class, TimerSyncPacket::encode, TimerSyncPacket::decode, TimerSyncPacket::handle);
        NETWORK.registerMessage(1, KeySyncPacket.class, KeySyncPacket::encode, KeySyncPacket::decode, KeySyncPacket::handle);
        NETWORK.registerMessage(2, WaypointSyncPacket.class, WaypointSyncPacket::encode, WaypointSyncPacket::decode, WaypointSyncPacket::handle);
        NETWORK.registerMessage(3, SoundSyncPacket.class, SoundSyncPacket::encode, SoundSyncPacket::decode, SoundSyncPacket::handle);
    }

    @SubscribeEvent
    public void onCommandsRegister(RegisterCommandsEvent event) {
        ModCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStart(ServerStartingEvent event) {
        Path path = event.getServer().getWorldPath(LevelResource.ROOT).resolve("lux_soporte.json");
        if (Files.exists(path)) {
            try (Reader r = Files.newBufferedReader(path)) {
                DataStorage loadedData = GSON.fromJson(r, DataStorage.class);
                if (loadedData != null) DATA = loadedData;
            } catch (Exception e) { e.printStackTrace(); }
        } else {
            DATA = new DataStorage();
        }
        RESOURCE_PACK_URL = DATA.savedPackUrl != null ? DATA.savedPackUrl : "";
        RESOURCE_PACK_HASH = DATA.savedPackHash != null ? DATA.savedPackHash : "";
    }

    @SubscribeEvent
    public void onServerStop(ServerStoppingEvent event) {
        Path path = event.getServer().getWorldPath(LevelResource.ROOT).resolve("lux_soporte.json");
        try (Writer w = Files.newBufferedWriter(path)) { GSON.toJson(DATA, w); }
        catch (Exception e) { e.printStackTrace(); }
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {

            // Calculamos ambos UUIDs para asegurar encontrar la data guardada offline
            UUID currentId = player.getUUID();
            UUID offlineId = java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + player.getName().getString()).getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // 1. RESOURCE PACK EN SEGUNDO PLANO
            if (RESOURCE_PACK_URL != null && !RESOURCE_PACK_URL.isEmpty()) {
                player.sendTexturePack(RESOURCE_PACK_URL, RESOURCE_PACK_HASH, false, Component.literal("§e[Luxfiro] §fDescargando assets en segundo plano..."));
            }

            // 2. SINCRONIZAR KEYS (Movimiento, inventario, etc)
            PlayerKeySettings keys = DATA.playerKeys.get(currentId);
            if (keys == null) keys = DATA.playerKeys.get(offlineId); // Busca el respaldo offline si no encuentra el actual

            if (keys != null) {
                NETWORK.send(PacketDistributor.PLAYER.with(() -> player), new KeySyncPacket(GSON.toJson(keys)));
            } else {
                NETWORK.send(PacketDistributor.PLAYER.with(() -> player), new KeySyncPacket("{}"));
            }

            // 3. SINCRONIZAR TIMERS (Para que aparezcan al reconectarse)
            ServerTimer timer = serverTimers.get(currentId);
            if (timer == null) timer = serverTimers.get(offlineId); // Busca el respaldo offline

            if (timer != null && timer.ticksRemaining > 0) {
                NETWORK.send(PacketDistributor.PLAYER.with(() -> player), new TimerSyncPacket(timer.ticksRemaining, timer.background));
            }

            // 4. SINCRONIZAR WAYPOINTS Y TABLIST
            NETWORK.send(PacketDistributor.PLAYER.with(() -> player), new WaypointSyncPacket(GSON.toJson(DATA.waypoints)));
            ModCommands.updateTabListForPlayer(player);

            // 5. MÚSICA GLOBAL
            if (DATA.globalMusic != null && !DATA.globalMusic.soundId.isEmpty()) {
                NETWORK.send(PacketDistributor.PLAYER.with(() -> player), new SoundSyncPacket(DATA.globalMusic.soundId, DATA.globalMusic.category, false, DATA.globalMusic.vol, DATA.globalMusic.fadeIn, 0));
            }
        }
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.side.isServer() && event.player instanceof ServerPlayer player) {
            PlayerKeySettings keys = DATA.playerKeys.get(player.getUUID());
            if (keys != null && keys.disableSprint) player.setSprinting(false);

            if (player.tickCount % 20 == 0) ModCommands.updateTabListForPlayer(player);

            if (player.tickCount % 10 == 0) {
                BlockPos pos = player.blockPosition();
                Region currentRegObj = null;

                for (Region r : DATA.regions) {
                    if (r.isInside(pos)) { currentRegObj = r; break; }
                }

                String currentRegion = currentRegObj != null ? currentRegObj.name : null;
                String lastRegion = playerCurrentRegions.get(player.getUUID());

                if (!Objects.equals(currentRegion, lastRegion)) {
                    if (lastRegion != null) {
                        Region lastRegObj = getRegionByName(lastRegion);
                        if (lastRegObj != null && lastRegObj.flags.containsKey("sound")) {
                            String[] soundData = lastRegObj.flags.get("sound").split(",");
                            if (soundData.length >= 5) {
                                int out = Integer.parseInt(soundData[4]);
                                NETWORK.send(PacketDistributor.PLAYER.with(() -> player), new SoundSyncPacket(soundData[0], "master", true, 0, 0, out));
                            }
                        }
                    }
                    if (currentRegObj != null) {
                        if (currentRegObj.flags.containsKey("sound")) {
                            String[] soundData = currentRegObj.flags.get("sound").split(",");
                            if (soundData.length >= 5) {
                                float vol = Float.parseFloat(soundData[2]);
                                int in = Integer.parseInt(soundData[3]);
                                int out = Integer.parseInt(soundData[4]);
                                NETWORK.send(PacketDistributor.PLAYER.with(() -> player), new SoundSyncPacket(soundData[0], soundData[1], false, vol, in, out));
                            }
                        }
                        if (currentRegObj.flags.containsKey("greeting")) {
                            player.displayClientMessage(Component.literal(currentRegObj.flags.get("greeting").replace("&", "§")), true);
                        }
                        if (currentRegObj.flags.containsKey("gamemode")) {
                            String modeStr = currentRegObj.flags.get("gamemode");
                            GameType targetMode = "adventure".equalsIgnoreCase(modeStr) ? GameType.ADVENTURE : GameType.SURVIVAL;
                            if (player.gameMode.getGameModeForPlayer() != targetMode) {
                                player.setGameMode(targetMode);
                                player.displayClientMessage(Component.literal("§7[Región] Modo cambiado a " + targetMode.getName()), true);
                            }
                        }
                    }
                    playerCurrentRegions.put(player.getUUID(), currentRegion);
                }
                if (currentRegObj != null && "true".equalsIgnoreCase(currentRegObj.flags.get("frog"))) {
                    player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0, false, false, false));
                }
            }
        }
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer p && !p.hasPermissions(2)) {
            for (Region r : DATA.regions) {
                if (r.isInside(event.getPos())) {
                    if ("false".equalsIgnoreCase(r.flags.getOrDefault("break", "true")) || "false".equalsIgnoreCase(r.flags.getOrDefault("build", "true"))) {
                        event.setCanceled(true);
                        p.displayClientMessage(Component.literal("§cEsta zona está protegida."), true);
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public void onLivingAttack(LivingAttackEvent event) {
        if (event.getEntity() instanceof ServerPlayer victim && event.getSource().getEntity() instanceof ServerPlayer attacker) {
            if (attacker.hasPermissions(2)) return;
            for (Region r : DATA.regions) {
                if (r.isInside(victim.blockPosition()) || r.isInside(attacker.blockPosition())) {
                    if ("false".equalsIgnoreCase(r.flags.getOrDefault("pvp", "true"))) {
                        event.setCanceled(true);
                        attacker.displayClientMessage(Component.literal("§cEl PvP está desactivado en esta zona."), true);
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public void onMobSpawn(MobSpawnEvent.FinalizeSpawn event) {
        String id = EntityType.getKey(event.getEntity().getType()).toString();
        if (DATA.badMobs.contains(id)) {
            event.setSpawnCancelled(true);
            event.setResult(Event.Result.DENY);
            return;
        }
        BlockPos pos = BlockPos.containing(event.getX(), event.getY(), event.getZ());
        for (Region r : DATA.regions) {
            if (r.isInside(pos)) {
                if ("false".equalsIgnoreCase(r.flags.get("mob_spawning"))) {
                    event.setSpawnCancelled(true);
                    event.setResult(Event.Result.DENY);
                    break;
                }
            }
        }
    }

    public static Region getRegionByName(String name) {
        for (Region r : DATA.regions) { if (r.name.equals(name)) return r; }
        return null;
    }

    public static class DataStorage {
        public String savedPackUrl = "";
        public String savedPackHash = "";
        public List<Waypoint> waypoints = new ArrayList<>();
        public List<Region> regions = new ArrayList<>();
        public Set<String> badMobs = new HashSet<>();
        public Map<UUID, PlayerKeySettings> playerKeys = new HashMap<>();

        public List<String> tabHeaderLines = new ArrayList<>();
        public List<String> tabFooterLines = new ArrayList<>();
        public String tabTheme = "moderno";

        public GlobalSound globalMusic = new GlobalSound();
        public Map<String, String> rutinas = new HashMap<>();
        public List<String> tickets = new ArrayList<>();
    }

    public static class GlobalSound { public String soundId = ""; public String category = "master"; public float vol = 1.0f; public int fadeIn = 0; }

    public static class ServerTimer { public int ticksRemaining; public String command; public String background; }

    public static class PlayerKeySettings {
        public boolean forceCamera = false; public int cameraMode = 1; public boolean disableSpace = false; public boolean disableMove = false;
        public boolean disableF1 = false; public boolean disableF3 = false; public boolean disableInventory = false; public boolean disableDrop = false;
        public boolean disableAttack = false; public boolean disableInteract = false; public boolean disableSneak = false; public boolean disableSprint = false;
        public boolean showHud = false; public String hudPos = "top_center";
    }

    public static class TimerSyncPacket {
        public int ticks; public String bg; public TimerSyncPacket(int t, String b) { ticks = t; bg = b; }
        public static void encode(TimerSyncPacket msg, FriendlyByteBuf buf) { buf.writeInt(msg.ticks); buf.writeUtf(msg.bg); }
        public static TimerSyncPacket decode(FriendlyByteBuf buf) { return new TimerSyncPacket(buf.readInt(), buf.readUtf()); }
        public static void handle(TimerSyncPacket msg, Supplier<NetworkEvent.Context> ctx) { ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ModEvents.ClientEvents.updateTimer(msg.ticks, msg.bg))); ctx.get().setPacketHandled(true); }
    }

    public static class KeySyncPacket {
        public String json; public KeySyncPacket(String j) { json = j; }
        public static void encode(KeySyncPacket msg, FriendlyByteBuf buf) { buf.writeUtf(msg.json); }
        public static KeySyncPacket decode(FriendlyByteBuf buf) { return new KeySyncPacket(buf.readUtf()); }
        public static void handle(KeySyncPacket msg, Supplier<NetworkEvent.Context> ctx) { ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ModEvents.ClientEvents.updateKeys(msg.json))); ctx.get().setPacketHandled(true); }
    }

    public static class WaypointSyncPacket {
        public String json; public WaypointSyncPacket(String j) { json = j; }
        public static void encode(WaypointSyncPacket msg, FriendlyByteBuf buf) { buf.writeUtf(msg.json); }
        public static WaypointSyncPacket decode(FriendlyByteBuf buf) { return new WaypointSyncPacket(buf.readUtf()); }
        public static void handle(WaypointSyncPacket msg, Supplier<NetworkEvent.Context> ctx) { ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ModEvents.ClientEvents.updateWaypoints(msg.json))); ctx.get().setPacketHandled(true); }
    }

    public static class SoundSyncPacket {
        public String soundId; public String category; public boolean stop; public float vol; public int fadeIn; public int fadeOut;
        public SoundSyncPacket(String id, String cat, boolean stop, float v, int in, int out) { this.soundId = id; this.category = cat; this.stop = stop; this.vol = v; this.fadeIn = in; this.fadeOut = out; }
        public static void encode(SoundSyncPacket msg, FriendlyByteBuf buf) { buf.writeUtf(msg.soundId); buf.writeUtf(msg.category); buf.writeBoolean(msg.stop); buf.writeFloat(msg.vol); buf.writeInt(msg.fadeIn); buf.writeInt(msg.fadeOut); }
        public static SoundSyncPacket decode(FriendlyByteBuf buf) { return new SoundSyncPacket(buf.readUtf(), buf.readUtf(), buf.readBoolean(), buf.readFloat(), buf.readInt(), buf.readInt()); }
        public static void handle(SoundSyncPacket msg, Supplier<NetworkEvent.Context> ctx) { ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ModEvents.ClientEvents.handleSound(msg))); ctx.get().setPacketHandled(true); }
    }

    public static class Waypoint {
        public String id;
        public String title;
        public String color; public String target; public double x, y, z;
        public float scale = 1.0f; public String subtext = ""; public String style = "moderno";
    }

    public static class Region {
        public String name; public int x1, y1, z1, x2, y2, z2; public Map<String, String> flags = new HashMap<>();
        public Region() {}
        public Region(String n, Vec3 p1, Vec3 p2) {
            this.name = n; this.x1 = (int) Math.min(p1.x, p2.x); this.y1 = (int) Math.min(p1.y, p2.y); this.z1 = (int) Math.min(p1.z, p2.z);
            this.x2 = (int) Math.max(p1.x, p2.x); this.y2 = (int) Math.max(p1.y, p2.y); this.z2 = (int) Math.max(p1.z, p2.z);
        }
        public boolean isInside(BlockPos p) { return p.getX() >= x1 && p.getX() <= x2 && p.getY() >= y1 && p.getY() <= y2 && p.getZ() >= z1 && p.getZ() <= z2; }
    }
}