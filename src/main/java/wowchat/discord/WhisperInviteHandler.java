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
 * WhisperInviteHandler
 *
 * Listens for in-game whispers containing one of the configured trigger words.
 * When matched, sends a CMSG_GUILD_INVITE packet to invite the whisperer.
 *
 * Config (wowchat.conf):
 *   whisperInvite {
 *     enabled    = true
 *     triggers   = ["invite", "join"]
 *     exactMatch = false
 *   }
 */
public final class WhisperInviteHandler {

    private static volatile boolean      loaded   = false;
    private static volatile boolean      enabled  = false;
    private static volatile List<String> triggers = Collections.emptyList();
    private static volatile boolean      exact    = false;

    // Guids waiting for a name query response before we can send the invite
    private static final java.util.concurrent.ConcurrentHashMap<Long, Boolean> pendingInvites =
        new java.util.concurrent.ConcurrentHashMap<>();

    private WhisperInviteHandler() {}

    // Called from handle_SMSG_NAME_QUERY after a name is resolved
    public static void onNameResolved(long guid, String name, GamePacketHandler handler) {
        if (!enabled) return;
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

            if (!config.hasPath("whisperInvite")) return;
            Config c = config.getConfig("whisperInvite");

            enabled = c.hasPath("enabled") && c.getBoolean("enabled");
            if (!enabled) return;

            List<String> rawTriggers = c.hasPath("triggers")
                ? c.getStringList("triggers")
                : Collections.emptyList();
            List<String> parsed = new ArrayList<>();
            for (String t : rawTriggers) {
                String trimmed = t.trim().toLowerCase();
                if (!trimmed.isEmpty()) parsed.add(trimmed);
            }
            triggers = Collections.unmodifiableList(parsed);

            exact = c.hasPath("exactMatch") && c.getBoolean("exactMatch");

            if (triggers.isEmpty()) {
                enabled = false;
                System.err.println("[WhisperInvite] triggers list is empty — feature disabled.");
                return;
            }

            System.out.println("[WhisperInvite] Enabled. Triggers: " + triggers
                + " (" + (exact ? "exact match" : "contains") + ")");

        } catch (Throwable t) {
            System.err.println("[WhisperInvite] Config error: " + t.getMessage());
            enabled = false;
        }
    }

    // WotLK SMSG_MESSAGECHAT structure:
    //   type(1) + lang(4) + guid(8) + unknown(4) + guid_again(8) + txtLen(4) + txt(txtLen-1) + null(1) + tag(1)
    public static void handleIfWhisper(Packet msg, GamePacketHandler handler) {
        msg.byteBuf().markReaderIndex();
        try {
            if (!enabled) return;

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
            // Strip server-injected prefix
            if (text.startsWith("{?")) text = text.substring(2);
            text = text.toLowerCase();

            // Check against triggers
            boolean matched = false;
            for (String trigger : triggers) {
                if (exact ? text.equals(trigger) : text.contains(trigger)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) return;

            // Get sender name from player roster via guid
            scala.Option<wowchat.game.Player> playerOpt = handler.playerRoster().get(
                scala.runtime.BoxesRunTime.boxToLong(senderGuid));
            if (playerOpt == null || playerOpt.isEmpty()) {
                // Name not known yet — queue invite until name query resolves
                pendingInvites.put(senderGuid, Boolean.TRUE);
                handler.sendNameQuery(senderGuid);
                return;
            }
            handler.sendGuildInvite(playerOpt.get().name());

        } catch (Throwable t) {
            System.err.println("[WhisperInvite] Error handling whisper: " + t.getMessage());
        } finally {
            msg.byteBuf().resetReaderIndex();
        }
    }
}
