package wowchat.discord;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigResolveOptions;
import wowchat.common.Packet;
import wowchat.game.GamePacketHandler;

import java.io.File;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

/**
 * AutoGuildInviteHandler
 *
 * Listens for trigger words in whispers and/or custom in-game channels.
 * When matched, sends a CMSG_GUILD_INVITE to the sender.
 *
 * Config (wowchat.conf):
 *   autoGuildInvite {
 *     triggers       = ["invite", "join"]
 *     exactMatch     = false
 *     whisperEnabled = true
 *     channels       = ["recruitment"]
 *   }
 *
 * If the block is absent or triggers is empty, the feature is disabled.
 * whisperEnabled and channels are independent - either or both can be active.
 */
public final class AutoGuildInviteHandler {

    private static volatile boolean      loaded         = false;
    private static volatile boolean      active         = false;
    private static volatile List<String> triggers       = Collections.emptyList();
    private static volatile boolean      exact          = false;
    private static volatile boolean      whisperEnabled = false;
    private static volatile List<String> channels       = Collections.emptyList();

    private static final java.util.concurrent.ConcurrentHashMap<Long, Boolean> pendingInvites =
        new java.util.concurrent.ConcurrentHashMap<>();

    private AutoGuildInviteHandler() {}

    public static List<String> getChannels() { return channels; }

    public static void onNameResolved(long guid, String name, GamePacketHandler handler) {
        if (!active) return;
        if (!pendingInvites.remove(guid, Boolean.TRUE)) return;
        handler.sendGuildInvite(name);
    }

    public static void init() {
        if (loaded) return;
        loaded = true;
        try {
            String configFile = System.getProperty("wowchat.configFile", "wowchat.conf");
            Config config = ConfigFactory.parseFile(new File(configFile))
                .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true));

            if (!config.hasPath("autoGuildInvite")) return;
            Config c = config.getConfig("autoGuildInvite");

            List<String> rawTriggers = c.hasPath("triggers")
                ? c.getStringList("triggers")
                : Collections.emptyList();
            List<String> parsed = new ArrayList<>();
            for (String t : rawTriggers) {
                String trimmed = t.trim().toLowerCase();
                if (!trimmed.isEmpty()) parsed.add(trimmed);
            }
            triggers = Collections.unmodifiableList(parsed);

            if (triggers.isEmpty()) {
                System.err.println("[AutoGuildInvite] triggers list is empty - feature disabled.");
                return;
            }

            exact          = c.hasPath("exactMatch") && c.getBoolean("exactMatch");
            whisperEnabled = !c.hasPath("whisperEnabled") || c.getBoolean("whisperEnabled");

            List<String> rawChannels = c.hasPath("channels")
                ? c.getStringList("channels")
                : Collections.emptyList();
            List<String> parsedChannels = new ArrayList<>();
            for (String ch : rawChannels) {
                String trimmed = ch.trim().toLowerCase();
                if (!trimmed.isEmpty()) parsedChannels.add(trimmed);
            }
            channels = Collections.unmodifiableList(parsedChannels);

            active = whisperEnabled || !channels.isEmpty();
            if (!active) {
                System.err.println("[AutoGuildInvite] Neither whisperEnabled nor channels configured - feature disabled.");
                return;
            }

            System.out.println("[AutoGuildInvite] Enabled. Triggers: " + triggers
                + " (" + (exact ? "exact match" : "contains") + ")"
                + " | Whisper: " + whisperEnabled
                + (channels.isEmpty() ? "" : " | Channels: " + channels));

        } catch (Throwable t) {
            System.err.println("[AutoGuildInvite] Config error: " + t.getMessage());
        }
    }

    // WotLK CHAT_MSG_CHANNEL structure:
    // type(1) + lang(4) + senderGuid(8) + skip(4) + channelName(string) + skip(8) + txtLen(4) + txt
    public static void handleIfChannel(Packet msg, GamePacketHandler handler) {
        if (!active || channels.isEmpty()) return;

        msg.byteBuf().markReaderIndex();
        try {
            msg.byteBuf().readByte();              // type
            int lang = msg.byteBuf().readIntLE();
            if (lang == -1) return;                // addon message

            long senderGuid = msg.byteBuf().readLongLE();
            msg.byteBuf().skipBytes(4);

            String rawChannel = msg.readString();
            String normalizedChannel = rawChannel.toLowerCase().replaceFirst("^\\d+\\.\\s*", "");
            if (!channels.contains(normalizedChannel)) return;

            msg.byteBuf().skipBytes(8);            // sender guid again

            int txtLen = msg.byteBuf().readIntLE();
            if (txtLen <= 0) return;
            String text = msg.byteBuf().readCharSequence(txtLen - 1, Charset.forName("UTF-8"))
                .toString().trim().toLowerCase();
            if (text.startsWith("{?")) text = text.substring(2);

            boolean matched = false;
            for (String trigger : triggers) {
                if (exact ? text.equals(trigger) : text.contains(trigger)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) return;

            scala.Option<wowchat.game.Player> playerOpt = handler.playerRoster().get(
                scala.runtime.BoxesRunTime.boxToLong(senderGuid));
            if (playerOpt == null || playerOpt.isEmpty()) {
                pendingInvites.put(senderGuid, Boolean.TRUE);
                handler.sendNameQuery(senderGuid);
                return;
            }
            handler.sendGuildInvite(playerOpt.get().name());

        } catch (Throwable t) {
            System.err.println("[AutoGuildInvite] Error handling channel message: " + t.getMessage());
        } finally {
            msg.byteBuf().resetReaderIndex();
        }
    }

    // WotLK CHAT_MSG_WHISPER structure:
    // type(1) + lang(4) + guid(8) + unknown(4) + guid_again(8) + txtLen(4) + txt(txtLen-1) + null(1) + tag(1)
    public static void handleIfWhisper(Packet msg, GamePacketHandler handler) {
        if (!active || !whisperEnabled) return;

        msg.byteBuf().markReaderIndex();
        try {
            msg.byteBuf().readByte();              // type
            int lang = msg.byteBuf().readIntLE();
            if (lang == -1) return;                // addon message

            long senderGuid = msg.byteBuf().readLongLE();
            msg.byteBuf().skipBytes(4);            // unknown
            msg.byteBuf().skipBytes(8);            // guid again

            int txtLen = msg.byteBuf().readIntLE();
            if (txtLen <= 0) return;
            String text = msg.byteBuf().readCharSequence(txtLen - 1, Charset.forName("UTF-8"))
                .toString().trim();
            if (text.startsWith("{?")) text = text.substring(2);
            text = text.toLowerCase();

            boolean matched = false;
            for (String trigger : triggers) {
                if (exact ? text.equals(trigger) : text.contains(trigger)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) return;

            scala.Option<wowchat.game.Player> playerOpt = handler.playerRoster().get(
                scala.runtime.BoxesRunTime.boxToLong(senderGuid));
            if (playerOpt == null || playerOpt.isEmpty()) {
                pendingInvites.put(senderGuid, Boolean.TRUE);
                handler.sendNameQuery(senderGuid);
                return;
            }
            handler.sendGuildInvite(playerOpt.get().name());

        } catch (Throwable t) {
            System.err.println("[AutoGuildInvite] Error handling whisper: " + t.getMessage());
        } finally {
            msg.byteBuf().resetReaderIndex();
        }
    }
}
