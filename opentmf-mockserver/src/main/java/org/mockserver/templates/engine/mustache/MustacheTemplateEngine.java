package org.mockserver.templates.engine.mustache;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.formatting.StringFormatter.formatLogMessage;
import static org.mockserver.log.model.LogEntry.LogMessageType.TEMPLATE_GENERATED;
import static org.mockserver.log.model.LogEntryMessages.TEMPLATE_GENERATED_MESSAGE_FORMAT;

import com.google.common.base.Splitter;
import com.jayway.jsonpath.JsonPath;
import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.model.DTO;
import org.mockserver.templates.engine.TemplateEngine;
import org.mockserver.templates.engine.TemplateFunctions;
import org.mockserver.templates.engine.model.HttpRequestTemplateObject;
import org.mockserver.templates.engine.serializer.HttpTemplateOutputDeserializer;
import org.slf4j.event.Level;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The Mustache template engine of upstream MockServer 5.15.0, without its {@code xPath} lambda
 * (this build vendors no XPath evaluator). See https://github.com/samskivert/jmustache for the
 * syntax.
 *
 * @author jamesdbloom
 */
public class MustacheTemplateEngine implements TemplateEngine {

  private static final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
  private final MockServerLogger mockServerLogger;
  private final Configuration configuration;
  private final Mustache.Compiler compiler;
  private final HttpTemplateOutputDeserializer httpTemplateOutputDeserializer;

  public MustacheTemplateEngine(MockServerLogger mockServerLogger, Configuration configuration) {
    this.mockServerLogger = mockServerLogger;
    this.configuration = configuration;
    this.httpTemplateOutputDeserializer = new HttpTemplateOutputDeserializer(mockServerLogger);
    compiler =
        Mustache.compiler()
            .emptyStringIsFalse(true)
            .zeroIsFalse(true)
            .strictSections(false)
            .defaultValue("")
            .withCollector(new ExtendedCollector());
  }

  @Override
  public <T> T executeTemplate(
      String template, HttpRequest request, Class<? extends DTO<T>> dtoClass) {
    try {
      validateTemplate(template);
      Writer writer = new StringWriter();
      Template compiledTemplate = compiler.compile(template);
      Map<String, Object> data = new ConcurrentHashMap<>();
      data.put("request", new HttpRequestTemplateObject(request));
      data.putAll(TemplateFunctions.BUILT_IN_FUNCTIONS);
      data.put(
          "jsonPath",
          (Mustache.Lambda) (frag, out) -> evaluateJsonPath(data, frag.execute(), request, out));
      compiledTemplate.execute(data, writer);
      if (MockServerLogger.isEnabled(Level.INFO)) {
        mockServerLogger.logEvent(
            new LogEntry()
                .setType(TEMPLATE_GENERATED)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request)
                .setMessageFormat(TEMPLATE_GENERATED_MESSAGE_FORMAT)
                .setArguments(generatedObject(writer.toString(), request), template, request));
      }
      return httpTemplateOutputDeserializer.deserializer(request, writer.toString(), dtoClass);
    } catch (Exception e) {
      throw new IllegalStateException(
          formatLogMessage(
              "Exception:{}transforming template:{}for request:{}",
              isNotBlank(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName(),
              template,
              request),
          e);
    }
  }

  private Object generatedObject(String generated, HttpRequest request) {
    try {
      JsonNode node = objectMapper.readTree(generated);
      return node != null ? node : generated;
    } catch (Exception e) {
      mockServerLogger.logEvent(
          new LogEntry()
              .setLogLevel(Level.INFO)
              .setHttpRequest(request)
              .setMessageFormat(
                  "exception deserialising generated content:{}into json node for request:{}")
              .setArguments(generated, request));
      return generated;
    }
  }

  private void validateTemplate(String template) {
    if (isNotBlank(template) && isNotBlank(configuration.mustacheDisallowedText())) {
      Iterable<String> deniedStrings =
          Splitter.on(",").trimResults().split(configuration.mustacheDisallowedText());
      for (String deniedString : deniedStrings) {
        if (template.contains(deniedString)) {
          throw new UnsupportedOperationException(
              "Found disallowed string \"" + deniedString + "\" in template: " + template);
        }
      }
    }
  }

  private void evaluateJsonPath(
      Map<String, Object> data, String jsonPath, HttpRequest request, Writer out) {
    try {
      Object jsonPathResult = JsonPath.compile(jsonPath).read(request.getBodyAsJsonOrXmlString());
      data.put("jsonPathResult", jsonPathResult);
      out.write("");
    } catch (Exception exception) {
      mockServerLogger.logEvent(
          new LogEntry()
              .setLogLevel(Level.INFO)
              .setHttpRequest(request)
              .setMessageFormat("exception evaluating jsonPath:{}against json body:{}")
              .setArguments(jsonPath, request.getBodyAsJsonOrXmlString())
              .setThrowable(exception));
    }
  }
}
