package wowchat.common

import java.io.{File, PrintWriter}
import java.nio.file.{Files, Paths}
import scala.io.Source
import scala.collection.mutable

/**
 * IgnoreManager - Bot-side ignore list for filtering WoW messages
 * 
 * Stores ignored player names in ignored_players.txt (one per line)
 * All comparisons are case-insensitive
 */
object IgnoreManager {

  private val IGNORE_FILE = "ignored_players.txt"
  private val ignoredPlayers = mutable.Set[String]()

  // Load ignore list on startup
  def init(): Unit = {
    val file = new File(IGNORE_FILE)
    if (file.exists()) {
      try {
        val source = Source.fromFile(file)
        ignoredPlayers.clear()
        ignoredPlayers ++= source.getLines()
          .map(_.trim.toLowerCase)
          .filter(_.nonEmpty)
        source.close()
        println(s"[IgnoreManager] Loaded ${ignoredPlayers.size} ignored players")
      } catch {
        case e: Exception =>
          System.err.println(s"[IgnoreManager] Failed to load ignore list: ${e.getMessage}")
      }
    } else {
      println(s"[IgnoreManager] No ignore file found, starting with empty list")
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
      true // Newly ignored
    }
  }

  // Remove a player from ignore list
  def unignore(playerName: String): Boolean = {
    val normalized = playerName.toLowerCase
    if (ignoredPlayers.contains(normalized)) {
      ignoredPlayers -= normalized
      saveToFile()
      true // Was ignored, now removed
    } else {
      false // Wasn't ignored
    }
  }

  // Get all ignored players
  def getIgnoredPlayers: List[String] = {
    ignoredPlayers.toList.sorted
  }

  // Save current ignore list to file
  private def saveToFile(): Unit = {
    try {
      val writer = new PrintWriter(new File(IGNORE_FILE))
      ignoredPlayers.toList.sorted.foreach(writer.println)
      writer.close()
      println(s"[IgnoreManager] Saved ${ignoredPlayers.size} ignored players to file")
    } catch {
      case e: Exception =>
        System.err.println(s"[IgnoreManager] Failed to save ignore list: ${e.getMessage}")
    }
  }
}
