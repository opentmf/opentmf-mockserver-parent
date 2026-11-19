package org.mockserver.templates.engine.serializer;

import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.apache.commons.lang3.StringUtils.uncapitalize;
import static org.mockserver.validator.jsonschema.JsonSchemaHttpResponseValidator.jsonSchemaHttpResponseValidator;

import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.model.DTO;
import org.mockserver.serialization.model.HttpResponseDTO;
import org.mockserver.validator.jsonschema.JsonSchemaHttpResponseValidator;
import org.slf4j.event.Level;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a template's output into the response it describes, after validating it against the
 * response schema; {@code null} when the output is not a valid response.
 *
 * @author jamesdbloom
 */
public class HttpTemplateOutputDeserializer {

  private static final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
  private final MockServerLogger mockServerLogger;
  private final JsonSchemaHttpResponseValidator httpResponseValidator;

  public HttpTemplateOutputDeserializer(MockServerLogger mockServerLogger) {
    this.mockServerLogger = mockServerLogger;
    httpResponseValidator = jsonSchemaHttpResponseValidator(mockServerLogger);
  }

  public <T> T deserializer(HttpRequest request, String json, Class<? extends DTO<T>> dtoClass) {
    T result = null;
    try {
      String validationErrors = "";
      if (dtoClass.isAssignableFrom(HttpResponseDTO.class)) {
        validationErrors = httpResponseValidator.isValid(json);
      }
      if (isEmpty(validationErrors)) {
        result = objectMapper.readValue(json, dtoClass).buildObject();
      } else {
        mockServerLogger.logEvent(
            new LogEntry()
                .setLogLevel(Level.ERROR)
                .setHttpRequest(request)
                .setMessageFormat(
                    "validation failed:{}" + uncapitalize(dtoClass.getSimpleName()) + ":{}")
                .setArguments(validationErrors, json));
      }
    } catch (Exception e) {
      mockServerLogger.logEvent(
          new LogEntry()
              .setLogLevel(Level.ERROR)
              .setHttpRequest(request)
              .setMessageFormat("exception transforming json:{}")
              .setArguments(json)
              .setThrowable(e));
    }
    return result;
  }
}
