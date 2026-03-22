package l3gh.stats.mod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ModInitializer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class Statmod implements ModInitializer {
	public static final String MOD_ID = "stat-mod";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {

			ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
			LOGGER.info("yes, the l3gh mod DID start atleast...");

			Path configFile = FabricLoader.getInstance().getConfigDir().resolve("stat-mod.json");
			String secret;
			String endpoint;

			try {
				String content = Files.readString(configFile);
				JsonObject config = JsonParser.parseString(content).getAsJsonObject();
				secret = config.get("secret").getAsString();
				endpoint = config.get("endpoint").getAsString();
			} catch (IOException e) {
				LOGGER.error("stat-mod: config file missing at config/stat-mod.json");
				return;
			}

			scheduler.scheduleAtFixedRate(
					() -> {
						Path statsDir = server.getWorldPath(LevelResource.PLAYER_STATS_DIR);
						Path userCache = server.getServerDirectory().toPath().resolve("usercache.json");
						try {
							String userCacheContent = Files.readString(userCache);

							JsonObject payload = new JsonObject();
							payload.addProperty("secret", secret);
							payload.add("usercache", JsonParser.parseString(userCacheContent));

							JsonObject stats = new JsonObject();
							Files.list(statsDir).forEach(statFile -> {
								try {
									String statContent = Files.readString(statFile);
									String uuid = statFile.getFileName().toString().replace(".json", "");
									long lastModified = Files.getLastModifiedTime(statFile).toMillis();

									JsonObject playerData = new JsonObject();
									playerData.add("stats", JsonParser.parseString(statContent));
									playerData.addProperty("lastSeen", lastModified);
									stats.add(uuid, playerData);
								} catch (IOException e) {
									LOGGER.error("Failed to read stat file: " + e.getMessage());
								}
							});
							payload.add("stats", stats);

							LOGGER.info("Pushing stats... payload built for " + stats.size() + " players");

							HttpClient client = HttpClient.newHttpClient();
							HttpRequest request = HttpRequest.newBuilder()
									.uri(URI.create(endpoint))
									.header("Content-Type", "application/json")
									.POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
									.build();

							HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
							LOGGER.info("Ingest response: " + response.statusCode());

						} catch (IOException | InterruptedException e) {
							LOGGER.error("Failed to read files: " + e.getMessage());
						}
						LOGGER.info("Pushing stats...");
					},  // thats the whole mod ^^
					0,              // initial delay - changing this is lowk pointless
					3,              // interval in given unit
					TimeUnit.MINUTES // unit - just dont touch me ig
			);
		});
	}
}