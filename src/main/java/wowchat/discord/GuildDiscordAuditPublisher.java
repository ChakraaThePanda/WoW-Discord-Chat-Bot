package wowchat.discord;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigResolveOptions;
import net.dv8tion.jda.api.EmbedBuilder;
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

import java.awt.Color;
import java.io.File;
import java.util.*;

/**
 * GuildDiscordAuditPublisher
 *
 * Publishes guild sync audit showing:
 * - Discord members not linked to WoW characters
 * - Guild rank mismatches (alts with different ranks)
 *
 * Called directly from GuildRosterPublisher.tick() for deterministic ordering.
 */
public final class GuildDiscordAuditPublisher {

    private static final String AUDIT_MARKER = "\u200b\u200b\u200b\u200b\u200b\u200b\u200b\u200b\u200b\u200b\u200b";
    
    private static volatile String auditMessageId = null;

    private GuildDiscordAuditPublisher() {}

    // Called from GuildRosterPublisher.tick()
    public static void publish(TextChannel channel) {
        if (!isAuditEnabled()) return;
        
        try {
            MessageEmbed embed = buildAuditEmbed(channel.getJDA());

            // Try to find existing message on first run
            if (auditMessageId == null) {
                auditMessageId = findExistingMessageId(channel, AUDIT_MARKER);
            }

            // Post new or edit existing
            if (auditMessageId == null) {
                try {
                    Message sent = channel.sendMessageEmbeds(embed)
                        .setContent(AUDIT_MARKER)
                        .complete();
                    auditMessageId = sent.getId();
                } catch (Throwable t) {
                    System.err.println("[GuildAudit] Failed to send embed: " + t.getMessage());
                }
            } else {
                try {
                    channel.editMessageById(auditMessageId, AUDIT_MARKER)
                        .setEmbeds(embed)
                        .complete();
                } catch (Throwable t) {
                    System.err.println("[GuildAudit] Failed to edit embed (will retry): " + t.getMessage());
                    auditMessageId = null;
                }
            }
        } catch (Throwable t) {
            System.err.println("[GuildAudit] Publish error: " + t.getMessage());
            t.printStackTrace();
        }
    }

    private static boolean isAuditEnabled() {
        try {
            String configFile = System.getProperty("wowchat.configFile", "wowchat.conf");
            Config config = ConfigFactory.parseFile(new File(configFile))
                .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true));
            
            // Try NEW path first, fall back to OLD
            if (config.hasPath("guildRoster.audit.enabled")) {
                return config.getBoolean("guildRoster.audit.enabled");
            } else if (config.hasPath("guildAuditEnabled")) {
                return config.getBoolean("guildAuditEnabled");
            }
            // Default enabled for backward compat
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

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

    private static MessageEmbed buildAuditEmbed(net.dv8tion.jda.api.JDA jda) {
        Map<String, String> rosterNotes = GuildDataCache.getInstance().getOfficerNotes();
        
        List<Guild> guilds = jda.getGuilds();
        if (guilds.isEmpty()) return buildEmptyEmbed();
        Guild discordGuild = guilds.get(0);

        // Build set of all linked Discord IDs
        Set<String> linkedDiscordIds = new HashSet<>();
        for (String note : rosterNotes.values()) {
            String id = extractDiscordId(note);
            if (id != null) linkedDiscordIds.add(id);
        }

        // --- Panel: Discord members missing WoW link, grouped by role ---
        StringBuilder panel = new StringBuilder();
        List<String> auditRoleIds = getAuditRoleIds();
        
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

            if (panel.length() > 0) panel.append("\n");
            panel.append("**").append(role.getName()).append("**");
            if (unlinkedDiscord.isEmpty()) {
                panel.append("\nAll linked.");
            } else {
                for (String name : unlinkedDiscord) {
                    panel.append("\n- ").append(name);
                }
            }
        }
        
        // --- Linked Wrong Rank Panel ---
        StringBuilder linkedWrongRankPanel = buildLinkedWrongRankPanel(rosterNotes, discordGuild);
        String rankToCheck = getLinkedRankToCheck();
        
        // --- Rank Mismatch Panel ---
        StringBuilder rankMismatchPanel = buildRankMismatchPanel(rosterNotes, discordGuild);

        // --- Build embed description ---
        StringBuilder auditDesc = new StringBuilder();
        
        // Panel 1: Discord members not linked
        auditDesc.append("### Discord Members that aren't linked to any characters in the Guild\n");
        if (auditRoleIds.isEmpty()) {
            auditDesc.append("No audit roles configured (guildAuditRoleIds).");
        } else if (panel.length() == 0) {
            auditDesc.append("None of the configured roles were found in this Discord server.");
        } else {
            auditDesc.append(panel);
        }
        
        // Panel 2: Linked characters with wrong rank (if configured)
        if (rankToCheck != null) {
            auditDesc.append("\n### ").append(rankToCheck).append(" Rank with Discord Link (Should be Promoted)\n");
            if (linkedWrongRankPanel.length() == 0) {
                auditDesc.append("None.");
            } else {
                auditDesc.append(linkedWrongRankPanel);
            }
        }
        
        // Panel 3: Rank mismatch
        auditDesc.append("\n### Guild Rank Mismatch\n");
        if (rankMismatchPanel.length() == 0) {
            auditDesc.append("None.");
        } else {
            auditDesc.append(rankMismatchPanel);
        }

        String auditDescStr = auditDesc.toString();
        if (auditDescStr.length() > 4000) auditDescStr = auditDescStr.substring(0, 3997) + "...";

        return new EmbedBuilder()
            .setTitle("Guild Sync Audit")
            .setDescription(auditDescStr)
            .setColor(Color.decode("#2b2d31"))
            .setFooter("Last updated: " + new java.util.Date())
            .build();
    }

    private static MessageEmbed buildEmptyEmbed() {
        return new EmbedBuilder()
            .setTitle("Guild Sync Audit")
            .setDescription("No guild data available.")
            .setColor(Color.decode("#2b2d31"))
            .build();
    }

    // -------------------------------------------------------------------------
    // Rank Mismatch
    // -------------------------------------------------------------------------
    
    private static StringBuilder buildLinkedWrongRankPanel(Map<String, String> rosterNotes, Guild discordGuild) {
        StringBuilder panel = new StringBuilder();
        
        String rankToCheck = getLinkedRankToCheck();
        if (rankToCheck == null) return panel; // Feature disabled if not configured
        
        try {
            Map<Integer, String> guildRanks = getRankNames();
            if (guildRanks.isEmpty()) return panel;
            
            // Find the rank index for the rank we're checking
            Integer targetRankIndex = null;
            for (Map.Entry<Integer, String> entry : guildRanks.entrySet()) {
                if (entry.getValue().equalsIgnoreCase(rankToCheck)) {
                    targetRankIndex = entry.getKey();
                    break;
                }
            }
            
            if (targetRankIndex == null) return panel; // Rank not found
            
            // Find characters at this rank who have Discord IDs
            List<CharWithOwner> flaggedChars = new ArrayList<>();
            
            for (Map.Entry<String, String> entry : rosterNotes.entrySet()) {
                String charName = entry.getKey();
                String discordId = extractDiscordId(entry.getValue());
                
                if (discordId == null) continue; // No Discord ID
                
                GuildMember m = GuildDataCache.getInstance().getMember(charName);
                if (m == null) continue;
                
                // Check if character has the target rank
                if (m.rankIndex() == targetRankIndex) {
                    Member discordMember = discordGuild.getMemberById(discordId);
                    String displayName = discordMember != null ? discordMember.getEffectiveName() : discordId;
                    flaggedChars.add(new CharWithOwner(charName, discordId, displayName));
                }
            }
            
            // Sort alphabetically by character name
            Collections.sort(flaggedChars, (a, b) -> a.charName.compareToIgnoreCase(b.charName));
            
            for (CharWithOwner c : flaggedChars) {
                panel.append("\n- ").append(c.charName).append(" (<@").append(c.discordId).append(">)");
            }
            
        } catch (Throwable t) {
            System.err.println("[GuildAudit] Error building linked wrong rank panel: " + t.getMessage());
            t.printStackTrace();
        }
        
        return panel;
    }
    
    private static class CharWithOwner {
        String charName;
        String discordId;
        String displayName;
        
        CharWithOwner(String charName, String discordId, String displayName) {
            this.charName = charName;
            this.discordId = discordId;
            this.displayName = displayName;
        }
    }
    
    private static StringBuilder buildRankMismatchPanel(Map<String, String> rosterNotes, Guild discordGuild) {
        StringBuilder panel = new StringBuilder();
        
        try {
            Map<Integer, String> guildRanks = getRankNames();
            
            // Group characters by Discord ID
            Map<String, List<CharRankInfo>> charsByDiscordId = new LinkedHashMap<>();
            
            for (Map.Entry<String, String> entry : rosterNotes.entrySet()) {
                String charName = entry.getKey();
                String discordId = extractDiscordId(entry.getValue());
                
                if (discordId == null) continue;
                
                GuildMember m = GuildDataCache.getInstance().getMember(charName);
                if (m != null) {
                    int rankIndex = m.rankIndex();
                    String rankName = guildRanks.getOrDefault(rankIndex, "Rank " + rankIndex);
                    
                    charsByDiscordId
                        .computeIfAbsent(discordId, k -> new ArrayList<>())
                        .add(new CharRankInfo(charName, rankIndex, rankName));
                }
            }
            
            // Find Discord users with rank mismatches
            List<RankMismatch> mismatches = new ArrayList<>();
            
            for (Map.Entry<String, List<CharRankInfo>> entry : charsByDiscordId.entrySet()) {
                String discordId = entry.getKey();
                List<CharRankInfo> chars = entry.getValue();
                
                if (chars.size() < 2) continue;
                
                // Treat rank 0 (Guild Master) as rank 1 (Officer) for comparison purposes
                // This way GM shows as Officer in the mismatch list
                List<CharRankInfo> adjustedChars = new ArrayList<>();
                for (CharRankInfo c : chars) {
                    int adjustedRank = (c.rankIndex == 0) ? 1 : c.rankIndex;
                    String adjustedRankName = (c.rankIndex == 0) ? guildRanks.getOrDefault(1, "Officer") : c.rankName;
                    adjustedChars.add(new CharRankInfo(c.charName, adjustedRank, adjustedRankName));
                }
                
                // Find highest rank (lowest rankIndex)
                int highestRank = adjustedChars.stream().mapToInt(c -> c.rankIndex).min().orElse(Integer.MAX_VALUE);
                String highestRankName = adjustedChars.stream()
                    .filter(c -> c.rankIndex == highestRank)
                    .findFirst()
                    .map(c -> c.rankName)
                    .orElse("Unknown");
                
                // Find all characters with lower ranks
                List<CharRankInfo> lowerRankedChars = new ArrayList<>();
                for (CharRankInfo c : adjustedChars) {
                    if (c.rankIndex != highestRank) {
                        lowerRankedChars.add(c);
                    }
                }
                
                if (!lowerRankedChars.isEmpty()) {
                    mismatches.add(new RankMismatch(discordId, highestRankName, lowerRankedChars));
                }
            }
            
            if (mismatches.isEmpty()) return panel;
            
            // Sort alphabetically by Discord display name
            mismatches.sort((a, b) -> {
                Member ma = discordGuild.getMemberById(a.discordId);
                Member mb = discordGuild.getMemberById(b.discordId);
                String nameA = ma != null ? ma.getEffectiveName() : a.discordId;
                String nameB = mb != null ? mb.getEffectiveName() : b.discordId;
                return nameA.compareToIgnoreCase(nameB);
            });
            
            for (RankMismatch mismatch : mismatches) {
                // Get display name from Discord
                Member member = discordGuild.getMemberById(mismatch.discordId);
                String displayName = member != null ? member.getEffectiveName() : mismatch.discordId;
                
                // Format: <@ID> - **Name** (Highest Rank)
                panel.append("<@").append(mismatch.discordId).append("> - **").append(displayName).append("** (").append(mismatch.highestRankName).append(")\n");
                
                mismatch.lowerRankedChars.sort(Comparator
                    .comparingInt((CharRankInfo c) -> c.rankIndex)
                    .thenComparing(c -> c.charName));
                
                for (CharRankInfo c : mismatch.lowerRankedChars) {
                    panel.append("- ").append(c.charName).append(" (").append(c.rankName).append(")\n");
                }
            }
            
        } catch (Throwable t) {
            System.err.println("[GuildAudit] Error building rank mismatch panel: " + t.getMessage());
            t.printStackTrace();
        }
        
        return panel;
    }
    
    private static class CharRankInfo {
        final String charName;
        final int rankIndex;
        final String rankName;
        
        CharRankInfo(String charName, int rankIndex, String rankName) {
            this.charName = charName;
            this.rankIndex = rankIndex;
            this.rankName = rankName;
        }
    }
    
    private static class RankMismatch {
        final String discordId;
        final String highestRankName;
        final List<CharRankInfo> lowerRankedChars;
        
        RankMismatch(String discordId, String highestRankName, List<CharRankInfo> lowerRankedChars) {
            this.discordId = discordId;
            this.highestRankName = highestRankName;
            this.lowerRankedChars = lowerRankedChars;
        }
    }
    
    private static Map<Integer, String> getRankNames() {
        Map<Integer, String> ranks = new LinkedHashMap<>();
        try {
            Option<GameCommandHandler> gameOpt = Global$.MODULE$.game();
            if (gameOpt == null || gameOpt.isEmpty()) return ranks;
            GameCommandHandler handler = gameOpt.get();
            if (!(handler instanceof GamePacketHandler)) return ranks;
            
            GamePacketHandler gph = (GamePacketHandler) handler;
            
            // Access guildInfo field - it's in the parent class, so search the hierarchy
            java.lang.reflect.Field field = null;
            Class<?> clazz = gph.getClass();
            while (clazz != null && field == null) {
                try {
                    field = clazz.getDeclaredField("guildInfo");
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }
            
            if (field == null) {
                System.err.println("[GuildAudit] Field 'guildInfo' not found in class hierarchy");
                return ranks;
            }
            
            field.setAccessible(true);
            Object guildInfo = field.get(gph);
            
            // guildInfo can be null if guild query hasn't completed yet
            if (guildInfo == null) {
                System.err.println("[GuildAudit] guildInfo is null - guild query may not have completed yet");
                return ranks;
            }
            
            // Get ranks() method from GuildInfo
            java.lang.reflect.Method ranksMethod = guildInfo.getClass().getMethod("ranks");
            @SuppressWarnings("unchecked")
            scala.collection.immutable.Map<Object, Object> scalaRanks = 
                (scala.collection.immutable.Map<Object, Object>) ranksMethod.invoke(guildInfo);
            
            if (scalaRanks == null) {
                System.err.println("[GuildAudit] ranks map is null");
                return ranks;
            }
            
            // Convert Scala Map to Java Map
            scala.collection.Iterator<scala.Tuple2<Object, Object>> it = scalaRanks.iterator();
            while (it.hasNext()) {
                scala.Tuple2<Object, Object> entry = it.next();
                ranks.put((Integer) entry._1(), (String) entry._2());
            }
        } catch (NoSuchMethodException e) {
            System.err.println("[GuildAudit] Method 'ranks' not found in GuildInfo");
        } catch (Throwable t) {
            System.err.println("[GuildAudit] Error getting rank names: " + t.getClass().getSimpleName() + " - " + t.getMessage());
        }
        
        return ranks;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
    
    private static String extractDiscordId(String note) {
        if (note == null || note.isEmpty()) return null;
        String trimmed = note.trim();
        if (trimmed.matches("\\d{17,19}")) return trimmed;
        return null;
    }
    
    public static List<String> getAuditRoleIds() {
        try {
            String configFile = System.getProperty("wowchat.configFile", "wowchat.conf");
            Config config = ConfigFactory.parseFile(new File(configFile))
                .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true));
            
            // Try NEW path first, fall back to OLD
            if (config.hasPath("guildRoster.audit.roleIds")) {
                return config.getStringList("guildRoster.audit.roleIds");
            } else if (config.hasPath("guildAuditRoleIds")) {
                return config.getStringList("guildAuditRoleIds");
            }
        } catch (Throwable t) {}
        return Collections.emptyList();
    }
    
    private static String getLinkedRankToCheck() {
        try {
            String configFile = System.getProperty("wowchat.configFile", "wowchat.conf");
            Config config = ConfigFactory.parseFile(new File(configFile))
                .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true));
            
            // Try NEW path first, fall back to OLD
            if (config.hasPath("guildRoster.audit.linkedRankToCheck")) {
                return config.getString("guildRoster.audit.linkedRankToCheck");
            } else if (config.hasPath("guildAuditLinkedRankToCheck")) {
                return config.getString("guildAuditLinkedRankToCheck");
            }
        } catch (Throwable t) {}
        return null;
    }
}
