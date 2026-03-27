package wowchat.discord;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigResolveOptions;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import scala.Option;
import wowchat.common.Global$;
import wowchat.game.GameCommandHandler;
import wowchat.game.GamePacketHandler;
import wowchat.game.GuildMember;
import wowchat.game.Player;

import java.io.File;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * GuildDiscordAuditPublisher
 *
 * Posts and continuously updates a single Discord message with two audit panels:
 *
 *   Panel 1 - WoW members missing a Discord link
 *     Guild members whose officer note is empty, not a valid snowflake ID,
 *     or doesn't match any member in the Discord server.
 *     Sorted alphabetically by character name.
 *     Respects guildOnlineListIgnore (bot character excluded).
 *
 *   Panel 2 - Discord members missing a WoW link
 *     Discord members who have one of the configured audit roles but whose
 *     user ID is not found in any guild member's officer note.
 *     Grouped by role (in config order), alphabetically within each group.
 *
 * Config keys:
 *   guildAuditChannelId  - Discord channel ID to post the message in (0 = disabled)
 *   guildAuditRoleIds    - list of Discord role IDs to check for Panel 2
 *
 * Update interval: reuses discordFeaturesUpdateMinutes.
 * Bot exclusion:   reuses guildOnlineListIgnore.
 */
public final class GuildDiscordAuditPublisher {

    private static final String MARKER = "\u200b\u200b\u200b\u200b\u200b\u200b\u200b\u200b\u200b\u200b\u200b";

    // Config
    private static volatile long         channelId     = 0L;
    private static volatile int          updateMinutes = 5;
    private static volatile Set<String>  ignoreLower   = Collections.emptySet();
    private static volatile List<String> auditRoleIds  = Collections.emptyList();

    // Runtime state
    private static volatile boolean started     = false;
    private static volatile String  messageId   = null;
    private static volatile String  lastPayload = null;

    private GuildDiscordAuditPublisher() {}

    // -------------------------------------------------------------------------
    // Init - called once from WoWChat.main(), after Discord connects
    // -------------------------------------------------------------------------

    public static synchronized void init() {
        if (started) return;
        started = true;

        loadConfig();

        if (channelId == 0L) return; // Disabled - no channel configured

        System.out.println("[GuildAudit] Initializing. Channel ID: " + channelId
            + ", update interval: " + updateMinutes + " min.");

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            new GuildOnlineListPublisher.DaemonThreadFactory("wowchat-guild-audit"));

        long periodSec = Math.max(1, updateMinutes) * 60L;

        scheduler.scheduleAtFixedRate(() -> {
            try {
                tick();
            } catch (Throwable t) {
                System.err.println("[GuildAudit] Unexpected error in update tick: " + t.getMessage());
            }
        }, 20L, periodSec, TimeUnit.SECONDS);
    }

    // -------------------------------------------------------------------------
    // Main update tick - builds the message and posts/edits it
    // -------------------------------------------------------------------------

    private static void tick() {
        JDA jda = GuildOnlineListPublisher.getJda();
        if (jda == null) return;

        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel == null) {
            System.err.println("[GuildAudit] Could not find text channel with ID " + channelId + ".");
            return;
        }

        // Get guild member roster from WoW
        Map<String, String> rosterNotes = getGuildRosterNotes(); // charName -> officerNote
        if (rosterNotes == null) return; // Game not ready yet

        // Get Discord guild
        List<Guild> guilds = jda.getGuilds();
        if (guilds.isEmpty()) return;
        Guild discordGuild = guilds.get(0);

        // Build set of all officer note IDs that are valid Discord snowflakes
        Set<String> linkedDiscordIds = new HashSet<>();
        for (String note : rosterNotes.values()) {
            String id = extractDiscordId(note);
            if (id != null) linkedDiscordIds.add(id);
        }

        // --- Panel 1: WoW members missing a Discord link ---
        List<String> unlinkdWoW = new ArrayList<>();
        for (Map.Entry<String, String> entry : rosterNotes.entrySet()) {
            String charName = entry.getKey();
            String note     = entry.getValue();
            if (ignoreLower.contains(charName.toLowerCase(Locale.ROOT))) continue;
            String id = extractDiscordId(note);
            if (id == null) {
                // No valid ID in officer note
                unlinkdWoW.add(charName);
            } else {
                // Valid ID but user not in Discord server
                Member m = discordGuild.getMemberById(id);
                if (m == null) unlinkdWoW.add(charName);
            }
        }
        Collections.sort(unlinkdWoW, String.CASE_INSENSITIVE_ORDER);

        // --- Panel 2: Discord members missing a WoW link, grouped by role ---
        // Build map of roleId -> list of member display names not in roster
        StringBuilder panel2 = new StringBuilder();
        for (String roleId : auditRoleIds) {
            Role role = discordGuild.getRoleById(roleId);
            if (role == null) continue;

            List<Member> membersWithRole = discordGuild.getMembersWithRoles(role);
            List<String> unlinkedDiscord = new ArrayList<>();
            for (Member member : membersWithRole) {
                if (member.getUser().isBot()) continue;
                String uid = member.getUser().getId();
                if (!linkedDiscordIds.contains(uid)) {
                    unlinkedDiscord.add(member.getEffectiveName());
                }
            }
            Collections.sort(unlinkedDiscord, String.CASE_INSENSITIVE_ORDER);

            if (panel2.length() > 0) panel2.append("\n");
            panel2.append("**").append(role.getName()).append("**");
            if (unlinkedDiscord.isEmpty()) {
                panel2.append("\nAll linked.");
            } else {
                for (String name : unlinkedDiscord) {
                    panel2.append("\n- ").append(name);
                }
            }
        }

        // Compose full message
        StringBuilder sb = new StringBuilder();
        sb.append("## Guild Sync Audit\n");

        sb.append("### [WoW] Guild Members that aren't in the Discord");
        sb.append(" (").append(unlinkdWoW.size()).append(")");
        sb.append("\n");
        if (unlinkdWoW.isEmpty()) {
            sb.append("All guild members have a linked Discord ID.\n");
        } else {
            for (String name : unlinkdWoW) {
                sb.append("- ").append(name).append("\n");
            }
        }

        sb.append("### [Discord] Members that aren't in the Guild\n");
        if (auditRoleIds.isEmpty()) {
            sb.append("No audit roles configured (guildAuditRoleIds).");
        } else if (panel2.length() == 0) {
            sb.append("None of the configured roles were found in this Discord server.");
        } else {
            sb.append(panel2);
        }

        // Timestamp
        sb.append("\n\n*Last updated: <t:")
          .append(System.currentTimeMillis() / 1000L)
          .append(":R>*");

        String payload = sb.toString();

        // Find existing message if needed
        if (messageId == null) {
            messageId = findExistingMessageId(channel);
        }

        // Always edit to refresh the timestamp, but skip if content unchanged and message exists
        String fullContent = payload + MARKER;

        if (messageId == null) {
            try {
                Message sent = channel.sendMessage(fullContent).complete();
                messageId   = sent.getId();
                lastPayload = payload;
            } catch (Throwable t) {
                System.err.println("[GuildAudit] Failed to send message: " + t.getMessage());
            }
        } else {
            try {
                channel.editMessageById(messageId, fullContent).complete();
                lastPayload = payload;
            } catch (Throwable t) {
                System.err.println("[GuildAudit] Failed to edit message (will retry): " + t.getMessage());
                messageId   = null;
                lastPayload = null;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Get guild roster: charName -> raw officer note
    // -------------------------------------------------------------------------

    private static Map<String, String> getGuildRosterNotes() {
        try {
            Option<GameCommandHandler> gameOpt = Global$.MODULE$.game();
            if (gameOpt == null || gameOpt.isEmpty()) return null;
            GameCommandHandler handler = gameOpt.get();
            if (!(handler instanceof GamePacketHandler)) return null;

            GamePacketHandler gph = (GamePacketHandler) handler;
            scala.collection.Map<Object, GuildMember> roster = gph.guildRoster();
            if (roster == null || roster.isEmpty()) return null;

            Map<String, String> result = new LinkedHashMap<>();
            scala.collection.Iterator<GuildMember> it = roster.valuesIterator();
            while (it.hasNext()) {
                GuildMember p = it.next();
                String name = p.name();
                String note = p.officerNote() != null ? p.officerNote().trim() : "";
                result.put(name, note);
            }
            return result;
        } catch (Throwable t) {
            System.err.println("[GuildAudit] Error reading guild roster: " + t.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Extract a valid Discord snowflake ID from an officer note string.
    // Returns null if the note doesn't contain a valid ID.
    // A Discord snowflake is a 17-19 digit numeric string.
    // -------------------------------------------------------------------------

    private static String extractDiscordId(String note) {
        if (note == null || note.isEmpty()) return null;
        String trimmed = note.trim();
        if (trimmed.matches("\\d{17,19}")) return trimmed;
        return null;
    }

    // -------------------------------------------------------------------------
    // Find our previously posted message in channel history by marker
    // -------------------------------------------------------------------------

    private static String findExistingMessageId(TextChannel channel) {
        try {
            List<Message> history = channel.getHistory().retrievePast(100).complete();
            if (history == null) return null;
            for (Message msg : history) {
                User author = msg.getAuthor();
                if (author == null || !author.isBot()) continue;
                String content = msg.getContentRaw();
                if (content != null && content.endsWith(MARKER)) {
                    return msg.getId();
                }
            }
        } catch (Throwable t) {
            System.err.println("[GuildAudit] Error searching message history: " + t.getMessage());
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Config loading
    // -------------------------------------------------------------------------

    private static void loadConfig() {
        try {
            String configFile = System.getProperty("wowchat.configFile", "wowchat.conf");
            Config config = ConfigFactory.parseFile(new File(configFile))
                .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true));

            // Channel ID
            channelId = 0L;
            try {
                channelId = config.getLong("guildAuditChannelId");
            } catch (ConfigException.WrongType e) {
                try {
                    channelId = Long.parseLong(config.getString("guildAuditChannelId").trim());
                } catch (Throwable ignored) {}
            } catch (ConfigException.Missing ignored) {}

            // Update interval - shared with GuildOnlineList
            updateMinutes = 5;
            try {
                if (config.hasPath("discordFeaturesUpdateMinutes")) {
                    updateMinutes = config.getInt("discordFeaturesUpdateMinutes");
                }
                if (updateMinutes < 1) updateMinutes = 1;
            } catch (ConfigException ignored) {}

            // Ignore list - shared with GuildOnlineList
            Set<String> ignoreSet = new HashSet<>();
            try {
                if (config.hasPath("guildOnlineListIgnore")) {
                    for (String name : config.getStringList("guildOnlineListIgnore")) {
                        if (name != null && !name.trim().isEmpty()) {
                            ignoreSet.add(name.trim().toLowerCase(Locale.ROOT));
                        }
                    }
                }
            } catch (Throwable ignored) {}
            ignoreLower = ignoreSet.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(ignoreSet);

            // Audit role IDs
            List<String> roleList = new ArrayList<>();
            try {
                if (config.hasPath("guildAuditRoleIds")) {
                    for (String id : config.getStringList("guildAuditRoleIds")) {
                        if (id != null && !id.trim().isEmpty()) roleList.add(id.trim());
                    }
                }
            } catch (Throwable ignored) {}
            auditRoleIds = roleList.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(roleList);

        } catch (Throwable t) {
            System.err.println("[GuildAudit] Failed to load config: " + t.getMessage());
            channelId = 0L;
        }
    }
}
