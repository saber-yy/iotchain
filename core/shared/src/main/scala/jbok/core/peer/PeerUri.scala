package jbok.core.peer

import java.net._

import cats.implicits._
import enumeratum._
import io.circe.generic.JsonCodec
import jbok.crypto.signature.KeyPair
import jbok.crypto._
import scodec.bits.ByteVector

//sealed trait PeerSource extends EnumEntry
//object PeerSource extends Enum[PeerSource] {
//  val values = findValues
//
//  case object Discovery extends PeerSource
//  case object Seed      extends PeerSource
//  case object Admin     extends PeerSource
//}

@JsonCodec
final case class PeerUri(
    scheme: String,
//    pk: KeyPair.Public,
    host: String,
    port: Int
) {
//  lazy val id: ByteVector = pk.bytes.kec256

  lazy val uri: String = s"${scheme}://${host}:${port}"

  lazy val address: InetSocketAddress = new InetSocketAddress(host, port)
}

object PeerUri {
  val schemes = List("tcp", "http", "https")

  val PublicLength = 64

  def fromTcpAddr(addr: InetSocketAddress): PeerUri =
    PeerUri("tcp", addr.getHostString, addr.getPort)

  /**
    * Parse a node string, for it to be valid it should have the format:
    * "[scheme]://[128 char (64bytes) hex string]@[IPv4 address | '['IPv6 address']' ]:[port]"
    */
  def fromStr(str: String): Either[Throwable, PeerUri] = {
    def checkURI(str: String) = Either.catchNonFatal(new URI(str))

    def checkScheme(uri: URI) = schemes.find(_ == uri.getScheme.toLowerCase()) match {
      case Some(scheme) => Right(scheme)
      case None         => Left(new Exception(s"invalid scheme"))
    }

//    def checkPk(uri: URI) =
//      ByteVector
//        .fromHex(uri.getUserInfo)
//        .map(hex => {
//          if (hex.length == PublicLength) Right(KeyPair.Public(hex))
//          else Left(new Exception("invalid pk length"))
//        })
//        .getOrElse(Left(new Exception("invalid pk")))

    def checkAddress(uri: URI) =
      for {
        (host, port) <- Either.catchNonFatal(InetAddress.getByName(uri.getHost) -> uri.getPort)
        host2 <- host match {
          case _: Inet4Address | _: Inet6Address => Right(host)
          case _ =>
            Left(new Exception(s"invalid host ${host}"))
        }
        addr <- Either.catchNonFatal(new InetSocketAddress(host2, port))
      } yield addr

    for {
      uri  <- checkURI(str)
      _    <- checkScheme(uri)
//      pk   <- checkPk(uri)
      addr <- checkAddress(uri)
    } yield PeerUri.fromTcpAddr(addr)
  }
}
