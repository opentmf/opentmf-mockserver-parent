package org.opentmf.mockserver.callback;

import static org.opentmf.mockserver.model.TmfConstants.HREF;
import static org.opentmf.mockserver.model.TmfConstants.ID;
import static org.opentmf.mockserver.model.TmfConstants.UPDATED_BY;
import static org.opentmf.mockserver.model.TmfConstants.UPDATED_DATE;
import static org.opentmf.mockserver.model.TmfConstants.VERSION;
import static org.opentmf.mockserver.util.AuditFieldUtil.setCreateFields;
import static org.opentmf.mockserver.util.Constants.ADDITIONAL_FIELDS;
import static org.opentmf.mockserver.util.ErrorResponseUtil.getErrorResponse;

import org.apache.commons.lang3.RandomStringUtils;
import org.mockserver.mock.action.ExpectationResponseCallback;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.HttpStatusCode;
import org.mockserver.model.MediaType;
import org.opentmf.mockserver.model.RequestContext;
import org.opentmf.mockserver.token.TokenEnforcer;
import org.opentmf.mockserver.util.IdempotencyGuard;
import org.opentmf.mockserver.util.JacksonUtil;
import org.opentmf.mockserver.util.PayloadCache;
import tools.jackson.databind.node.ObjectNode;

/**
 *
 *
 * <h2>DynamicPostCallback</h2>
 *
 * <ul>
 *   <li>Tries to retrieve <code>id</code> and <code>version</code> (if versioned entity) from the
 *       payload.
 *   <li>If versioned entity but no <code>version</code> in the payload, tries to retrieve the
 *       version from the path using <code>:(version=XYZ)</code>
 *   <li>If versioned entity but no <code>version</code> found yet, tries to get the version from
 *       the query parameters like <code>?version=XYZ</code>
 *   <li>If <code>id</code> and `<code>version</code> (if versioned entity) is provided, checks if
 *       that exists in the payload cache. Returns 400 if so.
 *   <li>Uses either the provided id, or generates a new id for the posted payload.
 *   <li>If a versioned entity and version is not provided, sets <code>"version": "0"</code>.
 *   <li>Adds createdBy, createdDate and revision fields. Overrides if they are already provided.
 *   <li>Removes updatedDate and updatedBy, if they are provided in the payload.
 *   <li>Decides the state field name and initial value according to the path.
 *   <li>If state (or status) is not provided, sets the state value to the default initial. Here is
 *       the state value matrix that matches the configured path according to the type field: <br>
 *       <table style="border:1px solid #666; padding:4px">
 *       <caption>Initial and final state per entity type</caption>
 *  <thead>
 *  <tr>
 *  <th>Type</th>
 *  <th>Field name</th>
 *  <th>Initial Value</th>
 *  <th>Final Value</th>
 *  </tr>
 *  </thead>
 *  <tbody>
 *  <tr>
 *  <td>Orders</td>
 *  <td>state</td>
 *  <td>acknowledged</td>
 *  <td>completed</td>
 *  </tr>
 *  <tr>
 *  <td>Inventory</td>
 *  <td>status</td>
 *  <td>created</td>
 *  <td>active</td>
 *  </tr>
 *  <tr>
 *  <td>Catalog</td>
 *  <td>lifecycleStatus</td>
 *  <td>inStudy</td>
 *  <td>inDesign</td>
 *  </tr>
 *  <tr>
 *  <td>Candidate</td>
 *  <td>lifecycleStatus</td>
 *  <td>inStudy</td>
 *  <td>inDesign</td>
 *  </tr>
 *  <tr>
 *  <td>Default</td>
 *  <td>state</td>
 *  <td>acknowledged</td>
 *  <td>completed</td>
 *  </tr>
 *  </tbody>
 *  </table>
 *       <br>
 *   <li>If at least two of the state, status and/or lifecycleStatus are provided at the same time,
 *       returns 400.
 *   <li>If environment variable ADDITIONAL_FIELDS is provided, splits it using comma, and for each
 *       item, if the item is provided as `key=value`, sets to the resulting payload `"key":
 *       "value"`. If the item is provided without an equals sign, sets to the resulting payload
 *       `"item": "${randomAlphanumeric_10_characters}"
 *   <li>Caches the payload, and returns 200.
 * </ul>
 *
 * @author Gokhan Demir
 */
public class DynamicPostCallback implements ExpectationResponseCallback {

  private static final PayloadCache CACHE = PayloadCache.getInstance();

  @Override
  public HttpResponse handle(HttpRequest httpRequest) {
    HttpResponse authError = TokenEnforcer.getInstance().validateForRequest(httpRequest);
    if (authError != null) {
      return authError;
    }

    HttpResponse replay = IdempotencyGuard.precheck(httpRequest);
    if (replay != null) {
      return replay;
    }

    // Parse the request body
    String body = httpRequest.getBodyAsString();
    ObjectNode parsedBody = (ObjectNode) JacksonUtil.readAsTree(body);

    RequestContext ctx = RequestContext.initialize(httpRequest, false, parsedBody);

    prepareForCache(ctx, parsedBody);

    // Atomic insert-if-absent closes the check-then-put race two concurrent POSTs with the
    // same client-supplied id used to hit (both saw the id absent, both reached CACHE.put,
    // and the second raised IllegalArgumentException surfacing as 500).
    if (!CACHE.putIfAbsent(ctx, parsedBody)) {
      return getErrorResponse(
          HttpStatusCode.BAD_REQUEST_400, "[" + ctx.getId() + "] already exists.");
    }

    String responseJson = JacksonUtil.writeAsString(parsedBody);
    HttpResponse response =
        HttpResponse.response()
            .withStatusCode(HttpStatusCode.CREATED_201.code())
            .withContentType(MediaType.APPLICATION_JSON)
            .withBody(responseJson);
    IdempotencyGuard.store(httpRequest, response, ctx);
    return response;
  }

  /**
   * Mutates {@code parsedBody} in place with everything DynamicPostCallback adds to a posted
   * payload before caching: generates an id and version (when applicable), sets {@code href},
   * defaults the state/status field, drops any {@code updatedBy}/{@code updatedDate}, sets create
   * audit fields, and applies {@code ADDITIONAL_FIELDS}. Does not touch the cache or check for
   * existing ids; the caller is responsible for both.
   */
  public static void prepareForCache(RequestContext ctx, ObjectNode parsedBody) {
    ctx.generateNewIdIfNecessary();
    prepareForCacheWithHref(ctx, parsedBody, ctx.toHref());
  }

  /**
   * Same as {@link #prepareForCache(RequestContext, ObjectNode)} but uses a caller-supplied {@code
   * href} instead of {@link RequestContext#toHref()}. Useful when the request path already contains
   * the resource id (e.g. PUT {basePath}/{id}), where {@code toHref()} would produce a doubled id.
   * The caller is expected to have already populated {@code ctx.getId()} (id and, when versioned,
   * version).
   */
  public static void prepareForCacheWithHref(
      RequestContext ctx, ObjectNode parsedBody, String href) {
    parsedBody.put(ID, ctx.getId().getId());
    if (ctx.isVersioned()) {
      parsedBody.put(VERSION, ctx.getId().getVersion());
    }
    parsedBody.put(HREF, href);

    if (!parsedBody.has(ctx.getTmfStatePath().getVariableName())) {
      parsedBody.put(
          ctx.getTmfStatePath().getVariableName(), ctx.getTmfStatePath().getInitialState());
    }

    removeUpdateFieldIfExist(parsedBody);
    setCreateFields(parsedBody);
    addAdditionalFields(parsedBody);
  }

  static void removeUpdateFieldIfExist(ObjectNode objectNode) {
    objectNode.remove(UPDATED_BY);
    objectNode.remove(UPDATED_DATE);
  }

  static void addAdditionalFields(ObjectNode node) {
    String additionalFields = System.getenv(ADDITIONAL_FIELDS);
    if (additionalFields == null) {
      return;
    }
    String[] fields = additionalFields.split(",");
    for (String field : fields) {
      if (field.trim().isEmpty()) {
        continue;
      }
      String[] fieldParts = field.split("=");
      if (fieldParts.length == 2) {
        node.put(fieldParts[0].trim(), fieldParts[1].trim());
      } else {
        node.put(field.trim(), RandomStringUtils.insecure().nextAlphanumeric(10));
      }
    }
  }
}
