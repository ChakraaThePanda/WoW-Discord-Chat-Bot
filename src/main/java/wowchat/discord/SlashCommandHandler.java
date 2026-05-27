package wowchat.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.components.buttons.Button;

import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * SlashCommandHandler
 *
 * Registers all slash commands and handles /prof interactions.
 * /who, /online, /gmotd are handled in Discord.scala for clean Scala interop.
 */
public final class SlashCommandHandler {

    private SlashCommandHandler() {}

    // -------------------------------------------------------------------------
    // Ban list - mirrors IgnoreManager pattern but in Java, stored in data/
    // -------------------------------------------------------------------------

    private static final String BAN_FILE = "data/banned_players.json";
    private static final Set<String> bannedPlayers = new TreeSet<>(); // TreeSet = always sorted

    public static void initBanList() {
        new File("data").mkdirs();
        File f = new File(BAN_FILE);
        if (!f.exists()) {
            saveBanList();
            return;
        }
        try {
            String content = new String(Files.readAllBytes(Paths.get(BAN_FILE)), "UTF-8");
            // Parse {"banned": ["name1", "name2"]}
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"banned\"\\s*:\\s*\\[(.*?)\\]", java.util.regex.Pattern.DOTALL)
                .matcher(content);
            if (m.find()) {
                String arr = m.group(1);
                for (String part : arr.split(",")) {
                    String name = part.trim().replaceAll("\"", "");
                    if (!name.isEmpty()) bannedPlayers.add(name.toLowerCase());
                }
            }
            System.out.println("[BanList] Loaded " + bannedPlayers.size() + " banned players.");
        } catch (Throwable t) {
            System.err.println("[BanList] Failed to load ban list: " + t.getMessage());
        }
    }

    public static boolean ban(String playerName) {
        String normalized = playerName.toLowerCase();
        if (bannedPlayers.contains(normalized)) return false;
        bannedPlayers.add(normalized);
        saveBanList();
        System.out.println("[BanList] Banned: " + playerName);
        return true;
    }

    public static boolean unban(String playerName) {
        String normalized = playerName.toLowerCase();
        if (!bannedPlayers.contains(normalized)) return false;
        bannedPlayers.remove(normalized);
        saveBanList();
        System.out.println("[BanList] Unbanned: " + playerName);
        return true;
    }

    public static boolean isBanned(String playerName) {
        return bannedPlayers.contains(playerName.toLowerCase());
    }

    public static List<String> getBannedPlayers() {
        return new ArrayList<>(bannedPlayers); // already sorted by TreeSet
    }

    private static void saveBanList() {
        try {
            new File("data").mkdirs();
            StringBuilder sb = new StringBuilder("{\"banned\": [");
            boolean first = true;
            for (String name : bannedPlayers) {
                if (!first) sb.append(", ");
                first = false;
                sb.append("\"").append(name).append("\"");
            }
            sb.append("]}");
            try (PrintWriter pw = new PrintWriter(new File(BAN_FILE), "UTF-8")) {
                pw.println(sb.toString());
            }
        } catch (Throwable t) {
            System.err.println("[BanList] Failed to save ban list: " + t.getMessage());
        }
    }

    public static void registerCommands(JDA jda) {
        jda.updateCommands().addCommands(
            Commands.slash("who", "Look up a player in the guild or on the server")
                .addOption(OptionType.STRING, "player", "Character name to look up", true),

            Commands.slash("online", "Show which guild members are currently online"),

            Commands.slash("gmotd", "Show the guild message of the day"),
            
            Commands.slash("ignore", "[Permissions Needed] Ignore a player's messages in WoW chat")
                .addOption(OptionType.STRING, "player", "Player name to ignore", true),
            
            Commands.slash("unignore", "[Permissions Needed] Unignore a previously ignored player")
                .addOption(OptionType.STRING, "player", "Player name to unignore", true),

            Commands.slash("ignored", "List all ignored players"),

            Commands.slash("ban", "[Permissions Needed] Ban a player (silences messages + auto-kicks from guild each cycle)")
                .addOption(OptionType.STRING, "player", "Player name to ban", true),

            Commands.slash("unban", "[Permissions Needed] Remove a player from the ban list")
                .addOption(OptionType.STRING, "player", "Player name to unban", true),

            Commands.slash("banned", "List all banned players"),

            Commands.slash("profession", "Manage character professions")
                .addSubcommands(
                    new SubcommandData("add", "Add or update a profession for a character (with optional skill level 1-450)")
                        .addOption(OptionType.STRING, "character", "Character name", true)
                        .addOption(OptionType.STRING, "profession", "Profession name", true, true)
                        .addOption(OptionType.INTEGER, "skill", "Skill level (1-450)", false),
                    new SubcommandData("remove", "Remove a profession from one of your characters")
                        .addOption(OptionType.STRING, "character", "Character name", true)
                        .addOption(OptionType.STRING, "profession", "Profession to remove", true, true),
                    new SubcommandData("list", "List all guild members with a given profession")
                        .addOption(OptionType.STRING, "profession", "Profession to search", true, true)
                )
        ).queue(
            cmds -> System.out.println("[SlashCommands] Registered " + cmds.size() + " slash commands."),
            err  -> System.err.println("[SlashCommands] Failed to register commands: " + err.getMessage())
        );
    }

    public static void handleAutoComplete(CommandAutoCompleteInteractionEvent event) {
        if (!event.getName().equals("profession")) return;
        String focused = event.getFocusedOption().getName();
        if (!focused.equals("profession")) return;

        String input = event.getFocusedOption().getValue().toLowerCase();
        List<Command.Choice> choices = ProfessionManager.VALID_PROFESSIONS.stream()
            .filter(p -> p.toLowerCase().startsWith(input))
            .limit(11)
            .map(p -> new Command.Choice(p, p))
            .collect(Collectors.toList());

        event.replyChoices(choices).queue();
    }

    public static void handleProfCommand(SlashCommandInteractionEvent event) {
        String sub = event.getSubcommandName();
        Guild guild = event.getGuild();
        String callerId = event.getUser().getId();
        String character = event.getOption("character") != null ? event.getOption("character").getAsString() : null;
        String profession = event.getOption("profession").getAsString();

        // Normalize profession capitalization
        String normalized = ProfessionManager.VALID_PROFESSIONS.stream()
            .filter(p -> p.equalsIgnoreCase(profession))
            .findFirst().orElse(null);

        if (normalized == null) {
            event.reply("Invalid profession. Valid options: " + String.join(", ", ProfessionManager.VALID_PROFESSIONS))
                .setEphemeral(true).queue();
            return;
        }

        String error;
        if ("add".equals(sub)) {
            Integer skill = event.getOption("skill") != null ? (int) event.getOption("skill").getAsLong() : null;
            error = ProfessionManager.add(character, normalized, skill, callerId, guild);
            if (error == null) {
                String msg = "Added **" + normalized + (skill != null ? " (" + skill + ")" : "") + "** for **" + character + "**.";
                event.reply(msg).setEphemeral(true).queue();
            } else {
                event.reply(error).setEphemeral(true).queue();
            }
        } else if ("remove".equals(sub)) {
            error = ProfessionManager.remove(character, normalized, callerId, guild);
            if (error == null) {
                event.reply("Removed **" + normalized + "** from **" + character + "**.")
                    .setEphemeral(true).queue();
            } else {
                event.reply(error).setEphemeral(true).queue();
            }
        } else if ("list".equals(sub)) {
            java.util.List<String[]> results = ProfessionManager.listByProfession(normalized);
            if (results.isEmpty()) {
                event.reply("No guild members have **" + normalized + "** registered.")
                    .setEphemeral(true).queue();
                return;
            }

            // Build response - look up Discord ID from guild roster officer notes
            StringBuilder sb = new StringBuilder("**" + normalized + "** (" + results.size() + "):\n");
            for (String[] entry : results) {
                String charName = entry[0];
                String stored   = entry[1];
                String display  = ProfessionManager.formatProfession(stored);

                // Try to find linked Discord ID via officer note
                String discordMention = "";
                try {
                    wowchat.common.Global$ g = wowchat.common.Global$.MODULE$;
                    scala.Option<wowchat.game.GameCommandHandler> gameOpt = g.game();
                    if (gameOpt != null && !gameOpt.isEmpty() && gameOpt.get() instanceof wowchat.game.GamePacketHandler) {
                        wowchat.game.GamePacketHandler gph = (wowchat.game.GamePacketHandler) gameOpt.get();
                        scala.collection.Iterator<wowchat.game.GuildMember> it = gph.getGuildRoster().valuesIterator();
                        while (it.hasNext()) {
                            wowchat.game.GuildMember m = it.next();
                            if (m.name().equalsIgnoreCase(charName)) {
                                String discordId = DiscordIdExtractor.extractDiscordId(m);
                                if (discordId != null) {
                                    discordMention = " (<@" + discordId + ">)";
                                }
                                break;
                            }
                        }
                    }
                } catch (Throwable ignored) {}

                // Capitalize char name for display
                String displayName = charName.substring(0, 1).toUpperCase() + charName.substring(1);
                sb.append("- **").append(displayName).append("**").append(discordMention)
                  .append(" \u2014 ").append(display).append("\n");
            }

            String content = sb.toString().trim();
            Button postButton = Button.primary("post_profession:" + profession, "Post to Channel");
            event.reply(content)
                .addActionRow(postButton)
                .setEphemeral(true)
                .queue();
        }
    }
    
    /**
     * Handle /ignored command - list all ignored players
     */
    public static void handleIgnoredCommand(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();
        
        // Get ignored players list (no owner check - anyone can view)
        scala.collection.immutable.List<String> scalaList = wowchat.discord.IgnoreManager.getIgnoredPlayers();
        java.util.List<String> ignoredPlayers = scala.collection.JavaConverters.seqAsJavaList(scalaList);
        
        if (ignoredPlayers.isEmpty()) {
            event.getHook().sendMessage("No players are currently ignored.").setEphemeral(true).queue();
            return;
        }
        
        // Build the list with capitalized first letters
        StringBuilder sb = new StringBuilder();
        sb.append("**Ignored (").append(ignoredPlayers.size()).append("):**\n");
        
        for (String name : ignoredPlayers) {
            // Capitalize first letter
            String displayName = name.substring(0, 1).toUpperCase() + name.substring(1);
            sb.append("- **").append(displayName).append("**\n");
        }
        
        String content = sb.toString().trim();
        Button postButton = Button.primary("post_ignored", "Post to Channel");
        event.getHook().sendMessage(content)
            .addActionRow(postButton)
            .setEphemeral(true)
            .queue();
    }
    
    /**
     * Handle /banned command - list all banned players
     */
    public static void handleBannedCommand(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();

        List<String> banned = getBannedPlayers();

        if (banned.isEmpty()) {
            event.getHook().sendMessage("No players are currently banned.").setEphemeral(true).queue();
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("**Banned (").append(banned.size()).append("):**\n");

        for (String name : banned) {
            String displayName = name.substring(0, 1).toUpperCase() + name.substring(1);
            sb.append("- **").append(displayName).append("**\n");
        }

        String content = sb.toString().trim();
        Button postButton = Button.primary("post_banned", "Post to Channel");
        event.getHook().sendMessage(content)
            .addActionRow(postButton)
            .setEphemeral(true)
            .queue();
    }

    /**
     * Handle button interactions for "Post to Channel"
     */
    public static void handleButtonInteraction(ButtonInteractionEvent event) {
        String buttonId = event.getComponentId();
        
        if (buttonId.startsWith("post_profession:")) {
            // Extract original message content
            String originalContent = event.getMessage().getContentRaw();
            
            // Post non-ephemerally to channel with mentions disabled to prevent pinging
            event.getChannel().sendMessage(originalContent)
                .setAllowedMentions(java.util.Collections.emptyList())
                .queue();
            
            // Acknowledge button click and update original message
            event.reply("Posted to channel!").setEphemeral(true).queue();
        } else if (buttonId.equals("post_who")) {
            // Extract original message content
            String originalContent = event.getMessage().getContentRaw();
            
            // Escape mentions to prevent pinging: <@123> becomes <@!123> or we can use allowed mentions
            // Post non-ephemerally to channel with mentions disabled
            event.getChannel().sendMessage(originalContent)
                .setAllowedMentions(java.util.Collections.emptyList())
                .queue();
            
            // Acknowledge button click
            event.reply("Posted to channel!").setEphemeral(true).queue();
        } else if (buttonId.equals("post_ignored")) {
            // Extract original message content
            String originalContent = event.getMessage().getContentRaw();
            
            // Post non-ephemerally to channel
            event.getChannel().sendMessage(originalContent).queue();
            
            // Acknowledge button click
            event.reply("Posted to channel!").setEphemeral(true).queue();
        } else if (buttonId.equals("post_banned")) {
            String originalContent = event.getMessage().getContentRaw();
            event.getChannel().sendMessage(originalContent).queue();
            event.reply("Posted to channel!").setEphemeral(true).queue();
        }
    }
}
