/*
 * GuildRoleSync.java
 *
 * Automatically assigns Discord roles to guild members based on their in-game guild rank.
 *
 * HOW IT WORKS:
 *   1. When SMSG_GUILD_ROSTER is received, GuildMember now stores rankIndex and officerNote.
 *   2. Each tick, we iterate the guild roster via GamePacketHandler.guildRoster().
 *   3. For each member, we read their officerNote as a Discord user ID.
 *   4. We look up the Discord user by that ID and assign any roles mapped to their rankIndex.
 *   5. Roles are only ever granted, never removed.
 *   6. If the user already has the role, we skip the API call entirely.
 *
 * CONFIG (wowchat.conf):
 *   guildRoleSync = [
 *     { guildRank = "Member",  discordRoleId = "123456789012345678" },
 *     { guildRank = "Officer", discordRoleId = "987654321098765432" }
 *   ]
 *
 *   Guild members should put their Discord user ID (numeric) in their officer note in-game.
 *   To get your Discord user ID: Enable Developer Mode in Discord settings, then right-click
 *   your username and select "Copy User ID".
 *
 * RANK NAMES:
 *   Rank names are matched case-insensitively against the rank names stored in GuildInfo,
 *   which are fetched via SMSG_GUILD_QUERY on login.
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
        System.out.println("[GuildRoleSync] Initializing with " + rankToRoleId.size() + " rank mapping(s).");

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wowchat-role-sync");
            t.setDaemon(true);
            return t;
        });

        // Run once 30 seconds after startup (give roster time to load), then every 5 minutes
        scheduler.scheduleAtFixedRate(() -> {
            try {
                tick();
            } catch (Throwable t) {
                System.err.println("[GuildRoleSync] Unexpected error in tick: " + t.getMessage());
            }
        }, 30L, 300L, TimeUnit.SECONDS);
    }

    // -------------------------------------------------------------------------
    // Main sync tick
    // -------------------------------------------------------------------------
    private static void tick() {
        JDA jda = getJda();
        if (jda == null) return;

        GamePacketHandler handler = getHandler();
        if (handler == null) return;

        // Get rank name map from GuildInfo: rankIndex -> rankName
        Map<Integer, String> rankNames = getRankNames(handler);
        if (rankNames.isEmpty()) {
            System.out.println("[GuildRoleSync] No rank names available yet, skipping tick.");
            return;
        }

        // Get the first Discord guild the bot is in
        List<Guild> guilds = jda.getGuilds();
        if (guilds.isEmpty()) {
            System.err.println("[GuildRoleSync] Bot is not in any Discord guild.");
            return;
        }
        Guild discordGuild = guilds.get(0);

        int processed = 0, assigned = 0, skipped = 0;

        // Iterate guild roster
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

            String roleId = rankToRoleId.get(rankName);
            if (roleId == null) continue;

            processed++;

            try {
                Member discordMember = discordGuild.retrieveMemberById(officerNote).complete();
                if (discordMember == null) continue;

                Role role = discordGuild.getRoleById(roleId);
                if (role == null) {
                    System.err.println("[GuildRoleSync] Role ID " + roleId + " not found in Discord guild.");
                    continue;
                }

                // Already has this role — skip
                if (discordMember.getRoles().contains(role)) {
                    skipped++;
                    continue;
                }

                // Grant the role
                discordGuild.addRoleToMember(discordMember, role).complete();
                System.out.println("[GuildRoleSync] Assigned role '" + role.getName() + "' to "
                    + discordMember.getUser().getName() + " (WoW: " + member.name() + ", rank: " + rankNames.get(member.rankIndex()) + ")");
                assigned++;

            } catch (Throwable t) {
                System.err.println("[GuildRoleSync] Failed to process member " + member.name()
                    + " (Discord ID: " + officerNote + "): " + t.getMessage());
            }
        }

        if (processed > 0) {
            System.out.println("[GuildRoleSync] Tick complete: " + processed + " matched, "
                + assigned + " role(s) assigned, " + skipped + " already had role.");
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

            if (!config.hasPath("guildRoleSync")) return;

            Map<String, String> map = new LinkedHashMap<>();
            for (Config entry : config.getConfigList("guildRoleSync")) {
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
