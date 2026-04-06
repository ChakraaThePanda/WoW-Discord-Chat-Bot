/*
 * GuildOnlineListPublisher.java - hardened version.
 *
 * CHANGES FROM ORIGINAL GPT VERSION:
 *
 * 1. JDA extraction via reflection made robust:
 *    The original used raw reflection with no logging if it failed, so the feature
 *    would silently stop working. Now: reflection result is cached after first success,
 *    failures are logged clearly, and the scheduler keeps retrying rather than dying.
 *
 * 2. Scheduler is properly named and isolated:
 *    The original DaemonThreadFactory was fine but the scheduler could swallow
 *    exceptions silently. Now we log any unexpected error in tick() clearly.
 *
 * 3. Config loading is more defensive:
 *    channelId parsing is cleaner with a single try/catch pattern.
 *
 * 4. Message marker changed to a single recognizable invisible char sequence.
 *    Functionally identical to original but easier to reason about.
 *
 * 5. Each entry now shows full info matching the ?online command format:
 *    "Symastic (40 Warrior in Stranglethorn Vale)"
 *    Uses the same Classes().valueOf() and GameResources$.AREA() lookups
 *    that GamePacketHandler uses for ?online.
 *
 * 6. Health file writer: writes lastRequestedGuildRoster() timestamp every 30s.
 *    The Watchdog reads this file to detect dead WoW connections without
 *    relying on log lines (which stop flowing during guild silence).
 *
 * NOTE ON THE REFLECTION HACK:
 *    The JDA field in Discord.java is private with no public accessor. Since we're
 *    working with a decompiled JAR and can't modify Discord.java directly, reflection
 *    is the correct workaround here. We cache the result so reflection only runs once.
 */
package wowchat.discord;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import java.awt.Color;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import scala.Option;
import wowchat.common.Global$;
import wowchat.discord.DiscordDMHandler;
import wowchat.game.GameCommandHandler;
import wowchat.game.GamePacketHandler;
import wowchat.game.GuildMember;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.lang.reflect.Field;
import java.util.*;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class GuildOnlineListPublisher {

    // -------------------------------------------------------------------------
    // Race cache - populated from SMSG_NAME_QUERY responses via cacheRace()
    // -------------------------------------------------------------------------
    private static final java.util.concurrent.ConcurrentHashMap<String, String> raceCache =
        new java.util.concurrent.ConcurrentHashMap<>();

    public static void cacheRace(String name, String race) {
        if (name != null && !name.isEmpty() && race != null && !race.isEmpty()) {
            raceCache.put(name.toLowerCase(Locale.ROOT), race);
        }
    }

    static String getRace(String name) {
        if (name == null || name.isEmpty()) return "";
        String race = raceCache.get(name.toLowerCase(Locale.ROOT));
        return race != null ? race + " " : "";
    }

    /**
     * Invisible Unicode marker appended to our message so we can identify it
     * in channel history without affecting visible content.
     * 10 zero-width spaces - same as original.
     */
    private static final String MARKER = "\u200b\u200b\u200b\u200b\u200b\u200b\u200b\u200b\u200b\u200b";

    /** Health file path - Watchdog reads this to know WoW packets are still flowing. */
    private static final String HEALTH_FILE = "watchdog.health";

    /** How often we write the health file (seconds). */
    private static final int HEALTH_WRITE_INTERVAL_SEC = 30;

    // Config values - loaded once at init()
    private static volatile long    channelId     = 0L;
    private static volatile int     updateMinutes = 5;
    private static volatile Set<String> ignoreLower = Collections.emptySet();

    // Status rotation config
    private static volatile List<String> statusMessages   = Collections.emptyList();
    /** When true, GamePacketHandler.updateGuildiesOnline() is suppressed via bytecode patch. */
    public  static volatile boolean      rotationActive   = false;
    private static volatile int          statusRotateSecs = 60;
    private static volatile int          statusIndex      = 0;

    // Runtime state
    private static volatile boolean started     = false;
    private static volatile String  messageId   = null;

    // Cached JDA reference - extracted once via reflection, reused forever after
    // NOTE: If this is null after the first successful extraction it means something
    // is seriously wrong with the Discord class structure.
    private static volatile JDA cachedJda = null;

    private static ScheduledExecutorService scheduler;

    private GuildOnlineListPublisher() {}

    public static boolean isEnabled() { return channelId > 0L; }

    // -------------------------------------------------------------------------
    // Init - called once from WoWChat.main()
    // -------------------------------------------------------------------------

    public static int getUpdateMinutes() {
        return updateMinutes;
    }

    public static synchronized void init() {
        if (started) return;
        started = true;

        loadConfig();

        System.out.println("[GuildOnlineList] Initializing. Channel ID: " + channelId
            + ", update interval: " + updateMinutes + " min.");

        scheduler = Executors.newSingleThreadScheduledExecutor(
            new DaemonThreadFactory("wowchat-online-list"));

        long initialDelaySec = 15L;
        long periodSec       = Math.max(1, updateMinutes) * 60L;

        // Only schedule online list updates if a channel is configured
        if (channelId > 0L) {
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    tick();
                } catch (Throwable t) {
                    System.err.println("[GuildOnlineList] Unexpected error in update tick: " + t.getMessage());
                }
            }, initialDelaySec, periodSec, TimeUnit.SECONDS);
        }

        // Health file writer - runs every 30s regardless of guild list update interval
        scheduler.scheduleAtFixedRate(() -> {
            try {
                writeHealthFile();
            } catch (Throwable t) {
                System.err.println("[GuildOnlineList] Error writing health file: " + t.getMessage());
            }
        }, 10L, HEALTH_WRITE_INTERVAL_SEC, TimeUnit.SECONDS);

        // Status rotation - only scheduled if messages are configured
        rotationActive = !statusMessages.isEmpty();
        if (!statusMessages.isEmpty()) {
            long rotatePeriod = Math.max(5, statusRotateSecs);
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    rotateStatus();
                } catch (Throwable t) {
                    System.err.println("[GuildOnlineList] Error rotating status: " + t.getMessage());
                }
            }, 15L, rotatePeriod, TimeUnit.SECONDS);
        }
    }

    // -------------------------------------------------------------------------
    // Health file writer - called every 30s by the scheduler
    //
    // Writes the timestamp of the last WoW packet received to watchdog.health.
    // The Watchdog reads this file to determine if the WoW connection is alive.
    // If lastPacketReceivedMs is 0 (bot never connected), nothing is written.
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Status rotation - cycles through guildStatusMessages on a fixed timer.
    // Replaces {online-members} with "N Guild Member" or "N Guild Members" (pluralized).
    // Calls Discord.changeGuildStatus() directly, same as the bot does internally.
    // -------------------------------------------------------------------------

    private static void rotateStatus() {
        if (statusMessages.isEmpty()) return;

        // Pick next message in rotation
        String template = statusMessages.get(statusIndex % statusMessages.size());
        statusIndex++;

        // Resolve {online-members} if present - returns "1 Guild Member" or "N Guild Members"
        String message = template;
        if (message.contains("{online-members}")) {
            int count = getOnlineCount();
            String membersLabel = count == 1 ? "1 Guild Member" : count + " Guild Members";
            message = message.replace("{online-members}", membersLabel);
        }

        // Push to Discord presence
        try {
            Global$.MODULE$.discord().changeGuildStatus(message);
        } catch (Throwable t) {
            System.err.println("[GuildOnlineList] Failed to set Discord status: " + t.getMessage());
        }
    }

    // Returns the number of online guild members (excluding the bot character).
    private static int getOnlineCount() {
        try {
            Option<GameCommandHandler> gameOpt = Global$.MODULE$.game();
            if (gameOpt == null || gameOpt.isEmpty()) return 0;
            GameCommandHandler handler = gameOpt.get();
            if (!(handler instanceof GamePacketHandler)) return 0;
            // buildGuildiesOnline() excludes the bot character, same as ?online
            // getGuildiesOnlineMessage(true) returns "N Guild Members online" - parse the count from there
            String msg = ((GamePacketHandler) handler).getGuildiesOnlineMessage(true);
            // format: "N Guild Members online" or "1 Guild Member online"
            String[] parts = msg.split(" ");
            if (parts.length > 0) return Integer.parseInt(parts[0]);
        } catch (Throwable t) {
            // ignore - return 0 safely
        }
        return 0;
    }

    private static void writeHealthFile() {
        try {
            Option<GameCommandHandler> gameOpt = Global$.MODULE$.game();
            if (gameOpt == null || gameOpt.isEmpty()) return;

            GameCommandHandler handler = gameOpt.get();
            if (!(handler instanceof GamePacketHandler)) return;

            long lastRoster = ((GamePacketHandler) handler).lastRequestedGuildRoster();
            if (lastRoster == 0L) return; // Bot hasn't connected to WoW yet

            // Write the timestamp of the last guild roster request.
            // The bot requests a fresh roster from the WoW server every 60s while connected.
            // If this timestamp stops updating, the WoW connection is dead.
            byte[] data = Long.toString(lastRoster).getBytes("UTF-8");
            Files.write(Paths.get(HEALTH_FILE), data,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Throwable t) {
            System.err.println("[GuildOnlineList] Failed to write health file: " + t.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Main update tick
    // -------------------------------------------------------------------------

    private static void tick() {
        JDA jda = getJda();
        if (jda == null) {
            // Not ready yet (Discord not fully connected), skip this tick
            return;
        }

        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel == null) {
            System.err.println("[GuildOnlineList] Could not find text channel with ID " + channelId
                + ". Check your guildOnlineListChannelId config value.");
            return;
        }

        // Build the sorted list of online member names
        List<String> onlineNames = getOnlineNames();
        Collections.sort(onlineNames, String.CASE_INSENSITIVE_ORDER);

        String listContent;
        String title;
        if (onlineNames.isEmpty()) {
            listContent = "No Guild Members currently online.";
            title = "Who's Online? (0)";
        } else {
            StringBuilder sb = new StringBuilder();
            for (String name : onlineNames) {
                sb.append("- ").append(name).append("\n");
            }
            listContent = sb.toString().trim();
            title = "Who's Online? (" + onlineNames.size() + ")";
        }

        MessageEmbed embed = new EmbedBuilder()
            .setTitle(title)
            .setDescription(listContent)
            .setColor(Color.decode("#2b2d31"))
            .setFooter("Last updated: " + new java.util.Date())
            .build();

        // Find our existing message if we don't have its ID cached
        if (messageId == null) {
            messageId = findExistingMessageId(channel);
        }

        if (messageId == null) {
            try {
                Message sent = channel.sendMessageEmbeds(embed)
                    .setContent(MARKER)
                    .complete();
                messageId = sent.getId();
            } catch (Throwable t) {
                System.err.println("[GuildOnlineList] Failed to send message: " + t.getMessage());
            }
        } else {
            try {
                channel.editMessageById(messageId, MARKER)
                    .setEmbeds(embed)
                    .complete();
            } catch (Throwable t) {
                System.err.println("[GuildOnlineList] Failed to edit message (will retry): " + t.getMessage());
                messageId = null;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Get list of online guild member entries, formatted to match ?online output:
    //   "Symastic (40 Warrior in Stranglethorn Vale)"
    //
    // Delegates directly to GamePacketHandler.buildGuildiesOnline() which already
    // handles all class name and zone name resolution correctly using the same
    // Scala collection iterators as the rest of the bot. No reimplementation needed.
    //
    // buildGuildiesOnline() returns one of:
    //   "Currently no Guild Members online."   - when empty (no newline)
    //   "Currently 2 guildies online:\n        - when populated
    //    Name1 (40 Warrior in Zone), Name2 (...)"
    //
    // We strip the header line, split the comma-separated entries into a List
    // so tick() can sort them alphabetically and join with newlines.
    // -------------------------------------------------------------------------

    private static List<String> getOnlineNames() {
        try {
            Option<GameCommandHandler> gameOpt = Global$.MODULE$.game();
            if (gameOpt == null || gameOpt.isEmpty()) return Collections.emptyList();

            GameCommandHandler handler = gameOpt.get();
            if (!(handler instanceof GamePacketHandler)) return Collections.emptyList();

            String raw = ((GamePacketHandler) handler).buildGuildiesOnline();
            if (raw == null || raw.isEmpty()) return Collections.emptyList();

            // No newline means "Currently no Guild Members online." - nothing to show
            if (!raw.contains("\n")) return Collections.emptyList();

            // Strip the "Currently N guildies online:\n" header, split on ", "
            String entriesPart = raw.substring(raw.indexOf('\n') + 1).trim();
            if (entriesPart.isEmpty()) return Collections.emptyList();

            List<String> result = new ArrayList<>(Arrays.asList(entriesPart.split(", ")));

            // Apply ignore list if configured
            if (!ignoreLower.isEmpty()) {
                result.removeIf(entry -> {
                    // entry is "Name (40 Warrior in Zone)" - extract just the name
                    int paren = entry.indexOf(" (");
                    String name = paren > 0 ? entry.substring(0, paren) : entry;
                    return ignoreLower.contains(name.trim().toLowerCase(Locale.ROOT));
                });
            }

            // Inject race into each entry: "Name (40 Warrior in Zone)" -> "Name (40 Night Elf Warrior in Zone)"
            result.replaceAll(entry -> {
                int parenOpen = entry.indexOf(" (");
                if (parenOpen < 0) return entry;
                String charName = entry.substring(0, parenOpen);
                String race = getRace(charName);
                if (race.isEmpty()) return entry;
                int innerStart = parenOpen + 2;
                if (entry.substring(innerStart).startsWith("Level ")) innerStart += 6;
                int spaceAfterLevel = entry.indexOf(' ', innerStart);
                if (spaceAfterLevel < 0) return entry;
                return entry.substring(0, spaceAfterLevel + 1) + race + entry.substring(spaceAfterLevel + 1);
            });

            // Append Discord mention if officer note contains a valid Discord ID
            scala.collection.Map<Object, GuildMember> roster = ((GamePacketHandler) handler).guildRoster();
            result.replaceAll(entry -> {
                int parenOpen = entry.indexOf(" (");
                String charName = parenOpen > 0 ? entry.substring(0, parenOpen) : entry;
                scala.collection.Iterator<GuildMember> it = roster.valuesIterator();
                while (it.hasNext()) {
                    GuildMember m = it.next();
                    if (m.name().equalsIgnoreCase(charName.trim())) {
                        String note = m.officerNote() != null ? m.officerNote().trim() : "";
                        if (note.matches("\\d{17,19}")) {
                            return entry + " (<@" + note + ">)";
                        }
                        break;
                    }
                }
                return entry;
            });

            return result;
        } catch (Throwable t) {
            System.err.println("[GuildOnlineList] Error reading guild roster: " + t.getMessage());
            return Collections.emptyList();
        }
    }

    // -------------------------------------------------------------------------
    // Find our previously posted message in channel history
    // -------------------------------------------------------------------------

    private static String findExistingMessageId(TextChannel channel) {
        try {
            List<Message> history = channel.getHistory().retrievePast(100).complete();
            if (history == null) return null;

            for (Message msg : history) {
                User author = msg.getAuthor();
                if (author == null || !author.isBot()) continue;

                String content = msg.getContentRaw();
                if (content != null && content.endsWith(MARKER)) {
                    return msg.getId();
                }
            }
        } catch (Throwable t) {
            System.err.println("[GuildOnlineList] Error searching message history: " + t.getMessage());
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // JDA extraction via reflection - cached after first success
    //
    // WHY REFLECTION: Discord.java has a private `jda` field and no public
    // accessor. Since this is a decompiled JAR we cannot modify Discord.java
    // directly. Reflection is the correct workaround. We cache the result so
    // this overhead only happens once per process lifetime.
    // -------------------------------------------------------------------------

    public static JDA getJda() {
        // Return cached instance if we already found it
        if (cachedJda != null) return cachedJda;

        Discord discord = Global$.MODULE$.discord();
        if (discord == null) return null;

        try {
            for (Field field : discord.getClass().getDeclaredFields()) {
                if (!JDA.class.isAssignableFrom(field.getType())) continue;

                field.setAccessible(true);
                Object value = field.get(discord);

                if (value instanceof JDA) {
                    cachedJda = (JDA) value; // Cache it - never reflect again
                    System.out.println("[GuildOnlineList] JDA instance found and cached via reflection.");
                    return cachedJda;
                }
            }

            // If we get here, no JDA field was found - this is a structural problem
            System.err.println("[GuildOnlineList] WARNING: Could not find JDA field in Discord class. "
                + "Guild online list will not work. This may indicate a Discord class structure change.");

        } catch (Throwable t) {
            System.err.println("[GuildOnlineList] Reflection error accessing JDA: " + t.getMessage());
        }

        return null;
    }

    // -------------------------------------------------------------------------
    // Config loading
    // -------------------------------------------------------------------------

    private static void loadConfig() {
        try {
            String configFile = System.getProperty("wowchat.configFile", "wowchat.conf");
            Config config = ConfigFactory.parseFile(new File(configFile))
                .resolve(com.typesafe.config.ConfigResolveOptions.defaults().setAllowUnresolved(true));

            // Channel ID - try as long first, then as string
            channelId = 0L;
            try {
                channelId = config.getLong("guildOnlineListChannelId");
            } catch (ConfigException.WrongType e) {
                try {
                    channelId = Long.parseLong(config.getString("guildOnlineListChannelId").trim());
                } catch (Throwable ignored) {
                    channelId = 0L;
                }
            } catch (ConfigException.Missing e) {
                channelId = 0L;
            }

            // Update interval - new key with fallback to old key for backward compatibility
            try {
                if (config.hasPath("discordFeaturesUpdateMinutes")) {
                    updateMinutes = config.getInt("discordFeaturesUpdateMinutes");
                } else {
                    updateMinutes = config.getInt("guildOnlineListUpdateMinutes");
                }
                if (updateMinutes < 1) updateMinutes = 1;
            } catch (ConfigException e) {
                updateMinutes = 5;
            }

            // Ignore list
            Set<String> ignoreSet = new HashSet<>();
            try {
                if (config.hasPath("guildOnlineListIgnore")) {
                    for (String name : config.getStringList("guildOnlineListIgnore")) {
                        if (name != null && !name.trim().isEmpty()) {
                            ignoreSet.add(name.trim().toLowerCase(Locale.ROOT));
                        }
                    }
                }
            } catch (Throwable ignored) {}

            ignoreLower = ignoreSet.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(ignoreSet);

            // Status rotation messages
            List<String> msgList = new ArrayList<>();
            try {
                if (config.hasPath("guildStatusMessages")) {
                    for (String msg : config.getStringList("guildStatusMessages")) {
                        if (msg != null && !msg.trim().isEmpty()) {
                            msgList.add(msg.trim());
                        }
                    }
                }
            } catch (Throwable ignored) {}
            statusMessages = msgList.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(msgList);

            // Status rotation interval
            try {
                statusRotateSecs = config.getInt("guildStatusRotateSeconds");
                if (statusRotateSecs < 5) statusRotateSecs = 5;
            } catch (ConfigException e) {
                statusRotateSecs = 60;
            }

        } catch (Throwable t) {
            System.err.println("[GuildOnlineList] Failed to load config: " + t.getMessage()
                + ". Guild online list will be disabled.");
            channelId     = 0L;
            updateMinutes = 5;
            ignoreLower   = Collections.emptySet();
        }

        // Register DM auto-reply handler
        String configFile = System.getProperty("wowchat.configFile", "wowchat.conf");
        DiscordDMHandler.register(configFile);

        // Initialize whisper invite handler
        WhisperInviteHandler.init();
    }

    // -------------------------------------------------------------------------
    // Thread factory - produces daemon threads so they don't block JVM shutdown
    // -------------------------------------------------------------------------

    public static final class DaemonThreadFactory implements ThreadFactory {
        private static final AtomicInteger IDX = new AtomicInteger(1);
        private final String prefix;

        public DaemonThreadFactory(String prefix) {
            this.prefix = (prefix != null && !prefix.trim().isEmpty())
                ? prefix.trim() : "wowchat";
        }

        public DaemonThreadFactory() {
            this("wowchat");
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName(prefix + "-" + IDX.getAndIncrement());
            return t;
        }
    }
}
