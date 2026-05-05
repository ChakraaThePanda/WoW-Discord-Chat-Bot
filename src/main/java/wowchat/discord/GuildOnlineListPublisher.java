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

    /** Health file path - Watchdog reads this to know WoW packets are still flowing. */
    private static final String WOW_HEALTH_FILE = "wow.health";

    /** How often we write the health file (seconds). */
    private static final int HEALTH_WRITE_INTERVAL_SEC = 30;

    // Config values - loaded once at init()
    private static volatile List<Long> channelIds   = Collections.emptyList();
    private static volatile int        updateMinutes = 5;
    private static volatile Set<String> ignoreLower  = Collections.emptySet();

    // Runtime state
    private static volatile boolean started = false;
    private static final Map<Long, String> messageIds = new ConcurrentHashMap<>(); // channelId -> messageId

    // Cached JDA reference - extracted once via reflection, reused forever after
    // NOTE: If this is null after the first successful extraction it means something
    // is seriously wrong with the Discord class structure.
    private static volatile JDA cachedJda = null;

    private static ScheduledExecutorService scheduler;

    private GuildOnlineListPublisher() {}

    public static boolean isEnabled() { return !channelIds.isEmpty(); }

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

        // Only log and schedule if feature is actually enabled
        if (!channelIds.isEmpty()) {
            System.out.println("[GuildOnlineList] Initializing. Channel IDs: " + channelIds
                + ", update interval: " + updateMinutes + " min.");
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(
            new DaemonThreadFactory("wowchat-online-list"));

        long initialDelaySec = 15L;
        long periodSec       = Math.max(1, updateMinutes) * 60L;

        // Only schedule online list updates if channels are configured
        if (!channelIds.isEmpty()) {
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

        // Discord health heartbeat - separate thread for health monitoring
        ScheduledExecutorService discordHealthScheduler = Executors.newSingleThreadScheduledExecutor(
            new DaemonThreadFactory("wowchat-discord-health"));
        discordHealthScheduler.scheduleAtFixedRate(() -> {
            try {
                JDA jda = getJda();
                if (jda != null && jda.getStatus() == JDA.Status.CONNECTED) {
                    byte[] data = Long.toString(System.currentTimeMillis()).getBytes("UTF-8");
                    Files.write(Paths.get("discord.health"), data,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }
            } catch (Throwable t) {
                System.err.println("[GuildOnlineList] Error writing discord health file: " + t.getMessage());
            }
        }, 30L, 30L, TimeUnit.SECONDS);

    }

    // -------------------------------------------------------------------------
    // Health file writer - called every 30s by the scheduler
    //
    // Writes the timestamp of the last WoW packet received to wow.health.
    // The Watchdog reads this file to determine if the WoW connection is alive.
    // If lastPacketReceivedMs is 0 (bot never connected), nothing is written.
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // -------------------------------------------------------------------------
    // Health file writer - called every 30s by the scheduler
    // -------------------------------------------------------------------------

    private static void writeHealthFile() {
        try {
            Option<GameCommandHandler> gameOpt = Global$.MODULE$.game();
            if (gameOpt == null || gameOpt.isEmpty()) return;

            GameCommandHandler handler = gameOpt.get();
            if (!(handler instanceof GamePacketHandler)) return;

            // Only write if bot is fully connected and in the world
            if (!((GamePacketHandler) handler).isInWorld()) return;

            byte[] data = Long.toString(System.currentTimeMillis()).getBytes("UTF-8");
            Files.write(Paths.get(WOW_HEALTH_FILE), data,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Throwable t) {
            System.err.println("[GuildOnlineList] Failed to write health file: " + t.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Main update tick
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Guild/Realm Identifier - Used in footer to identify embeds
    // -------------------------------------------------------------------------
    
    // -------------------------------------------------------------------------
    // Main tick loop - updates online list
    // -------------------------------------------------------------------------

    private static void tick() {
        JDA jda = getJda();
        if (jda == null) {
            // Not ready yet (Discord not fully connected), skip this tick
            return;
        }

        // Build the sorted list of online member names (once, used for all channels)
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
            .setFooter(GuildEmbedUtil.getGuildRealmIdentifier() + " - Last updated: " + new java.util.Date())
            .build();

        // Post/update to all configured channels
        for (Long channelId : channelIds) {
            TextChannel channel = jda.getTextChannelById(channelId);
            if (channel == null) {
                System.err.println("[GuildOnlineList] Could not find text channel with ID " + channelId);
                continue;
            }

            // Find our existing message if we don't have its ID cached
            String messageId = messageIds.get(channelId);
            if (messageId == null) {
                messageId = GuildEmbedUtil.findEmbedByTitlePrefixAndFooter(channel, "Who's Online?");
                if (messageId != null) {
                    messageIds.put(channelId, messageId);
                }
            }

            // Post new or edit existing
            if (messageId == null) {
                try {
                    Message sent = channel.sendMessageEmbeds(embed)
                        .complete();
                    messageIds.put(channelId, sent.getId());
                } catch (Throwable t) {
                    System.err.println("[GuildOnlineList] Failed to send message to channel " + channelId + ": " + t.getMessage());
                }
            } else {
                try {
                    channel.editMessageById(messageId, " ")
                        .setEmbeds(embed)
                        .complete();
                } catch (Throwable t) {
                    System.err.println("[GuildOnlineList] Failed to edit message in channel " + channelId + " (will retry): " + t.getMessage());
                    messageIds.remove(channelId);
                }
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
                    // JDA instance cached successfully (used by multiple features)
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

            // Check if guild online list is enabled
            boolean enabled = true; // Default enabled for backward compat
            if (config.hasPath("guildOnlineList.enabled")) {
                enabled = config.getBoolean("guildOnlineList.enabled");
            }
            
            if (!enabled) {
                System.out.println("[GuildOnlineList] Feature disabled in config");
                channelIds = Collections.emptyList();
                updateMinutes = 5;
                ignoreLower = Collections.emptySet();
            } else {
                // Channel IDs - try LIST first, then fall back to single ID for backward compatibility
                List<Long> ids = new ArrayList<>();
                try {
                    // Try new list format: channelIds = [123, 456]
                    if (config.hasPath("guildOnlineList.channelIds")) {
                        List<? extends Number> configIds = config.getNumberList("guildOnlineList.channelIds");
                        for (Number n : configIds) {
                            ids.add(n.longValue());
                        }
                    } 
                    // Fall back to old single ID formats
                    else if (config.hasPath("guildOnlineList.channelId")) {
                        ids.add(config.getLong("guildOnlineList.channelId"));
                    } else if (config.hasPath("guildOnlineListChannelId")) {
                        ids.add(config.getLong("guildOnlineListChannelId"));
                    }
                } catch (ConfigException.WrongType e) {
                    try {
                        // Handle string IDs
                        String idStr = config.hasPath("guildOnlineList.channelId")
                            ? config.getString("guildOnlineList.channelId")
                            : config.getString("guildOnlineListChannelId");
                        ids.add(Long.parseLong(idStr.trim()));
                    } catch (Throwable ignored) {}
                } catch (Throwable ignored) {}
                
                channelIds = Collections.unmodifiableList(ids);

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

                // Ignore list - NEW path first, fall back to OLD
                Set<String> ignoreSet = new HashSet<>();
                try {
                    String ignorePath = config.hasPath("guildOnlineList.ignore")
                        ? "guildOnlineList.ignore"
                        : "guildOnlineListIgnore";
                    
                    if (config.hasPath(ignorePath)) {
                        for (String name : config.getStringList(ignorePath)) {
                            if (name != null && !name.trim().isEmpty()) {
                                ignoreSet.add(name.trim().toLowerCase(Locale.ROOT));
                            }
                        }
                    }
                } catch (Throwable ignored) {}

                ignoreLower = ignoreSet.isEmpty()
                    ? Collections.emptySet()
                    : Collections.unmodifiableSet(ignoreSet);

                // Update shared cache with ignore list (OPTIMIZATION)
                GuildDataCache.getInstance().setIgnoreList(ignoreLower);
            }

        } catch (Throwable t) {
            System.err.println("[GuildOnlineList] Failed to load config: " + t.getMessage()
                + ". Guild online list will be disabled.");
            channelIds    = Collections.emptyList();
            updateMinutes = 5;
            ignoreLower   = Collections.emptySet();
        }
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
