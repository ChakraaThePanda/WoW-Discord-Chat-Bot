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

    private static final java.util.concurrent.ConcurrentHashMap<Long, String> messageIdByChannel =
        new java.util.concurrent.ConcurrentHashMap<>();

    private GuildDiscordAuditPublisher() {}

    // Called from GuildRosterPublisher.tick()
    public static void publish(TextChannel channel) {
        if (!isAuditEnabled()) return;

        long channelKey = channel.getIdLong();

        try {
            MessageEmbed embed = buildAuditEmbed(channel.getJDA());

            // Try to find existing message on first run for this channel
            if (!messageIdByChannel.containsKey(channelKey)) {
                String found = GuildEmbedUtil.findEmbedByTitleAndFooter(channel, "Guild Sync Audit");
                if (found != null) messageIdByChannel.put(channelKey, found);
            }

            String auditMessageId = messageIdByChannel.get(channelKey);

            // Post new or edit existing
            if (auditMessageId == null) {
                try {
                    Message sent = channel.sendMessageEmbeds(embed)
                        .complete();
                    messageIdByChannel.put(channelKey, sent.getId());
                } catch (Throwable t) {
                    System.err.println("[GuildAudit] Failed to send embed: " + t.getMessage());
                }
            } else {
                try {
                    channel.editMessageById(auditMessageId, " ")
                        .setEmbeds(embed)
                        .complete();
                } catch (Throwable t) {
                    System.err.println("[GuildAudit] Failed to edit embed (will retry): " + t.getMessage());
                    messageIdByChannel.remove(channelKey);
                }
            }
        } catch (Throwable t) {
            System.err.println("[GuildAudit] Publish error: " + t.getMessage());
        }
    }

    private static boolean isAuditEnabled() {
        return ConfigHelper.isAuditEnabled();
    }

    private static MessageEmbed buildAuditEmbed(net.dv8tion.jda.api.JDA jda) {
        Collection<GuildMember> members = GuildDataCache.getInstance().getMembers(false);

        List<Guild> guilds = jda.getGuilds();
        if (guilds.isEmpty()) return buildEmptyEmbed();
        Guild discordGuild = guilds.get(0);

        Map<Integer, String> guildRanks = getRankNames();

        // Build set of all linked Discord IDs
        Set<String> linkedDiscordIds = new HashSet<>();
        for (GuildMember member : members) {
            String id = DiscordIdExtractor.extractDiscordId(member);
            if (id != null) linkedDiscordIds.add(id);
        }

        StringBuilder auditDesc = new StringBuilder();

        // --- Panel 1: Unlinked Discord members ---
        if (ConfigHelper.isAuditUnlinkedEnabled()) {
            auditDesc.append("### Discord Members Not Linked to Any Guild Character\n");
            List<String> auditRoleIds = getAuditRoleIds();
            if (auditRoleIds.isEmpty()) {
                auditDesc.append("No roles configured (`audit.unlinked.roleIds`).");
            } else {
                StringBuilder panel = new StringBuilder();
                for (String roleId : auditRoleIds) {
                    Role role = discordGuild.getRoleById(roleId);
                    if (role == null) continue;
                    List<Member> membersWithRole = discordGuild.getMembersWithRoles(role);
                    List<String> unlinked = new ArrayList<>();
                    for (Member member : membersWithRole) {
                        if (member.getUser().isBot()) continue;
                        if (!linkedDiscordIds.contains(member.getUser().getId())) {
                            unlinked.add(member.getEffectiveName());
                        }
                    }
                    Collections.sort(unlinked, String.CASE_INSENSITIVE_ORDER);
                    if (panel.length() > 0) panel.append("\n");
                    panel.append("**").append(role.getName()).append("**");
                    if (unlinked.isEmpty()) {
                        panel.append("\nAll linked.");
                    } else {
                        for (String name : unlinked) panel.append("\n- ").append(name);
                    }
                }
                if (panel.length() == 0) {
                    auditDesc.append("None of the configured roles were found in this server.");
                } else {
                    auditDesc.append(panel);
                }
            }
        }

        // --- Panel 2: Linked rank promotion (multiple ranks) ---
        if (ConfigHelper.isAuditLinkedRankPromotionEnabled()) {
            List<String> ranksToCheck = ConfigHelper.getAuditLinkedRanksToCheck();
            if (!ranksToCheck.isEmpty()) {
                for (String rankToCheck : ranksToCheck) {
                    if (rankToCheck == null || rankToCheck.trim().isEmpty()) continue;
                    StringBuilder panel = buildLinkedWrongRankPanel(members, discordGuild, guildRanks, rankToCheck.trim());
                    if (auditDesc.length() > 0) auditDesc.append("\n");
                    auditDesc.append("### **").append(rankToCheck.trim()).append("** — Linked & Ready for Promotion\n");
                    if (panel.length() == 0) {
                        auditDesc.append("None.");
                    } else {
                        auditDesc.append(panel);
                    }
                }
            }
        }

        // --- Panel 3: Rank mismatch ---
        if (ConfigHelper.isAuditRankMismatchEnabled()) {
            StringBuilder panel = buildRankMismatchPanel(members, discordGuild, guildRanks);
            if (auditDesc.length() > 0) auditDesc.append("\n");
            auditDesc.append("### Guild Rank Mismatch\n");
            if (panel.length() == 0) {
                auditDesc.append("None.");
            } else {
                auditDesc.append(panel);
            }
        }

        // --- Panel 4: Promotion due ---
        if (ConfigHelper.isAuditPromotionDueEnabled()) {
            StringBuilder panel = buildPromotionDuePanel(members, guildRanks);
            if (auditDesc.length() > 0) auditDesc.append("\n");
            auditDesc.append("### Promotion Due\n");
            if (panel.length() == 0) {
                auditDesc.append("None.");
            } else {
                auditDesc.append(panel);
            }
        }

        if (auditDesc.length() == 0) {
            auditDesc.append("All audit sub-modules are disabled.");
        }

        String auditDescStr = auditDesc.toString();
        if (auditDescStr.length() > 4000) auditDescStr = auditDescStr.substring(0, 3997) + "...";

        return new EmbedBuilder()
            .setTitle("Guild Sync Audit")
            .setDescription(auditDescStr)
            .setColor(Color.decode("#2b2d31"))
            .setFooter(GuildEmbedUtil.getGuildRealmIdentifier() + " - Last updated: " + new java.util.Date())
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
    
    private static StringBuilder buildLinkedWrongRankPanel(
            Collection<GuildMember> members, Guild discordGuild,
            Map<Integer, String> guildRanks, String rankToCheck) {
        StringBuilder panel = new StringBuilder();
        if (guildRanks.isEmpty()) return panel;
        try {
            
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
            
            for (GuildMember member : members) {
                String discordId = DiscordIdExtractor.extractDiscordId(member);
                
                if (discordId == null) continue; // No Discord ID
                
                // Check if character has the target rank
                if (member.rankIndex() == targetRankIndex) {
                    Member discordMember = discordGuild.getMemberById(discordId);
                    String displayName = discordMember != null ? discordMember.getEffectiveName() : discordId;
                    flaggedChars.add(new CharWithOwner(member.name(), discordId, displayName));
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
    
    private static StringBuilder buildRankMismatchPanel(Collection<GuildMember> members, Guild discordGuild,
            Map<Integer, String> guildRanks) {
        StringBuilder panel = new StringBuilder();

        try {
            // Group characters by Discord ID
            Map<String, List<CharRankInfo>> charsByDiscordId = new LinkedHashMap<>();
            
            for (GuildMember member : members) {
                String discordId = DiscordIdExtractor.extractDiscordId(member);
                
                if (discordId == null) continue;
                
                int rankIndex = member.rankIndex();
                String rankName = guildRanks.getOrDefault(rankIndex, "Rank " + rankIndex);
                
                charsByDiscordId
                    .computeIfAbsent(discordId, k -> new ArrayList<>())
                    .add(new CharRankInfo(member.name(), rankIndex, rankName));
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
    
    private static StringBuilder buildPromotionDuePanel(Collection<GuildMember> members, Map<Integer, String> guildRanks) {
        StringBuilder panel = new StringBuilder();
        try {
            List<? extends com.typesafe.config.Config> rankConfigs = ConfigHelper.getAuditPromotionDueRanks();
            if (rankConfigs.isEmpty()) return panel;

            for (com.typesafe.config.Config rankConfig : rankConfigs) {
                String rankName = rankConfig.getString("rank").trim();
                int daysRequired = rankConfig.getInt("daysRequired");

                Integer targetRankIndex = null;
                for (Map.Entry<Integer, String> entry : guildRanks.entrySet()) {
                    if (entry.getValue().equalsIgnoreCase(rankName)) {
                        targetRankIndex = entry.getKey();
                        break;
                    }
                }
                if (targetRankIndex == null) continue;

                List<String> flagged = new ArrayList<>();
                for (GuildMember member : members) {
                    if (member.rankIndex() != targetRankIndex) continue;
                    long days = GuildRankTracker.getDaysSinceRankAssigned(member.name(), targetRankIndex);
                    if (days >= daysRequired) {
                        String entry = member.name() + " (" + days + "d)";
                        String discordId = DiscordIdExtractor.extractDiscordId(member);
                        if (discordId != null) entry += " (<@" + discordId + ">)";
                        flagged.add(entry);
                    }
                }
                Collections.sort(flagged, String.CASE_INSENSITIVE_ORDER);

                if (!flagged.isEmpty()) {
                    if (panel.length() > 0) panel.append("\n");
                    panel.append("**").append(rankName).append("** (").append(daysRequired).append("+ days)\n");
                    for (String entry : flagged) panel.append("- ").append(entry).append("\n");
                }
            }
        } catch (Throwable t) {
            System.err.println("[GuildAudit] Error building promotion due panel: " + t.getMessage());
        }
        return panel;
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
    
    public static List<String> getAuditRoleIds() {
        return ConfigHelper.getAuditRoleIds();
    }
    
}
