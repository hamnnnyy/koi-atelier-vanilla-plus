package io.github.hamnnnyy.packtester;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 모드팩 자동 테스트. 환경 변수 PACKTEST=1 일 때만 동작:
 * 월드 생성 -> 스크린샷(1인칭, 3인칭+코스메틱, 몹) -> 10초 FPS 측정 -> 종료.
 * 결과는 로그의 [PackTest] 줄과 screenshots/ 폴더.
 */
public class PackTester implements ClientModInitializer {
    private static final int FPS_START = 160, FPS_END = 360; // 10초

    private static int state = 0;
    private static int ticks = 0;
    private static String pendingShot;
    private static final List<Integer> fps = new ArrayList<>();

    @Override
    public void onInitializeClient() {
        if (!"1".equals(System.getenv("PACKTEST"))) return;
        ClientTickEvents.END_CLIENT_TICK.register(PackTester::tick);
    }

    private static void log(String message) {
        System.out.println("[PackTest] " + message);
    }

    private static void cmd(MinecraftClient mc, String... commands) {
        for (String c : commands) mc.player.networkHandler.sendChatCommand(c);
    }

    private static void look(MinecraftClient mc, Perspective perspective, float yaw, float pitch) {
        mc.options.setPerspective(perspective);
        mc.player.setYaw(yaw);
        mc.player.setPitch(pitch);
        mc.player.bodyYaw = yaw;
        mc.player.headYaw = yaw;
    }

    public static void onFrameEnd(MinecraftClient mc) {
        if (pendingShot == null) return;
        ScreenshotRecorder.saveScreenshot(mc.runDirectory, pendingShot, mc.getFramebuffer(), msg -> log("saved " + msg.getString()));
        pendingShot = null;
    }

    private static void tick(MinecraftClient mc) {
        mc.options.pauseOnLostFocus = false;
        if (state == 0 && mc.currentScreen instanceof TitleScreen && mc.getOverlay() == null) {
            state = 1;
            log("title screen reached, mods loaded: " + FabricLoader.getInstance().getAllMods().size());
            mc.getWindow().setWindowedSize(1280, 720);
            String name = "packtest" + System.currentTimeMillis();
            mc.createIntegratedServerLoader().createAndStart(name,
                    new LevelInfo(name, GameMode.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), DataConfiguration.SAFE_MODE),
                    new GeneratorOptions(12345L, true, false), WorldPresets::createDemoOptions, new TitleScreen());
            return;
        }
        if (state != 1 || mc.player == null || mc.currentScreen != null) return;

        ticks++;
        switch (ticks) {
            case 1 -> log("world joined");
            case 20 -> cmd(mc, "time set 6000", "weather clear", "gamerule doDaylightCycle false");
            case 60 -> {
                look(mc, Perspective.FIRST_PERSON, 0, 10);
                pendingShot = "1_first_person.png";
            }
            case 62 -> cmd(mc, "koi halo true", "koi wings true", "koi cape true", "koi pet true", "koi head_text Vanilla+");
            case 64 -> look(mc, Perspective.THIRD_PERSON_FRONT, 0, 10);
            case 100 -> pendingShot = "2_third_person.png";
            case 102 -> {
                cmd(mc, "koi halo false", "koi wings false", "koi pet false", "koi head_text clear",
                        "summon cow ^-2 ^ ^4", "summon sheep ^ ^ ^5", "summon pig ^2 ^ ^4", "summon chicken ^1 ^ ^3");
                look(mc, Perspective.FIRST_PERSON, 0, 15);
            }
            case 140 -> pendingShot = "3_mobs.png";
            case 142 -> look(mc, Perspective.FIRST_PERSON, 90, 0);
            default -> {
            }
        }
        if (ticks >= FPS_START && ticks < FPS_END) {
            look(mc, Perspective.FIRST_PERSON, 90 + (ticks - FPS_START) * 1.8f, 0); // 천천히 한 바퀴 돌며 측정
            fps.add(mc.getCurrentFps());
        }
        if (ticks == FPS_END) {
            double avg = fps.stream().mapToInt(Integer::intValue).average().orElse(0);
            int min = fps.stream().mapToInt(Integer::intValue).min().orElse(0);
            log(String.format("fps avg=%.0f min=%d (10s, rotating view)", avg, min));
            log("done");
            mc.scheduleStop();
        }
    }
}
