package wowchat.discord;

import wowchat.common.Packet;

/**
 * ProfessionCache - DEBUG version
 *
 * Receives SMSG_INSPECT_TALENT responses and dumps raw hex for packet structure analysis.
 * Hardcoded to only process Chakraa, Slawta, Rizo.
 */
public final class ProfessionCache {

    // Track pending inspects: guid -> character name, with timestamp for timeout detection
    private static final java.util.concurrent.ConcurrentHashMap<Long, String> pendingInspects =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<Long, Long> pendingTimestamps =
        new java.util.concurrent.ConcurrentHashMap<>();

    private static final java.util.concurrent.ScheduledExecutorService scheduler =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "profession-cache-timeout");
            t.setDaemon(true);
            return t;
        });

    static {
        // Check every 30s for timed-out inspect requests (no response after 15s = server ignored us)
        scheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            pendingTimestamps.forEach((guid, ts) -> {
                if (now - ts > 15000) {
                    String name = pendingInspects.remove(guid);
                    pendingTimestamps.remove(guid);
                    if (name != null) {
                        System.out.println("[ProfessionCache] WARNING: No response for " + name
                            + " (guid=" + guid + ") after 15s - server may be ignoring CMSG_INSPECT");
                    }
                }
            });
        }, 30, 30, java.util.concurrent.TimeUnit.SECONDS);
    }

    private ProfessionCache() {}

    public static void trackInspect(long guid, String name) {
        System.out.println("[ProfessionCache] Tracking inspect for " + name + " (guid=" + guid + ")");
        pendingInspects.put(guid, name);
        pendingTimestamps.put(guid, System.currentTimeMillis());
    }

    public static void handleInspectTalent(Packet msg) {
        msg.byteBuf().markReaderIndex();
        try {
            int readable = msg.byteBuf().readableBytes();

            // First 8 bytes are the target GUID (LE)
            if (readable < 8) {
                System.out.println("[ProfessionCache] SMSG_INSPECT_TALENT too small: " + readable + " bytes, ignoring");
                return;
            }

            long guid = msg.byteBuf().readLongLE();
            String name = pendingInspects.remove(guid);
            pendingTimestamps.remove(guid);

            System.out.println("[ProfessionCache] SMSG_INSPECT_TALENT received for guid=" + guid
                + " name=" + (name != null ? name : "UNKNOWN (not in pending)"));
            System.out.println("[ProfessionCache] Total packet size: " + readable + " bytes");
            System.out.println("[ProfessionCache] Remaining bytes after guid: " + msg.byteBuf().readableBytes());

            // Dump full packet hex (up to 200 bytes) for structure analysis
            msg.byteBuf().resetReaderIndex();
            int dumpSize = Math.min(readable, 200);
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < dumpSize; i++) {
                if (i > 0 && i % 16 == 0) hex.append("\n[ProfessionCache] ");
                hex.append(String.format("%02X ", msg.byteBuf().getByte(msg.byteBuf().readerIndex() + i)));
            }
            System.out.println("[ProfessionCache] Full hex dump:");
            System.out.println("[ProfessionCache] " + hex.toString().trim());
            if (readable > 200) {
                System.out.println("[ProfessionCache] ... (" + (readable - 200) + " more bytes truncated)");
            }

        } catch (Throwable t) {
            System.err.println("[ProfessionCache] Error handling inspect: " + t.getMessage());
            t.printStackTrace();
        } finally {
            msg.byteBuf().resetReaderIndex();
        }
    }
}
