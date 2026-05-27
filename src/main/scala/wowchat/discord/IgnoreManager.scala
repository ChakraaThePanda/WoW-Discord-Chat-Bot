package wowchat.discord

import java.io.{File, PrintWriter}
import scala.io.Source
import scala.collection.mutable

/**
 * IgnoreManager - Bot-side ignore list for filtering WoW messages
 * 
 * Stores ignored player names in ignored_players.json
 * Format: {"ignored": ["player1", "player2", ...]}
 * All comparisons are case-insensitive
 */
object IgnoreManager {

  private val DATA_DIR  = "data"
  private val IGNORE_FILE = s"$DATA_DIR/ignored_players.json"
  private val ignoredPlayers = mutable.Set[String]()

  // Load ignore list on startup
  def init(): Unit = {
    new File(DATA_DIR).mkdirs()
    val file = new File(IGNORE_FILE)
    if (file.exists()) {
      try {
        val source = Source.fromFile(file)
        val content = source.mkString
        source.close()
        
        // Simple JSON parsing - extract array from {"ignored": ["name1", "name2"]}
        val arrayPattern = """"ignored"\s*:\s*\[(.*?)\]""".r
        arrayPattern.findFirstMatchIn(content) match {
          case Some(m) =>
            val arrayContent = m.group(1)
            val names = arrayContent.split(",")
              .map(_.trim.replaceAll("\"", ""))
              .filter(_.nonEmpty)
              .map(_.toLowerCase)
            
            ignoredPlayers.clear()
            ignoredPlayers ++= names
            println(s"[IgnoreManager] Loaded ${ignoredPlayers.size} ignored players from JSON")
          case None =>
            println("[IgnoreManager] No 'ignored' array found in JSON")
        }
      } catch {
        case e: Exception =>
          System.err.println(s"[IgnoreManager] Failed to load ignore list: ${e.getMessage}")
      }
    } else {
      println(s"[IgnoreManager] No ignore file found, starting with empty list")
      // Create empty JSON file
      saveToFile()
    }
  }

  // Check if a player is ignored
  def isIgnored(playerName: String): Boolean = {
    ignoredPlayers.contains(playerName.toLowerCase)
  }

  // Add a player to ignore list
  def ignore(playerName: String): Boolean = {
    val normalized = playerName.toLowerCase
    if (ignoredPlayers.contains(normalized)) {
      false // Already ignored
    } else {
      ignoredPlayers += normalized
      saveToFile()
      println(s"[IgnoreManager] Added '$playerName' to ignore list")
      true // Newly ignored
    }
  }

  // Remove a player from ignore list
  def unignore(playerName: String): Boolean = {
    val normalized = playerName.toLowerCase
    if (ignoredPlayers.contains(normalized)) {
      ignoredPlayers -= normalized
      saveToFile()
      println(s"[IgnoreManager] Removed '$playerName' from ignore list")
      true // Was ignored, now removed
    } else {
      false // Wasn't ignored
    }
  }

  // Get all ignored players
  def getIgnoredPlayers: List[String] = {
    ignoredPlayers.toList.sorted
  }

  // Save current ignore list to JSON file
  private def saveToFile(): Unit = {
    try {
      val writer = new PrintWriter(new File(IGNORE_FILE))
      val json = s"""{"ignored": [${ignoredPlayers.toList.sorted.map(n => s""""$n"""").mkString(", ")}]}"""
      writer.println(json)
      writer.close()
      println(s"[IgnoreManager] Saved ${ignoredPlayers.size} ignored players to JSON")
    } catch {
      case e: Exception =>
        System.err.println(s"[IgnoreManager] Failed to save ignore list: ${e.getMessage}")
    }
  }
}
