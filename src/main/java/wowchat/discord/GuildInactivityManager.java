package wowchat.discord;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigResolveOptions;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages Discord role assignment for inactive guild members.
 * 
 * Checks all linked characters for each Discord user. If ALL characters
 * have been offline longer than the configured threshold, assigns an
 * "inactive" role to that Discord user.
 */
public class GuildInactivityManager {

    private GuildInactivityManager() {}

    /**
     * Check for inactive players and assign/remove roles accordingly.
     * 
     * @param discordGuild The Discord guild
     * @param guildMembers Map of WoW guild members (guid -> GuildMember from Scala)
     * @return Count of inactive players (for roster stats)
     */
    public static int processInactivity(Guild discordGuild, Map<Long, Object> guildMembers) {
        if (!isInactivityEnabled()) {
            return 0;
        }

        float inactiveDays = getInactiveDays();
        String inactiveRoleId = getInactiveRoleId();
        
        if (inactiveRoleId == null || inactiveRoleId.isEmpty()) {
            System.err.println("[GuildInactivity] inactiveRoleId not configured - skipping");
            return 0;
        }

        Role inactiveRole;
        try {
            inactiveRole = discordGuild.getRoleById(inactiveRoleId);
            if (inactiveRole == null) {
                System.err.println("[GuildInactivity] Could not find role with ID: " + inactiveRoleId);
                return 0;
            }
        } catch (Exception e) {
            System.err.println("[GuildInactivity] Error getting role: " + e.getMessage());
            return 0;
        }

        // Get audit roleIds to know which Discord users to check
        List<String> auditRoleIds = GuildDiscordAuditPublisher.getAuditRoleIds();
        if (auditRoleIds.isEmpty()) {
            // No audit roles configured - can't determine which users to check
            return 0;
        }

        // Build map of Discord User ID -> List of their characters
        Map<String, List<Object>> userCharacters = new HashMap<>();
        for (Object member : guildMembers.values()) {
            try {
                // Access Scala case class fields via reflection
                String officerNote = (String) member.getClass().getMethod("officerNote").invoke(member);
                String discordId = extractDiscordId(officerNote);
                if (discordId != null && !discordId.isEmpty()) {
                    userCharacters.computeIfAbsent(discordId, k -> new ArrayList<>()).add(member);
                }
            } catch (Exception e) {
                // Skip this member if reflection fails
            }
        }

        int inactiveCount = 0;
        Set<String> processedUsers = new HashSet<>();

        // Check each Discord member with audit roles
        for (String roleIdStr : auditRoleIds) {
            try {
                Role role = discordGuild.getRoleById(roleIdStr);
                if (role == null) continue;

                for (Member discordMember : discordGuild.getMembersWithRoles(role)) {
                    String discordId = discordMember.getId();
                    
                    // Skip if already processed (user might have multiple audit roles)
                    if (processedUsers.contains(discordId)) {
                        continue;
                    }
                    processedUsers.add(discordId);

                    List<Object> characters = userCharacters.get(discordId);
                    
                    if (characters == null || characters.isEmpty()) {
                        // No linked characters - skip (audit handles this)
                        // But if they have the inactive role, remove it (shouldn't have it with no characters)
                        if (discordMember.getRoles().contains(inactiveRole)) {
                            try {
                                discordGuild.removeRoleFromMember(discordMember, inactiveRole).queue(
                                    success -> {},
                                    error -> System.err.println("[GuildInactivity] Failed to remove role from " 
                                        + discordMember.getEffectiveName() + ": " + error.getMessage())
                                );
                                System.out.println("[GuildInactivity] Removed inactive role from " 
                                    + discordMember.getEffectiveName() + " (no linked characters)");
                            } catch (Exception e) {
                                System.err.println("[GuildInactivity] Error removing role: " + e.getMessage());
                            }
                        }
                        continue;
                    }

                    // Check if ALL characters are inactive
                    boolean allInactive = true;
                    for (Object character : characters) {
                        try {
                            // Access isOnline() and lastLogoff() via reflection
                            boolean isOnline = (boolean) character.getClass().getMethod("isOnline").invoke(character);
                            float lastLogoff = (float) character.getClass().getMethod("lastLogoff").invoke(character);
                            
                            if (isOnline || lastLogoff <= inactiveDays) {
                                allInactive = false;
                                break;
                            }
                        } catch (Exception e) {
                            // If we can't check, assume active
                            allInactive = false;
                            break;
                        }
                    }

                    if (allInactive) {
                        // All characters inactive - assign role
                        if (!discordMember.getRoles().contains(inactiveRole)) {
                            try {
                                discordGuild.addRoleToMember(discordMember, inactiveRole).queue(
                                    success -> {},
                                    error -> System.err.println("[GuildInactivity] Failed to add role to " 
                                        + discordMember.getEffectiveName() + ": " + error.getMessage())
                                );
                                System.out.println("[GuildInactivity] Marked " + discordMember.getEffectiveName() 
                                    + " as inactive (all characters offline " + inactiveDays + "+ days)");
                            } catch (Exception e) {
                                System.err.println("[GuildInactivity] Error adding role: " + e.getMessage());
                            }
                        }
                        inactiveCount++;
                    } else {
                        // At least one character active - remove role if present
                        if (discordMember.getRoles().contains(inactiveRole)) {
                            try {
                                discordGuild.removeRoleFromMember(discordMember, inactiveRole).queue(
                                    success -> {},
                                    error -> System.err.println("[GuildInactivity] Failed to remove role from " 
                                        + discordMember.getEffectiveName() + ": " + error.getMessage())
                                );
                                System.out.println("[GuildInactivity] Removed inactive role from " 
                                    + discordMember.getEffectiveName() + " (has active characters)");
                            } catch (Exception e) {
                                System.err.println("[GuildInactivity] Error removing role: " + e.getMessage());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[GuildInactivity] Error processing role " + roleIdStr + ": " + e.getMessage());
            }
        }
        
        // REVERSE CHECK: Remove inactive role from anyone who has it but shouldn't
        // This catches manually assigned roles or people who became active
        try {
            for (Member member : discordGuild.getMembersWithRoles(inactiveRole)) {
                String discordId = member.getId();
                
                // Skip if we already processed this user (they're tracked and we handled them above)
                if (processedUsers.contains(discordId)) {
                    continue;
                }
                
                // This user has inactive role but isn't in our tracked set
                // Could be: no audit role, no characters, or was manually assigned
                // Remove the role since we're not tracking them
                try {
                    discordGuild.removeRoleFromMember(member, inactiveRole).queue(
                        success -> {},
                        error -> System.err.println("[GuildInactivity] Failed to remove role from " 
                            + member.getEffectiveName() + ": " + error.getMessage())
                    );
                    System.out.println("[GuildInactivity] Removed inactive role from " 
                        + member.getEffectiveName() + " (not tracked or manually assigned)");
                } catch (Exception e) {
                    System.err.println("[GuildInactivity] Error removing role: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("[GuildInactivity] Error in reverse check: " + e.getMessage());
        }

        return inactiveCount;
    }

    /**
     * Extract Discord ID from officer note (18-19 digit snowflake)
     */
    private static String extractDiscordId(String officerNote) {
        if (officerNote == null || officerNote.trim().isEmpty()) {
            return null;
        }
        String trimmed = officerNote.trim();
        // Discord IDs are 17-19 digits
        if (trimmed.matches("\\d{17,19}")) {
            return trimmed;
        }
        return null;
    }

    private static boolean isInactivityEnabled() {
        try {
            String configFile = System.getProperty("wowchat.configFile", "wowchat.conf");
            Config config = ConfigFactory.parseFile(new File(configFile))
                .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true));
            
            return config.hasPath("guildRoster.inactivity.enabled") 
                && config.getBoolean("guildRoster.inactivity.enabled");
        } catch (Throwable t) {
            return false;
        }
    }

    private static float getInactiveDays() {
        try {
            String configFile = System.getProperty("wowchat.configFile", "wowchat.conf");
            Config config = ConfigFactory.parseFile(new File(configFile))
                .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true));
            
            if (config.hasPath("guildRoster.inactivity.inactiveDays")) {
                int days = config.getInt("guildRoster.inactivity.inactiveDays");
                return (float) days;
            }
            return 30.0f; // Default 30 days
        } catch (Throwable t) {
            return 30.0f;
        }
    }

    private static String getInactiveRoleId() {
        try {
            String configFile = System.getProperty("wowchat.configFile", "wowchat.conf");
            Config config = ConfigFactory.parseFile(new File(configFile))
                .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true));
            
            if (config.hasPath("guildRoster.inactivity.inactiveRoleId")) {
                return config.getString("guildRoster.inactivity.inactiveRoleId");
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }
}
