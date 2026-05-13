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

import java.util.List;
import java.util.stream.Collectors;

/**
 * SlashCommandHandler
 *
 * Registers all slash commands and handles /prof interactions.
 * /who, /online, /gmotd are handled in Discord.scala for clean Scala interop.
 */
public final class SlashCommandHandler {

    private SlashCommandHandler() {}

    public static void registerCommands(JDA jda) {
        jda.updateCommands().addCommands(
            Commands.slash("who", "Look up a player in the guild or on the server")
                .addOption(OptionType.STRING, "player", "Character name to look up", true),

            Commands.slash("online", "Show which guild members are currently online"),

            Commands.slash("gmotd", "Show the guild message of the day"),
            
            Commands.slash("ignore", "[Bot Owner Only] Ignore a player's messages in WoW chat")
                .addOption(OptionType.STRING, "player", "Player name to ignore", true),
            
            Commands.slash("unignore", "[Bot Owner Only] Unignore a previously ignored player")
                .addOption(OptionType.STRING, "player", "Player name to unignore", true),

            Commands.slash("ignored", "List all ignored players"),

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
        scala.collection.immutable.List<String> scalaList = wowchat.common.IgnoreManager.getIgnoredPlayers();
        java.util.List<String> ignoredPlayers = scala.collection.JavaConverters.seqAsJavaList(scalaList);
        
        if (ignoredPlayers.isEmpty()) {
            event.getHook().sendMessage("✅ No players are currently ignored.").setEphemeral(true).queue();
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
        }
    }
}
