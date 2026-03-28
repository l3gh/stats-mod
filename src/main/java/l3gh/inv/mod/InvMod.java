package l3gh.inv.mod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

public class InvMod implements ModInitializer {
    public static final String MOD_ID = "inv-mod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static MinecraftServer server;
    private static WsServer wsServer;

    // Debounce: markDirty() fires on every slot change, so we wait 300ms of
    // silence before actually serialising + broadcasting. This batches rapid
    // inventory operations (e.g. shift-clicking a stack) into one push.
    private static final ScheduledExecutorService debouncer =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "inv-mod-debouncer");
                t.setDaemon(true);
                return t;
            });
    private static final Map<UUID, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;

            Path configFile = FabricLoader.getInstance().getConfigDir().resolve("inv-mod.json");
            String secret;
            int port;

            try {
                String content = Files.readString(configFile);
                JsonObject config = JsonParser.parseString(content).getAsJsonObject();
                secret = config.get("secret").getAsString();
                port   = config.get("port").getAsInt();
            } catch (IOException e) {
                LOGGER.error("inv-mod: config missing — create config/inv-mod.json:");
                LOGGER.error("  {\"secret\": \"your-token-here\", \"port\": 15059}");
                return;
            }

            wsServer = new WsServer(secret, port);
            try {
                wsServer.start();
            } catch (Exception e) {
                LOGGER.error("inv-mod: failed to start WebSocket server on port " + port + ": " + e.getMessage());
                LOGGER.error("inv-mod: port may be blocked — check your host panel or try a different port");
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            if (wsServer != null) wsServer.stop();
            debouncer.shutdownNow();
        });
    }

    /**
     * Called by WsFrameHandler after a client authenticates.
     * Immediately sends the current inventory state of every online player
     * so the frontend doesn't show an empty grid until someone moves an item.
     */
    public static void pushAllOnlinePlayers(Channel ch) {
        if (server == null || !ch.isActive()) return;
        server.execute(() -> {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                String json = InvSerializer.serialize(p).toString();
                ch.writeAndFlush(new TextWebSocketFrame(json));
            }
        });
    }

    /**
     * Called from PlayerInventoryMixin whenever a player's inventory is marked dirty.
     * Debounces rapid successive calls and schedules a serialise+broadcast after 300ms of quiet.
     */
    public static void onInventoryChanged(ServerPlayer player) {
        if (wsServer == null) return;

        UUID uuid = player.getUUID();

        // cancel any pending push for this player and reschedule
        ScheduledFuture<?> existing = pending.remove(uuid);
        if (existing != null) existing.cancel(false);

        pending.put(uuid, debouncer.schedule(() -> {
            pending.remove(uuid);
            if (server == null || wsServer == null) return;

            // jump back to the server thread to safely read inventory state
            server.execute(() -> {
                ServerPlayer p = server.getPlayerList().getPlayer(uuid);
                if (p == null) return; // player logged off in the 300ms window
                String json = InvSerializer.serialize(p).toString();
                wsServer.broadcast(json);
                LOGGER.debug("inv-mod: pushed inventory for {}", p.getName().getString());
            });
        }, 300, TimeUnit.MILLISECONDS));
    }
}
