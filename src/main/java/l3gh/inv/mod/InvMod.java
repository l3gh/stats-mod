package l3gh.inv.mod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

public class InvMod implements ModInitializer {
    public static final String MOD_ID = "inv-mod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static MinecraftServer server;
    private static String secret;
    private static String endpoint;
    private static final HttpClient httpClient = HttpClient.newHttpClient();

    // debounce rapid slot changes — 300ms quiet before posting
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
            try {
                String content = Files.readString(configFile);
                JsonObject config = JsonParser.parseString(content).getAsJsonObject();
                secret   = config.get("secret").getAsString();
                endpoint = config.get("endpoint").getAsString();
            } catch (IOException e) {
                LOGGER.error("inv-mod: config missing — create config/inv-mod.json:");
                LOGGER.error("  {\"secret\": \"your-token\", \"endpoint\": \"https://l3gh.com/api/ingest-inv\"}");
                return;
            }

            // push every player's last known inventory from disk on startup
            pushAllOfflinePlayerData(s);
        });

        // when a player logs out, push their final live inventory
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
            ServerPlayer player = handler.getPlayer();
            JsonObject payload = InvSerializer.serialize(player);
            postAsync(payload);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            debouncer.shutdownNow();
        });
    }

    private static void pushAllOfflinePlayerData(MinecraftServer s) {
        Path playerDataDir = s.getWorldPath(LevelResource.ROOT).resolve("playerdata");
        if (!Files.exists(playerDataDir)) {
            LOGGER.warn("inv-mod: playerdata directory not found at {}", playerDataDir);
            return;
        }

        debouncer.submit(() -> {
            try {
                Files.list(playerDataDir)
                    .filter(p -> p.toString().endsWith(".dat"))
                    .forEach(datFile -> {
                        String filename = datFile.getFileName().toString();
                        String uuidStr  = filename.replace(".dat", "");
                        try { UUID.fromString(uuidStr); } catch (IllegalArgumentException e) { return; }

                        try {
                            CompoundTag nbt = NbtIo.readCompressed(datFile.toFile());
                            JsonObject payload = InvSerializer.serializeFromDat(uuidStr, nbt);
                            postAsync(payload);
                        } catch (IOException e) {
                            LOGGER.error("inv-mod: failed to read playerdata for {}: {}", uuidStr, e.getMessage());
                        }
                    });
                LOGGER.info("inv-mod: finished pushing offline player inventory data");
            } catch (IOException e) {
                LOGGER.error("inv-mod: failed to list playerdata directory: {}", e.getMessage());
            }
        });
    }

    public static void onInventoryChanged(ServerPlayer player) {
        if (endpoint == null) return;

        UUID uuid = player.getUUID();
        ScheduledFuture<?> existing = pending.remove(uuid);
        if (existing != null) existing.cancel(false);

        pending.put(uuid, debouncer.schedule(() -> {
            pending.remove(uuid);
            if (server == null) return;
            server.execute(() -> {
                ServerPlayer p = server.getPlayerList().getPlayer(uuid);
                if (p == null) return;
                JsonObject payload = InvSerializer.serialize(p);
                postAsync(payload);
                LOGGER.debug("inv-mod: pushed inventory for {}", p.getName().getString());
            });
        }, 300, TimeUnit.MILLISECONDS));
    }

    public static void postAsync(JsonObject payload) {
        if (endpoint == null || secret == null) return;
        payload.addProperty("secret", secret);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(res -> {
                    if (res.statusCode() != 200) {
                        LOGGER.warn("inv-mod: ingest returned {}", res.statusCode());
                    }
                })
                .exceptionally(e -> {
                    LOGGER.error("inv-mod: failed to post inventory: {}", e.getMessage());
                    return null;
                });
    }
}