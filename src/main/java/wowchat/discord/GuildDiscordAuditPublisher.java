package wowchat.discord;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigResolveOptions;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import scala.Option;
import wowchat.common.Global$;
import wowchat.game.GameCommandHandler;
import wowchat.game.GamePacketHandler;
import wowchat.game.GuildMember;
import wowchat.game.Player;

import java.awt.Color;
import java.io.File;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * GuildDiscordAuditPublisher
 *
 * Posts and continuously updates two Discord messages in the same channel:
 *
 *   Message 1 - Audit:
 *     Panel 1: WoW members without a linked Discord account
 *     Panel 2: Discord members (by role) without a linked WoW character
 *
 *   Message 2 - Guild Roster (embed):
 *     All guild members grouped by Discord user (via officer note).
 *     Unlinked characters listed in a separate section at the bottom.
 *
 * Config keys:
 *   guildAuditChannelId  - Discord channel ID
 *   guildAuditRoleIds    - list of Discord role IDs for Panel 2
 */
public final class GuildDiscordAuditPublisher {

    // Separate markers so we can distinguish the two messages in channel history
    private static final String AUDIT_MARKER  = "\u200b\u200b\u200b\u200b\u200b\u200b\u200b\u200b\u200b\u200b\u200b";
    private static final String ROSTER_MARKER = "\u200b\u200b\u200b\u200b\u200b\u200b\u200b\u200b\u200b\u200b\u200b\u200b";

    // Config
    private static volatile long         channelId     = 0L;
    private static volatile int          updateMinutes = 5;
    private static volatile Set<String>  ignoreLower   = Collections.emptySet();
    private static volatile List<String> auditRoleIds  = Collections.emptyList();

    // Runtime state - audit message
    private static volatile boolean started        = false;
    private static volatile String  auditMessageId = null;
    // Runtime state - roster messages (one per page)
    private static final List<String> rosterMessageIds = new ArrayList<>();

    private GuildDiscordAuditPublisher() {}

    public static synchronized void init() {
        if (started) return;
        started = true;

        loadConfig();

        if (channelId == 0L) return;

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

    private static void tick() {
        JDA jda = GuildOnlineListPublisher.getJda();
        if (jda == null) return;

        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel == null) {
            System.err.println("[GuildAudit] Could not find text channel with ID " + channelId + ".");
            return;
        }

        Map<String, String> rosterNotes = getGuildRosterNotes();
        if (rosterNotes == null) return;

        List<Guild> guilds = jda.getGuilds();
        if (guilds.isEmpty()) return;
        Guild discordGuild = guilds.get(0);

        // Build set of all linked Discord IDs from officer notes
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
            if (id == null || discordGuild.getMemberById(id) == null) {
                unlinkdWoW.add(charName);
            }
        }
        Collections.sort(unlinkdWoW, String.CASE_INSENSITIVE_ORDER);

        // --- Panel 2: Discord members missing a WoW link, grouped by role ---
        StringBuilder panel2 = new StringBuilder();
        for (String roleId : auditRoleIds) {
            Role role = discordGuild.getRoleById(roleId);
            if (role == null) continue;

            List<Member> membersWithRole = discordGuild.getMembersWithRoles(role);
            List<String> unlinkedDiscord = new ArrayList<>();
            for (Member member : membersWithRole) {
                if (member.getUser().isBot()) continue;
                if (!linkedDiscordIds.contains(member.getUser().getId())) {
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

        // --- Build audit message ---
        // Build audit embed description
        StringBuilder auditDesc = new StringBuilder();
        auditDesc.append("### Discord Members that aren't linked to any characters in the Guild\n");
        if (auditRoleIds.isEmpty()) {
            auditDesc.append("No audit roles configured (guildAuditRoleIds).");
        } else if (panel2.length() == 0) {
            auditDesc.append("None of the configured roles were found in this Discord server.");
        } else {
            auditDesc.append(panel2);
        }

        String auditDescStr = auditDesc.toString();
        if (auditDescStr.length() > 4000) auditDescStr = auditDescStr.substring(0, 3997) + "...";

        MessageEmbed auditEmbed = new EmbedBuilder()
            .setTitle("Guild Sync Audit")
            .setDescription(auditDescStr)
            .setColor(Color.decode("#2b2d31"))
            .setFooter("Last updated: " + new java.util.Date())
            .build();

        postOrEditEmbed(channel, auditEmbed, AUDIT_MARKER,
            id -> auditMessageId = id, () -> auditMessageId, id -> auditMessageId = null);

        // --- Build roster embed ---
        postOrEditRoster(channel, discordGuild, rosterNotes);
    }

    // -------------------------------------------------------------------------
    // Format a single character entry: "Name (Level X Race Class)"
    // -------------------------------------------------------------------------

    private static String getClassName(byte charClass) {
        switch (charClass) {
            case 0x01: return "Warrior";
            case 0x02: return "Paladin";
            case 0x03: return "Hunter";
            case 0x04: return "Rogue";
            case 0x05: return "Priest";
            case 0x06: return "Death Knight";
            case 0x07: return "Shaman";
            case 0x08: return "Mage";
            case 0x09: return "Warlock";
            case 0x0A: return "Monk";
            case 0x0B: return "Druid";
            default:   return "Unknown";
        }
    }

    private static String formatCharEntry(String charName, scala.collection.Map<Object, GuildMember> roster) {
        scala.collection.Iterator<GuildMember> it = roster.valuesIterator();
        while (it.hasNext()) {
            GuildMember m = it.next();
            if (m.name().equalsIgnoreCase(charName)) {
                String race = GuildOnlineListPublisher.getRace(charName);
                String cls = getClassName(m.charClass());
                int level = m.level() & 0xFF; // byte to unsigned int
                String attrs = "Level " + level
                    + (race.isEmpty() ? "" : " " + race.trim())
                    + " " + cls;
                return charName + " (" + attrs + ")";
            }
        }
        return charName;
    }

    // -------------------------------------------------------------------------
    // Guild Roster embed
    // -------------------------------------------------------------------------

    private static void postOrEditRoster(TextChannel channel, Guild discordGuild, Map<String, String> rosterNotes) {
        // Get full roster for attribute lookup
        scala.collection.Map<Object, GuildMember> finalRoster = null;
        try {
            Option<wowchat.game.GameCommandHandler> gameOpt = Global$.MODULE$.game();
            if (gameOpt != null && !gameOpt.isEmpty() && gameOpt.get() instanceof wowchat.game.GamePacketHandler) {
                finalRoster = ((wowchat.game.GamePacketHandler) gameOpt.get()).guildRoster();
            }
        } catch (Throwable ignored) {}

        // Build list of user blocks: each block is one Discord user + their characters
        // A block is a self-contained string that must never be split across pages
        List<String> blocks = new ArrayList<>();

        // Sort all characters alphabetically
        List<String> sortedChars = new ArrayList<>(rosterNotes.keySet());
        Collections.sort(sortedChars, String.CASE_INSENSITIVE_ORDER);

        // Group by Discord ID, preserving alphabetical order within each group
        Map<String, List<String>> byDiscordId = new LinkedHashMap<>();
        List<String> unlinked = new ArrayList<>();

        for (String charName : sortedChars) {
            if (ignoreLower.contains(charName.toLowerCase(Locale.ROOT))) continue;
            String note = rosterNotes.get(charName);
            String discordId = extractDiscordId(note);
            if (discordId != null && discordGuild.getMemberById(discordId) != null) {
                byDiscordId.computeIfAbsent(discordId, k -> new ArrayList<>()).add(charName);
            } else {
                unlinked.add(charName);
            }
        }

        // Sort linked users by Discord display name
        List<Map.Entry<String, List<String>>> linkedEntries = new ArrayList<>(byDiscordId.entrySet());
        linkedEntries.sort((a, b) -> {
            Member ma = discordGuild.getMemberById(a.getKey());
            Member mb = discordGuild.getMemberById(b.getKey());
            String na = ma != null ? ma.getEffectiveName() : a.getKey();
            String nb = mb != null ? mb.getEffectiveName() : b.getKey();
            return na.compareToIgnoreCase(nb);
        });

        // Build one block per linked user
        for (Map.Entry<String, List<String>> entry : linkedEntries) {
            StringBuilder block = new StringBuilder();
            Member m = discordGuild.getMemberById(entry.getKey());
            String displayName = m != null ? m.getEffectiveName() : entry.getKey();
            block.append("<@").append(entry.getKey()).append("> - **").append(displayName).append("**\n");
            for (String charName : entry.getValue()) {
                block.append("- ").append(finalRoster != null ? formatCharEntry(charName, finalRoster) : charName).append("\n");
            }
            blocks.add(block.toString());
        }

        // Unlinked section as one block
        if (!unlinked.isEmpty()) {
            StringBuilder block = new StringBuilder();
            block.append("**Unlinked Characters**\n");
            for (String charName : unlinked) {
                block.append("- ").append(finalRoster != null ? formatCharEntry(charName, finalRoster) : charName).append("\n");
            }
            blocks.add(block.toString());
        }

        // Pack blocks into pages - never split a user block across pages
        List<String> pages = new ArrayList<>();
        StringBuilder currentPage = new StringBuilder();
        final int PAGE_LIMIT = 3800;

        for (String block : blocks) {
            if (currentPage.length() + block.length() > PAGE_LIMIT && currentPage.length() > 0) {
                pages.add(currentPage.toString());
                currentPage = new StringBuilder();
            }
            currentPage.append(block);
        }
        if (currentPage.length() > 0) pages.add(currentPage.toString());
        if (pages.isEmpty()) pages.add("No guild members found.");

        // Grow or shrink rosterMessageIds list to match page count
        while (rosterMessageIds.size() < pages.size()) rosterMessageIds.add(null);
        while (rosterMessageIds.size() > pages.size()) {
            // Delete extra messages that are no longer needed
            String extraId = rosterMessageIds.remove(rosterMessageIds.size() - 1);
            if (extraId != null) {
                try { channel.deleteMessageById(extraId).complete(); }
                catch (Throwable ignored) {}
            }
        }

        // Post or edit each page
        for (int i = 0; i < pages.size(); i++) {
            String title = pages.size() > 1 ? "Guild Roster (" + (i + 1) + "/" + pages.size() + ")" : "Guild Roster";
            MessageEmbed embed = new EmbedBuilder()
                .setTitle(title)
                .setDescription(pages.get(i))
                .setColor(Color.decode("#2b2d31"))
                .setFooter(i == pages.size() - 1 ? "Last updated: " + new java.util.Date() : null)
                .build();

            final int idx = i;
            postOrEditEmbed(channel, embed, ROSTER_MARKER,
                id -> rosterMessageIds.set(idx, id),
                () -> rosterMessageIds.get(idx),
                id -> rosterMessageIds.set(idx, null));
        }
    }

    // -------------------------------------------------------------------------
    // Post or edit an embed message
    // -------------------------------------------------------------------------

    private interface Setter { void set(String id); }
    private interface Getter { String get(); }

    private static void postOrEditEmbed(TextChannel channel, MessageEmbed embed, String marker,
                                        Setter setId, Getter getId, Setter clearId) {
        if (getId.get() == null) {
            setId.set(findExistingMessageId(channel, marker));
        }

        if (getId.get() == null) {
            try {
                Message sent = channel.sendMessageEmbeds(embed)
                    .setContent(marker)
                    .complete();
                setId.set(sent.getId());
            } catch (Throwable t) {
                System.err.println("[GuildAudit] Failed to send embed: " + t.getMessage());
            }
        } else {
            try {
                channel.editMessageById(getId.get(), marker)
                    .setEmbeds(embed)
                    .complete();
            } catch (Throwable t) {
                System.err.println("[GuildAudit] Failed to edit embed (will retry): " + t.getMessage());
                clearId.set(null);
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
                String note = p.officerNote() != null ? p.officerNote().trim() : "";
                result.put(p.name(), note);
            }
            return result;
        } catch (Throwable t) {
            System.err.println("[GuildAudit] Error reading guild roster: " + t.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Extract a valid Discord snowflake ID (17-19 digits) from an officer note
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

    private static String findExistingMessageId(TextChannel channel, String marker) {
        try {
            List<Message> history = channel.getHistory().retrievePast(100).complete();
            if (history == null) return null;
            for (Message msg : history) {
                User author = msg.getAuthor();
                if (author == null || !author.isBot()) continue;
                String content = msg.getContentRaw();
                if (content != null && content.endsWith(marker) && !content.endsWith(marker + "\u200b")) {
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

            channelId = 0L;
            try {
                channelId = config.getLong("guildAuditChannelId");
            } catch (ConfigException.WrongType e) {
                try { channelId = Long.parseLong(config.getString("guildAuditChannelId").trim()); }
                catch (Throwable ignored) {}
            } catch (ConfigException.Missing ignored) {}

            updateMinutes = 5;
            try {
                if (config.hasPath("discordFeaturesUpdateMinutes")) {
                    updateMinutes = config.getInt("discordFeaturesUpdateMinutes");
                }
                if (updateMinutes < 1) updateMinutes = 1;
            } catch (ConfigException ignored) {}

            Set<String> ignoreSet = new HashSet<>();
            try {
                if (config.hasPath("guildOnlineListIgnore")) {
                    for (String name : config.getStringList("guildOnlineListIgnore")) {
                        if (name != null && !name.trim().isEmpty())
                            ignoreSet.add(name.trim().toLowerCase(Locale.ROOT));
                    }
                }
            } catch (Throwable ignored) {}
            ignoreLower = ignoreSet.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(ignoreSet);

            List<String> roleList = new ArrayList<>();
            try {
                if (config.hasPath("guildAuditRoleIds")) {
                    for (String id : config.getStringList("guildAuditRoleIds")) {
                        if (id != null && !id.trim().isEmpty()) roleList.add(id.trim());
                    }
                }
            } catch (Throwable ignored) {}
            auditRoleIds = roleList.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(roleList);

        } catch (Throwable t) {
            System.err.println("[GuildAudit] Failed to load config: " + t.getMessage());
            channelId = 0L;
        }
    }
}
