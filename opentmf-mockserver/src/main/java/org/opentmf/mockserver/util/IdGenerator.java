package org.opentmf.mockserver.util;

import static org.opentmf.mockserver.util.Constants.ID_FORMATS;
import static org.opentmf.mockserver.util.Constants.ID_FORMAT_DEFAULT;

import io.hypersistence.tsid.TSID;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * Generates the id of a resource that is created without one.
 *
 * <p>An id is generated from a template that carries exactly one placeholder:
 *
 * <ul>
 *   <li>{@code {tsid}} – the 13-character Crockford TSID string (the default);
 *   <li>{@code {tsidNumber}} – the same TSID as a decimal number;
 *   <li>{@code {uuid}} – a random UUID; the bare keyword {@code uuid} is the same as {@code
 *       {uuid}}.
 * </ul>
 *
 * <p>The template is chosen per resource domain: the {@code ID_FORMATS} entry for the domain if
 * there is one, else {@code ID_FORMAT_DEFAULT}, else {@code {tsid}}. {@code ID_FORMATS} is a comma
 * separated list of {@code <domain>=<template>} entries, where the domain is the request path
 * without its leading slash (e.g. {@code document=urn:example:document-id:{tsidNumber}} for {@code
 * /document}).
 *
 * <p>The TSID node comes from hypersistence-tsid's own setting, system property {@code tsid.node}
 * or environment variable {@code TSID_NODE} (random when neither is set). The library silently
 * wraps an out-of-range node and ignores a non-numeric one, so this class rejects both.
 *
 * <p>A malformed value raises {@link InvalidIdFormatException}; it never falls back to the default
 * silently.
 */
public class IdGenerator {

  static final String TSID_PLACEHOLDER = "{tsid}";
  static final String TSID_NUMBER_PLACEHOLDER = "{tsidNumber}";
  static final String UUID_PLACEHOLDER = "{uuid}";
  static final String UUID_KEYWORD = "uuid";

  private static final String[] PLACEHOLDERS = {
    TSID_PLACEHOLDER, TSID_NUMBER_PLACEHOLDER, UUID_PLACEHOLDER
  };
  private static final int DEFAULT_NODE_COUNT = 1024;
  private static final int MAX_NODE_COUNT = 1 << 20;

  private IdGenerator() {}

  /**
   * Generates a new id for the given resource domain.
   *
   * @param domain the resource domain, as resolved by {@link PathExtractor}
   * @return the generated id
   * @throws InvalidIdFormatException if {@code ID_FORMATS}, {@code ID_FORMAT_DEFAULT} or the TSID
   *     node setting is malformed
   */
  public static String generate(String domain) {
    validateTsidNode();
    String defaultTemplate = defaultTemplate(System.getenv(ID_FORMAT_DEFAULT));
    String template = parse(System.getenv(ID_FORMATS)).get(normalize(domain));
    return expand(template == null ? defaultTemplate : template);
  }

  static String defaultTemplate(String value) {
    if (StringUtils.isBlank(value)) {
      return TSID_PLACEHOLDER;
    }
    return toTemplate(ID_FORMAT_DEFAULT + " [" + value.trim() + "]", value.trim());
  }

  static Map<String, String> parse(String value) {
    Map<String, String> formats = new HashMap<>();
    if (value == null) {
      return formats;
    }
    for (String entry : value.split(",")) {
      if (entry.isBlank()) {
        continue;
      }
      String source = ID_FORMATS + " entry [" + entry.trim() + "]";
      int pos = entry.indexOf('=');
      if (pos < 0) {
        throw invalid(source, "expected <domain>=<template>");
      }
      String domain = normalize(entry.substring(0, pos));
      if (domain.isEmpty()) {
        throw invalid(source, "the domain is empty");
      }
      String template = toTemplate(source, entry.substring(pos + 1).trim());
      if (formats.putIfAbsent(domain, template) != null) {
        throw invalid(source, "domain [" + domain + "] is configured more than once");
      }
    }
    return formats;
  }

  private static String toTemplate(String source, String value) {
    String template = UUID_KEYWORD.equalsIgnoreCase(value) ? UUID_PLACEHOLDER : value;
    int placeholders = 0;
    String literal = template;
    for (String placeholder : PLACEHOLDERS) {
      placeholders += StringUtils.countMatches(template, placeholder);
      literal = literal.replace(placeholder, "");
    }
    if (placeholders != 1) {
      throw invalid(source, "the template needs exactly one {tsid}, {tsidNumber} or {uuid}");
    }
    if (StringUtils.containsAny(literal, '{', '}')) {
      throw invalid(source, "the template has an unknown placeholder");
    }
    // Each of these would break resolving the id back from a GET/PATCH/DELETE path.
    if (StringUtils.containsAny(literal, '/', '?', '#')
        || StringUtils.containsWhitespace(literal)) {
      throw invalid(source, "the template must not contain '/', '?', '#' or whitespace");
    }
    if (literal.toLowerCase(Locale.UK).contains(":(version=")) {
      throw invalid(source, "the template must not contain ':(version='");
    }
    return template;
  }

  private static String expand(String template) {
    if (template.contains(UUID_PLACEHOLDER)) {
      return template.replace(UUID_PLACEHOLDER, UUID.randomUUID().toString());
    }
    TSID tsid = TSID.Factory.getTsid();
    return template.contains(TSID_NUMBER_PLACEHOLDER)
        ? template.replace(TSID_NUMBER_PLACEHOLDER, Long.toString(tsid.toLong()))
        : template.replace(TSID_PLACEHOLDER, tsid.toString());
  }

  /** Mirrors hypersistence-tsid's lookup: system property first, then environment variable. */
  static void validateTsidNode() {
    String node = tsidSetting("tsid.node", "TSID_NODE");
    if (node == null) {
      return;
    }
    String count = tsidSetting("tsid.node.count", "TSID_NODE_COUNT");
    int nodeCount =
        count == null
            ? DEFAULT_NODE_COUNT
            : parseInRange("TSID node count [" + count + "]", count, 1, MAX_NODE_COUNT);
    parseInRange("TSID node [" + node + "]", node, 0, nodeCount - 1);
  }

  private static String tsidSetting(String property, String environmentVariable) {
    String value = System.getProperty(property);
    if (StringUtils.isEmpty(value)) {
      value = System.getenv(environmentVariable);
    }
    return StringUtils.isEmpty(value) ? null : value;
  }

  private static int parseInRange(String source, String value, int min, int max) {
    int parsed;
    try {
      parsed = Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw invalid(source, "not an integer");
    }
    if (parsed < min || parsed > max) {
      throw invalid(source, "must be between " + min + " and " + max);
    }
    return parsed;
  }

  private static String normalize(String domain) {
    return StringUtils.strip(StringUtils.trimToEmpty(domain), "/");
  }

  private static InvalidIdFormatException invalid(String source, String reason) {
    return new InvalidIdFormatException("Invalid " + source + ": " + reason + ".");
  }
}
