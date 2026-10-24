package org.opentmf.mockserver.util;

/**
 * Raised when an id format setting ({@code ID_FORMATS}, {@code ID_FORMAT_DEFAULT} or the TSID node)
 * is malformed.
 */
public class InvalidIdFormatException extends RuntimeException {

  public InvalidIdFormatException(String message) {
    super(message);
  }
}
