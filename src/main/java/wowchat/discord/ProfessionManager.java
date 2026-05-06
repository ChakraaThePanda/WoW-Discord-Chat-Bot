package wowchat.discord;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigResolveOptions;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * ProfessionManager
 *
 * Stores up to 2 primary professions per character name.
 * Persists to professions.json in the bot working directory.
 *
 * Permissions:
 *   - A Discord user can only modify characters linked to their own Discord ID
 *     (via officer note in the guild roster).
 *   - Users with a configured officer role can modify any character.
 */
public final class ProfessionManager {

    public static final List<String> VALID_PROFESSIONS = Collections.unmodifiableList(Arrays.asList(
        "Alchemy", "Blacksmithing", "Enchanting", "Engineering",
        "Herbalism", "Inscription", "Jewelcrafting", "Leatherworking",
        "Mining", "Skinning", "Tailoring"
    ));

    public static final int MAX_PROFESSIONS = 2;
    private static final String FILE = "professions.json";
    private static final long STALE_THRESHOLD_MS = 60 * 60 * 1000; // 1 hour

    // charName (lowercased) -> sorted list of professions
    private static final Map<String, List<String>> data = new LinkedHashMap<>();
    
    // charName (lowercased) -> last update timestamp (for cleanup grace period)
    private static final Map<String, Long> lastUpdated = new LinkedHashMap<>();

    // Discord role IDs allowed to manage professions for any character.
    // Loaded from profCommandRoleIds in wowchat.conf.
    private static final Set<String> commandRoleIds = new LinkedHashSet<>();

    static {
        load();
        loadCommandRoles();
    }

    private ProfessionManager() {}

    /**
     * Called on every guild roster update. Removes entries for characters
     * no longer in the guild, keeping professions.json clean over time.
     */
    /**
     * Returns a list of entries for characters that have the given profession.
     * Each entry is a String[2]: [charName, storedValue e.g. "Herbalism:289"]
     */
    public static List<String[]> listByProfession(String profession) {
        List<String[]> results = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : data.entrySet()) {
            for (String stored : e.getValue()) {
                if (profName(stored).equalsIgnoreCase(profession)) {
                    results.add(new String[]{ e.getKey(), stored });
                }
            }
        }
        // Sort alphabetically by char name
        results.sort((a, b) -> a[0].compareToIgnoreCase(b[0]));
        return results;
    }

    /**
     * Called on every guild roster update. 
     * 
     * For members currently in the guild: updates their timestamp to prevent cleanup.
     * For members not in the guild: only removes if entry is older than 1 hour.
     * 
     * This grace period allows characters that leave and rejoin quickly to keep their professions.
     */
    public static void cleanupStaleEntries(java.util.Set<String> currentMemberNamesLower) {
        long now = System.currentTimeMillis();
        boolean changed = false;
        
        // Update timestamps for current members
        for (String currentMember : currentMemberNamesLower) {
            if (data.containsKey(currentMember)) {
                lastUpdated.put(currentMember, now);
                changed = true; // Timestamp refresh requires save
            }
        }
        
        // Remove entries for non-members that are older than 1 hour
        java.util.Iterator<String> it = data.keySet().iterator();
        while (it.hasNext()) {
            String key = it.next();
            if (!currentMemberNamesLower.contains(key)) {
                Long timestamp = lastUpdated.get(key);
                long age = timestamp == null ? 0 : (now - timestamp);
                if (timestamp == null || age > STALE_THRESHOLD_MS) {
                    it.remove();
                    lastUpdated.remove(key);
                    changed = true;
                    System.out.println("[ProfessionManager] Removed stale entry for " + key 
                        + " (not in guild for " + (age / 1000 / 60) + " minutes)");
                }
            }
        }
        
        if (changed) {
            save();
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public static List<String> getProfessions(String charName) {
        return data.getOrDefault(charName.toLowerCase(), Collections.emptyList());
    }

    /** Returns display string e.g. "Herbalism (289)" or "Herbalism" */
    public static String formatProfession(String stored) {
        int sep = stored.indexOf(':');
        if (sep < 0) return stored;
        return stored.substring(0, sep) + " (" + stored.substring(sep + 1) + ")";
    }

    /** Returns just the profession name without skill level */
    public static String profName(String stored) {
        int sep = stored.indexOf(':');
        return sep < 0 ? stored : stored.substring(0, sep);
    }

    /**
     * Sets a profession for a character.
     * Returns null on success, or an error message string on failure.
     */
    public static String add(String charName, String profession, Integer skillLevel, String callerDiscordId, Guild guild) {
        if (!VALID_PROFESSIONS.contains(profession)) {
            return "Invalid profession. Valid options: " + String.join(", ", VALID_PROFESSIONS);
        }
        if (skillLevel != null && (skillLevel < 1 || skillLevel > 450)) {
            return "Skill level must be between 1 and 450.";
        }
        if (!isInGuild(charName)) {
            return "**" + charName + "** is not a member of the guild.";
        }
        if (!canModify(callerDiscordId, charName, guild)) {
            return "You can only add professions for your own linked characters.";
        }

        String stored = skillLevel != null ? profession + ":" + skillLevel : profession;
        String key = charName.toLowerCase();
        List<String> profs = new ArrayList<>(data.getOrDefault(key, new ArrayList<>()));

        // Check if profession already exists (update it)
        boolean updated = false;
        for (int i = 0; i < profs.size(); i++) {
            if (profName(profs.get(i)).equals(profession)) {
                profs.set(i, stored);
                updated = true;
                break;
            }
        }

        if (!updated) {
            if (profs.size() >= MAX_PROFESSIONS) {
                List<String> profNames = new ArrayList<>();
                for (String p : profs) profNames.add(profName(p));
                return charName + " already has " + MAX_PROFESSIONS + " professions (" + String.join(", ", profNames) + "). Remove one first.";
            }
            profs.add(stored);
        }

        Collections.sort(profs);
        data.put(key, profs);
        lastUpdated.put(key, System.currentTimeMillis());
        save();
        return null;
    }

    /**
     * Removes a profession from a character.
     * Returns null on success, or an error message string on failure.
     */
    public static String remove(String charName, String profession, String callerDiscordId, Guild guild) {
        if (!canModify(callerDiscordId, charName, guild)) {
            return "You can only remove professions from your own linked characters.";
        }

        String key = charName.toLowerCase();
        List<String> profs = new ArrayList<>(data.getOrDefault(key, new ArrayList<>()));

        boolean removed = profs.removeIf(p -> profName(p).equals(profession));
        if (!removed) {
            return charName + " does not have " + profession + ".";
        }

        if (profs.isEmpty()) {
            data.remove(key);
            lastUpdated.remove(key);
        } else {
            data.put(key, profs);
            lastUpdated.put(key, System.currentTimeMillis());
        }
        save();
        return null;
    }

    // -------------------------------------------------------------------------
    // Permission check
    // -------------------------------------------------------------------------

    public static boolean hasCommandRole(String discordId, Guild guild) {
        if (guild == null || discordId == null) return false;
        if (commandRoleIds.isEmpty()) return false;
        try {
            Member member = guild.retrieveMemberById(discordId).complete();
            if (member == null) return false;
            return member.getRoles().stream().anyMatch(r -> commandRoleIds.contains(r.getId()));
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isInGuild(String charName) {
        try {
            wowchat.common.Global$ g = wowchat.common.Global$.MODULE$;
            scala.Option<wowchat.game.GameCommandHandler> gameOpt = g.game();
            if (gameOpt == null || gameOpt.isEmpty()) return false;
            wowchat.game.GameCommandHandler handler = gameOpt.get();
            if (!(handler instanceof wowchat.game.GamePacketHandler)) return false;
            wowchat.game.GamePacketHandler gph = (wowchat.game.GamePacketHandler) handler;
            scala.collection.mutable.Map<Object, wowchat.game.GuildMember> roster = gph.getGuildRoster();
            scala.collection.Iterator<wowchat.game.GuildMember> it = roster.valuesIterator();
            while (it.hasNext()) {
                if (it.next().name().equalsIgnoreCase(charName)) return true;
            }
        } catch (Throwable t) {
            System.err.println("[ProfessionManager] isInGuild check error: " + t.getMessage());
        }
        return false;
    }

    private static boolean canModify(String callerDiscordId, String charName, Guild guild) {
        // Server owner bypasses all checks
        if (guild != null && guild.getOwnerId().equals(callerDiscordId)) {
            return true;
        }
        
        if (hasCommandRole(callerDiscordId, guild)) return true;

        // Check if charName is linked to callerDiscordId via guild roster officer notes
        try {
            wowchat.common.Global$ g = wowchat.common.Global$.MODULE$;
            scala.Option<wowchat.game.GameCommandHandler> gameOpt = g.game();
            if (gameOpt == null || gameOpt.isEmpty()) return false;
            wowchat.game.GameCommandHandler handler = gameOpt.get();
            if (!(handler instanceof wowchat.game.GamePacketHandler)) return false;
            wowchat.game.GamePacketHandler gph = (wowchat.game.GamePacketHandler) handler;
            scala.collection.mutable.Map<Object, wowchat.game.GuildMember> roster = gph.getGuildRoster();
            scala.collection.Iterator<wowchat.game.GuildMember> it = roster.valuesIterator();
            while (it.hasNext()) {
                wowchat.game.GuildMember member = it.next();
                String memberDiscordId = DiscordIdExtractor.extractDiscordId(member);
                if (member.name().equalsIgnoreCase(charName)
                        && callerDiscordId.equals(memberDiscordId)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            System.err.println("[ProfessionManager] Permission check error: " + t.getMessage());
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    private static void load() {
        try {
            File f = new File(FILE);
            if (!f.exists()) return;
            String json = new String(Files.readAllBytes(Paths.get(FILE)), "UTF-8").trim();
            json = json.replaceAll("^\\{|\\}$", "").trim();
            if (json.isEmpty()) return;
            
            // Split by top-level entries (character names)
            for (String entry : json.split(",\\s*(?=\"[^\"]+\"\\s*:)")) {
                entry = entry.trim();
                int firstColon = entry.indexOf(":");
                if (firstColon < 0) continue;
                
                String key = entry.substring(0, firstColon).trim().replaceAll("\"", "").toLowerCase();
                String val = entry.substring(firstColon + 1).trim();
                
                List<String> profs = new ArrayList<>();
                Long timestamp = null;
                
                // Check if new format (object with professions + lastUpdated) or old format (array)
                if (val.startsWith("{")) {
                    // New format: { "professions": [...], "lastUpdated": 123456 }
                    String profsPattern = "\"professions\"\\s*:\\s*\\[([^\\]]*)\\]";
                    java.util.regex.Pattern p = java.util.regex.Pattern.compile(profsPattern);
                    java.util.regex.Matcher m = p.matcher(val);
                    if (m.find()) {
                        String profsList = m.group(1);
                        for (String prof : profsList.split(",")) {
                            String cleanProf = prof.trim().replaceAll("\"", "");
                            if (!cleanProf.isEmpty()) profs.add(cleanProf);
                        }
                    }
                    
                    String timestampPattern = "\"lastUpdated\"\\s*:\\s*(\\d+)";
                    p = java.util.regex.Pattern.compile(timestampPattern);
                    m = p.matcher(val);
                    if (m.find()) {
                        timestamp = Long.parseLong(m.group(1));
                    }
                } else {
                    // Old format: ["Prof1", "Prof2"]
                    val = val.replaceAll("^\\[|\\]$", "").trim();
                    for (String prof : val.split(",")) {
                        String cleanProf = prof.trim().replaceAll("\"", "");
                        if (!cleanProf.isEmpty()) profs.add(cleanProf);
                    }
                }
                
                Collections.sort(profs);
                if (!profs.isEmpty()) {
                    data.put(key, profs);
                    lastUpdated.put(key, timestamp != null ? timestamp : System.currentTimeMillis());
                }
            }
            System.out.println("[ProfessionManager] Loaded " + data.size() + " character profession entries.");
        } catch (Throwable t) {
            System.err.println("[ProfessionManager] Failed to load professions.json: " + t.getMessage());
            t.printStackTrace();
        }
    }

    private static void save() {
        try {
            StringBuilder sb = new StringBuilder("{\n");
            boolean first = true;
            for (Map.Entry<String, List<String>> e : data.entrySet()) {
                if (!first) sb.append(",\n");
                first = false;
                
                String charName = e.getKey();
                List<String> profs = e.getValue();
                Long timestamp = lastUpdated.get(charName);
                
                sb.append("  \"").append(charName).append("\": {\n");
                sb.append("    \"professions\": [");
                for (int i = 0; i < profs.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append("\"").append(profs.get(i)).append("\"");
                }
                sb.append("],\n");
                sb.append("    \"lastUpdated\": ").append(timestamp != null ? timestamp : System.currentTimeMillis()).append("\n");
                sb.append("  }");
            }
            sb.append("\n}");
            Files.write(Paths.get(FILE), sb.toString().getBytes("UTF-8"),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Throwable t) {
            System.err.println("[ProfessionManager] Failed to save professions.json: " + t.getMessage());
        }
    }

    private static void loadCommandRoles() {
        try {
            String configFile = System.getProperty("wowchat.configFile", "wowchat.conf");
            Config config = ConfigFactory.parseFile(new File(configFile))
                .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true));
            
            // Try NEW path first, fall back to OLD
            String configPath = config.hasPath("professions.commandRoleIds")
                ? "professions.commandRoleIds"
                : "profCommandRoleIds";
            
            if (!config.hasPath(configPath)) return;
            
            for (String roleId : config.getStringList(configPath)) {
                roleId = roleId.trim();
                if (!roleId.isEmpty()) commandRoleIds.add(roleId);
            }
            if (!commandRoleIds.isEmpty()) {
                System.out.println("[ProfessionManager] Command roles loaded: " + commandRoleIds);
            }
        } catch (Throwable t) {
            System.err.println("[ProfessionManager] Could not load officer roles: " + t.getMessage());
        }
    }
    
    /**
     * Check if profession cache is enabled in config
     */
    public static boolean isCacheEnabled() {
        try {
            String configFile = System.getProperty("wowchat.configFile", "wowchat.conf");
            Config config = ConfigFactory.parseFile(new File(configFile))
                .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true));
            
            // Check if cache is explicitly disabled
            if (config.hasPath("professions.cache.enabled")) {
                return config.getBoolean("professions.cache.enabled");
            }
            // Default enabled for backward compat
            return true;
        } catch (Throwable t) {
            return true; // Default enabled
        }
    }
}
