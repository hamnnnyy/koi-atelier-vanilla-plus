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
 * PACKTEST_BENCH=1 이면 10초 측정 대신 자세한 벤치마크: 제자리 둘러보기 20초 + 빠르게 날아가기 20초,
 * 프레임마다 걸린 시간으로 평균 FPS, 1% Low, 최대 프레임 시간, 끊김 횟수, 메모리를 기록.
 * 런처에서 기존 월드로 바로 들어가면(--quickPlaySingleplayer) 새 월드를 만들지 않고 그 월드에서 진행.
 * PACKTEST_PREGEN=<반경> 이면 다른 테스트 대신 Chunky로 현재 위치 중심 반경만큼 지형을 미리 생성하고 끝나면 종료.
 */
public class PackTester implements ClientModInitializer {
    private static final int FPS_START = 160, FPS_END = 360; // 10초

    private static int state = 0;
    private static int ticks = 0;
    private static String pendingShot;
    private static final List<Integer> fps = new ArrayList<>();

    // 벤치마크: 구간마다 프레임 시간(나노초) 기록
    private static final boolean BENCH = "1".equals(System.getenv("PACKTEST_BENCH"));
    // 측정 시작 전 안정화 시간: PACKTEST_WARMUP=초 (기본 8초). 기존 월드는 불러오는 동안 끊김이 섞이지 않게 길게
    private static final int LOOK_START = 20 * Integer.parseInt(System.getenv().getOrDefault("PACKTEST_WARMUP", "8"));
    private static final int FLY_START = LOOK_START + 400, BENCH_END = FLY_START + 400; // 각 20초
    private static final List<Long> frameNanos = new ArrayList<>();
    private static long lastFrame = 0;
    private static boolean measuring = false;
    private static double flyY;

    // 지형 미리 생성 (Chunky)
    private static final String PREGEN = System.getenv("PACKTEST_PREGEN");
    private static boolean pregenStarted;

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
        if (measuring) {
            long now = System.nanoTime();
            if (lastFrame != 0) frameNanos.add(now - lastFrame);
            lastFrame = now;
        }
        if (pendingShot == null) return;
        ScreenshotRecorder.saveScreenshot(mc.runDirectory, pendingShot, mc.getFramebuffer(), msg -> log("saved " + msg.getString()));
        pendingShot = null;
    }

    private static void tick(MinecraftClient mc) {
        mc.options.pauseOnLostFocus = false;
        // PACKTEST_JOIN=1 이면 런처가 기존 월드로 들어가므로 새 월드를 절대 만들지 않음
        boolean joinExisting = "1".equals(System.getenv("PACKTEST_JOIN"));
        if (state == 0 && !joinExisting && mc.currentScreen instanceof TitleScreen && mc.getOverlay() == null) {
            state = 1;
            log("title screen reached, mods loaded: " + FabricLoader.getInstance().getAllMods().size());
            // 창 크기: PACKTEST_RES=1920x1080 처럼 지정 (기본 1280x720)
            String[] res = System.getenv().getOrDefault("PACKTEST_RES", "1280x720").split("x");
            mc.getWindow().setWindowedSize(Integer.parseInt(res[0]), Integer.parseInt(res[1]));
            // PACKTEST_WORLD=이름 이면 실제로 플레이할 월드(서바이벌, 보통, 치트 켬)를 만듦
            String world = System.getenv("PACKTEST_WORLD");
            String name = world != null ? world : "packtest" + System.currentTimeMillis();
            mc.createIntegratedServerLoader().createAndStart(name,
                    new LevelInfo(name, world != null ? GameMode.SURVIVAL : GameMode.CREATIVE, false,
                            world != null ? Difficulty.NORMAL : Difficulty.PEACEFUL, true, new GameRules(), DataConfiguration.SAFE_MODE),
                    new GeneratorOptions(Long.parseLong(System.getenv().getOrDefault("PACKTEST_SEED", "12345")), true, false), WorldPresets::createDemoOptions, new TitleScreen());
            return;
        }
        if (state == 0 && mc.player != null) state = 1; // 런처가 기존 월드로 바로 들어간 경우
        // PACKTEST_CMDSHOT="명령어": 월드에 들어가서 그 명령어를 실행하고, 열린 화면까지 스크린샷
        String cmdShot = System.getenv("PACKTEST_CMDSHOT");
        if (cmdShot != null && state == 1 && mc.player != null) {
            ticks++;
            if (ticks == 40) {
                cmd(mc, cmdShot);
                log("ran /" + cmdShot);
            }
            if (ticks == 100) {
                log("screen after command: " + (mc.currentScreen == null ? "none" : mc.currentScreen.getClass().getName()));
                pendingShot = "cmd.png";
            }
            if (ticks == 140) {
                log("done");
                mc.scheduleStop();
            }
            return;
        }
        if (state != 1 || mc.player == null || mc.currentScreen != null) return;
        if (PREGEN != null) {
            ticks++;
            pregen(mc);
            return;
        }

        ticks++;
        if ("1".equals(System.getenv("PACKTEST_SCENIC"))) {
            scenic(mc);
            return;
        }
        switch (ticks) {
            case 1 -> {
                log("world joined");
                // FPS를 제대로 재려고 테스트하는 동안만 수직 동기화와 프레임 제한을 끔
                mc.options.getEnableVsync().setValue(false);
                mc.options.getMaxFps().setValue(260);
            }
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
                        // AI 없이 소환해서 나무 위 등에서도 떨어지지 않고 제자리에
                        "summon cow ^-2 ^ ^4 {NoAI:1b}", "summon sheep ^ ^ ^5 {NoAI:1b}", "summon pig ^2 ^ ^4 {NoAI:1b}",
                        "summon chicken ^1 ^ ^3 {NoAI:1b}");
                look(mc, Perspective.FIRST_PERSON, 0, 15);
            }
            case 140 -> pendingShot = "3_mobs.png";
            case 142 -> look(mc, Perspective.FIRST_PERSON, 90, 0);
            default -> {
            }
        }
        if (BENCH) {
            bench(mc);
            return;
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

    // PACKTEST_SCENIC=1: 시드 미리보기. 스폰 위 공중에서 네 방향 + 내려다보기, HUD 숨김, 근처 마을 위치 로그
    private static void scenic(MinecraftClient mc) {
        switch (ticks) {
            case 1 -> {
                log("world joined, seed spawn " + mc.player.getBlockPos().toShortString()
                        + " biome " + mc.world.getBiome(mc.player.getBlockPos()).getKey().map(k -> k.getValue().toString()).orElse("?"));
                mc.options.getEnableVsync().setValue(false);
                mc.options.hudHidden = true;
            }
            case 20 -> cmd(mc, "time set 1000", "weather clear", "gamerule doDaylightCycle false", "locate structure #minecraft:village");
            // 관전자 모드는 떨어지지 않고 블록에 걸리지도 않음
            case 30 -> cmd(mc, "gamemode spectator");
            case 40 -> {
                var pos = mc.player.getBlockPos();
                cmd(mc, "tp @s " + pos.getX() + " " + (pos.getY() + 35) + " " + pos.getZ());
            }
            default -> {
            }
        }
        // 청크가 그려질 시간을 두고 방향마다 한 장씩 (남, 서, 북, 동, 내려다보기)
        String[] names = {"1_south", "2_west", "3_north", "4_east", "5_down"};
        for (int i = 0; i < names.length; i++) {
            int at = 200 + i * 120;
            if (ticks == at - 100) look(mc, Perspective.FIRST_PERSON, i * 90f, i == 4 ? 60 : 12);
            if (ticks == at) {
                pendingShot = names[i] + ".png";
                log("shot " + names[i] + " at y=" + mc.player.getBlockY());
            }
        }
        if (ticks == 200 + names.length * 120) {
            log("done");
            mc.scheduleStop();
        }
    }

    private static void startPhase() {
        frameNanos.clear();
        lastFrame = 0;
        measuring = true;
    }

    private static void endPhase(String name) {
        measuring = false;
        List<Long> sorted = new ArrayList<>(frameNanos);
        sorted.sort(null);
        double totalSec = frameNanos.stream().mapToLong(Long::longValue).sum() / 1e9;
        double avgFps = frameNanos.size() / totalSec;
        int onePct = Math.max(1, sorted.size() / 100);
        double slowestAvgMs = sorted.subList(sorted.size() - onePct, sorted.size()).stream().mapToLong(Long::longValue).average().orElse(0) / 1e6;
        double maxMs = sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1) / 1e6;
        long stutters = frameNanos.stream().filter(n -> n > 50_000_000L).count(); // 50ms(=20fps) 넘게 멈춘 프레임
        Runtime rt = Runtime.getRuntime();
        log(String.format("bench %s: avg=%.0f fps, 1%% low=%.0f fps, max frame=%.0f ms, stutters(>50ms)=%d, frames=%d, mem=%d/%d MB",
                name, avgFps, 1000 / slowestAvgMs, maxMs, stutters, frameNanos.size(),
                (rt.totalMemory() - rt.freeMemory()) / 1048576, rt.maxMemory() / 1048576));
    }

    private static void bench(MinecraftClient mc) {
        if (ticks == LOOK_START) {
            log(String.format("bench settings: render distance=%d, simulation distance=%d, window=%dx%d, graphics=%s",
                    mc.options.getViewDistance().getValue(), mc.options.getSimulationDistance().getValue(),
                    mc.getWindow().getFramebufferWidth(), mc.getWindow().getFramebufferHeight(), mc.options.getGraphicsMode().getValue()));
            startPhase();
        }
        if (ticks >= LOOK_START && ticks < FLY_START) {
            look(mc, Perspective.FIRST_PERSON, 90 + (ticks - LOOK_START) * 0.9f, 0); // 20초에 한 바퀴
        }
        if (ticks == FLY_START) {
            endPhase("look-around 20s");
            mc.player.getAbilities().flying = true;
            flyY = Math.max(mc.player.getY() + 10, 140);
            startPhase();
        }
        if (ticks >= FLY_START && ticks < BENCH_END) {
            // 비행선처럼 초당 20블록으로 북쪽으로 날아가며 새 지형을 계속 불러옴
            mc.player.setPosition(mc.player.getX(), flyY, mc.player.getZ() - 1.0);
            look(mc, Perspective.FIRST_PERSON, 180, 20);
        }
        if (ticks == BENCH_END) {
            endPhase("flying 20 blocks/s 20s");
            log("done");
            mc.scheduleStop();
        }
    }

    // Chunky 작업이 아직 돌고 있는지 (컴파일 의존성 없이 Chunky API를 리플렉션으로 확인)
    private static boolean chunkyRunning() {
        try {
            Object chunky = Class.forName("org.popcraft.chunky.ChunkyProvider").getMethod("get").invoke(null);
            Object api = chunky.getClass().getMethod("getApi").invoke(chunky);
            return (boolean) api.getClass().getMethod("isRunning", String.class).invoke(api, "minecraft:overworld");
        } catch (ReflectiveOperationException e) {
            log("chunky api error: " + e);
            return false;
        }
    }

    private static void pregen(MinecraftClient mc) {
        // Chunky는 진행 중인 작업을 모드팩 전체에 저장해서, 다른 월드에서 멈춘 작업이 있으면 새로 시작이 안 됨 -> 먼저 취소
        // 생성하는 동안 몹에게 죽지 않게 관전자로 (테스터 플레이어 데이터는 나중에 지움)
        if (ticks == 30) cmd(mc, "gamemode spectator", "chunky cancel", "chunky confirm");
        if (ticks == 40) {
            log(String.format("pregen start: center=%d,%d radius=%s", mc.player.getBlockX(), mc.player.getBlockZ(), PREGEN));
            cmd(mc, "chunky world minecraft:overworld", "chunky center " + mc.player.getBlockX() + " " + mc.player.getBlockZ(),
                    "chunky radius " + PREGEN, "chunky start");
        }
        if (ticks == 100) pregenStarted = chunkyRunning();
        if (ticks > 100 && ticks % 200 == 0) {
            boolean running = chunkyRunning();
            log("pregen running=" + running + " after " + ticks / 20 + "s");
            if (!running) {
                log(pregenStarted ? "pregen done" : "pregen did not start");
                log("done");
                mc.scheduleStop();
                state = 2;
            }
        }
    }
}
