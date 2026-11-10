package org.opentmf.mockserver.util;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * @author Yusuf BOZKURT
 */
public class TokenUtil {
  /** Thread-safe; one instance serves every caller instead of seeding a new one per token. */
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private TokenUtil() {}

  public static String generateRandomToken(int length) {
    byte[] tokenBytes = new byte[length];
    SECURE_RANDOM.nextBytes(tokenBytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
  }
}
