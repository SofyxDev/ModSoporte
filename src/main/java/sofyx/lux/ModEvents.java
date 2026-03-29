package sofyx.lux;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Iterator;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ModEvents {

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Iterator<Map.Entry<UUID, SoporteMod.ServerTimer>> it = SoporteMod.serverTimers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, SoporteMod.ServerTimer> entry = it.next();
            UUID playerId = entry.getKey();
            SoporteMod.ServerTimer timer = entry.getValue();
            timer.ticksRemaining--;

            if (timer.ticksRemaining % 20 == 0) {
                ServerPlayer p = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayer(playerId);
                if (p != null) SoporteMod.NETWORK.send(PacketDistributor.PLAYER.with(() -> p), new SoporteMod.TimerSyncPacket(timer.ticksRemaining, timer.background));
            }

            if (timer.ticksRemaining <= 0) {
                ServerPlayer p = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayer(playerId);
                if (p != null) {
                    SoporteMod.NETWORK.send(PacketDistributor.PLAYER.with(() -> p), new SoporteMod.TimerSyncPacket(0, ""));
                    p.getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack(), timer.command);
                }
                it.remove();
            }
        }
    }

    public static boolean isInside(Player p, SoporteMod.Region r) {
        return p.getX() >= Math.min(r.x1, r.x2) && p.getX() <= Math.max(r.x1, r.x2) &&
                p.getY() >= Math.min(r.y1, r.y2) && p.getY() <= Math.max(r.y1, r.y2) &&
                p.getZ() >= Math.min(r.z1, r.z2) && p.getZ() <= Math.max(r.z1, r.z2);
    }

    public static SoporteMod.Region getRegionAt(Player p) {
        for (SoporteMod.Region r : SoporteMod.DATA.regions) { if (isInside(p, r)) return r; }
        return null;
    }

    public static SoporteMod.Region getRegionAtLocation(boolean isClient, double x, double y, double z) {
        if (isClient) return null;
        for (SoporteMod.Region r : SoporteMod.DATA.regions) {
            if (x >= Math.min(r.x1, r.x2) && x <= Math.max(r.x1, r.x2) && y >= Math.min(r.y1, r.y2) && y <= Math.max(r.y1, r.y2) && z >= Math.min(r.z1, r.z2) && z <= Math.max(r.z1, r.z2)) {
                return r;
            }
        }
        return null;
    }

    @Mod.EventBusSubscriber(modid = SoporteMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static class ClientEvents {

        public static final Map<String, FadingSoundInstance> ACTIVE_SOUNDS = new HashMap<>();

        public static void updateKeys(String json) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                if (json.equals("{}")) SoporteMod.DATA.playerKeys.remove(mc.player.getUUID());
                else SoporteMod.DATA.playerKeys.put(mc.player.getUUID(), SoporteMod.GSON.fromJson(json, SoporteMod.PlayerKeySettings.class));
            }
        }

        public static void updateWaypoints(String json) {
            SoporteMod.DATA.waypoints.clear();
            SoporteMod.Waypoint[] wps = SoporteMod.GSON.fromJson(json, SoporteMod.Waypoint[].class);
            if (wps != null) {
                for (SoporteMod.Waypoint wp : wps) {
                    if (wp.id == null) wp.id = "migrado_" + System.currentTimeMillis();
                    if (wp.title == null) wp.title = "&fWaypoint";
                    if (wp.subtext == null) wp.subtext = "";
                    SoporteMod.DATA.waypoints.add(wp);
                }
            }
        }

        @SuppressWarnings("removal")
        public static void handleSound(SoporteMod.SoundSyncPacket msg) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;

            FadingSoundInstance active = ACTIVE_SOUNDS.get(msg.soundId);
            if (active != null && active.isStopped()) { ACTIVE_SOUNDS.remove(msg.soundId); active = null; }

            ResourceLocation soundLocation;
            String[] parts = msg.soundId.split(":");
            if (parts.length == 2) soundLocation = new ResourceLocation(parts[0], parts[1]);
            else soundLocation = new ResourceLocation("minecraft", msg.soundId);

            if (msg.stop) {
                if (active != null) { active.triggerStop(msg.fadeOut); ACTIVE_SOUNDS.remove(msg.soundId); }
                else { mc.getSoundManager().stop(soundLocation, getSource(msg.category)); }
            } else {
                if (active == null) {
                    FadingSoundInstance sound = new FadingSoundInstance(soundLocation, getSource(msg.category), msg.vol, msg.fadeIn, msg.fadeOut);
                    ACTIVE_SOUNDS.put(msg.soundId, sound);
                    mc.getSoundManager().play(sound);
                }
            }
        }

        private static net.minecraft.sounds.SoundSource getSource(String cat) {
            return switch (cat.toLowerCase()) {
                case "sountracks", "musica" -> net.minecraft.sounds.SoundSource.MUSIC;
                case "ambiente" -> net.minecraft.sounds.SoundSource.AMBIENT;
                case "dialogos", "voz" -> net.minecraft.sounds.SoundSource.VOICE;
                case "bloques" -> net.minecraft.sounds.SoundSource.BLOCKS;
                case "criaturas", "hostil" -> net.minecraft.sounds.SoundSource.HOSTILE;
                case "jugador", "pasos" -> net.minecraft.sounds.SoundSource.PLAYERS;
                default -> net.minecraft.sounds.SoundSource.MASTER;
            };
        }

        public static void updateTimer(int ticks, String bg) {
            SoporteMod.clientTimerTicks = ticks;
            SoporteMod.clientTimerBg = bg;
            if (SoporteMod.clientMaxTimerTicks == 0 || ticks > SoporteMod.clientMaxTimerTicks) SoporteMod.clientMaxTimerTicks = ticks;
        }

        public static class FadingSoundInstance extends net.minecraft.client.resources.sounds.AbstractTickableSoundInstance {
            private final int fadeInTicks; private int fadeOutTicks; private final float maxVolume;
            private int currentTick = 0; private boolean isFadingOut = false; private int fadeOutProgress = 0;

            public FadingSoundInstance(ResourceLocation loc, net.minecraft.sounds.SoundSource source, float vol, int fadeIn, int fadeOut) {
                super(net.minecraft.sounds.SoundEvent.createVariableRangeEvent(loc), source, net.minecraft.client.resources.sounds.SoundInstance.createUnseededRandom());
                this.maxVolume = vol; this.fadeInTicks = fadeIn; this.fadeOutTicks = fadeOut;
                this.volume = fadeIn > 0 ? 0.05f : maxVolume;
                this.looping = (source == net.minecraft.sounds.SoundSource.MUSIC || source == net.minecraft.sounds.SoundSource.AMBIENT || source == net.minecraft.sounds.SoundSource.MASTER);
                this.delay = 0;
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) { this.x = mc.player.getX(); this.y = mc.player.getY(); this.z = mc.player.getZ(); }
                this.attenuation = net.minecraft.client.resources.sounds.SoundInstance.Attenuation.NONE;
            }

            @Override
            public void tick() {
                if (this.isStopped()) return;
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) { this.x = mc.player.getX(); this.y = mc.player.getY(); this.z = mc.player.getZ(); }

                if (!isFadingOut) {
                    if (currentTick < fadeInTicks && fadeInTicks > 0) {
                        currentTick++; this.volume = maxVolume * ((float) currentTick / fadeInTicks);
                        if (this.volume < 0.05f) this.volume = 0.05f;
                    } else { this.volume = maxVolume; }
                } else {
                    if (fadeOutProgress < fadeOutTicks && fadeOutTicks > 0) {
                        fadeOutProgress++; this.volume = maxVolume * (1.0f - ((float) fadeOutProgress / fadeOutTicks));
                    } else { this.volume = 0.0f; this.stop(); }
                }
            }

            public void triggerStop(int forcedFadeOut) {
                this.isFadingOut = true;
                if (forcedFadeOut > 0) this.fadeOutTicks = forcedFadeOut;
                if (this.fadeOutTicks <= 0) this.stop();
            }
        }

        @SubscribeEvent
        public static void onKeyInput(InputEvent.Key event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            SoporteMod.PlayerKeySettings keys = SoporteMod.DATA.playerKeys.get(mc.player.getUUID());
            if (keys != null && keys.disableF1) { if (event.getKey() == GLFW.GLFW_KEY_F1) mc.options.hideGui = false; }
        }

        @SubscribeEvent
        public static void onRenderTick(TickEvent.RenderTickEvent event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                SoporteMod.PlayerKeySettings keys = SoporteMod.DATA.playerKeys.get(mc.player.getUUID());
                if (keys != null && keys.disableF1) mc.options.hideGui = false;
            }
        }

        @SubscribeEvent
        public static void onRenderOverlayPre(RenderGuiOverlayEvent.Pre event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            SoporteMod.PlayerKeySettings keys = SoporteMod.DATA.playerKeys.get(mc.player.getUUID());
            if (keys != null) { if (keys.disableF3 && event.getOverlay().id().getPath().equals("debug_text")) event.setCanceled(true); }
        }

        @SuppressWarnings("removal")
        @SubscribeEvent
        public static void onRenderGui(RenderGuiOverlayEvent.Post event) {
            if (!event.getOverlay().id().getPath().equals("hotbar")) return;

            GuiGraphics gfx = event.getGuiGraphics();
            Minecraft mc = Minecraft.getInstance();
            int screenWidth = gfx.guiWidth();
            int screenHeight = gfx.guiHeight();

            // 1. RENDERIZAR TIMER CLIENT-SIDE
            if (SoporteMod.clientTimerTicks > 0) {
                int totalSecs = SoporteMod.clientTimerTicks / 20;
                int h = totalSecs / 3600; int m = (totalSecs % 3600) / 60; int s = totalSecs % 60;
                float fraction = (SoporteMod.clientTimerTicks % 20) / 20.0f;
                float pop = 0.0f; if (fraction > 0.8f) pop = (fraction - 0.8f) / 0.2f * 0.2f;
                float baseScale = 1.0f; float scaleH = baseScale + ( (m == 59 && s == 59) ? pop : 0 ); float scaleM = baseScale + ( (s == 59) ? pop : 0 ); float scaleS = baseScale + pop;
                String strH = String.format("%02d", h); String strM = String.format("%02d", m); String strS = String.format("%02d", s); String colon = ":";
                int yPos = 20; int centerX = screenWidth / 2;

                if (!SoporteMod.clientTimerBg.isEmpty() && !SoporteMod.clientTimerBg.equals("none")) {
                    try {
                        ResourceLocation bgLoc;
                        if (SoporteMod.clientTimerBg.contains(":")) { String[] split = SoporteMod.clientTimerBg.split(":", 2); bgLoc = new ResourceLocation(split[0], split[1]); }
                        else { bgLoc = new ResourceLocation("minecraft", SoporteMod.clientTimerBg); }
                        gfx.blit(bgLoc, centerX - 50, yPos - 8, 0, 0, 100, 26, 100, 26);
                    } catch (Exception ignored) {}
                } else if (SoporteMod.clientTimerBg.equals("none")) {
                    gfx.fill(centerX - 45, yPos - 12, centerX + 45, yPos + 18, 0x77000000); gfx.fill(centerX - 45, yPos - 12, centerX + 45, yPos - 11, 0xAAFFAA00);
                    PoseStack pose = gfx.pose(); pose.pushPose(); pose.translate(centerX, yPos - 8, 0); pose.scale(0.6f, 0.6f, 1.0f);
                    gfx.drawCenteredString(mc.font, "TIEMPO RESTANTE", 0, 0, 0xAAAAAA); pose.popPose(); yPos += 5;
                }
                int color = 0xFFFFFF;
                drawCenteredScaledText(gfx, mc.font, strH, centerX - 24, yPos, scaleH, color); drawCenteredScaledText(gfx, mc.font, colon, centerX - 12, yPos, baseScale, color);
                drawCenteredScaledText(gfx, mc.font, strM, centerX, yPos, scaleM, color); drawCenteredScaledText(gfx, mc.font, colon, centerX + 12, yPos, baseScale, color); drawCenteredScaledText(gfx, mc.font, strS, centerX + 24, yPos, scaleS, color);
            }

            // 2. RENDERIZAR HUD DEL WAYPOINT (OPTIMIZADO CLIENT-SIDE)
            if (mc.player != null && !mc.options.hideGui) {
                SoporteMod.PlayerKeySettings keys = SoporteMod.DATA.playerKeys.get(mc.player.getUUID());
                if (keys != null && keys.showHud && !SoporteMod.DATA.waypoints.isEmpty()) {
                    SoporteMod.Waypoint closest = null;
                    double minDist = Double.MAX_VALUE;

                    for (SoporteMod.Waypoint wp : SoporteMod.DATA.waypoints) {
                        double dist = mc.player.distanceToSqr(wp.x, wp.y, wp.z);
                        if (dist < minDist) { minDist = dist; closest = wp; }
                    }

                    if (closest != null) {
                        int distBlocks = (int) Math.sqrt(minDist);
                        double dx = closest.x - mc.player.getX();
                        double dz = closest.z - mc.player.getZ();

                        double targetAngle = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
                        double playerAngle = mc.player.getYRot();
                        double angleDiff = (targetAngle - playerAngle) % 360.0;
                        if (angleDiff < -180.0) angleDiff += 360.0;
                        if (angleDiff > 180.0) angleDiff -= 360.0;

                        String arrow = "⬆";
                        if (angleDiff >= -22.5 && angleDiff < 22.5) arrow = "§a⬆";
                        else if (angleDiff >= 22.5 && angleDiff < 67.5) arrow = "§e⬈";
                        else if (angleDiff >= 67.5 && angleDiff < 112.5) arrow = "§c➡";
                        else if (angleDiff >= 112.5 && angleDiff < 157.5) arrow = "§4⬊";
                        else if (angleDiff >= 157.5 || angleDiff < -157.5) arrow = "§4⬇";
                        else if (angleDiff >= -157.5 && angleDiff < -112.5) arrow = "§4⬋";
                        else if (angleDiff >= -112.5 && angleDiff < -67.5) arrow = "§c⬅";
                        else if (angleDiff >= -67.5 && angleDiff < -22.5) arrow = "§e⬉";

                        String colorName = "§f";
                        if ("neon".equalsIgnoreCase(closest.style)) colorName = "§b§l";
                        else if ("clasico".equalsIgnoreCase(closest.style)) colorName = "§e";

                        String cleanName = closest.title;
                        try {
                            Component comp = Component.Serializer.fromJson(cleanName);
                            if (comp != null) cleanName = comp.getString();
                        } catch (Exception ignored) {}

                        String texto = String.format("%s §e%dm %s%s", arrow, distBlocks, colorName, ModCommands.translate(cleanName));
                        Component textComp = Component.literal(texto);

                        int tWidth = mc.font.width(textComp);
                        int tHeight = mc.font.lineHeight;
                        int x = 0; int y = 0;

                        String position = keys.hudPos != null ? keys.hudPos : "top_center";
                        switch (position) {
                            case "top_left": x = 5; y = 5; break;
                            case "top_right": x = screenWidth - tWidth - 5; y = 5; break;
                            case "center_left": x = 5; y = (screenHeight - tHeight) / 2; break;
                            case "center_right": x = screenWidth - tWidth - 5; y = (screenHeight - tHeight) / 2; break;
                            case "bottom_left": x = 5; y = screenHeight - tHeight - 5; break;
                            case "bottom_right": x = screenWidth - tWidth - 5; y = screenHeight - tHeight - 5; break;
                            case "bottom_center": x = (screenWidth - tWidth) / 2; y = screenHeight - 60; break; // Arriba de la hotbar
                            case "top_center":
                            default: x = (screenWidth - tWidth) / 2; y = 5; break;
                        }

                        // Dibuja el fondo y el texto del HUD exactamente donde quieras
                        gfx.fill(x - 3, y - 2, x + tWidth + 3, y + tHeight + 2, 0x55000000);
                        gfx.drawString(mc.font, textComp, x, y, 0xFFFFFF, false);
                    }
                }
            }
        }

        private static void drawCenteredScaledText(GuiGraphics gfx, net.minecraft.client.gui.Font font, String text, int x, int y, float scale, int color) {
            PoseStack pose = gfx.pose(); pose.pushPose(); pose.translate(x, y, 0); pose.scale(scale, scale, 1.0f);
            gfx.drawCenteredString(font, text, 0, 0, color); pose.popPose();
        }

        @SubscribeEvent
        public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
            Minecraft mc = Minecraft.getInstance(); if (mc.player == null) return;
            SoporteMod.PlayerKeySettings keys = SoporteMod.DATA.playerKeys.get(mc.player.getUUID());
            if (keys != null) {
                if (keys.disableAttack && event.isAttack()) { event.setCanceled(true); event.setSwingHand(false); }
                if (keys.disableInteract && event.isUseItem()) { event.setCanceled(true); event.setSwingHand(false); }
            }
        }

        @SubscribeEvent
        public static void onMovementInput(net.minecraftforge.client.event.MovementInputUpdateEvent event) {
            if (event.getEntity() == null) return;
            SoporteMod.PlayerKeySettings keys = SoporteMod.DATA.playerKeys.get(event.getEntity().getUUID());
            if (keys != null) {
                if (keys.disableMove) { event.getInput().up = false; event.getInput().down = false; event.getInput().left = false; event.getInput().right = false; event.getInput().forwardImpulse = 0; event.getInput().leftImpulse = 0; }
                if (keys.disableSpace) event.getInput().jumping = false;
                if (keys.disableSneak) event.getInput().shiftKeyDown = false;
            }
        }

        @SubscribeEvent
        public static void onScreenOpen(net.minecraftforge.client.event.ScreenEvent.Opening event) {
            Minecraft mc = Minecraft.getInstance(); if (mc.player == null) return;
            SoporteMod.PlayerKeySettings keys = SoporteMod.DATA.playerKeys.get(mc.player.getUUID());
            if (keys != null && keys.disableInventory) { if (event.getScreen() instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen) event.setCanceled(true); }
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance(); if (mc.player == null) return;
            if (SoporteMod.clientTimerTicks > 0) SoporteMod.clientTimerTicks--;

            SoporteMod.PlayerKeySettings keys = SoporteMod.DATA.playerKeys.get(mc.player.getUUID());
            if (keys != null) {
                if (keys.disableSprint) { if (mc.options.keySprint.isDown()) mc.options.keySprint.setDown(false); mc.player.setSprinting(false); }
                if (keys.disableDrop && mc.options.keyDrop.isDown()) mc.options.keyDrop.setDown(false);
                if (keys.disableAttack && mc.options.keyAttack.isDown()) mc.options.keyAttack.setDown(false);
                if (keys.disableInteract && mc.options.keyUse.isDown()) mc.options.keyUse.setDown(false);
                if (keys.disableSneak && mc.options.keyShift.isDown()) mc.options.keyShift.setDown(false);
                if (keys.disableF3) mc.options.renderDebug = false;

                if (keys.forceCamera) {
                    if (keys.cameraMode == 1) mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                    else if (keys.cameraMode == 2) mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
                    else if (keys.cameraMode == 3) mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
                }
            }
        }

        @SuppressWarnings("removal")
        @SubscribeEvent
        public static void onRenderLevelStage(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;

            Vec3 camPos = event.getCamera().getPosition();
            PoseStack poseStack = event.getPoseStack();
            MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
            ResourceLocation BEAM_LOCATION = new ResourceLocation("minecraft", "textures/entity/beacon_beam.png");
            long gameTime = mc.level.getGameTime();

            for (SoporteMod.Waypoint wp : SoporteMod.DATA.waypoints) {
                double distToPlayer = mc.player.distanceToSqr(wp.x, wp.y, wp.z);
                if (distToPlayer > 250000) continue;

                // 1. HAZ DE LUZ
                if (!wp.style.equalsIgnoreCase("clasico")) {
                    poseStack.pushPose();
                    poseStack.translate(wp.x - camPos.x, (wp.y - 50) - camPos.y, wp.z - camPos.z);
                    float[] colors = getColors(wp.color);
                    BeaconRenderer.renderBeaconBeam(poseStack, bufferSource, BEAM_LOCATION, event.getPartialTick(), wp.scale, gameTime, 0, 1024, colors, 0.2f, 0.25f);
                    poseStack.popPose();
                }

                // 2. RENDER TEXTO
                poseStack.pushPose();
                poseStack.translate(wp.x - camPos.x, wp.y + 3.0 - camPos.y, wp.z - camPos.z);
                poseStack.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());

                boolean isNeon = wp.style.equalsIgnoreCase("neon");
                int bgCol = isNeon ? 0 : 1073741824;
                int light = isNeon ? 15728880 : mc.getEntityRenderDispatcher().getPackedLightCoords(mc.player, event.getPartialTick());

                // Título principal
                poseStack.pushPose();
                float sTitle = -0.025f * wp.scale;
                poseStack.scale(sTitle, sTitle, sTitle);

                String translatedTitle = ModCommands.translate(wp.title);
                Component textComp;
                try {
                    textComp = Component.Serializer.fromJson(translatedTitle);
                    if (textComp == null) textComp = Component.literal(translatedTitle);
                } catch (Exception e) { textComp = Component.literal(translatedTitle); }

                float textWidth = (float)(-mc.font.width(textComp) / 2);
                mc.font.drawInBatch(textComp, textWidth, 0f, getHexColor(wp.color), isNeon, poseStack.last().pose(), bufferSource, net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH, bgCol, light);
                poseStack.popPose();

                // Subtexto
                poseStack.pushPose();
                poseStack.translate(0, -0.45f * wp.scale, 0);
                float sSub = -0.025f * (wp.scale * 0.4f);
                poseStack.scale(sSub, sSub, sSub);

                String translatedSubtext = ModCommands.translate(wp.subtext);
                int distInt = (int)Math.sqrt(distToPlayer);
                String fullSubtext = translatedSubtext.isEmpty() ? "§e(" + distInt + "m)" : translatedSubtext + " §e(" + distInt + "m)";

                Component subComp;
                try {
                    subComp = Component.Serializer.fromJson(fullSubtext);
                    if (subComp == null) subComp = Component.literal(fullSubtext);
                } catch (Exception e) { subComp = Component.literal(fullSubtext); }

                float subWidth = (float)(-mc.font.width(subComp) / 2);
                mc.font.drawInBatch(subComp, subWidth, 0f, 0xFFFFFFFF, isNeon, poseStack.last().pose(), bufferSource, net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH, bgCol, light);
                poseStack.popPose();

                poseStack.popPose();
            }
        }

        private static float[] getColors(String color) {
            if (color != null && color.startsWith("#") && color.length() == 7) {
                try {
                    int hex = Integer.parseInt(color.substring(1), 16);
                    return new float[]{ ((hex >> 16) & 0xFF) / 255f, ((hex >> 8) & 0xFF) / 255f, (hex & 0xFF) / 255f };
                } catch (Exception ignored) {}
            }
            return switch (color != null ? color.toLowerCase() : "") {
                case "rojo", "red" -> new float[]{1.0f, 0.0f, 0.0f}; case "azul", "blue" -> new float[]{0.0f, 0.0f, 1.0f};
                case "verde", "green" -> new float[]{0.0f, 1.0f, 0.0f}; case "amarillo", "yellow" -> new float[]{1.0f, 1.0f, 0.0f};
                case "dorado", "gold" -> new float[]{1.0f, 0.6f, 0.0f}; case "morado", "purple" -> new float[]{0.6f, 0.0f, 0.8f};
                case "aqua", "cyan" -> new float[]{0.0f, 1.0f, 1.0f}; case "negro", "black" -> new float[]{0.0f, 0.0f, 0.0f};
                case "gris", "gray" -> new float[]{0.5f, 0.5f, 0.5f};
                default -> new float[]{1.0f, 1.0f, 1.0f};
            };
        }

        private static int getHexColor(String color) {
            if (color != null && color.startsWith("#") && color.length() == 7) {
                try { return Integer.parseInt(color.substring(1), 16) | 0xFF000000; } catch (Exception ignored) {}
            }
            return switch (color != null ? color.toLowerCase() : "") {
                case "rojo", "red" -> 0xFFFF5555; case "azul", "blue" -> 0xFF5555FF;
                case "verde", "green" -> 0xFF55FF55; case "amarillo", "yellow" -> 0xFFFFFF55;
                case "dorado", "gold" -> 0xFFFFAA00; case "morado", "purple" -> 0xFFFF55FF;
                case "aqua", "cyan" -> 0xFF55FFFF; case "negro", "black" -> 0xFF000000;
                case "gris", "gray" -> 0xFFAAAAAA;
                default -> 0xFFFFFFFF;
            };
        }
    }
}