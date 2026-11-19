package org.mockserver.templates.engine;

import com.google.common.collect.ImmutableMap;
import java.security.SecureRandom;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;
import org.mockserver.serialization.Base64Converter;
import org.mockserver.time.TimeService;
import org.mockserver.uuid.UUIDService;

/** The built-in values a template can read: times, a UUID, random integers and bytes. */
public class TemplateFunctions implements Supplier<Object> {
  private static final Random random = new SecureRandom();
  private static final Base64Converter base64Converter = new Base64Converter();

  public static final Map<String, Supplier<Object>> BUILT_IN_FUNCTIONS =
      ImmutableMap.<String, Supplier<Object>>builder()
          .put(
              "now",
              new TemplateFunctions(() -> DateTimeFormatter.ISO_INSTANT.format(TimeService.now())))
          .put(
              "now_epoch",
              new TemplateFunctions(() -> String.valueOf(TimeService.now().getEpochSecond())))
          .put(
              "now_iso_8601",
              new TemplateFunctions(() -> DateTimeFormatter.ISO_INSTANT.format(TimeService.now())))
          .put(
              "now_rfc_1123",
              new TemplateFunctions(
                  () -> DateTimeFormatter.RFC_1123_DATE_TIME.format(TimeService.offsetNow())))
          .put("uuid", new TemplateFunctions(UUIDService::getUUID))
          .put("rand_int", new TemplateFunctions(() -> TemplateFunctions.randomInteger(10)))
          .put("rand_int_10", new TemplateFunctions(() -> TemplateFunctions.randomInteger(10)))
          .put("rand_int_100", new TemplateFunctions(() -> TemplateFunctions.randomInteger(100)))
          .put("rand_bytes", new TemplateFunctions(() -> TemplateFunctions.randomBytes(16)))
          .put("rand_bytes_16", new TemplateFunctions(() -> TemplateFunctions.randomBytes(16)))
          .put("rand_bytes_32", new TemplateFunctions(() -> TemplateFunctions.randomBytes(32)))
          .put("rand_bytes_64", new TemplateFunctions(() -> TemplateFunctions.randomBytes(64)))
          .put("rand_bytes_128", new TemplateFunctions(() -> TemplateFunctions.randomBytes(128)))
          .build();

  private final Supplier<String> supplier;

  public TemplateFunctions(Supplier<String> supplier) {
    this.supplier = supplier;
  }

  public static String randomInteger(int max) {
    return String.valueOf(random.nextInt(max));
  }

  public static String randomBytes(int size) {
    byte[] bytes = new byte[size];
    random.nextBytes(bytes);
    return base64Converter.bytesToBase64String(bytes);
  }

  @Override
  public Object get() {
    return supplier.get();
  }

  @Override
  public String toString() {
    return supplier.get();
  }
}
