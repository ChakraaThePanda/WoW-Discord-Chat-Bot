package wowchat.discord;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigResolveOptions;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import wowchat.common.Global$;

import java.io.File;

/**
 * Handles Discord DM auto-reply.
 * Reads dmAutoReply from wowchat.conf and replies to any DM the bot receives.
 * Registered onto the JDA instance at bot startup via GuildOnlineListPublisher.
 */
public class DiscordDMHandler extends ListenerAdapter {

    private final String replyMessage;

    public DiscordDMHandler(String replyMessage) {
        this.replyMessage = replyMessage;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        // Only handle DMs, ignore own messages
        if (event.getChannelType() != ChannelType.PRIVATE) return;
        if (event.getAuthor().isBot()) return;

        if (replyMessage != null && !replyMessage.isEmpty()) {
            event.getChannel().sendMessage(replyMessage).queue(
                success -> {},
                error -> System.err.println("[DiscordDMHandler] Failed to send DM reply: " + error.getMessage())
            );
        }
    }

    /**
     * Initialize DM auto-reply feature.
     * Called from WoWChat.main() during startup.
     */
    public static void init() {
        String configFile = System.getProperty("wowchat.configFile", "wowchat.conf");
        register(configFile);
    }

    /**
     * Loads the dmAutoReply config and registers this listener on the JDA instance.
     * Called once from init() during initialization.
     */
    public static void register(String configFile) {
        try {
            // Use allowUnresolved so missing env vars (${?VAR}) don't cause failures
            Config config = ConfigFactory.parseFile(new File(configFile))
                .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true));

            // Check if DM auto-reply is enabled (NEW path with fallback to OLD)
            boolean enabled = true; // Default enabled for backward compat
            if (config.hasPath("dmAutoReply.enabled")) {
                enabled = config.getBoolean("dmAutoReply.enabled");
            } else if (config.hasPath("dmAutoReply")) {
                // Old config exists (just the string), assume enabled
                enabled = true;
            }
            
            if (!enabled) {
                System.out.println("[DiscordDMHandler] DM auto-reply disabled in config");
                return;
            }

            // Get reply message - NEW path first, fall back to OLD
            String reply = null;
            if (config.hasPath("dmAutoReply.message")) {
                reply = config.getString("dmAutoReply.message").trim();
            } else if (config.hasPath("dmAutoReply")) {
                reply = config.getString("dmAutoReply").trim();
            }
            
            if (reply == null || reply.isEmpty()) return;

            // Get JDA - reuse cached instance from GuildOnlineListPublisher if available,
            // otherwise find it by type via reflection
            JDA jda = GuildOnlineListPublisher.getJda();
            if (jda == null) {
                wowchat.discord.Discord discord = (wowchat.discord.Discord) Global$.MODULE$.discord();
                for (java.lang.reflect.Field field : discord.getClass().getDeclaredFields()) {
                    if (!JDA.class.isAssignableFrom(field.getType())) continue;
                    field.setAccessible(true);
                    Object value = field.get(discord);
                    if (value instanceof JDA) { jda = (JDA) value; break; }
                }
            }

            if (jda == null) {
                System.err.println("[DiscordDMHandler] Could not obtain JDA instance.");
                return;
            }

            jda.addEventListener(new DiscordDMHandler(reply));
            System.out.println("[DiscordDMHandler] DM auto-reply registered.");

        } catch (Throwable t) {
            System.err.println("[DiscordDMHandler] Failed to register: " + t.getMessage());
        }
    }
}
