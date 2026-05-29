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
 * Each feature has its own enabled flag - no master switch.
 * Roster polling happens regardless of feature configuration.
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
    // COMMAND ROLE IDs - Controls who can use privileged slash commands
    // (/profession add/remove, /ignore, /unignore, /ban, /unban)
    // =========================================================================

    public static List<String> getCommandRoleIds() {
        try {
            Config config = getConfig();
            if (!config.hasPath("discord.commandRoleIds")) return Collections.emptyList();
            return config.getStringList("discord.commandRoleIds");
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    // =========================================================================
    // NOTE LOCATION - Where to find Discord IDs in guild roster
    // =========================================================================

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
    // GUILD ROSTER CHANNELS - channelIds
    // =========================================================================

    public static List<Long> getGuildRosterChannelIds() {
        try {
            Config config = getConfig();
            // Support both channelIds (list) and channelId (single, legacy)
            if (config.hasPath("guildDiscordLinking.guildRoster.channelIds")) {
                List<Long> ids = new java.util.ArrayList<>();
                for (String s : config.getStringList("guildDiscordLinking.guildRoster.channelIds")) {
                    try { ids.add(Long.parseLong(s.trim())); } catch (NumberFormatException ignored) {}
                }
                return ids;
            } else if (config.hasPath("guildDiscordLinking.guildRoster.channelId")) {
                try {
                    return Collections.singletonList(
                        Long.parseLong(config.getString("guildDiscordLinking.guildRoster.channelId").trim()));
                } catch (NumberFormatException ignored) {}
            }
            return Collections.emptyList();
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    // =========================================================================
    // AUDIT PANEL - top-level enable
    // =========================================================================

    public static boolean isAuditEnabled() {
        try {
            Config config = getConfig();
            if (config.hasPath("guildDiscordLinking.guildRoster.audit.enabled")) {
                return config.getBoolean("guildDiscordLinking.guildRoster.audit.enabled");
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    // =========================================================================
    // AUDIT SUB-MODULE: Unlinked Members
    // =========================================================================

    public static boolean isAuditUnlinkedEnabled() {
        try {
            Config config = getConfig();
            if (config.hasPath("guildDiscordLinking.guildRoster.audit.unlinked.enabled")) {
                return config.getBoolean("guildDiscordLinking.guildRoster.audit.unlinked.enabled");
            }
            return true; // default on when parent audit is enabled
        } catch (Throwable t) {
            return true;
        }
    }

    public static List<String> getAuditRoleIds() {
        try {
            Config config = getConfig();
            if (config.hasPath("guildDiscordLinking.guildRoster.audit.unlinked.roleIds")) {
                return config.getStringList("guildDiscordLinking.guildRoster.audit.unlinked.roleIds");
            }
            return Collections.emptyList();
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    // =========================================================================
    // AUDIT SUB-MODULE: Linked Rank Promotion
    // =========================================================================

    public static boolean isAuditLinkedRankPromotionEnabled() {
        try {
            Config config = getConfig();
            if (config.hasPath("guildDiscordLinking.guildRoster.audit.linkedRankPromotion.enabled")) {
                return config.getBoolean("guildDiscordLinking.guildRoster.audit.linkedRankPromotion.enabled");
            }
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    public static List<String> getAuditLinkedRanksToCheck() {
        try {
            Config config = getConfig();
            if (config.hasPath("guildDiscordLinking.guildRoster.audit.linkedRankPromotion.ranksToCheck")) {
                return config.getStringList("guildDiscordLinking.guildRoster.audit.linkedRankPromotion.ranksToCheck");
            }
            return Collections.emptyList();
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    // =========================================================================
    // AUDIT SUB-MODULE: Rank Mismatch
    // =========================================================================

    public static boolean isAuditRankMismatchEnabled() {
        try {
            Config config = getConfig();
            if (config.hasPath("guildDiscordLinking.guildRoster.audit.rankMismatch.enabled")) {
                return config.getBoolean("guildDiscordLinking.guildRoster.audit.rankMismatch.enabled");
            }
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    // =========================================================================
    // AUDIT SUB-MODULE: Promotion Due
    // =========================================================================

    public static boolean isAuditPromotionDueEnabled() {
        try {
            Config config = getConfig();
            if (config.hasPath("guildDiscordLinking.guildRoster.audit.promotionDue.enabled")) {
                return config.getBoolean("guildDiscordLinking.guildRoster.audit.promotionDue.enabled");
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Returns the list of rank-check configs for the promotionDue sub-module.
     * Each Config object has keys: rank (String) and daysRequired (Int).
     */
    public static List<? extends com.typesafe.config.Config> getAuditPromotionDueRanks() {
        try {
            Config config = getConfig();
            if (config.hasPath("guildDiscordLinking.guildRoster.audit.promotionDue.ranks")) {
                return config.getConfigList("guildDiscordLinking.guildRoster.audit.promotionDue.ranks");
            }
            return Collections.emptyList();
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    // =========================================================================
    // STATS PANEL
    // =========================================================================

    public static boolean isStatsEnabled() {
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

    /**
     * Check if ANY of the guild roster features (audit, stats, or roster) are enabled.
     * Used by GuildRosterPublisher to determine if it should initialize at all.
     */
    public static boolean isGuildRosterEnabled() {
        return isAuditEnabled() || isStatsEnabled() || isRosterPanelEnabled();
    }

    /**
     * Check if the roster panel specifically is enabled.
     */
    public static boolean isRosterPanelEnabled() {
        try {
            Config config = getConfig();
            if (config.hasPath("guildDiscordLinking.guildRoster.roster.enabled")) {
                return config.getBoolean("guildDiscordLinking.guildRoster.roster.enabled");
            }
            return false; // Default disabled
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isRosterEnabled() {
        return isGuildRosterEnabled();
    }
    
    /**
     * Check if unlinked characters should be shown in the roster panel.
     * Defaults to true if not specified.
     */
    public static boolean isShowUnlinkedCharactersEnabled() {
        try {
            Config config = getConfig();
            if (config.hasPath("guildDiscordLinking.guildRoster.roster.showUnlinkedCharacters")) {
                return config.getBoolean("guildDiscordLinking.guildRoster.roster.showUnlinkedCharacters");
            }
            return true; // Default enabled
        } catch (Throwable t) {
            return true;
        }
    }

    // =========================================================================
    // INACTIVITY TRACKING
    // =========================================================================

    public static boolean isInactivityEnabled() {
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
