/*
 * GuildRoleSync.java
 *
 * Automatically syncs Discord roles to guild members based on their in-game guild ranks.
 *
 * HOW IT WORKS:
 *   1. Groups all guild characters by Discord ID (from officer notes)
 *   2. For each Discord user, collects all their characters' guild ranks
 *   3. For each rank configured in guildRoleSync:
 *      - If user has ANY character with that rank → ADD Discord role
 *      - If user has NO characters with that rank → REMOVE Discord role
 *   4. Only touches roles defined in guildRoleSync config (other roles untouched)
 *   5. Handles multi-rank users (e.g., one char Trusted, one char Member → gets both roles)
 *
 * CONFIG (wowchat.conf):
 *   guildRoleSync = [
 *     { guildRank = "Trusted",        discordRoleId = "1475141936498086048" },
 *     { guildRank = "Discord Linked", discordRoleId = "1496228872033796287" },
 *     { guildRank = "Member",         discordRoleId = "1475142433988804628" }
 *   ]
 *
 *   Guild members should put their Discord user ID (numeric) in their officer note in-game.
 *   To get your Discord user ID: Enable Developer Mode in Discord settings, then right-click
 *   your username and select "Copy User ID".
 *
 * RANK NAMES:
 *   Rank names are matched case-insensitively against the rank names stored in GuildInfo,
 *   which are fetched via SMSG_GUILD_QUERY on login.
 *
 * EXAMPLE:
 *   User has: Char1 (Trusted), Char2 (Member)
 *   Result: Gets "Trusted" role + "Member" role, "Discord Linked" role removed if they had it
 */
package wowchat.discord;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import scala.collection.Iterator;
import wowchat.common.Global$;
import wowchat.game.GameCommandHandler;
import wowchat.game.GamePacketHandler;
import wowchat.game.GuildInfo;
import wowchat.game.GuildMember;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;

public final class GuildRoleSync {

    // -------------------------------------------------------------------------
    // Config: list of { guildRank -> discordRoleId } mappings
    // Key: rank name (lowercased), Value: Discord role ID string
    // -------------------------------------------------------------------------
    private static volatile Map<String, String> rankToRoleId = Collections.emptyMap();

    private static volatile boolean started = false;
    private static volatile JDA cachedJda = null;
    private static ScheduledExecutorService scheduler;

    private GuildRoleSync() {}

    // -------------------------------------------------------------------------
    // Public init — called once from WoWChat on Discord connect
    // -------------------------------------------------------------------------
    public static synchronized void init() {
        if (started) return;

        loadConfig();

        if (rankToRoleId.isEmpty()) {
            // No mappings configured — feature disabled
            return;
        }

        started = true;
        long periodSec = Math.max(1, GuildOnlineListPublisher.getUpdateMinutes()) * 60L;
        System.out.println("[GuildRoleSync] Initializing with " + rankToRoleId.size() + " rank mapping(s), interval: " + (periodSec / 60) + " min.");

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wowchat-role-sync");
            t.setDaemon(true);
            return t;
        });

        // Run once 10 seconds after startup, then on same interval as guild online list
        scheduler.scheduleAtFixedRate(() -> {
            try {
                tick();
            } catch (Throwable t) {
                System.err.println("[GuildRoleSync] Unexpected error in tick: " + t.getMessage());
            }
        }, 10L, periodSec, TimeUnit.SECONDS);
    }

    // -------------------------------------------------------------------------
    // Main sync tick
    // -------------------------------------------------------------------------
    private static void tick() {
        JDA jda = getJda();
        if (jda == null) { System.out.println("[GuildRoleSync] tick: JDA not available."); return; }

        GamePacketHandler handler = getHandler();
        if (handler == null) { System.out.println("[GuildRoleSync] tick: game handler not available yet."); return; }

        // Get rank name map from GuildInfo: rankIndex -> rankName
        Map<Integer, String> rankNames = getRankNames(handler);
        if (rankNames.isEmpty()) {
            System.out.println("[GuildRoleSync] tick: no rank names available yet, skipping.");
            return;
        }

        // Get the first Discord guild the bot is in
        List<Guild> guilds = jda.getGuilds();
        if (guilds.isEmpty()) {
            System.err.println("[GuildRoleSync] Bot is not in any Discord guild.");
            return;
        }
        Guild discordGuild = guilds.get(0);

        // Group guild characters by Discord ID and collect their ranks
        // Map: Discord ID -> Set of rank names (lowercase)
        Map<String, Set<String>> discordIdToRanks = new HashMap<>();
        
        scala.collection.Map<Object, GuildMember> roster = handler.guildRoster();
        Iterator<GuildMember> it = roster.valuesIterator();
        while (it.hasNext()) {
            GuildMember member = it.next();

            String officerNote = member.officerNote().trim();
            if (officerNote.isEmpty()) continue;

            // Officer note should be a numeric Discord user ID
            if (!officerNote.matches("\\d{17,20}")) continue;

            String rankName = rankNames.getOrDefault(member.rankIndex(), "").toLowerCase(Locale.ROOT);
            if (rankName.isEmpty()) continue;

            discordIdToRanks
                .computeIfAbsent(officerNote, k -> new HashSet<>())
                .add(rankName);
        }

        int processed = 0, added = 0, removed = 0, skipped = 0;

        // Now sync roles for each Discord user
        for (Map.Entry<String, Set<String>> entry : discordIdToRanks.entrySet()) {
            String discordId = entry.getKey();
            Set<String> userRanks = entry.getValue(); // Ranks this user has in guild

            try {
                Member discordMember = discordGuild.retrieveMemberById(discordId).complete();
                if (discordMember == null) continue;

                processed++;

                // For each configured rank mapping
                for (Map.Entry<String, String> mapping : rankToRoleId.entrySet()) {
                    String configuredRank = mapping.getKey(); // lowercase rank name
                    String roleId = mapping.getValue();

                    Role role = discordGuild.getRoleById(roleId);
                    if (role == null) {
                        System.err.println("[GuildRoleSync] Role ID " + roleId + " not found in Discord guild.");
                        continue;
                    }

                    boolean hasRole = discordMember.getRoles().contains(role);
                    boolean shouldHaveRole = userRanks.contains(configuredRank);

                    if (shouldHaveRole && !hasRole) {
                        // ADD role
                        discordGuild.addRoleToMember(discordMember, role).complete();
                        System.out.println("[GuildRoleSync] ADDED role '" + role.getName() + "' to " + discordMember.getEffectiveName());
                        added++;
                    } else if (!shouldHaveRole && hasRole) {
                        // REMOVE role
                        discordGuild.removeRoleFromMember(discordMember, role).complete();
                        System.out.println("[GuildRoleSync] REMOVED role '" + role.getName() + "' from " + discordMember.getEffectiveName());
                        removed++;
                    } else {
                        // No change needed
                        skipped++;
                    }
                }

            } catch (Throwable t) {
                System.err.println("[GuildRoleSync] Error syncing roles for Discord ID " + discordId + ": " + t.getMessage());
            }
        }

        if (added > 0 || removed > 0) {
            System.out.println("[GuildRoleSync] Sync complete. Users: " + processed 
                + ", Roles added: " + added + ", removed: " + removed);
        }
    }

    // -------------------------------------------------------------------------
    // Get rank index -> rank name map from GuildInfo
    // GuildInfo stores a Scala Map[Int, String] of rank index -> rank name
    // -------------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    private static Map<Integer, String> getRankNames(GamePacketHandler handler) {
        try {
            wowchat.game.GuildInfo guildInfo = handler.guildInfo();
            if (guildInfo == null) return Collections.emptyMap();

            scala.collection.Map<Object, String> ranks =
                (scala.collection.Map<Object, String>) guildInfo.ranks();

            Map<Integer, String> result = new HashMap<>();
            Iterator<scala.Tuple2<Object, String>> rit = ranks.iterator();
            while (rit.hasNext()) {
                scala.Tuple2<Object, String> entry = rit.next();
                result.put((Integer) entry._1(), entry._2());
            }
            return result;
        } catch (Throwable t) {
            System.err.println("[GuildRoleSync] Could not read rank names: " + t.getMessage());
            return Collections.emptyMap();
        }
    }

    // -------------------------------------------------------------------------
    // Config loading
    // -------------------------------------------------------------------------
    private static void loadConfig() {
        try {
            Config config = ConfigFactory.load(ConfigFactory.parseFile(
                new java.io.File(System.getProperty("config.file", "wowchat.conf"))));

            // Check if guild role sync is enabled (NEW path with fallback to OLD behavior)
            boolean enabled = true; // Default enabled for backward compat
            if (config.hasPath("guildRoleSync.enabled")) {
                enabled = config.getBoolean("guildRoleSync.enabled");
            } else if (config.hasPath("guildRoleSync")) {
                // Old config exists (just the array), assume enabled
                enabled = true;
            }
            
            if (!enabled) {
                System.out.println("[GuildRoleSync] Feature disabled in config");
                rankToRoleId = Collections.emptyMap();
                return;
            }

            // Try NEW path first, fall back to OLD
            String configPath = "guildRoleSync.ranks";
            if (!config.hasPath(configPath) && config.hasPath("guildRoleSync")) {
                // Check if old format is a list (backward compat)
                try {
                    config.getConfigList("guildRoleSync");
                    configPath = "guildRoleSync";
                } catch (ConfigException.WrongType e) {
                    // New format but no ranks defined
                    rankToRoleId = Collections.emptyMap();
                    return;
                }
            }
            
            if (!config.hasPath(configPath)) {
                rankToRoleId = Collections.emptyMap();
                return;
            }

            Map<String, String> map = new LinkedHashMap<>();
            for (Config entry : config.getConfigList(configPath)) {
                String rankName   = entry.getString("guildRank").toLowerCase(Locale.ROOT).trim();
                String discordRoleId = entry.getString("discordRoleId").trim();
                if (!rankName.isEmpty() && !discordRoleId.isEmpty()) {
                    map.put(rankName, discordRoleId);
                }
            }
            rankToRoleId = Collections.unmodifiableMap(map);
        } catch (ConfigException.Missing ignored) {
            // guildRoleSync not configured — fine
        } catch (Throwable t) {
            System.err.println("[GuildRoleSync] Config error: " + t.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
    private static GamePacketHandler getHandler() {
        try {
            scala.Option<GameCommandHandler> gameOpt = Global$.MODULE$.game();
            if (gameOpt == null || gameOpt.isEmpty()) return null;
            GameCommandHandler handler = gameOpt.get();
            if (!(handler instanceof GamePacketHandler)) return null;
            return (GamePacketHandler) handler;
        } catch (Throwable t) {
            return null;
        }
    }

    private static JDA getJda() {
        if (cachedJda != null) return cachedJda;
        try {
            wowchat.discord.Discord discord = Global$.MODULE$.discord();
            if (discord == null) return null;
            for (Field field : discord.getClass().getDeclaredFields()) {
                if (!JDA.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                Object value = field.get(discord);
                if (value instanceof JDA) {
                    cachedJda = (JDA) value;
                    System.out.println("[GuildRoleSync] JDA instance cached.");
                    return cachedJda;
                }
            }
        } catch (Throwable t) {
            System.err.println("[GuildRoleSync] Could not get JDA: " + t.getMessage());
        }
        return null;
    }
}
