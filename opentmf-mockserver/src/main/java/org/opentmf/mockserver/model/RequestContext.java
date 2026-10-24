package org.opentmf.mockserver.model;

import static org.opentmf.mockserver.model.TmfConstants.ID;
import static org.opentmf.mockserver.model.TmfConstants.VERSION;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.Parameters;
import org.opentmf.mockserver.util.IdGenerator;
import org.opentmf.mockserver.util.PathExtractor;
import tools.jackson.databind.JsonNode;

/**
 * @author Gokhan Demir
 */
public class RequestContext {

  private RequestContext() {}

  private String domain;
  private TmfStatePath tmfStatePath;
  private Id id;
  private HttpRequest httpRequest;

  public String getDomain() {
    return domain;
  }

  public void setDomain(String domain) {
    this.domain = domain;
  }

  public Id getId() {
    return id;
  }

  public void setId(Id id) {
    this.id = id;
  }

  public TmfStatePath getTmfStatePath() {
    return tmfStatePath;
  }

  public void setTmfStatePath(TmfStatePath tmfStatePath) {
    this.tmfStatePath = tmfStatePath;
  }

  public boolean hasId() {
    return id != null && id.getId() != null;
  }

  public boolean isVersioned() {
    return tmfStatePath != null && tmfStatePath.isVersioned();
  }

  private boolean isPointQuery() {
    return id != null && id.getId() != null && id.getVersion() != null;
  }

  public boolean usePointQuery() {
    return isPointQuery() || !isVersioned();
  }

  public String toHref() {
    StringBuilder buf = new StringBuilder();
    buf.append(httpRequest.getPath().getValue());
    if (hasId()) {
      buf.append("/").append(id.getHrefSuffix());
    }
    return buf.toString();
  }

  public void obtainVersionFromPayloadIfNecessary(JsonNode payload) {
    if (id.getVersion() == null && payload != null && payload.has(VERSION)) {
      id.setVersion(payload.get(VERSION).asString());
    }
  }

  public void generateNewIdIfNecessary() {
    if (id == null) {
      id = new Id();
      id.setId(IdGenerator.generate(domain));
      if (isVersioned()) {
        id.setVersion("0");
      }
    } else if (isVersioned() && id.getVersion() == null) {
      id.setVersion("0");
    }
  }

  public static RequestContext initialize(HttpRequest httpRequest, boolean pathContainsId,
      JsonNode parsedBody) {
    RequestContext ctx = new RequestContext();
    ctx.httpRequest = httpRequest;
    String rawPath = URLDecoder.decode(httpRequest.getPath().getValue(), StandardCharsets.UTF_8);

    ctx.setDomain(pathContainsId
        ? PathExtractor.extractDomainWithId(rawPath)
        : PathExtractor.extractDomainWithoutId(rawPath));

    ctx.setTmfStatePath(TmfStatePath.resolveFromPath(ctx.getDomain()));

    if (pathContainsId) {
      ctx.setId(parseId(PathExtractor.extractLastPart(rawPath)));
    } else if (parsedBody != null && parsedBody.has(ID)) {
      ctx.setId(parseId(parsedBody));
    }

    if (ctx.getId() == null && !StringUtils.isEmpty(httpRequest.getFirstQueryStringParameter(ID))) {
      ctx.setId(parseId(httpRequest.getFirstQueryStringParameter(ID)));
    }

    if (ctx.getId() != null && ctx.getId().getVersion() == null) {
      Parameters queryParams = httpRequest.getQueryStringParameters();
      if (queryParams != null && queryParams.containsEntry(VERSION)) {
        ctx.getId().setVersion(httpRequest.getFirstQueryStringParameter(VERSION));
      } else if (parsedBody != null && parsedBody.has(VERSION)) {
        ctx.getId().setVersion(parsedBody.get(VERSION).asString());
      }
    }

    return ctx;
  }

  private static Id parseId(String pureId) {
    Id id = new Id();
    if (pureId.toLowerCase(Locale.UK).contains(":(version=")) {
      id.setVersion(pureId.substring(pureId.indexOf("version=") + 8, pureId.length() - 1));
      id.setId(pureId.substring(0, pureId.indexOf(":(version=")));
    } else {
      id.setId(pureId);
    }
    return id;
  }

  private static Id parseId(JsonNode parsedBody) {
    if (!parsedBody.has(ID)) {
      return null;
    }
    Id id = new Id();
    id.setId(parsedBody.get(ID).asString());
    if (parsedBody.has(VERSION)) {
      id.setVersion(parsedBody.get(VERSION).asString());
    }
    return id;
  }

}
