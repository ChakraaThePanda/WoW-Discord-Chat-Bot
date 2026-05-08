package wowchat.discord;

import com.typesafe.config.Config;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for extracting Discord IDs from guild member notes.
 * 
 * Supports configurable note location (officer notes or public notes)
 * and flexible Discord ID patterns.
 */
public final class DiscordIdExtractor {
    
    // Pattern to match Discord snowflake IDs (17-19 digits)
    // Flexible: can appear anywhere in the note, with or without "Discord:" prefix
    // Will match the first valid Discord ID found
    private static final Pattern DISCORD_ID_PATTERN = Pattern.compile(
        "(?:Discord:\\s*)?(\\d{17,19})(?:\\s|$)",
        Pattern.CASE_INSENSITIVE
    );
    
    private static String noteLocation = "officer"; // Default to officer notes
    
    private DiscordIdExtractor() {} // Utility class - no instantiation
    
    /**
     * Initialize the extractor with configuration.
     * Call this once during bot startup.
     */
    public static void initialize(Config config) {
        // Use ConfigHelper for consistent config reading with backward compatibility
        noteLocation = ConfigHelper.getDiscordLinkingNoteLocation();
        
        if (!noteLocation.equals("officer") && !noteLocation.equals("public")) {
            System.err.println("[DiscordIdExtractor] Invalid guildLinkingNoteLocation: " + noteLocation + ", defaulting to 'officer'");
            noteLocation = "officer";
        }
    }
    
    /**
     * Extract Discord ID from the configured note field.
     * 
     * @param member The guild member object (Scala GuildMember)
     * @return Discord ID string, or null if not found/invalid
     */
    public static String extractDiscordId(Object member) {
        if (member == null) {
            return null;
        }
        
        try {
            // Get the appropriate note based on config
            String note;
            if (noteLocation.equals("public")) {
                note = (String) member.getClass().getMethod("publicNote").invoke(member);
            } else {
                note = (String) member.getClass().getMethod("officerNote").invoke(member);
            }
            
            return extractDiscordId(note);
            
        } catch (Exception e) {
            System.err.println("[DiscordIdExtractor] Failed to extract Discord ID from member: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * Extract Discord ID from a note string.
     * 
     * @param note The note text to parse
     * @return Discord ID string, or null if not found/invalid
     */
    public static String extractDiscordId(String note) {
        if (note == null || note.trim().isEmpty()) {
            return null;
        }
        
        Matcher matcher = DISCORD_ID_PATTERN.matcher(note.trim());
        if (matcher.find()) {
            return matcher.group(1);
        }
        
        return null;
    }
    
    /**
     * Get the current note location setting.
     * 
     * @return "officer" or "public"
     */
    public static String getNoteLocation() {
        return noteLocation;
    }
}
