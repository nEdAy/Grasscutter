package emu.grasscutter;

import java.io.*;
import java.util.Calendar;

import emu.grasscutter.auth.AuthenticationSystem;
import emu.grasscutter.auth.DefaultAuthentication;
import emu.grasscutter.command.CommandMap;
import emu.grasscutter.game.managers.StaminaManager.StaminaManager;
import emu.grasscutter.plugin.PluginManager;
import emu.grasscutter.plugin.api.ServerHook;
import emu.grasscutter.scripts.ScriptLoader;
import emu.grasscutter.server.http.HttpServer;
import emu.grasscutter.server.http.dispatch.DispatchHandler;
import emu.grasscutter.server.http.handlers.*;
import emu.grasscutter.server.http.dispatch.RegionHandler;
import emu.grasscutter.server.http.documentation.DocumentationServerHandler;
import emu.grasscutter.utils.ConfigContainer;
import emu.grasscutter.utils.Utils;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.reflections.Reflections;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import ch.qos.logback.classic.Logger;
import emu.grasscutter.data.ResourceLoader;
import emu.grasscutter.database.DatabaseManager;
import emu.grasscutter.utils.Language;
import emu.grasscutter.server.game.GameServer;
import emu.grasscutter.tools.Tools;
import emu.grasscutter.utils.Crypto;

import javax.annotation.Nullable;

import static emu.grasscutter.utils.Language.translate;
import static emu.grasscutter.Configuration.*;

public final class Grasscutter {
	private static final Logger log = (Logger) LoggerFactory.getLogger(Grasscutter.class);
	private static LineReader consoleLineReader = null;
	
	private static Language language;

	private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	public static final File configFile = new File("./config.json");

	private static int day; // Current day of week.

	private static HttpServer httpServer;
	private static GameServer gameServer;
	private static PluginManager pluginManager;
	private static AuthenticationSystem authenticationSystem;

	public static final Reflections reflector = new Reflections("emu.grasscutter");
	public static ConfigContainer config;
  
	static {
		// Declare logback configuration.
		System.setProperty("logback.configurationFile", "src/main/resources/logback.xml");

		// Load server configuration.
		Grasscutter.loadConfig();
		// Attempt to update configuration.
		ConfigContainer.updateConfig();

		// Load translation files.
		Grasscutter.loadLanguage();

		// Check server structure.
		Utils.startupCheck();
	}

  	public static void main(String[] args) throws Exception {

		// setup UncaughtExceptionHandler http://www.java2s.com/example/java-api/java/lang/thread/setdefaultuncaughtexceptionhandler-1-18.html
		Thread.UncaughtExceptionHandler handler = (thread, cause) -> {
			
		 String message = "";
         String metode = "ZERO";
        
         // Metode get message
         if(cause != null && cause.getMessage() != null && cause.getMessage().isBlank()) { 
          metode = "1";
          message = cause.getMessage();
         }else if(cause.getCause() != null && cause.getCause().getMessage() != null && cause.getCause().getMessage().isBlank()){
          metode = "2";
          message = cause.getCause().getMessage();
         } else {
          metode = "3";
          StringWriter sw = new StringWriter();
          cause.printStackTrace(new PrintWriter(sw));
          message = sw.toString();
         }

         // fiter messages
         String[] lines = message.split(System.getProperty("line.separator"));
         if(lines[0] != null && !lines[0].isEmpty()){
          message = lines[0];
         }
         if(message.matches("(.*)OutOfMemoryError(.*)")){         
          GameServer.doExit(0,"(2) Trying to exit program because memory is full");
         }else{
          Grasscutter.getLogger().error("BIG PROBLEM Thread (C"+metode+"): "+message);
		  //thread.interrupt();
         }
			
		};
		Thread.setDefaultUncaughtExceptionHandler(handler);
		Thread.currentThread().setUncaughtExceptionHandler(handler);

		Crypto.loadKeys(); // Load keys from buffers.
	
		// Parse arguments.
		boolean exitEarly = false;
		for (String arg : args) {
			switch (arg.toLowerCase()) {
             case "-boot" -> {
              Grasscutter.getLogger().info("Boot Mode");
		      exitEarly = true;
			 }
			}
		}
		
		// Exit early if argument sets it.
		if(exitEarly) GameServer.doExit(0,"exitEarly");
	
		// Initialize server.
		Grasscutter.getLogger().info(translate("messages.status.starting"));
	
		// Load all resources.
		Grasscutter.updateDayOfWeek();
		ResourceLoader.loadAll();
		ScriptLoader.init();
	
		// Initialize database.
		DatabaseManager.initialize();
		
		// Initialize the default authentication system.
		authenticationSystem = new DefaultAuthentication();
	
		// Create server instances.
		httpServer = new HttpServer();
		gameServer = new GameServer();
		// Create a server hook instance with both servers.
		new ServerHook(gameServer, httpServer);
		
		// Create plugin manager instance.
		pluginManager = new PluginManager();
		// Add HTTP routes after loading plugins.
		httpServer.addRouter(HttpServer.UnhandledRequestRouter.class);
		httpServer.addRouter(HttpServer.DefaultRequestRouter.class);
		httpServer.addRouter(RegionHandler.class);
		httpServer.addRouter(LogHandler.class);
		httpServer.addRouter(GenericHandler.class);
		httpServer.addRouter(AnnouncementsHandler.class);
		httpServer.addRouter(DispatchHandler.class);
		httpServer.addRouter(GachaHandler.class);
		httpServer.addRouter(DocumentationServerHandler.class);
		
		// TODO: find a better place?
		StaminaManager.initialize();
	
		// Start servers.
		var runMode = SERVER.runMode;
		if (runMode == ServerRunMode.HYBRID) {
			httpServer.start();
			gameServer.start();
		} else if (runMode == ServerRunMode.DISPATCH_ONLY) {
			httpServer.start();
		} else if (runMode == ServerRunMode.GAME_ONLY) {
			gameServer.start();
		} else {
			getLogger().error(translate("messages.status.run_mode_error", runMode));
			getLogger().error(translate("messages.status.run_mode_help"));
			getLogger().error(translate("messages.status.shutdown"));
			GameServer.doExit(0,"run_mode_error");
		}
	
		// Enable all plugins.
		pluginManager.enablePlugins();
	
		// Hook into shutdown event.
		Runtime.getRuntime().addShutdownHook(new Thread(Grasscutter::onShutdown));
	
		// Open console.
		startConsole();
 	}

	/**
	 * Server shutdown event.
	 */
	private static void onShutdown() {
		// Disable all plugins.
		pluginManager.disablePlugins();
	}

	/*
	 * Methods for the language system component.
	 */
	
	public static void loadLanguage() {
		var locale = config.language.language;
		language = Language.getLanguage(Utils.getLanguageCode(locale));
	}
	
	/*
	 * Methods for the configuration system component.
	 */

	/**
	 * Attempts to load the configuration from a file.
	 */
	public static void loadConfig() {
		// Check if config.json exists. If not, we generate a new config.
		if (!configFile.exists()) {
			getLogger().info("config.json could not be found. Generating a default configuration ...");
			config = new ConfigContainer();
			Grasscutter.saveConfig(config);
			return;
		} 

		// If the file already exists, we attempt to load it.
		try (FileReader file = new FileReader(configFile)) {
			config = gson.fromJson(file, ConfigContainer.class);
		} catch (Exception exception) {
			getLogger().error("There was an error while trying to load the configuration from config.json. Please make sure that there are no syntax errors. If you want to start with a default configuration, delete your existing config.json.");
			GameServer.doExit(0,"Error config.json");
		} 
	}

	/**
	 * Saves the provided server configuration.
	 * @param config The configuration to save, or null for a new one.
	 */
	public static void saveConfig(@Nullable ConfigContainer config) {
		if(config == null) config = new ConfigContainer();
		
		try (FileWriter file = new FileWriter(configFile)) {
			file.write(gson.toJson(config));
		} catch (IOException ignored) {
			Grasscutter.getLogger().error("Unable to write to config file.");
		} catch (Exception e) {
			Grasscutter.getLogger().error("Unable to save config file.", e);
		}
	}

	/*
	 * Getters for the various server components.
	 */
	
	public static ConfigContainer getConfig() {
		return config;
	}

	public static Language getLanguage() {
		return language;
	}

	public static void setLanguage(Language language) {
        Grasscutter.language = language;
	}

	public static Language getLanguage(String langCode) {
        return Language.getLanguage(langCode);
	}

	public static Logger getLogger() {
		return log;
	}

	public static LineReader getConsole() {
		if (consoleLineReader == null) {
			Terminal terminal = null;
			try {
				terminal = TerminalBuilder.builder().jna(true).build();
			} catch (Exception e) {
				try {
					// Fallback to a dumb jline terminal.
					terminal = TerminalBuilder.builder().dumb(true).build();
				} catch (Exception ignored) {
					// When dumb is true, build() never throws.
				}
			}
			consoleLineReader = LineReaderBuilder.builder()
					.terminal(terminal)
					.build();
		}
		return consoleLineReader;
	}

	public static Gson getGsonFactory() {
		return gson;
	}

	public static HttpServer getHttpServer() {
		return httpServer;
	}

	public static GameServer getGameServer() {
		return gameServer;
	}

	public static PluginManager getPluginManager() {
		return pluginManager;
	}
	
	public static AuthenticationSystem getAuthenticationSystem() {
		return authenticationSystem;
	}

	public static int getCurrentDayOfWeek() {
		return day;
	}
	
	/*
	 * Utility methods.
	 */
	
	public static void updateDayOfWeek() {
		Calendar calendar = Calendar.getInstance();
		day = calendar.get(Calendar.DAY_OF_WEEK); 
	}

	public static void startConsole() {
		// Console should not start in dispatch only mode.
		if (SERVER.runMode == ServerRunMode.DISPATCH_ONLY) {
			getLogger().info(translate("messages.dispatch.no_commands_error"));
			return;
		}

		getLogger().info(translate("messages.status.done"));
		String input = null;
		boolean isLastInterrupted = false;
		while (config.server.game.enableConsole) {
			try {
				input = consoleLineReader.readLine("> ");
			} catch (OutOfMemoryError e) {
				GameServer.doExit(1,"OutOfMemoryError consoleLineReader");
			} catch (UserInterruptException e) {
				if (!isLastInterrupted) {
					isLastInterrupted = true;
					Grasscutter.getLogger().info("Press Ctrl-C again to shutdown.");
					continue;
				} else {
					GameServer.doExit(1,"Exit by user...");
				}
			} catch (EndOfFileException e) {
				Grasscutter.getLogger().info("EOF detected.");
				continue;
			} catch (IOError e) {
				Grasscutter.getLogger().error("An IO error occurred.", e);
				continue;
			}

			isLastInterrupted = false;
			try {
				CommandMap.getInstance().invoke(null, null, input);
			} catch (Exception e) {
				Grasscutter.getLogger().error(translate("messages.game.command_error"), e);
			}
		}
	}

	/**
	 * Sets the authentication system for the server.
	 * @param authenticationSystem The authentication system to use.
	 */
	public static void setAuthenticationSystem(AuthenticationSystem authenticationSystem) {
		Grasscutter.authenticationSystem = authenticationSystem;
	}

	/*
	 * Enums for the configuration.
	 */
	
	public enum ServerRunMode {
		HYBRID, DISPATCH_ONLY, GAME_ONLY
	}

	public enum ServerDebugMode {
		ALL, MISSING, NONE
	}
}
