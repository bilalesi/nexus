package ch.epfl.bluebrain.nexus.delta.sdk.crypto

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import javax.crypto.Cipher._
import javax.crypto.spec.{PBEKeySpec, SecretKeySpec}
import javax.crypto.{Cipher, SecretKey, SecretKeyFactory}
import scala.util.Try

/**
  * Provides encryption and decryption functionality
  */
final class Crypto private (derivedKey: SecretKey) {

  /**
    * @return
    *   the key in its primary encoded format
    */
  private[crypto] def encoded: Array[Byte] = derivedKey.getEncoded

  /**
    * Encrypts the given input with the provided AES secret key.
    *
    * @return
    *   a right with the encrypted string in base64 encoding or a left with the error message
    */
  def encrypt(input: String): Try[String] =
    Try {
      val cipher = Cipher.getInstance(Crypto.transformation)
      cipher.init(ENCRYPT_MODE, derivedKey)
      val bytes  = cipher.doFinal(input.getBytes(UTF_8))
      Base64.getEncoder.encodeToString(bytes)
    }

  /**
    * Decrypts the given base64 encoded input with the provided AES secret key.
    *
    * @return
    *   a right with the decrypted string or a left with the error message
    */
  def decrypt(input: String): Try[String] =
    Try {
      val cipher = Cipher.getInstance(Crypto.transformation)
      cipher.init(DECRYPT_MODE, derivedKey)
      val bytes  = cipher.doFinal(Base64.getDecoder.decode(input))
      new String(bytes, UTF_8)
    }

  override def toString: String = "SECRET"
}

object Crypto {
  private val transformation = "AES"

  /**
    * Derives a suitable AES-256 secret key from a given password and a salt.
    */
  private def deriveKey(password: String, salt: String): SecretKey = {
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
    val spec    = new PBEKeySpec(password.toCharArray, salt.getBytes(UTF_8), 1000, 256)
    val key     = factory.generateSecret(spec)
    new SecretKeySpec(key.getEncoded, "AES")
  }

  /**
    * Creates a [[Crypto]] for AES-256
    *
    * @param password
    *   the password to use for encryption
    * @param salt
    *   the salt to use for encryption
    */
  final def apply(password: String, salt: String): Crypto =
    new Crypto(deriveKey(password, salt))

}
