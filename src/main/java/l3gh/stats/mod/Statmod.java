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


public class Statmod implements ModInitializer {
	public static final String MOD_ID = "stat-mod";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {

			ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
			LOGGER.info("L3gh Mod uhm idk just some log statement");

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
						Path userCache = server.getWorldPath(LevelResource.ROOT).getParent().resolve("usercache.json");
						try {
							String userCacheContent = Files.readString(userCache);

							Files.list(statsDir).forEach(statFile -> {
								try {
									String statContent = Files.readString(statFile);
									LOGGER.info("Read stats for: " + statFile.getFileName());
								} catch (IOException e) {
									LOGGER.error("Failed to read stat file: " + e.getMessage());
								}
							});
						} catch (IOException e) {
							LOGGER.error("Failed to read files: " + e.getMessage());
						}
						LOGGER.info("Pushing stats...");

					},  // the task
					0,              // initial delay
					5,              // interval
					TimeUnit.MINUTES // unit
			);
		});
	}
}