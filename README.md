# WoW-Discord-Chat-Bot
Recomp of a bot from [@fjaros](https://github.com/fjaros/wowchat) with added functionalities for use on 3.3.5 WOTLK Private Servers, mainly.
If you have any questions, feel free to add me on Discord: Chakraa

---

### Features
* Guild Online List — auto-updating Discord embed showing online members with level, race, class and zone, including Discord mention if linked
* Guild Roster — auto-updating Discord embed showing all guild members grouped by Discord account with level, race and class per character
* Guild Sync Audit — auto-updating Discord embed showing Discord members without a linked WoW character, grouped by role
* Guild Role Sync — automatically assigns Discord roles based on in-game guild rank via officer notes
* Whisper Invite — players whisper the bot a trigger word and automatically receive a guild invite in-game
* DM Auto-Reply — bot replies to anyone who DMs it with a configurable message
* Bot Status Rotation — cycles through custom Discord status messages with {online-members} token
* show_discord_username — per-channel option to hide Discord usernames when relaying to WoW
* Watchdog — separate process monitors bot health and restarts it automatically if the WoW connection dies or Discord relay breaks silently
* Guild Death Ping — automatically mentions a configured Discord role when a guild member dies in-game
* and more!

---

### How to Use

#### Running
* Run `run.bat` (or `run.sh` on Linux) — this starts the watchdog which manages the bot automatically
* To run on Windows startup, place a shortcut to `run.bat` in your Windows Startup folder (`shell:startup`)

#### Linking Discord accounts (for Guild Role Sync, Roster and Audit)
1. Enable Developer Mode in Discord: User Settings → Advanced → Developer Mode
2. Right-click your username anywhere in Discord and click **Copy User ID**
3. Log into WoW, open the Guild panel, find your character, and paste your User ID into your **Officer Note**
4. The bot syncs automatically every few minutes

#### Configuration
All features are documented and optional in `wowchat.conf`. Features are disabled by default — set the relevant channel IDs or enable flags to activate them.
