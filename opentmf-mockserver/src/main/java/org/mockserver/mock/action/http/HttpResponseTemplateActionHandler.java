package org.mockserver.mock.action.http;

import static org.mockserver.model.HttpResponse.notFoundResponse;

import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.HttpTemplate;
import org.mockserver.serialization.model.HttpResponseDTO;
import org.mockserver.templates.engine.mustache.MustacheTemplateEngine;

/**
 * Renders a response template. This build serves Mustache only; {@code HttpState} refuses every
 * other template type at load, and {@code HttpActionHandler} answers one loaded from an
 * initialization file with a 501.
 *
 * @author jamesdbloom
 */
public class HttpResponseTemplateActionHandler {

  private final MockServerLogger mockServerLogger;
  private final Configuration configuration;
  private MustacheTemplateEngine mustacheTemplateEngine;

  public HttpResponseTemplateActionHandler(
      MockServerLogger mockServerLogger, Configuration configuration) {
    this.mockServerLogger = mockServerLogger;
    this.configuration = configuration;
  }

  /**
   * The response the Mustache template renders for the request; a 404 when it renders no valid
   * response.
   *
   * @throws UnsupportedOperationException for a template type this build does not serve
   */
  public HttpResponse handle(HttpTemplate httpTemplate, HttpRequest httpRequest) {
    if (httpTemplate.getTemplateType() != HttpTemplate.TemplateType.MUSTACHE) {
      throw new UnsupportedOperationException(
          httpTemplate.getTemplateType() + " response templates are not supported in this build");
    }
    HttpResponse templatedResponse =
        getMustacheTemplateEngine()
            .executeTemplate(httpTemplate.getTemplate(), httpRequest, HttpResponseDTO.class);
    return templatedResponse != null ? templatedResponse : notFoundResponse();
  }

  private synchronized MustacheTemplateEngine getMustacheTemplateEngine() {
    if (mustacheTemplateEngine == null) {
      mustacheTemplateEngine = new MustacheTemplateEngine(mockServerLogger, configuration);
    }
    return mustacheTemplateEngine;
  }
}
