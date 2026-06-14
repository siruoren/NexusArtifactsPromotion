package com.nexus.artifacts.promotion.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility for encrypting/decrypting sensitive credentials.
 *
 * <p>Uses AES-256-GCM for encryption with a derived key from a fixed passphrase.
 * The encrypted output is Base64-encoded and includes the IV (nonce) prepended
 * to the ciphertext, making it self-contained.
 *
 * <p>This is NOT a replacement for a proper secrets management system (Vault, etc.),
 * but it prevents credentials from being stored or displayed in plaintext.
 *
 * <p>Usage:
 * <pre>
 *   String encrypted = CredentialEncryptor.encrypt("my-password");
 *   String decrypted = CredentialEncryptor.decrypt(encrypted);
 * </pre>
 */
public class CredentialEncryptor {

  private static final Logger log = LoggerFactory.getLogger(CredentialEncryptor.class);

  private static final String ALGORITHM = "AES/GCM/NoPadding";
  private static final int GCM_IV_LENGTH = 12;
  private static final int GCM_TAG_LENGTH = 128;
  private static final int KEY_LENGTH = 256;
  private static final int PBKDF2_ITERATIONS = 65536;

  /**
   * Fixed salt for key derivation. Not ideal but better than plaintext.
   * In production, this should be stored in a secure location.
   */
  private static final byte[] FIXED_SALT = hexToBytes("4e6578757341727469666163747350726f");

  /**
   * Derivation passphrase — based on Nexus installation fingerprint.
   * This prevents the encrypted values from being portable across instances.
   */
  private static final String DERIVATION_PASSPHRASE = "NexusArtifactsPromotion-Key-" +
      System.getProperty("nexus-workdir", "default");

  private static final SecureRandom secureRandom = new SecureRandom();

  private CredentialEncryptor() {
    // Utility class — no instantiation
  }

  /**
   * Encrypt a plaintext string.
   *
   * @param plaintext the string to encrypt
   * @return Base64-encoded encrypted string (IV + ciphertext)
   */
  public static String encrypt(String plaintext) {
    if (plaintext == null || plaintext.isEmpty()) {
      return plaintext;
    }

    try {
      SecretKey key = deriveKey();
      byte[] iv = new byte[GCM_IV_LENGTH];
      secureRandom.nextBytes(iv);

      Cipher cipher = Cipher.getInstance(ALGORITHM);
      GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
      cipher.init(Cipher.ENCRYPT_MODE, key, spec);

      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

      // Prepend IV to ciphertext
      byte[] combined = new byte[iv.length + ciphertext.length];
      System.arraycopy(iv, 0, combined, 0, iv.length);
      System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

      return "ENC:" + Base64.getEncoder().encodeToString(combined);
    }
    catch (Exception e) {
      log.error("Failed to encrypt credential: {}", e.getMessage());
      throw new RuntimeException("Encryption failed", e);
    }
  }

  /**
   * Decrypt an encrypted string.
   *
   * @param encrypted Base64-encoded encrypted string (IV + ciphertext) with "ENC:" prefix
   * @return the decrypted plaintext
   */
  public static String decrypt(String encrypted) {
    if (encrypted == null || encrypted.isEmpty()) {
      return encrypted;
    }

    // If not encrypted with our prefix, return as-is (backward compatibility)
    if (!encrypted.startsWith("ENC:")) {
      return encrypted;
    }

    try {
      String base64Data = encrypted.substring("ENC:".length());
      byte[] combined = Base64.getDecoder().decode(base64Data);

      // Extract IV and ciphertext
      byte[] iv = new byte[GCM_IV_LENGTH];
      byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
      System.arraycopy(combined, 0, iv, 0, iv.length);
      System.arraycopy(combined, iv.length, ciphertext, 0, ciphertext.length);

      SecretKey key = deriveKey();
      Cipher cipher = Cipher.getInstance(ALGORITHM);
      GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
      cipher.init(Cipher.DECRYPT_MODE, key, spec);

      byte[] plaintext = cipher.doFinal(ciphertext);
      return new String(plaintext, StandardCharsets.UTF_8);
    }
    catch (Exception e) {
      log.error("Failed to decrypt credential: {}", e.getMessage());
      throw new RuntimeException("Decryption failed", e);
    }
  }

  /**
   * Check if a string is encrypted (has ENC: prefix).
   */
  public static boolean isEncrypted(String value) {
    return value != null && value.startsWith("ENC:");
  }

  /**
   * Derive an AES-256 key from the passphrase using PBKDF2.
   */
  private static SecretKey deriveKey() throws Exception {
    PBEKeySpec spec = new PBEKeySpec(
        DERIVATION_PASSPHRASE.toCharArray(),
        FIXED_SALT,
        PBKDF2_ITERATIONS,
        KEY_LENGTH
    );
    SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
    byte[] keyBytes = factory.generateSecret(spec).getEncoded();
    return new SecretKeySpec(keyBytes, "AES");
  }

  private static byte[] hexToBytes(String hex) {
    int len = hex.length();
    byte[] data = new byte[len / 2];
    for (int i = 0; i < len; i += 2) {
      data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
          + Character.digit(hex.charAt(i + 1), 16));
    }
    return data;
  }
}
