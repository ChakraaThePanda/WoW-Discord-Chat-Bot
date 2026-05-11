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
 * GuildRosterPublisher
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
 *   guildRosterChannelId  - Discord channel ID
 *   guildAuditRoleIds    - list of Discord role IDs for Panel 2
 */
public final class GuildRosterPublisher {

    // Config
    private static volatile long         channelId     = 0L;
    private static volatile int          updateMinutes = 5;
    private static volatile Set<String>  ignoreLower   = Collections.emptySet();

    // Runtime state - roster messages (one per page)
    private static volatile boolean started = false;
    private static final List<String> rosterMessageIds = new ArrayList<>();

    private GuildRosterPublisher() {}

    public static boolean isEnabled() { return channelId > 0L; }
    
    public static String getChannelIdString() { return channelId > 0L ? String.valueOf(channelId) : null; }

    public static synchronized void init() {
        if (started) return;
        started = true;

        loadConfig();

        if (channelId == 0L) return;

        System.out.println("[GuildRoster] Initializing. Channel ID: " + channelId
            + ", update interval: " + updateMinutes + " min.");

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            new GuildOnlineListPublisher.DaemonThreadFactory("wowchat-guild-audit"));

        long periodSec = Math.max(1, updateMinutes) * 60L;

        scheduler.scheduleAtFixedRate(() -> {
            try {
                tick();
            } catch (Throwable t) {
                System.err.println("[GuildRoster] Unexpected error in update tick: " + t.getMessage());
            }
        }, 20L, periodSec, TimeUnit.SECONDS);
    }

    private static void tick() {
        // Skip update if game is disconnected - prevents "Unknown Guild" embeds
        String guildIdentifier = GuildEmbedUtil.getGuildRealmIdentifier();
        if (guildIdentifier.equals("Unknown Guild (Unknown Realm)")) {
            // Bot is disconnected from game server, skip this update
            return;
        }

        // Refresh shared cache once per tick (OPTIMIZATION)
        GuildDataCache.getInstance().refresh();
        
        // Clean up stale profession entries (1 hour grace period)
        Collection<GuildMember> currentMembers = GuildDataCache.getInstance().getMembers(true);
        Set<String> currentMemberNamesLower = new HashSet<>();
        for (GuildMember m : currentMembers) {
            currentMemberNamesLower.add(m.name().toLowerCase());
        }
        ProfessionManager.cleanupStaleEntries(currentMemberNamesLower);
        
        JDA jda = GuildOnlineListPublisher.getJda();
        if (jda == null) return;

        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel == null) {
            System.err.println("[GuildRoster] Could not find text channel with ID " + channelId + ".");
            return;
        }

        Map<String, String> rosterNotes = GuildDataCache.getInstance().getOfficerNotes(true);
        if (rosterNotes == null) return;

        // --- Get Discord guild for inactivity processing ---
        List<Guild> guilds = jda.getGuilds();
        if (guilds.isEmpty()) return;
        Guild discordGuild = guilds.get(0);
        
        // --- Get raw roster for inactivity checking ---
        scala.collection.mutable.Map<Object, wowchat.game.GuildMember> rawRoster = GuildDataCache.getInstance().getRawRoster();
        Map<Long, Object> guildMembers = new HashMap<>();
        if (rawRoster != null) {
            scala.collection.Iterator<scala.Tuple2<Object, wowchat.game.GuildMember>> it = rawRoster.iterator();
            while (it.hasNext()) {
                scala.Tuple2<Object, wowchat.game.GuildMember> entry = it.next();
                guildMembers.put((Long) entry._1(), entry._2());
            }
        }
        
        // --- Process inactivity (assigns/removes Discord roles) ---
        int inactiveCount = GuildInactivityManager.processInactivity(discordGuild, guildMembers);

        // --- Build and post audit embed ---
        GuildDiscordAuditPublisher.publish(channel);
        
        // Delay to avoid Discord rate limits (5 edits per 5 seconds = 1 per second)
        // Using 2 seconds to be safe and leave room for other interactions
        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}

        // --- Build and post stats embed ---
        GuildStatsPublisher.publish(channel);
        
        // Delay to avoid Discord rate limits
        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}

        // --- Build roster embed (only if enabled) ---
        if (ConfigHelper.isRosterPanelEnabled()) {
            postOrEditRoster(channel, discordGuild, rosterNotes, inactiveCount);
        }
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
        GuildMember m = GuildDataCache.getInstance().getMember(charName);
        if (m == null) {
            return charName + " (Level ? Unknown)";
        }
        
        String race = GuildOnlineListPublisher.getRace(charName);
        String cls = getClassName(m.charClass());
        int level = m.level() & 0xFF; // byte to unsigned int
        String attrs = "Level " + level
            + (race.isEmpty() ? "" : " " + race.trim())
            + " " + cls;
        StringBuilder entry = new StringBuilder(charName + " (" + attrs + ")");
        java.util.List<String> profs = ProfessionManager.getProfessions(charName);
        for (String prof : profs) {
            entry.append("\n  \u2022 ").append(ProfessionManager.formatProfession(prof));
        }
        return entry.toString();
    }

    // -------------------------------------------------------------------------
    // Guild Roster embed
    // -------------------------------------------------------------------------

    private static void postOrEditRoster(TextChannel channel, Guild discordGuild, Map<String, String> rosterNotes, int inactiveCount) {
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
            
            // Use DiscordIdExtractor to respect configured note location
            GuildMember member = GuildDataCache.getInstance().getMember(charName);
            String discordId = DiscordIdExtractor.extractDiscordId(member);
            
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
            int charCount = entry.getValue().size();
            block.append("<@").append(entry.getKey()).append("> - **").append(displayName).append("** (").append(charCount).append(")\n");
            for (String charName : entry.getValue()) {
                block.append("- ").append(finalRoster != null ? formatCharEntry(charName, finalRoster) : charName).append("\n");
            }
            blocks.add(block.toString());
        }

        // Unlinked section - add each character as a separate block so paging can split them
        if (!unlinked.isEmpty()) {
            // Add header as first unlinked block
            blocks.add("**Unlinked Characters (" + unlinked.size() + ")**\n");
            
            // Add each unlinked character as its own block (can be split across pages)
            for (String charName : unlinked) {
                String entry = "- " + (finalRoster != null ? formatCharEntry(charName, finalRoster) : charName) + "\n";
                blocks.add(entry);
            }
        }

        // Pack blocks into pages - never split a user block across pages
        // Reserve space on page 1 for the linked players + level 80s prefix
        int uniquePlayersCount = byDiscordId.size();
        List<String> pages = new ArrayList<>();
        StringBuilder currentPage = new StringBuilder();
        final int PAGE_LIMIT = 3800;
        final int FIRST_PAGE_LIMIT = PAGE_LIMIT - 50; // reserve ~50 chars for prefix on page 1

        for (String block : blocks) {
            int limit = pages.isEmpty() ? FIRST_PAGE_LIMIT : PAGE_LIMIT;
            if (currentPage.length() + block.length() > limit && currentPage.length() > 0) {
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
        int totalMembers = sortedChars.size() - (int) sortedChars.stream().filter(n -> ignoreLower.contains(n.toLowerCase(Locale.ROOT))).count();
        int level80Count = 0;
        if (finalRoster != null) {
            for (String charName : sortedChars) {
                if (ignoreLower.contains(charName.toLowerCase(Locale.ROOT))) continue;
                scala.collection.Iterator<GuildMember> it = finalRoster.valuesIterator();
                while (it.hasNext()) {
                    GuildMember member = it.next();
                    if (member.name().equalsIgnoreCase(charName)) {
                        if ((member.level() & 0xFF) >= 80) level80Count++;
                        break;
                    }
                }
            }
        }
        // Post pages in forward order (1, 2, 3...)
        // New pages appear at bottom, edits stay in place
        for (int i = 0; i < pages.size(); i++) {
            String title = pages.size() > 1 ? "Guild Roster (" + totalMembers + ") (" + (i + 1) + "/" + pages.size() + ")" : "Guild Roster (" + totalMembers + ")";
            
            // Build description prefix for first page
            StringBuilder prefix = new StringBuilder();
            if (i == 0) {
                prefix.append(uniquePlayersCount).append(" Linked Players");
                if (inactiveCount > 0) {
                    prefix.append(" (").append(inactiveCount).append(" Inactive)");
                }
                prefix.append("\n\n");
            }
            String description_prefix = prefix.toString();
            
            // Footer: all pages get the footer for identification
            String footerText = GuildEmbedUtil.getGuildRealmIdentifier() + " - Last updated: " + new java.util.Date();
            
            MessageEmbed embed = new EmbedBuilder()
                .setTitle(title)
                .setDescription(description_prefix + pages.get(i))
                .setColor(Color.decode("#2b2d31"))
                .setFooter(footerText)
                .build();

            final int idx = i;
            postOrEditEmbed(channel, embed, title,
                id -> rosterMessageIds.set(idx, id),
                () -> rosterMessageIds.get(idx),
                id -> rosterMessageIds.set(idx, null));
            
            // Delay between pages to avoid Discord rate limits
            if (i < pages.size() - 1) {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            }
        }
    }

    // -------------------------------------------------------------------------
    // Post or edit an embed message
    // -------------------------------------------------------------------------

    private interface Setter { void set(String id); }
    private interface Getter { String get(); }

    private static void postOrEditEmbed(TextChannel channel, MessageEmbed embed, String pageTitle,
                                        Setter setId, Getter getId, Setter clearId) {
        if (getId.get() == null) {
            setId.set(GuildEmbedUtil.findEmbedByTitleAndFooter(channel, pageTitle));
        }

        if (getId.get() == null) {
            try {
                Message sent = channel.sendMessageEmbeds(embed)
                    .complete();
                setId.set(sent.getId());
            } catch (Throwable t) {
                System.err.println("[GuildRoster] Failed to send embed: " + t.getMessage());
            }
        } else {
            try {
                channel.editMessageById(getId.get(), " ")
                    .setEmbeds(embed)
                    .complete();
            } catch (Throwable t) {
                System.err.println("[GuildRoster] Failed to edit embed (will retry): " + t.getMessage());
                clearId.set(null);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Config loading
    // -------------------------------------------------------------------------

    private static void loadConfig() {
        try {
            String configFile = System.getProperty("wowchat.configFile", "wowchat.conf");
            Config config = ConfigFactory.parseFile(new File(configFile))
                .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true));

            // Check if guild roster is enabled using ConfigHelper
            if (!ConfigHelper.isGuildRosterEnabled()) {
                System.out.println("[GuildRoster] Feature disabled in config");
                channelId = 0L;
                updateMinutes = 5;
                ignoreLower = Collections.emptySet();
                return;
            }

            // Channel ID using ConfigHelper
            String channelIdStr = ConfigHelper.getGuildRosterChannelId();
            if (channelIdStr != null) {
                try {
                    channelId = Long.parseLong(channelIdStr.trim());
                } catch (NumberFormatException e) {
                    System.err.println("[GuildRoster] Invalid channel ID: " + channelIdStr);
                    channelId = 0L;
                }
            } else {
                channelId = 0L;
            }

            updateMinutes = 5;
            try {
                if (config.hasPath("discord.featuresUpdateMinutes")) {
                    updateMinutes = config.getInt("discord.featuresUpdateMinutes");
                }
                if (updateMinutes < 1) updateMinutes = 1;
            } catch (ConfigException ignored) {}

            Set<String> ignoreSet = new HashSet<>();
            try {
                // Check both old and new paths for ignore list
                String ignorePath = config.hasPath("guildOnlineList.ignore")
                    ? "guildOnlineList.ignore"
                    : "guildOnlineListIgnore";
                
                if (config.hasPath(ignorePath)) {
                    for (String name : config.getStringList(ignorePath)) {
                        if (name != null && !name.trim().isEmpty())
                            ignoreSet.add(name.trim().toLowerCase(Locale.ROOT));
                    }
                }
            } catch (Throwable ignored) {}
            ignoreLower = ignoreSet.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(ignoreSet);

        } catch (Throwable t) {
            System.err.println("[GuildRoster] Failed to load config: " + t.getMessage());
            channelId = 0L;
        }
    }
}
