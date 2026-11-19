package org.mockserver.templates.engine;

import org.mockserver.model.HttpRequest;
import org.mockserver.serialization.model.DTO;

/**
 * @author jamesdbloom
 */
public interface TemplateEngine {

  <T> T executeTemplate(String template, HttpRequest httpRequest, Class<? extends DTO<T>> dtoClass);
}
