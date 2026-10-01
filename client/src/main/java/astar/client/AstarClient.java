package astar.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import astar.client.draw.OverlayColour;
import astar.movement.Scenario;
import astar.movement.exec.AimController;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * Records movement traces for calibrating the route executor's physics against the game.
 *
 * <ul>
 *   <li>{@code /trace start} records while you play; {@code /trace stop} ends it.
 *   <li>{@code /trace play <script>} stands still until you're on the ground, then plays a
 *       scripted run of inputs and records it.
 *   <li>{@code /trace list} names the scripts.
 * </ul>
 * Traces go to {@code .minecraft/astar-traces/}; your own scripts go in
 * {@code .minecraft/config/astar-scenarios/<name>.txt}.
 *
 * <p>{@code /trace course <x> <y> <z>} builds a flat test course there ({@link TestCourse}) in a
 * world with cheats on; after that, scripts with an {@code at} line start from their place on
 * the course.
 *
 * <p>A script with a {@code from} line teleports the player to that place in the world first
 * (commands must be allowed), which is how the executor's runs are played in a real map.
 *
 * <p>For unattended runs, {@code -Dastar.trace.commands="difficulty peaceful;time set day"}
 * runs those commands once a world is joined, {@code -Dastar.trace.course=x,y,z} builds the
 * course first, and {@code -Dastar.trace.autoplay=walk,sprint} plays those scripts one
 * after another, and {@code -Dastar.trace.autoquit=true} closes the game when they're done.
 * {@code -Dastar.shapes.dump=<file>} writes every block state's collision shape there
 * ({@link ShapeDump}) on the first tick.
 *
 * <p>{@code /goto <x> <y> <z>} walks the player there by itself ({@link Navigator}); any
 * movement key or mouse turn pauses it, {@code /goto resume} carries on and {@code /goto stop}
 * ends it, and {@code /goto show} hides or shows the route drawn in the world ({@code /goto
 * show <colour>} picks its colour: red, orange, yellow, green, cyan, blue, purple, pink, white)
 * ({@link RouteOverlay}). Routes can reach past what the game has loaded, through chunks saved
 * while exploring and the bundled Dwarven Mines map ({@link Places}); {@code /goto cache}
 * says what's saved, {@code on}/{@code off} turn saving on or off and {@code forget} drops the
 * saved chunks of the place you're in. {@code /goto warp <x> <y> <z>} also etherwarps where
 * that's quicker ({@link Warps}, {@link WarpCast}), {@code /goto it <x> <y> <z>} casts Instant
 * Transmission ({@link TransmitCast}) and {@code /goto aotv <x> <y> <z>} does both: with an
 * Aspect of the Void in the hotbar, or in single player, where a {@code /tp} stands in for the
 * cast; {@code -Dastar.goto.warp=warp|it|aotv} makes the unattended trips below do the same.
 * {@code -Dastar.goto=x,y,z} does the same unattended, after the scripts (or
 * {@code x,y,z;x,y,z;...}: one trip after another, with commands such as {@code tp @s x y z}
 * between them), and
 * autoquit waits for it; {@code -Dastar.goto.commands="40:tp @s ~ ~ ~-3"} runs commands so
 * many ticks into it, to disturb it.
 */
public final class AstarClient implements ClientModInitializer {

    /** How long a script waits for the player to stand still before giving up. */
    private static final int MAX_WAIT_TICKS = 100;

    private TraceRecorder recorder;
    private Scenario scenario;
    private ScriptedInput scripted;
    private int frame;
    private int waited;
    private float startYaw;
    private final ArrayDeque<String> autoplay = new ArrayDeque<>();
    private final boolean autoquit = Boolean.getBoolean("astar.trace.autoquit");
    private int ticksInWorld;
    /** Ticks between looks at whether to warm up /goto's copy ({@link LiveMap#warmUp}). */
    private static final int WARM_EVERY = 20;
    private int warmTicks;
    private TestCourse course;
    private String pendingCourse = System.getProperty("astar.trace.course", "");
    private String pendingCommands = System.getProperty("astar.trace.commands", "");
    private String pendingShapes = System.getProperty("astar.shapes.dump", "");
    private String pendingGoto = System.getProperty("astar.goto", "");
    /**
     * Commands to run while an unattended goto drives, each after so many ticks of it, to
     * disturb it: {@code 40:tp @s ~ ~ ~-3;80:fill 10 64 10 10 65 10 stone}. {@code 60:screenshot
     * name} saves a screenshot instead, {@code 30:thirdperson} (or {@code thirdperson front})
     * moves the camera behind (or in front of) the player, and {@code hidegui} hides the HUD.
     */
    private final List<String> gotoCommands = new ArrayList<>(List.of(
            System.getProperty("astar.goto.commands", "").split(";")));
    private int gotoTicks;
    private Navigator navigator;
    private final RouteOverlay overlay = new RouteOverlay(() -> navigator);
    private int courseTicks;
    /**
     * Where the script's {@code at} or {@code from} line puts the player, or null to start
     * where it is.
     */
    private Vec3 target;

    @Override
    public void onInitializeClient() {
        // So the first /goto's walk isn't laid out by code the JVM hasn't compiled yet.
        WalkWarmUp.start();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registries) ->
                dispatcher.register(literal("trace")
                        .then(literal("start").executes(c -> start(feedback(c.getSource()))))
                        .then(literal("stop").executes(c -> stop(feedback(c.getSource()))))
                        .then(literal("list").executes(c -> list(feedback(c.getSource()))))
                        .then(literal("course").then(argument("x", IntegerArgumentType.integer())
                                .then(argument("y", IntegerArgumentType.integer())
                                .then(argument("z", IntegerArgumentType.integer())
                                .executes(c -> buildCourse(feedback(c.getSource()), new BlockPos(
                                        IntegerArgumentType.getInteger(c, "x"),
                                        IntegerArgumentType.getInteger(c, "y"),
                                        IntegerArgumentType.getInteger(c, "z"))))))))
                        .then(literal("play").then(argument("script", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    scriptNames().forEach(b::suggest);
                                    return b.buildFuture();
                                })
                                .executes(c -> play(feedback(c.getSource()),
                                        StringArgumentType.getString(c, "script")))))));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registries) ->
                dispatcher.register(literal("goto")
                        .then(literal("stop").executes(c -> stopGoto(feedback(c.getSource()))))
                        .then(literal("resume").executes(c -> resumeGoto(
                                feedback(c.getSource()))))
                        .then(literal("cache")
                                .executes(c -> say(c.getSource(), Places.describe()))
                                .then(literal("on").executes(c -> {
                                    Places.setRecording(true);
                                    return say(c.getSource(), Places.describe());
                                }))
                                .then(literal("off").executes(c -> {
                                    Places.setRecording(false);
                                    return say(c.getSource(), Places.describe());
                                }))
                                .then(literal("forget").executes(c -> say(c.getSource(),
                                        Places.forget()))))
                        .then(literal("hand")
                                .executes(c -> say(c.getSource(), MouseHand.describe()))
                                .then(literal("left").executes(c -> {
                                    MouseHand.set(AimController.Hand.LEFT);
                                    return say(c.getSource(), MouseHand.describe());
                                }))
                                .then(literal("right").executes(c -> {
                                    MouseHand.set(AimController.Hand.RIGHT);
                                    return say(c.getSource(), MouseHand.describe());
                                })))
                        .then(literal("map")
                                .executes(c -> say(c.getSource(), Places.describe()))
                                .then(argument("name", StringArgumentType.word())
                                        .executes(c -> say(c.getSource(), Places.choose(
                                                StringArgumentType.getString(c, "name"))))))
                        .then(showCommand())
                        .then(teleporting("warp", Warps.Mode.ETHER))
                        .then(teleporting("it", Warps.Mode.TRANSMIT))
                        .then(teleporting("aotv", Warps.Mode.BOTH))
                        .then(argument("x", IntegerArgumentType.integer())
                                .then(argument("y", IntegerArgumentType.integer())
                                .then(argument("z", IntegerArgumentType.integer())
                                .executes(c -> startGoto(feedback(c.getSource()), new BlockPos(
                                        IntegerArgumentType.getInteger(c, "x"),
                                        IntegerArgumentType.getInteger(c, "y"),
                                        IntegerArgumentType.getInteger(c, "z")))))))));
        for (String name : System.getProperty("astar.trace.autoplay", "").split(",")) {
            if (!name.isBlank()) {
                autoplay.add(name.strip());
            }
        }
        overlay.register();
        CastLog.register();
        PracticeAotv.register();
        ClientChunkEvents.CHUNK_LOAD.register(Places::loaded);
        ClientChunkEvents.CHUNK_UNLOAD.register(Places::unloading);
        ClientTickEvents.START_CLIENT_TICK.register(this::startTick);
        ClientTickEvents.END_CLIENT_TICK.register(this::endTick);
    }

    /**
     * {@code /goto show} hides or shows the route; {@code /goto show <colour>} draws it all in
     * that colour (kept for later games) and shows it.
     */
    private LiteralArgumentBuilder<FabricClientCommandSource> showCommand() {
        var show = literal("show").executes(c -> {
            c.getSource().sendFeedback(Component.literal(overlay.toggle()
                    ? "Showing the route in " + overlay.colour().id() + "." : "Route hidden."));
            return 1;
        });
        for (OverlayColour colour : OverlayColour.values()) {
            show.then(literal(colour.id()).executes(c -> {
                overlay.colour(colour);
                c.getSource().sendFeedback(Component.literal("Route drawn in " + colour.id()
                        + "."));
                return 1;
            }));
        }
        return show;
    }

    private int start(Feedback source) {
        if (busy(source)) {
            return 0;
        }
        if (Minecraft.getInstance().player == null) {
            source.error(Component.literal("Join a world first."));
            return 0;
        }
        try {
            recorder = TraceRecorder.start(traceDir(), "", Minecraft.getInstance());
        } catch (IOException e) {
            source.error(Component.literal("Couldn't start a trace: " + e.getMessage()));
            return 0;
        }
        source.info(Component.literal("Recording to " + recorder.file().getFileName()
                + ". /trace stop ends it."));
        return 1;
    }

    private int buildCourse(Feedback source, BlockPos origin) {
        if (busy(source)) {
            return 0;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.getConnection() == null) {
            source.error(Component.literal("Join a world first."));
            return 0;
        }
        course = new TestCourse(origin);
        for (String command : course.commands()) {
            client.getConnection().sendCommand(command);
        }
        client.getConnection().sendCommand(course.teleport(2.5, 0, -12.5, -90));
        courseTicks = 0;
        source.info(Component.literal("Built the test course at " + origin.toShortString()
                + " (it needs cheats on). Scripts with an \"at\" line now start on it."));
        return 1;
    }

    private int stop(Feedback source) {
        if (recorder == null && scenario == null) {
            source.error(Component.literal("Nothing is recording."));
            return 0;
        }
        finish(scenario != null ? "stopped by /trace stop before the script ended" : null);
        return 1;
    }

    private int list(Feedback source) {
        source.info(Component.literal("Scripts: " + String.join(", ", scriptNames())));
        source.info(Component.literal("Your own go in " + scriptDir()).withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private int play(Feedback source, String name) {
        if (busy(source)) {
            return 0;
        }
        Scenario s;
        try {
            s = loadScript(name);
        } catch (IOException | IllegalArgumentException e) {
            source.error(Component.literal(e.getMessage()));
            return 0;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            source.error(Component.literal("Join a world first."));
            return 0;
        }
        if (client.options.autoJump().get()) {
            source.info(Component.literal("Auto-jump is on, so the game may add jumps the"
                    + " script didn't ask for. Turn it off in Controls for clean traces.")
                    .withStyle(ChatFormatting.YELLOW));
        }
        scenario = s;
        frame = 0;
        waited = 0;
        scripted = new ScriptedInput();
        client.player.input = scripted;
        target = null;
        if (s.start() != null && s.start().world()) {
            Scenario.Start at = s.start();
            client.getConnection().sendCommand("tp @s " + at.x() + " " + at.y() + " " + at.z()
                    + " " + at.yaw() + " 0");
            target = new Vec3(at.x(), at.y(), at.z());
        } else if (course != null && s.start() != null) {
            Scenario.Start at = s.start();
            client.getConnection().sendCommand(course.teleport(at.x(), at.y(), at.z(),
                    at.yaw()));
            BlockPos o = course.origin();
            target = new Vec3(o.getX() + at.x(), o.getY() + at.y(), o.getZ() + at.z());
        }
        source.info(Component.literal("Playing " + s.name() + " (" + s.ticks() + " ticks"
                + (s.description().isEmpty() ? "" : ": " + s.description())
                + ") once you're standing still on the ground."));
        return 1;
    }

    /** {@code /goto <name> x y z}: a trip that teleports as well as walks. */
    private com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource>
            teleporting(String name, Warps.Mode mode) {
        return literal(name).then(argument("x", IntegerArgumentType.integer())
                .then(argument("y", IntegerArgumentType.integer())
                .then(argument("z", IntegerArgumentType.integer())
                .executes(c -> startGoto(feedback(c.getSource()), new BlockPos(
                        IntegerArgumentType.getInteger(c, "x"),
                        IntegerArgumentType.getInteger(c, "y"),
                        IntegerArgumentType.getInteger(c, "z")), mode)))));
    }

    private int startGoto(Feedback source, BlockPos goal) {
        return startGoto(source, goal, null);
    }

    /** @param warp which teleports the trip uses, or null to walk only */
    private int startGoto(Feedback source, BlockPos goal, Warps.Mode warp) {
        if (busy(source)) {
            return 0;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            source.error(Component.literal("Join a world first."));
            return 0;
        }
        if (client.options.autoJump().get()) {
            source.info(Component.literal("Auto-jump is on, so the game may add jumps the route"
                    + " doesn't want. Turn it off in Controls.").withStyle(ChatFormatting.YELLOW));
        }
        navigator = new Navigator(goal, text -> say(client, text), warp);
        source.info(Component.literal("Planning a route to " + goal.toShortString() + "..."));
        navigator.plan(client);
        return 1;
    }

    private int stopGoto(Feedback source) {
        if (!going()) {
            source.error(Component.literal("Not going anywhere."));
            return 0;
        }
        navigator.cancel(Minecraft.getInstance(), "by /goto stop");
        return 1;
    }

    private int resumeGoto(Feedback source) {
        if (navigator == null || navigator.state() != Navigator.State.PAUSED) {
            source.error(Component.literal("Nothing is paused."));
            return 0;
        }
        source.info(Component.literal("Planning again from here to "
                + navigator.goal().toShortString() + "..."));
        navigator.resume(Minecraft.getInstance());
        return 1;
    }

    /** Runs the {@code astar.goto.commands} that are due. */
    private void runDuringGoto(Minecraft client) {
        gotoTicks++;
        gotoCommands.removeIf(c -> {
            int colon = c.indexOf(':');
            if (colon < 0 || Integer.parseInt(c.substring(0, colon).strip()) != gotoTicks) {
                return c.isBlank();
            }
            String command = c.substring(colon + 1).strip();
            if (command.startsWith("screenshot ")) {
                // Not a game command: saves screenshots/<name>.png, to see the overlay.
                Screenshot.grab(client.gameDirectory, command.substring(11).strip() + ".png",
                        client.gameRenderer.mainRenderTarget(), 1,
                        text -> System.out.println("[astar] " + text.getString()));
            } else if (command.startsWith("thirdperson")) {
                client.options.setCameraType(command.endsWith("front")
                        ? CameraType.THIRD_PERSON_FRONT : CameraType.THIRD_PERSON_BACK);
            } else if (command.equals("hidegui")) {
                if (!client.gui.hud.isHidden()) {
                    client.gui.hud.toggle();
                }
            } else {
                client.getConnection().sendCommand(command);
            }
            return true;
        });
    }

    /** Whether a {@code /goto} is planning, driving or paused. */
    private boolean going() {
        return navigator != null && navigator.state() != Navigator.State.DONE;
    }

    private void startTick(Minecraft client) {
        CastLog.tick(client);
        if (going() && !client.isPaused()) {
            if (client.player == null) {
                navigator.cancel(client, "left the world");
            } else {
                navigator.tick(client);
            }
            if (navigator.state() == Navigator.State.DRIVING) {
                runDuringGoto(client);
            }
            if (navigator.state() == Navigator.State.DONE) {
                System.out.println("[astar] goto " + navigator.goal().toShortString() + ": "
                        + navigator.outcome() + "; " + navigator.events());
            }
        }
        // While the game is paused the player doesn't move, so there's no tick to record.
        if ((recorder == null && scenario == null) || client.isPaused()) {
            return;
        }
        LocalPlayer player = client.player;
        if (player == null || (recorder != null && player != recorder.player())
                || (scenario != null && player.input != scripted)) {
            finish("the player was replaced (respawn, world change or disconnect)");
            return;
        }
        try {
            if (scenario != null && recorder == null) {
                boolean placed = target == null || (waited >= 5
                        && player.position().distanceToSqr(target) < 0.01);
                if (!placed || !standingStill(player)) {
                    if (++waited > MAX_WAIT_TICKS) {
                        say(client, Component.literal("Gave up on " + scenario.name() + ": stand still"
                                + " on the ground first.").withStyle(ChatFormatting.RED));
                        finish(null);
                    }
                    return;
                }
                startYaw = player.getYRot();
                recorder = TraceRecorder.start(traceDir(), scenario.name(), client);
                say(client, Component.literal("Recording " + scenario.name() + " to "
                        + recorder.file().getFileName()));
            }
            if (scenario != null) {
                if (frame == scenario.ticks()) {
                    recorder.event("script ended");
                    finish(null);
                    return;
                }
                Scenario.Frame f = scenario.frames().get(frame++);
                scripted.set(f.keys());
                player.setYRot(startYaw + f.yaw());
                if (!Float.isNaN(f.pitch())) {
                    player.setXRot(f.pitch());
                }
            }
            recorder.startTick();
        } catch (IOException e) {
            fail(client, e);
        }
    }

    private void endTick(Minecraft client) {
        if (going()) {
            navigator.blendCamera(client);
        }
        if (!pendingShapes.isBlank()) {
            Path file = Path.of(pendingShapes);
            pendingShapes = "";
            try {
                int n = ShapeDump.write(file);
                System.out.println("[astar] wrote " + n + " block states' shapes to "
                        + file.toAbsolutePath());
            } catch (IOException e) {
                System.out.println("[astar] couldn't write block shapes: " + e);
            }
        }
        if (!autoplay.isEmpty() || autoquit || !pendingGoto.isBlank()) {
            runUnattended(client);
        }
        // Now and then, while no trip is planning, the copy /goto plans on is made ahead of
        // time around the player (LiveMap.warmUp), so /goto, and the next stretch of a long
        // trip, plan at once.
        boolean planning = going() && navigator.state() == Navigator.State.PLANNING;
        if (client.player != null && client.level != null && !planning
                && ++warmTicks % WARM_EVERY == 0) {
            LiveMap.warmUp(client.level, client.player.blockPosition(),
                    client.options.getEffectiveRenderDistance());
        }
        if (recorder == null || client.isPaused()) {
            return;
        }
        try {
            recorder.endTick();
        } catch (IOException e) {
            fail(client, e);
        }
    }

    /** Plays the {@code astar.trace.autoplay} scripts in turn, then quits if asked to. */
    private void runUnattended(Minecraft client) {
        ticksInWorld = client.player == null ? 0 : ticksInWorld + 1;
        courseTicks++;
        // Give the world a couple of seconds to load around the player first, and the course
        // a couple more to be built.
        if (ticksInWorld < 40 || courseTicks < 40 || recorder != null || scenario != null
                || going()) {
            return;
        }
        if (!pendingCommands.isBlank()) {
            for (String command : pendingCommands.split(";")) {
                if (!command.isBlank()) {
                    client.getConnection().sendCommand(command.strip());
                }
            }
            pendingCommands = "";
            courseTicks = 0;
            return;
        }
        if (!pendingCourse.isBlank()) {
            String[] xyz = pendingCourse.split(",");
            pendingCourse = "";
            buildCourse(chat(client, "the course"), new BlockPos(Integer.parseInt(xyz[0].strip()),
                    Integer.parseInt(xyz[1].strip()), Integer.parseInt(xyz[2].strip())));
            return;
        }
        if (!autoplay.isEmpty()) {
            String name = autoplay.poll();
            play(chat(client, name), name);
        } else if (!pendingGoto.isBlank()) {
            // One goal, or several in turn: "x,y,z;x,y,z".
            int semi = pendingGoto.indexOf(';');
            String[] xyz = (semi < 0 ? pendingGoto : pendingGoto.substring(0, semi)).split(",");
            pendingGoto = semi < 0 ? "" : pendingGoto.substring(semi + 1);
            if (xyz.length == 1) {
                // A command between trips, such as "tp @s 168 202 283" back to the start.
                client.getConnection().sendCommand(xyz[0].strip());
                courseTicks = 0;
                return;
            }
            startGoto(chat(client, "the goto"), new BlockPos(Integer.parseInt(xyz[0].strip()),
                    Integer.parseInt(xyz[1].strip()), Integer.parseInt(xyz[2].strip())),
                    switch (System.getProperty("astar.goto.warp", "")) {
                        case "true", "warp" -> Warps.Mode.ETHER;
                        case "it" -> Warps.Mode.TRANSMIT;
                        case "aotv" -> Warps.Mode.BOTH;
                        default -> null;
                    });
        } else if (autoquit) {
            client.stop();
        }
    }

    /** Messages for an unattended step go to chat, and errors say what was skipped. */
    private static Feedback chat(Minecraft client, String what) {
        return new Feedback() {
            @Override
            public void info(Component text) {
                say(client, text);
            }

            @Override
            public void error(Component text) {
                say(client, Component.literal("Skipped " + what + ": ").append(text)
                        .withStyle(ChatFormatting.RED));
            }
        };
    }

    /** Ends the trace and gives the keyboard back. */
    private void finish(String why) {
        Minecraft client = Minecraft.getInstance();
        if (scenario != null && client.player != null && client.player.input == scripted) {
            client.player.input = new KeyboardInput(client.options);
        }
        scenario = null;
        scripted = null;
        if (recorder != null) {
            TraceRecorder r = recorder;
            recorder = null;
            try {
                if (why != null) {
                    r.event(why);
                }
                r.close();
                say(client, Component.literal("Saved " + r.ticks() + " ticks to " + r.file()
                        + (why == null ? "" : " (" + why + ")")));
            } catch (IOException e) {
                say(client, Component.literal("Couldn't finish the trace: " + e.getMessage())
                        .withStyle(ChatFormatting.RED));
            }
        }
    }

    private void fail(Minecraft client, IOException e) {
        say(client, Component.literal("Trace stopped, couldn't write: " + e.getMessage())
                .withStyle(ChatFormatting.RED));
        finish(null);
    }

    /** Where a command's messages go: the command's own feedback, or chat when unattended. */
    private interface Feedback {
        void info(Component text);

        void error(Component text);
    }

    private static int say(FabricClientCommandSource source, String text) {
        source.sendFeedback(Component.literal(text));
        return 1;
    }

    private static Feedback feedback(FabricClientCommandSource source) {
        return new Feedback() {
            @Override
            public void info(Component text) {
                source.sendFeedback(text);
            }

            @Override
            public void error(Component text) {
                source.sendError(text);
            }
        };
    }

    private boolean busy(Feedback source) {
        if (recorder != null || scenario != null) {
            source.error(Component.literal("A trace is already running; /trace stop ends it."));
            return true;
        }
        if (going()) {
            source.error(Component.literal("Already going to " + navigator.goal().toShortString()
                    + "; /goto stop ends it."));
            return true;
        }
        return false;
    }

    private static boolean standingStill(LocalPlayer player) {
        return player.onGround() && player.getDeltaMovement().horizontalDistanceSqr() < 1e-6;
    }

    private static void say(Minecraft client, Component text) {
        if (client.player != null) {
            client.player.sendSystemMessage(text);
        }
    }

    private static Scenario loadScript(String name) throws IOException {
        Path file = scriptDir().resolve(name + ".txt");
        if (Files.isRegularFile(file)) {
            return Scenario.parse(name, Files.readString(file, StandardCharsets.UTF_8));
        }
        return Scenario.builtIn(name);
    }

    private static List<String> scriptNames() {
        List<String> names = new ArrayList<>(Scenario.BUILT_IN);
        try (Stream<Path> files = Files.list(scriptDir())) {
            files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".txt"))
                    .map(n -> n.substring(0, n.length() - 4))
                    .filter(n -> !names.contains(n))
                    .sorted()
                    .forEach(names::add);
        } catch (IOException e) {
            // No scripts folder yet: only the built-in ones.
        }
        return names;
    }

    private static Path traceDir() {
        return FabricLoader.getInstance().getGameDir().resolve("astar-traces");
    }

    private static Path scriptDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("astar-scenarios");
    }
}
