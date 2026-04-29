package wowchat.discord;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigResolveOptions;
import net.dv8tion.jda.api.JDA;
import wowchat.common.Global$;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Handles Discord bot status rotation.
 * Cycles through configured status messages on a timer.
 */
public class GuildStatusRotation {

    private static volatile List<String> statusMessages = Collections.emptyList();
    private static volatile int statusRotateSecs = 60;
    private static volatile int statusIndex = 0;
    private static volatile boolean started = false;
    
    /** When true, GamePacketHandler.updateGuildiesOnline() is suppressed via bytecode patch. */
    public static volatile boolean rotationActive = false;

    private static ScheduledExecutorService scheduler;

    private GuildStatusRotation() {}

    public static synchronized void init() {
        if (started) return;
        started = true;

        loadConfig();

        // Only schedule if messages are configured
        rotationActive = !statusMessages.isEmpty();
        if (!statusMessages.isEmpty()) {
            scheduler = Executors.newSingleThreadScheduledExecutor(
                new GuildOnlineListPublisher.DaemonThreadFactory("wowchat-status-rotation"));
            
            long rotatePeriod = Math.max(5, statusRotateSecs);
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    rotateStatus();
                } catch (Throwable t) {
                    System.err.println("[GuildStatus] Error rotating status: " + t.getMessage());
                }
            }, 15L, rotatePeriod, TimeUnit.SECONDS);
            
            System.out.println("[GuildStatus] Status rotation initialized. " 
                + statusMessages.size() + " messages, " + statusRotateSecs + "s interval.");
        } else {
            System.out.println("[GuildStatus] No status messages configured - using default status.");
        }
    }

    private static void loadConfig() {
        try {
            String configFile = System.getProperty("wowchat.configFile", "wowchat.conf");
            Config config = ConfigFactory.parseFile(new File(configFile))
                .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true));

            // Check if status rotation is enabled
            boolean enabled = true; // Default enabled for backward compat
            if (config.hasPath("guildStatus.enabled")) {
                enabled = config.getBoolean("guildStatus.enabled");
            }
            
            if (!enabled) {
                System.out.println("[GuildStatus] Feature disabled in config");
                statusMessages = Collections.emptyList();
                rotationActive = false;
                return;
            }

            // Status rotation messages - NEW path first, fall back to OLD
            List<String> msgList = new ArrayList<>();
            try {
                String messagesPath = config.hasPath("guildStatus.messages")
                    ? "guildStatus.messages"
                    : "guildStatusMessages";
                
                if (config.hasPath(messagesPath)) {
                    for (String msg : config.getStringList(messagesPath)) {
                        if (msg != null && !msg.trim().isEmpty()) {
                            msgList.add(msg.trim());
                        }
                    }
                }
            } catch (Throwable ignored) {}
            
            statusMessages = msgList.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(msgList);

            // Status rotation interval - NEW path first, fall back to OLD
            try {
                if (config.hasPath("guildStatus.rotateSeconds")) {
                    statusRotateSecs = config.getInt("guildStatus.rotateSeconds");
                } else if (config.hasPath("guildStatusRotateSeconds")) {
                    statusRotateSecs = config.getInt("guildStatusRotateSeconds");
                }
                if (statusRotateSecs < 5) statusRotateSecs = 5;
            } catch (ConfigException e) {
                statusRotateSecs = 60;
            }

        } catch (Throwable t) {
            System.err.println("[GuildStatus] Failed to load config: " + t.getMessage());
            statusMessages = Collections.emptyList();
        }
    }

    private static void rotateStatus() {
        if (statusMessages.isEmpty()) return;

        // Pick next message in rotation
        String template = statusMessages.get(statusIndex % statusMessages.size());
        statusIndex++;

        // Resolve {online-members} if present
        String message = template;
        if (message.contains("{online-members}")) {
            int count = getOnlineCount();
            String membersLabel = count == 1 ? "1 Guild Member" : count + " Guild Members";
            message = message.replace("{online-members}", membersLabel);
        }

        // Push to Discord presence
        try {
            JDA jda = GuildOnlineListPublisher.getJda();
            if (jda != null) {
                Global$.MODULE$.discord().changeGuildStatus(message);
            }
        } catch (Throwable t) {
            System.err.println("[GuildStatus] Failed to update status: " + t.getMessage());
        }
    }
    
    /**
     * Get the count of online guild members directly from GamePacketHandler.
     * This is more reliable than using the cache since the cache might be stale.
     */
    private static int getOnlineCount() {
        try {
            wowchat.game.GameCommandHandler handler = Global$.MODULE$.game().get();
            if (handler instanceof wowchat.game.GamePacketHandler) {
                wowchat.game.GamePacketHandler gph = (wowchat.game.GamePacketHandler) handler;
                // Use getGuildiesOnlineMessage which returns "N guildies online"
                String msg = gph.getGuildiesOnlineMessage(true);
                // Parse the count from "N guildies online" or "N guildie online"
                String[] parts = msg.split(" ");
                if (parts.length > 0) {
                    return Integer.parseInt(parts[0]);
                }
            }
        } catch (Throwable t) {
            // Ignore and return 0
        }
        return 0;
    }
}
