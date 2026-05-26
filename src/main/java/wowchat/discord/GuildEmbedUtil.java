package wowchat.discord;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import scala.Option;
import wowchat.common.Global$;
import wowchat.game.GameCommandHandler;
import wowchat.game.GamePacketHandler;

import java.util.List;

/**
 * Shared utilities for guild embed management.
 * Centralizes common functionality used by multiple guild feature publishers.
 */
public final class GuildEmbedUtil {

    private GuildEmbedUtil() {} // Utility class - no instantiation
    
    // Cache the last known identifier to avoid "Unknown Guild" after reconnects
    private static String cachedIdentifier = null;

    /**
     * Get guild and realm identifier for embed footer.
     * Format: "GuildName (RealmName)"
     * 
     * Used to uniquely identify embeds when multiple bots are in same channel.
     * Each bot can identify its own embeds by matching this identifier in the footer.
     * 
     * @return Guild identifier string, or "Unknown Guild (Unknown Realm)" if unavailable
     */
    public static String getGuildRealmIdentifier() {
        try {
            Option<GameCommandHandler> gameOpt = Global$.MODULE$.game();
            if (gameOpt == null || gameOpt.isEmpty()) {
                return cachedIdentifier != null ? cachedIdentifier : "Unknown Guild (Unknown Realm)";
            }
            
            GameCommandHandler handler = gameOpt.get();
            if (!(handler instanceof GamePacketHandler)) {
                return cachedIdentifier != null ? cachedIdentifier : "Unknown Guild (Unknown Realm)";
            }
            
            GamePacketHandler gph = (GamePacketHandler) handler;
            
            // Search entire class hierarchy for guildInfo
            String guildName = "Unknown Guild";
            try {
                java.lang.reflect.Field guildInfoField = findFieldInHierarchy(gph.getClass(), "guildInfo");
                if (guildInfoField != null) {
                    guildInfoField.setAccessible(true);
                    Object guildInfoObj = guildInfoField.get(gph);
                    if (guildInfoObj != null) {
                        guildName = (String) guildInfoObj.getClass().getMethod("name").invoke(guildInfoObj);
                    }
                }
            } catch (Exception e) {
                // Guild info not available yet
            }
            
            // Search entire class hierarchy for realmName
            String realmName = "Unknown Realm";
            try {
                java.lang.reflect.Field realmField = findFieldInHierarchy(gph.getClass(), "realmName");
                if (realmField != null) {
                    realmField.setAccessible(true);
                    Object value = realmField.get(gph);
                    if (value instanceof String) {
                        realmName = (String) value;
                    }
                }
            } catch (Exception e) {
                // Realm name not available yet
            }
            
            String identifier = guildName + " (" + realmName + ")";
            
            // Only cache if we have real data (not Unknown)
            if (!guildName.equals("Unknown Guild") && !realmName.equals("Unknown Realm")) {
                cachedIdentifier = identifier;
            }
            
            // If we still have Unknown but have a cached value, use cached
            if ((guildName.equals("Unknown Guild") || realmName.equals("Unknown Realm")) && cachedIdentifier != null) {
                return cachedIdentifier;
            }
            
            return identifier;
            
        } catch (Throwable t) {
            return cachedIdentifier != null ? cachedIdentifier : "Unknown Guild (Unknown Realm)";
        }
    }
    
    /**
     * Search for a field by name in the entire class hierarchy
     */
    private static java.lang.reflect.Field findFieldInHierarchy(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /**
     * Find an existing embed message by matching title and footer identifier.
     * 
     * Searches the last 100 messages in the channel for a bot-posted embed where:
     * - The embed title matches the given title
     * - The embed footer starts with our guild/realm identifier
     * 
     * This allows multiple bots (different guilds/servers) to coexist in the same channel
     * without interfering with each other's embeds.
     * 
     * @param channel The Discord channel to search
     * @param title The embed title to match
     * @return Message ID if found, null otherwise
     */
    public static String findEmbedByTitleAndFooter(TextChannel channel, String title) {
        try {
            String ourIdentifier = getGuildRealmIdentifier();
            List<Message> history = channel.getHistory().retrievePast(100).complete();
            if (history == null) return null;
            
            for (Message msg : history) {
                User author = msg.getAuthor();
                if (author == null || !author.isBot()) continue;
                
                List<MessageEmbed> embeds = msg.getEmbeds();
                if (embeds != null && !embeds.isEmpty()) {
                    MessageEmbed embed = embeds.get(0);
                    
                    // Check if title matches
                    if (title != null && title.equals(embed.getTitle())) {
                        // Check if footer starts with our identifier
                        MessageEmbed.Footer footer = embed.getFooter();
                        if (footer != null && footer.getText() != null && footer.getText().startsWith(ourIdentifier)) {
                            return msg.getId();
                        }
                    }
                }
            }
        } catch (Throwable t) {
            System.err.println("[GuildEmbedUtil] Error searching message history: " + t.getMessage());
        }
        return null;
    }

    /**
     * Find an existing embed message by matching title pattern and footer identifier.
     * 
     * Similar to findEmbedByTitleAndFooter, but for paginated embeds where the title
     * contains page numbers or other dynamic content. Checks if the embed title
     * STARTS WITH the given titlePrefix.
     * 
     * Example: titlePrefix="Guild Roster" matches "Guild Roster (102) (1/2)"
     * 
     * @param channel The Discord channel to search
     * @param titlePrefix The prefix the embed title should start with
     * @return Message ID if found, null otherwise
     */
    public static String findEmbedByTitlePrefixAndFooter(TextChannel channel, String titlePrefix) {
        try {
            String ourIdentifier = getGuildRealmIdentifier();
            List<Message> history = channel.getHistory().retrievePast(100).complete();
            if (history == null) return null;
            
            for (Message msg : history) {
                User author = msg.getAuthor();
                if (author == null || !author.isBot()) continue;
                
                List<MessageEmbed> embeds = msg.getEmbeds();
                if (embeds != null && !embeds.isEmpty()) {
                    MessageEmbed embed = embeds.get(0);
                    
                    // Check if title starts with prefix
                    String embedTitle = embed.getTitle();
                    if (embedTitle != null && embedTitle.startsWith(titlePrefix)) {
                        // Check if footer starts with our identifier
                        MessageEmbed.Footer footer = embed.getFooter();
                        if (footer != null && footer.getText() != null && footer.getText().startsWith(ourIdentifier)) {
                            return msg.getId();
                        }
                    }
                }
            }
        } catch (Throwable t) {
            System.err.println("[GuildEmbedUtil] Error searching message history: " + t.getMessage());
        }
        return null;
    }
}
