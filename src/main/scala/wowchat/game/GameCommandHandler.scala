package wowchat.game

trait GameCommandHandler {

  def sendMessageToWow(tp: Byte, message: String, target: Option[String])
  def sendNotification(message: String)

  def handleWho(arguments: Option[String]): Option[String]
  def handleGmotd(): Option[String]

  def sendGuildPromote(playerName: String): Unit
  def sendGuildDemote(playerName: String): Unit
}
