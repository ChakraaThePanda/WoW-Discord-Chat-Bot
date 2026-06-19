package wowchat.discord;

import wowchat.game.GuildMember;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks when each guild member was first seen at their current rank.
 * Used by the audit "promotionDue" sub-module to flag characters overdue for promotion.
 *
 * Data persists to data/guild_rank_history.json so timers survive bot restarts.
 * On first run (or after clearing the file), all members start with the current
 * timestamp — their actual guild join/rank-change date is not available via the
 * WoW protocol for Vanilla/TBC/WotLK/Cataclysm.
 */
public final class GuildRankTracker {

    private static final String FILE = "data/guild_rank_history.json";

    private static final Map<String, RankRecord> history = new LinkedHashMap<>();

    private GuildRankTracker() {}

    private static final class RankRecord {
        final int rankIndex;
        final long firstSeenAtMs;

        RankRecord(int rankIndex, long firstSeenAtMs) {
            this.rankIndex = rankIndex;
            this.firstSeenAtMs = firstSeenAtMs;
        }
    }

    /**
     * Called on each roster tick. Updates rank history for all current members.
     * A member whose rank changed (or who is new) gets their timer reset to now.
     * Members no longer in the guild are pruned from history.
     */
    public static synchronized void update(Collection<GuildMember> members) {
        load(); // always reload from file so manual edits are picked up each tick

        long now = System.currentTimeMillis();
        boolean changed = false;
        Set<String> currentKeys = new HashSet<>();

        for (GuildMember member : members) {
            String key = member.name().toLowerCase();
            currentKeys.add(key);
            RankRecord existing = history.get(key);
            if (existing == null || existing.rankIndex != member.rankIndex()) {
                history.put(key, new RankRecord(member.rankIndex(), now));
                changed = true;
            }
        }

        int sizeBefore = history.size();
        history.keySet().retainAll(currentKeys);
        if (history.size() != sizeBefore) changed = true;

        if (changed) save();
    }

    /**
     * Returns how many full hours a character has been at their current rank index.
     * Returns -1 if not tracked (should not happen after update() has been called).
     */
    public static synchronized long getHoursSinceRankAssigned(String charName, int rankIndex) {
        RankRecord rec = history.get(charName.toLowerCase());
        if (rec == null || rec.rankIndex != rankIndex) return -1;
        return (System.currentTimeMillis() - rec.firstSeenAtMs) / (1000L * 60 * 60);
    }


    private static void load() {
        history.clear();
        File f = new File(FILE);
        if (!f.exists()) return;
        try {
            String json = new String(Files.readAllBytes(Paths.get(FILE)), "UTF-8").trim();
            json = json.replaceAll("^\\{|\\}$", "").trim();
            if (json.isEmpty()) return;
            Pattern entry = Pattern.compile(
                "\"([^\"]+)\"\\s*:\\s*\\{\\s*\"rank\"\\s*:\\s*(\\d+)\\s*,\\s*\"since\"\\s*:\\s*(\\d+)\\s*\\}");
            Matcher m = entry.matcher(json);
            while (m.find()) {
                String key = m.group(1).toLowerCase();
                int rank = Integer.parseInt(m.group(2));
                long since = Long.parseLong(m.group(3));
                history.put(key, new RankRecord(rank, since));
            }
        } catch (Throwable t) {
            System.err.println("[GuildRankTracker] Failed to load rank history: " + t.getMessage());
        }
    }

    private static void save() {
        try {
            File dir = new File("data");
            if (!dir.exists()) dir.mkdirs();
            StringBuilder sb = new StringBuilder("{\n");
            boolean first = true;
            for (Map.Entry<String, RankRecord> e : history.entrySet()) {
                if (!first) sb.append(",\n");
                first = false;
                sb.append("  \"").append(e.getKey()).append("\": {")
                  .append("\"rank\": ").append(e.getValue().rankIndex)
                  .append(", \"since\": ").append(e.getValue().firstSeenAtMs)
                  .append("}");
            }
            sb.append("\n}");
            Files.write(Paths.get(FILE), sb.toString().getBytes("UTF-8"));
        } catch (Throwable t) {
            System.err.println("[GuildRankTracker] Failed to save rank history: " + t.getMessage());
        }
    }
}
