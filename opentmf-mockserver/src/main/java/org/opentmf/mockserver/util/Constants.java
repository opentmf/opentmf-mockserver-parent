package org.opentmf.mockserver.util;

/**
 * @author Gokhan Demir
 */
public class Constants {

  private Constants() {}

  public static final String CACHE_DURATION_MILLIS = "CACHE_DURATION_MILLIS";
  public static final String TWO_HOURS = String.valueOf(1000L * 60 * 60 * 2);
  public static final String THREE_SECONDS = String.valueOf(1000L * 3);

  /** comma separated list of additional fields to be included in the POST response */
  public static final String ADDITIONAL_FIELDS = "ADDITIONAL_FIELDS";

  /** comma separated list of {@code <domain>=<template>} formats for generated ids */
  public static final String ID_FORMATS = "ID_FORMATS";

  /** format for generated ids of a domain without an {@code ID_FORMATS} entry */
  public static final String ID_FORMAT_DEFAULT = "ID_FORMAT_DEFAULT";
}
