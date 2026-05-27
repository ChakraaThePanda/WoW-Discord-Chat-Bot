package wowchat.discord;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import scala.Option;
import wowchat.common.Global$;
import wowchat.game.GameCommandHandler;
import wowchat.game.GamePacketHandler;
import wowchat.game.GuildMember;

import java.awt.Color;
import java.util.*;

/**
 * GuildStatsPublisher
 *
 * Publishes guild statistics (faction/race/class distribution, profession counts)
 * as a Discord embed. Called directly from GuildRosterPublisher.tick() to ensure
 * deterministic ordering: Audit -> Stats -> Roster.
 */
public final class GuildStatsPublisher {

    private static final java.util.concurrent.ConcurrentHashMap<Long, String> messageIdByChannel =
        new java.util.concurrent.ConcurrentHashMap<>();

    private GuildStatsPublisher() {}

    // Called from GuildRosterPublisher.tick() - posts stats between Audit and Roster
    public static void publish(TextChannel channel) {
        if (!isStatsEnabled()) return;

        long channelKey = channel.getIdLong();

        try {
            MessageEmbed embed = buildStatsEmbed();

            // Try to find existing message on first run for this channel
            if (!messageIdByChannel.containsKey(channelKey)) {
                String found = GuildEmbedUtil.findEmbedByTitleAndFooter(channel, "Guild Statistics");
                if (found != null) messageIdByChannel.put(channelKey, found);
            }

            String messageId = messageIdByChannel.get(channelKey);

            // Post new or edit existing
            if (messageId == null) {
                try {
                    net.dv8tion.jda.api.entities.Message sent = channel.sendMessageEmbeds(embed)
                        .complete();
                    messageIdByChannel.put(channelKey, sent.getId());
                } catch (Throwable t) {
                    System.err.println("[GuildStats] Failed to send embed: " + t.getMessage());
                }
            } else {
                try {
                    channel.editMessageById(messageId, " ")
                        .setEmbeds(embed)
                        .complete();
                } catch (Throwable t) {
                    System.err.println("[GuildStats] Failed to edit embed (will retry): " + t.getMessage());
                    messageIdByChannel.remove(channelKey);
                }
            }
        } catch (Throwable t) {
            System.err.println("[GuildStats] Publish error: " + t.getMessage());
        }
    }

    private static boolean isStatsEnabled() {
        return ConfigHelper.isStatsEnabled();
    }

    private static MessageEmbed buildStatsEmbed() {
        Collection<GuildMember> members = GuildDataCache.getInstance().getMembers(true);
        
        Map<String, Integer> factionCounts = new LinkedHashMap<>();
        Map<String, Integer> raceCounts = new LinkedHashMap<>();
        Map<String, Integer> classCounts = new LinkedHashMap<>();

        factionCounts.put("Alliance", 0);
        factionCounts.put("Horde", 0);
        
        // Initialize all classes with 0 (Death Knight excluded - not allowed in Hardcore)
        classCounts.put("Druid", 0);
        classCounts.put("Hunter", 0);
        classCounts.put("Mage", 0);
        classCounts.put("Paladin", 0);
        classCounts.put("Priest", 0);
        classCounts.put("Rogue", 0);
        classCounts.put("Shaman", 0);
        classCounts.put("Warlock", 0);
        classCounts.put("Warrior", 0);
        
        // Initialize all races with 0
        raceCounts.put("Draenei", 0);
        raceCounts.put("Dwarf", 0);
        raceCounts.put("Gnome", 0);
        raceCounts.put("Human", 0);
        raceCounts.put("Night Elf", 0);
        raceCounts.put("Blood Elf", 0);
        raceCounts.put("Orc", 0);
        raceCounts.put("Tauren", 0);
        raceCounts.put("Troll", 0);
        raceCounts.put("Undead", 0);
        
        // Initialize level brackets with 0
        Map<String, Integer> levelCounts = new LinkedHashMap<>();
        levelCounts.put("1-10", 0);
        levelCounts.put("11-20", 0);
        levelCounts.put("21-30", 0);
        levelCounts.put("31-40", 0);
        levelCounts.put("41-50", 0);
        levelCounts.put("51-60", 0);
        levelCounts.put("61-70", 0);
        levelCounts.put("71-79", 0);
        levelCounts.put("80", 0);

        for (GuildMember m : members) {
            // No ignore filter needed - already applied by cache
            
            // Faction
            String race = GuildOnlineListPublisher.getRace(m.name());
            String faction = getFaction(race);
            factionCounts.put(faction, factionCounts.get(faction) + 1);

            // Race - only if not empty
            String raceName = race.trim();
            if (!raceName.isEmpty()) {
                raceCounts.put(raceName, raceCounts.getOrDefault(raceName, 0) + 1);
            }

            // Class
            String cls = getClassName(m.charClass());
            classCounts.put(cls, classCounts.getOrDefault(cls, 0) + 1);
            
            // Level brackets
            int level = m.level() & 0xFF;
            if (level >= 1 && level <= 10) levelCounts.put("1-10", levelCounts.get("1-10") + 1);
            else if (level >= 11 && level <= 20) levelCounts.put("11-20", levelCounts.get("11-20") + 1);
            else if (level >= 21 && level <= 30) levelCounts.put("21-30", levelCounts.get("21-30") + 1);
            else if (level >= 31 && level <= 40) levelCounts.put("31-40", levelCounts.get("31-40") + 1);
            else if (level >= 41 && level <= 50) levelCounts.put("41-50", levelCounts.get("41-50") + 1);
            else if (level >= 51 && level <= 60) levelCounts.put("51-60", levelCounts.get("51-60") + 1);
            else if (level >= 61 && level <= 70) levelCounts.put("61-70", levelCounts.get("61-70") + 1);
            else if (level >= 71 && level <= 79) levelCounts.put("71-79", levelCounts.get("71-79") + 1);
            else if (level == 80) levelCounts.put("80", levelCounts.get("80") + 1);
        }

        // Profession counts
        Map<String, Integer> profCounts = getProfessionCounts();
        
        int totalMembers = members.size();

        EmbedBuilder eb = new EmbedBuilder();
        eb.setTitle("Guild Statistics");
        eb.setColor(Color.decode("#2b2d31"));
        eb.setFooter(GuildEmbedUtil.getGuildRealmIdentifier() + " - Last updated: " + new java.util.Date());

        // Add fields based on configuration
        if (ConfigHelper.isStatsBlockEnabled("show_faction_distribution")) {
            eb.addField("Faction Distribution", buildFactionGraph(factionCounts, totalMembers), false);
        }
        
        if (ConfigHelper.isStatsBlockEnabled("show_race_distribution")) {
            // Only show race distribution if we have race data
            String raceDistribution = raceCounts.isEmpty() ? "Race data not yet available" : buildRaceGraph(raceCounts, totalMembers);
            eb.addField("Race Distribution", raceDistribution, false);
        }
        
        if (ConfigHelper.isStatsBlockEnabled("show_class_distribution")) {
            eb.addField("Class Distribution", buildClassGraph(classCounts, totalMembers), false);
        }
        
        if (ConfigHelper.isStatsBlockEnabled("show_level_distribution")) {
            eb.addField("Level Distribution", buildLevelGraph(levelCounts, totalMembers), false);
        }
        
        if (ConfigHelper.isStatsBlockEnabled("show_professions")) {
            eb.addField("Professions", buildProfessionList(profCounts), false);
        }

        return eb.build();
    }

    private static String getFaction(String race) {
        if (race == null || race.trim().isEmpty()) return "Unknown";
        race = race.trim();
        Set<String> horde = new HashSet<>(Arrays.asList("Orc", "Undead", "Tauren", "Troll", "Blood Elf"));
        Set<String> alliance = new HashSet<>(Arrays.asList("Draenei", "Dwarf", "Gnome", "Human", "Night Elf"));
        if (horde.contains(race)) return "Horde";
        if (alliance.contains(race)) return "Alliance";
        return "Unknown";
    }

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
            case 0x0B: return "Druid";
            default:   return "Unknown";
        }
    }

    // Faction graph: Always show both factions in order (Alliance, Horde)
    private static String buildFactionGraph(Map<String, Integer> counts, int total) {
        if (total == 0) return "No data";
        
        final int MAX_BAR_LENGTH = 20;
        
        // Find max count to scale bars relative to it
        int maxCount = Math.max(counts.getOrDefault("Alliance", 0), counts.getOrDefault("Horde", 0));
        if (maxCount == 0) return "No data";
        
        int maxDigits = String.valueOf(maxCount).length();

        StringBuilder sb = new StringBuilder("```\n");
        for (String faction : Arrays.asList("Alliance", "Horde")) {
            int count = counts.getOrDefault(faction, 0);
            int pct = (int) Math.round((count * 100.0) / total);
            int bars = (int) Math.round((count / (double) maxCount) * MAX_BAR_LENGTH);
            
            sb.append(String.format("%-17s ", faction + ":"));
            for (int i = 0; i < (MAX_BAR_LENGTH - bars); i++) sb.append(" ");
            for (int i = 0; i < bars; i++) sb.append("=");
            sb.append(String.format(" %" + maxDigits + "d (%d%%)      \n", count, pct)); // Added 5 spaces after %%
        }
        sb.append("```");
        return sb.toString();
    }

    // Race graph: Alphabetical per faction, Alliance first then Horde
    private static String buildRaceGraph(Map<String, Integer> counts, int total) {
        if (total == 0) return "No data";
        
        final int MAX_BAR_LENGTH = 20;
        
        // Find max count across all races
        int maxCount = counts.values().stream().max(Integer::compareTo).orElse(0);
        if (maxCount == 0) return "No data";
        
        int maxDigits = String.valueOf(maxCount).length();

        List<String> allianceRaces = Arrays.asList("Draenei", "Dwarf", "Gnome", "Human", "Night Elf");
        List<String> hordeRaces = Arrays.asList("Blood Elf", "Orc", "Tauren", "Troll", "Undead");

        StringBuilder sb = new StringBuilder("```\n");
        
        // Alliance races
        for (String race : allianceRaces) {
            int count = counts.getOrDefault(race, 0);
            int pct = (int) Math.round((count * 100.0) / total);
            int bars = (int) Math.round((count / (double) maxCount) * MAX_BAR_LENGTH);
            
            sb.append(String.format("%-17s ", race + ":"));
            for (int i = 0; i < (MAX_BAR_LENGTH - bars); i++) sb.append(" ");
            for (int i = 0; i < bars; i++) sb.append("=");
            sb.append(String.format(" %" + maxDigits + "d (%d%%)      \n", count, pct));
        }
        
        // Horde races
        for (String race : hordeRaces) {
            int count = counts.getOrDefault(race, 0);
            int pct = (int) Math.round((count * 100.0) / total);
            int bars = (int) Math.round((count / (double) maxCount) * MAX_BAR_LENGTH);
            
            sb.append(String.format("%-17s ", race + ":"));
            for (int i = 0; i < (MAX_BAR_LENGTH - bars); i++) sb.append(" ");
            for (int i = 0; i < bars; i++) sb.append("=");
            sb.append(String.format(" %" + maxDigits + "d (%d%%)      \n", count, pct));
        }
        
        sb.append("```");
        return sb.toString();
    }

    // Class graph: Alphabetical order
    private static String buildClassGraph(Map<String, Integer> counts, int total) {
        if (total == 0) return "No data";
        
        final int MAX_BAR_LENGTH = 20;
        
        // Find max count across all classes
        int maxCount = counts.values().stream().max(Integer::compareTo).orElse(0);
        if (maxCount == 0) return "No data";
        
        int maxDigits = String.valueOf(maxCount).length();

        List<String> classOrder = Arrays.asList(
            "Druid", "Hunter", "Mage", "Paladin", 
            "Priest", "Rogue", "Shaman", "Warlock", "Warrior"
        );

        StringBuilder sb = new StringBuilder("```\n");
        for (String cls : classOrder) {
            int count = counts.getOrDefault(cls, 0);
            int pct = (int) Math.round((count * 100.0) / total);
            int bars = (int) Math.round((count / (double) maxCount) * MAX_BAR_LENGTH);
            
            sb.append(String.format("%-17s ", cls + ":"));
            for (int i = 0; i < (MAX_BAR_LENGTH - bars); i++) sb.append(" ");
            for (int i = 0; i < bars; i++) sb.append("=");
            sb.append(String.format(" %" + maxDigits + "d (%d%%)      \n", count, pct));
        }
        sb.append("```");
        return sb.toString();
    }

    // Level graph: Bracket order
    private static String buildLevelGraph(Map<String, Integer> counts, int total) {
        if (total == 0) return "No data";
        
        final int MAX_BAR_LENGTH = 20;
        
        // Find max count across all level brackets
        int maxCount = counts.values().stream().max(Integer::compareTo).orElse(0);
        if (maxCount == 0) return "No data";
        
        int maxDigits = String.valueOf(maxCount).length();

        List<String> levelOrder = Arrays.asList(
            "1-10", "11-20", "21-30", "31-40", "41-50",
            "51-60", "61-70", "71-79", "80"
        );

        StringBuilder sb = new StringBuilder("```\n");
        for (String bracket : levelOrder) {
            int count = counts.getOrDefault(bracket, 0);
            int pct = (int) Math.round((count * 100.0) / total);
            int bars = (int) Math.round((count / (double) maxCount) * MAX_BAR_LENGTH);
            
            sb.append(String.format("%-17s ", bracket + ":"));
            for (int i = 0; i < (MAX_BAR_LENGTH - bars); i++) sb.append(" ");
            for (int i = 0; i < bars; i++) sb.append("=");
            sb.append(String.format(" %" + maxDigits + "d (%d%%)      \n", count, pct));
        }
        sb.append("```");
        return sb.toString();
    }

    private static String buildProfessionList(Map<String, Integer> profCounts) {
        if (profCounts.isEmpty()) return "No professions registered.";
        
        final int MAX_BAR_LENGTH = 20;

        List<String> profs = new ArrayList<>(profCounts.keySet());
        Collections.sort(profs);
        
        // Find max count and total
        int maxCount = profCounts.values().stream().max(Integer::compareTo).orElse(1);
        int total = profCounts.values().stream().mapToInt(Integer::intValue).sum();
        int maxDigits = String.valueOf(maxCount).length();

        StringBuilder sb = new StringBuilder("```\n");
        for (String prof : profs) {
            int count = profCounts.get(prof);
            int pct = (int) Math.round((count * 100.0) / total);
            int bars = (int) Math.round((count / (double) maxCount) * MAX_BAR_LENGTH);
            
            sb.append(String.format("%-17s ", prof + ":"));
            for (int i = 0; i < (MAX_BAR_LENGTH - bars); i++) sb.append(" ");
            for (int i = 0; i < bars; i++) sb.append("=");
            sb.append(String.format(" %" + maxDigits + "d (%d%%)      \n", count, pct));
        }
        sb.append("```");
        return sb.toString();
    }

    private static Map<String, Integer> getProfessionCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        
        // Initialize all professions with 0
        counts.put("Alchemy", 0);
        counts.put("Blacksmithing", 0);
        counts.put("Enchanting", 0);
        counts.put("Engineering", 0);
        counts.put("Herbalism", 0);
        counts.put("Inscription", 0);
        counts.put("Jewelcrafting", 0);
        counts.put("Leatherworking", 0);
        counts.put("Mining", 0);
        counts.put("Skinning", 0);
        counts.put("Tailoring", 0);
        
        try {
            Collection<GuildMember> members = GuildDataCache.getInstance().getMembers(true);
            
            for (GuildMember m : members) {
                List<String> profs = ProfessionManager.getProfessions(m.name());
                for (String stored : profs) {
                    String prof = ProfessionManager.profName(stored);
                    counts.put(prof, counts.getOrDefault(prof, 0) + 1);
                }
            }
        } catch (Throwable t) {
            System.err.println("[GuildStats] Profession count error: " + t.getMessage());
        }
        
        return counts;
    }
}
