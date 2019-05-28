package jbok.network

import java.nio.charset.StandardCharsets
import java.util.UUID

import cats.effect.{Effect, Sync}
import cats.implicits._
import fs2.{Chunk, Pipe, Stream}
import io.circe._
import jbok.codec.rlp.RlpCodec
import jbok.codec.rlp.implicits._
import org.http4s
import org.http4s.{MediaType, Method, Status, Uri}
import scodec.bits.{BitVector, ByteVector}

sealed trait ContentType
object ContentType {
  case object Binary extends ContentType
  case object Json   extends ContentType
  case object Text   extends ContentType

  implicit val codec: RlpCodec[ContentType] = RlpCodec.gen[ContentType]
}

sealed trait Message[F[_]] {
  def id: UUID

  def body: Array[Byte]

  def contentType: ContentType

  def contentLength: Int = body.length

  def as[A](implicit F: Sync[F], codec: RlpCodec[A]): F[A] =
    F.fromEither(codec.decode(BitVector(body)).toEither.leftMap(err => new Exception(err.messageWithContext)).map(_.value))
}

object Message {
  implicit def codec[G[_]]: RlpCodec[Message[G]] = RlpCodec.gen[Message[G]]

  val emptyBody: ByteVector = ().asBytes

  def encodeBytes[F[_]: Sync](message: Message[F]): F[ByteVector] =
    Sync[F].delay(RlpCodec.encode(message).require.bytes)

  def decodeBytes[F[_]: Sync](bytes: ByteVector): F[Message[F]] =
    Sync[F].delay(RlpCodec.decode[Message[F]](bytes.bits).require.value)

  def decodeChunk[F[_]: Sync](chunk: Chunk[Byte]): F[Message[F]] =
    Sync[F].delay(RlpCodec.decode[Message[F]](BitVector(chunk.toArray)).require.value)

  def encodePipe[F[_]: Effect]: Pipe[F, Message[F], Byte] = { ms: Stream[F, Message[F]] =>
    scodec.stream.encode
      .many[Message[F]](RlpCodec[Message[F]])
      .encode(ms)
      .flatMap(bits => {
        val bytes = bits.bytes
        Stream.chunk(Chunk.byteVector(bytes))
      })
  }

  def decodePipe[F[_]: Effect]: Pipe[F, Byte, Message[F]] = { bytes: Stream[F, Byte] =>
    val bits = bytes.mapChunks(chunk => Chunk(ByteVector(chunk.toByteBuffer).toBitVector))
    bits through scodec.stream.decode.pipe[F, Message[F]]
  }
}

final case class Request[F[_]](
    id: UUID,
    method: String,
    contentType: ContentType,
    body: Array[Byte]
) extends Message[F] {
  override def toString: String =
    s"Request(id=${id}, method=${method}, contentType=${contentType}, contentLength=${contentLength})"
}

object Request {
  implicit def codec[G[_]]: RlpCodec[Request[G]] = RlpCodec.gen[Request[G]]

  def binary[F[_], A](method: String, body: ByteVector, id: UUID = UUID.randomUUID()): Request[F] =
    Request(id, method, ContentType.Binary, body.toArray)

  def json[F[_]](method: String, body: Json, id: UUID = UUID.randomUUID()): Request[F] =
    text[F](method, body.noSpaces, id)

  def text[F[_]](method: String, body: String, id: UUID = UUID.randomUUID()): Request[F] =
    Request[F](id, method, ContentType.Text, body.getBytes(StandardCharsets.UTF_8))

  def toHttp4s[F[_]](req: Request[F]): http4s.Request[F] = {
    val uri = Uri.unsafeFromString("")
    http4s.Request[F](Method.POST, uri).withEntity(req.body)
  }

  def fromHttp4s[F[_]: Sync](req: http4s.Request[F]): F[Request[F]] =
    req.as[Array[Byte]].map { bytes =>
      Request[F](
        UUID.randomUUID(),
        req.uri.path,
        ContentType.Binary,
        bytes
      )
    }
}

final case class Response[F[_]](
    id: UUID,
    code: Int,
    message: String,
    contentType: ContentType,
    body: Array[Byte],
) extends Message[F] {

  def isSuccess: Boolean =
    code < 300

  override def toString: String =
    s"Response(id=${id}, code=${code}, message=${message}, contentType=${contentType}, contentLength=${contentLength})"
}

object Response {
  implicit def codec[G[_]]: RlpCodec[Response[G]] = RlpCodec.gen[Response[G]]

  def toHttp4s[F[_]](res: Response[F]): http4s.Response[F] =
    http4s.Response[F](status = Status(res.code, res.message)).withEntity(res.body)

  def fromHttp4s[F[_]: Sync](res: http4s.Response[F]): F[Response[F]] =
    res.as[Array[Byte]].map { bytes =>
      Response[F](
        UUID.randomUUID(),
        res.status.code,
        res.status.reason,
        res.contentType.map(_.mediaType) match {
          case Some(MediaType.application.json) => ContentType.Json
          case Some(MediaType.text.plain)       => ContentType.Text
          case _                                => ContentType.Binary
        },
        bytes
      )
    }
}
