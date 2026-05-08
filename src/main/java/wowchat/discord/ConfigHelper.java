package wowchat.discord;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * ConfigHelper - Centralized config reading for guild features.
 * 
 * All guild feature configuration goes through this utility to avoid
 * duplicating config reading logic across multiple files.
 * 
 * NO BACKWARD COMPATIBILITY - reads only the new nested structure.
 */
public final class ConfigHelper {

    private ConfigHelper() {} // Utility class

    /**
     * Get the loaded config with proper resolution
     */
    public static Config getConfig() {
        try {
            String configFile = System.getProperty("wowchat.configFile", "wowchat.conf");
            return ConfigFactory.parseFile(new File(configFile))
                .resolve(com.typesafe.config.ConfigResolveOptions.defaults().setAllowUnresolved(true));
        } catch (Throwable t) {
            System.err.println("[ConfigHelper] Error loading config: " + t.getMessage());
            return ConfigFactory.empty();
        }
    }

    // =========================================================================
    // GUILD DISCORD LINKING - Master switch
    // =========================================================================

    public static boolean isDiscordLinkingEnabled() {
        try {
            Config config = getConfig();
            return config.hasPath("guildDiscordLinking.enabled") 
                && config.getBoolean("guildDiscordLinking.enabled");
        } catch (Throwable t) {
            return false;
        }
    }

    public static String getDiscordLinkingNoteLocation() {
        try {
            Config config = getConfig();
            if (config.hasPath("guildDiscordLinking.noteLocation")) {
                return config.getString("guildDiscordLinking.noteLocation").toLowerCase();
            }
            return "officer"; // Default
        } catch (Throwable t) {
            return "officer";
        }
    }

    // =========================================================================
    // GUILD ROSTER CHANNEL - channelId
    // =========================================================================

    public static String getGuildRosterChannelId() {
        try {
            Config config = getConfig();
            if (config.hasPath("guildDiscordLinking.guildRoster.channelId")) {
                return config.getString("guildDiscordLinking.guildRoster.channelId");
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    // =========================================================================
    // AUDIT PANEL
    // =========================================================================

    public static boolean isAuditEnabled() {
        if (!isDiscordLinkingEnabled()) return false;
        
        try {
            Config config = getConfig();
            if (config.hasPath("guildDiscordLinking.guildRoster.audit.enabled")) {
                return config.getBoolean("guildDiscordLinking.guildRoster.audit.enabled");
            }
            return true; // Default enabled if Discord linking is on
        } catch (Throwable t) {
            return true;
        }
    }

    public static List<String> getAuditRoleIds() {
        try {
            Config config = getConfig();
            if (config.hasPath("guildDiscordLinking.guildRoster.audit.roleIds")) {
                return config.getStringList("guildDiscordLinking.guildRoster.audit.roleIds");
            }
            return Collections.emptyList();
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    public static String getAuditLinkedRankToCheck() {
        try {
            Config config = getConfig();
            if (config.hasPath("guildDiscordLinking.guildRoster.audit.linkedRankToCheck")) {
                return config.getString("guildDiscordLinking.guildRoster.audit.linkedRankToCheck");
            }
            return "";
        } catch (Throwable t) {
            return "";
        }
    }

    // =========================================================================
    // STATS PANEL
    // =========================================================================

    public static boolean isStatsEnabled() {
        if (!isDiscordLinkingEnabled()) return false;
        
        try {
            Config config = getConfig();
            if (config.hasPath("guildDiscordLinking.guildRoster.stats.enabled")) {
                return config.getBoolean("guildDiscordLinking.guildRoster.stats.enabled");
            }
            return false; // Default disabled
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isStatsBlockEnabled(String blockName) {
        try {
            Config config = getConfig();
            String path = "guildDiscordLinking.guildRoster.stats." + blockName;
            if (config.hasPath(path)) {
                return config.getBoolean(path);
            }
            return true; // Default to showing if not specified
        } catch (Throwable t) {
            return true;
        }
    }

    // =========================================================================
    // ROSTER PANEL
    // =========================================================================

    public static boolean isGuildRosterEnabled() {
        if (!isDiscordLinkingEnabled()) return false;
        
        try {
            Config config = getConfig();
            if (config.hasPath("guildDiscordLinking.guildRoster.roster.enabled")) {
                return config.getBoolean("guildDiscordLinking.guildRoster.roster.enabled");
            }
            return true; // Default enabled if Discord linking is on
        } catch (Throwable t) {
            return true;
        }
    }

    public static boolean isRosterEnabled() {
        return isGuildRosterEnabled();
    }

    // =========================================================================
    // INACTIVITY TRACKING
    // =========================================================================

    public static boolean isInactivityEnabled() {
        if (!isDiscordLinkingEnabled()) return false;
        
        try {
            Config config = getConfig();
            if (config.hasPath("guildDiscordLinking.inactivity.enabled")) {
                return config.getBoolean("guildDiscordLinking.inactivity.enabled");
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    public static int getInactivityDays() {
        try {
            Config config = getConfig();
            if (config.hasPath("guildDiscordLinking.inactivity.inactiveDays")) {
                return config.getInt("guildDiscordLinking.inactivity.inactiveDays");
            }
            return 30; // Default
        } catch (Throwable t) {
            return 30;
        }
    }

    public static String getInactivityRoleId() {
        try {
            Config config = getConfig();
            if (config.hasPath("guildDiscordLinking.inactivity.inactiveRoleId")) {
                return config.getString("guildDiscordLinking.inactivity.inactiveRoleId");
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    // =========================================================================
    // ROLE SYNC
    // =========================================================================

    public static boolean isRoleSyncEnabled() {
        if (!isDiscordLinkingEnabled()) return false;
        
        try {
            Config config = getConfig();
            if (config.hasPath("guildDiscordLinking.roleSync.enabled")) {
                return config.getBoolean("guildDiscordLinking.roleSync.enabled");
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    public static String getRoleSyncConfigPath() {
        try {
            Config config = getConfig();
            if (config.hasPath("guildDiscordLinking.roleSync.ranks")) {
                return "guildDiscordLinking.roleSync.ranks";
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }
}
