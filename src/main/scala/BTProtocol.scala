package org.storrent

import akka.actor.{ Actor, ActorRef, ActorLogging, Props }
import akka.util.ByteString
import scala.collection.mutable

object BTProtocol {
  case class GetPiece(index: Int)
  case class Choke()
  case class Unchoke()
  case class Have(index: Int)
  case class Bitfield(bitfieldSet: mutable.Set[Int])
  case class Piece(index: Int, chunk: ByteString)

  def bytesToInt(bytes: IndexedSeq[Byte]): Int = { java.nio.ByteBuffer.wrap(bytes.toArray).getInt }
}

class BTProtocol(ip: String, port: Int, peerConnection: ActorRef, info_hash: Array[Int], fileLength: Long, pieceLength: Long) 
    extends Actor with ActorLogging {
  import BTProtocol._
  import Frame._

  var messageReader = handshakeReader _

  val numPieces = (fileLength / pieceLength + (fileLength % pieceLength) % 1)

  val peerTcp = context.actorOf(Props(new TCPClient(ip, port, self)), s"tcp-${ip}:${port}")
  peerTcp ! TCPClient.SendData(createHandshakeFrame(info_hash)) // send handshake  

  // Return number of bytes to consume
  def handshakeReader(LocalBuffer: ByteString): Int = {
    if (LocalBuffer.length < 68) {
      0
    } else {
      println("Sending Interested message")
      peerTcp ! TCPClient.SendData(createInterestedFrame())
      messageReader = peerReader
      68
    }
  }

  // Return number of bytes to consume. Process message, if there is one
  def peerReader(localBuffer: ByteString): Int = {
    parseFrame(localBuffer) match {
      case (0, _) => 0
      case (n, None) => n // this case can happen (keep-alive message)
      case (n, Some(m)) => processFrame(m); n
    }
  }

  // Decode ID field of message and then execute some action
  def processFrame(m: ByteString) {
    val rest = m.drop(1)
    m(0) & 0xFF match {
      case 0 => // CHOKE
        println("CHOKE")
        peerConnection ! Choke()
      case 1 => // UNCHOKE
        println("UNCHOKE")
        peerConnection ! Unchoke()
      case 4 => // HAVE piece
        val index = bytesToInt(rest.take(4))
        println(s"HAVE ${index}")
        peerConnection ! Have(index)
      case 5 => // BITFIELD
        println(s"BITFIELD")
        var peerBitfieldSet: mutable.Set[Int] = mutable.Set()
        bitfieldToSet(rest, 0, peerBitfieldSet)
        peerConnection ! Bitfield(peerBitfieldSet)
      case 7 => // PIECE
        val index = bytesToInt(rest.take(4))
        // FIXME: we assume that offset within piece is always 0
        peerConnection ! Piece(index, rest.drop(4).drop(4))
        println(s"PIECE ${rest.take(4)}")
    }
  }

  def receive = {
    case TCPClient.DataReceived(buffer) =>
      sender ! messageReader(buffer)
    case TCPClient.ConnectionClosed =>
      println("")
    case PeerConnection.RequestNextPiece(index) =>
      peerTcp ! TCPClient.SendData(createPieceFrame(index, 0, pieceLength))

  }
}
