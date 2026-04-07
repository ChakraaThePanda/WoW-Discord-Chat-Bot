package wowchat;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * WatchdogMain - Manages wowchat.jar as a child process.
 *
 * Monitors two health files written by the bot:
 *
 *   wow.health     - updated every 30s with the last WoW roster request timestamp.
 *                    If stale: WoW connection is dead. Restart.
 *
 *   discord.health - updated every time a message is successfully sent to Discord.
 *                    If stale while wow.health is fresh: Discord relay broken. Restart.
 *
 * The bot's internal WoW reconnect loop handles normal server bounces on its own.
 * The watchdog only intervenes when the bot is truly stuck or silently broken.
 *
 * Built automatically by mvn package alongside wowchat.jar.
 * Run: java -jar watchdog.jar wowchat.jar wowchat.conf
 */
public final class WatchdogMain {

    private static final String WOW_HEALTH_FILE         = "wow.health";
    private static final String DISCORD_HEALTH_FILE     = "discord.health";

    private static final int    STARTUP_TIMEOUT_SECONDS = 120;
    private static final int    WOW_STALE_SECONDS       = 180;  // 3 min
    private static final int    DISCORD_STALE_SECONDS   = 600;  // 10 min
    private static final int    RESTART_DELAY_SECONDS   = 15;
    private static final int    CHECK_INTERVAL_MS       = 10000;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("[Watchdog] Usage: java -jar watchdog.jar wowchat.jar [wowchat.conf]");
            System.exit(1);
        }

        String jarFile   = args[0];
        String[] botArgs = new String[args.length - 1];
        System.arraycopy(args, 1, botArgs, 0, botArgs.length);

        System.out.println("[Watchdog] Starting. Managing: " + jarFile);

        //noinspection InfiniteLoopStatement
        while (true) {
            try { Files.deleteIfExists(Paths.get(WOW_HEALTH_FILE)); } catch (Throwable ignored) {}
            try { Files.deleteIfExists(Paths.get(DISCORD_HEALTH_FILE)); } catch (Throwable ignored) {}

            Process process = startProcess(jarFile, botArgs);
            System.out.println("[Watchdog] Bot process started.");

            pump(process.getInputStream(), false);
            pump(process.getErrorStream(), true);

            long    startTime     = System.currentTimeMillis();
            boolean shouldRestart = false;
            String  restartReason = "";

            while (true) {
                try {
                    int code = process.exitValue();
                    restartReason = "Bot process exited with code " + code;
                    shouldRestart = true;
                    break;
                } catch (IllegalThreadStateException e) {
                    // Still running
                }

                long now    = System.currentTimeMillis();
                long uptime = (now - startTime) / 1000L;
                File wowHf  = new File(WOW_HEALTH_FILE);

                if (!wowHf.exists()) {
                    if (uptime > STARTUP_TIMEOUT_SECONDS) {
                        restartReason = "wow.health never created after " + uptime + "s - bot stuck at startup";
                        shouldRestart = true;
                        break;
                    }
                    Thread.sleep(CHECK_INTERVAL_MS);
                    continue;
                }

                long wowTimestamp = readTimestamp(WOW_HEALTH_FILE);
                long wowStaleBy   = (now - wowTimestamp) / 1000L;

                if (wowStaleBy > WOW_STALE_SECONDS) {
                    restartReason = "wow.health stale by " + wowStaleBy + "s - WoW connection dead";
                    shouldRestart = true;
                    break;
                }

                File discordHf = new File(DISCORD_HEALTH_FILE);
                if (discordHf.exists()) {
                    long discordTimestamp = readTimestamp(DISCORD_HEALTH_FILE);
                    long discordStaleBy   = (now - discordTimestamp) / 1000L;
                    if (discordStaleBy > DISCORD_STALE_SECONDS && wowStaleBy < 60) {
                        restartReason = "discord.health stale by " + discordStaleBy + "s while WoW is active - Discord relay broken";
                        shouldRestart = true;
                        break;
                    }
                }

                Thread.sleep(CHECK_INTERVAL_MS);
            }

            if (shouldRestart) {
                System.out.println("[Watchdog] Restarting. Reason: " + restartReason);
                process.destroy();
                Thread.sleep(RESTART_DELAY_SECONDS * 1000L);
            }
        }
    }

    private static long readTimestamp(String path) {
        try {
            String raw = new String(Files.readAllBytes(Paths.get(path)), "UTF-8").trim();
            return Long.parseLong(raw);
        } catch (Throwable t) {
            System.err.println("[Watchdog] Could not read " + path + ": " + t.getMessage());
            return System.currentTimeMillis();
        }
    }

    private static Process startProcess(String jarFile, String[] args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-XX:+HeapDumpOnOutOfMemoryError");
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-Dlogback.configurationFile=logback.xml");
        cmd.add("-jar");
        cmd.add(jarFile);
        for (String a : args) cmd.add(a);
        return new ProcessBuilder(cmd)
            .directory(new File("."))
            .start();
    }

    private static void pump(final InputStream in, final boolean isErr) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (isErr) System.err.println(line);
                    else       System.out.println(line);
                }
            } catch (Throwable ignored) {}
        });
        t.setDaemon(true);
        t.start();
    }
}
