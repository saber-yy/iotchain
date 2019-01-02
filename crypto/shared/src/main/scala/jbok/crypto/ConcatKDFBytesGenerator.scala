package jbok.crypto

import org.bouncycastle.crypto.Digest
import org.bouncycastle.util.Pack
import scodec.bits.ByteVector

/**
  * Basic KDF generator for derived keys and ivs as defined by NIST SP 800-56A.
  * @param digest for source of derived keys
  */
class ConcatKDFBytesGenerator(digest: Digest) {
  val digestSize: Int = digest.getDigestSize

  def generateBytes(outputLength: Int, seed: Array[Byte]): ByteVector = {
    require(outputLength <= (digestSize * 8) * ((2L << 32) - 1), "Output length too large")

    val counterStart: Long = 1
    val hashBuf            = new Array[Byte](digestSize)
    val counterValue       = new Array[Byte](Integer.BYTES)

    digest.reset()

    (0 until (outputLength / digestSize + 1))
      .map { i =>
        Pack.intToBigEndian(((counterStart + i) % (2L << 32)).toInt, counterValue, 0)
        digest.update(counterValue, 0, counterValue.length)
        digest.update(seed, 0, seed.length)
        digest.doFinal(hashBuf, 0)

        val spaceLeft = outputLength - (i * digestSize)

        if (spaceLeft > digestSize) {
          ByteVector(hashBuf)
        } else {
          ByteVector(hashBuf).dropRight(digestSize - spaceLeft)
        }
      }
      .foldLeft(ByteVector.empty)(_ ++ _)
  }
}
