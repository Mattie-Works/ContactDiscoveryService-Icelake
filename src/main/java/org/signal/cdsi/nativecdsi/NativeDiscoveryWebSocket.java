/* SPDX-License-Identifier: AGPL-3.0-only */
package org.signal.cdsi.nativecdsi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.http.scope.RequestScope;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.OnClose;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.OnOpen;
import io.micronaut.websocket.annotation.ServerWebSocket;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.signal.cdsi.limits.RateLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Explicitly non-attested, authenticated, single-query custom-client endpoint.
 *
 * NOTE: Production deployment must enforce WSS at its trusted reverse proxy,
 * prevent plaintext access to the backend, and apply the existing connection
 * limiter. Enabling this endpoint does NOT yet populate the native directory
 * or disable the SGX application context.
 */
@Requires(property = "cdsi.backend", value = "native")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ServerWebSocket("/v2/native/discovery")
@RequestScope
public final class NativeDiscoveryWebSocket {
  private enum Phase { AWAIT_ACCEPT, PREPARING, AWAIT_QUERY, DONE }

  private static final Logger log = LoggerFactory.getLogger(NativeDiscoveryWebSocket.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int MAX_MESSAGE_BYTES = 1 << 20;

  private final NativeLookupService lookupService;
  private CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
  private Phase phase = Phase.AWAIT_ACCEPT;
  private String user;
  private int expectedCount;
  private volatile boolean closed;

  public NativeDiscoveryWebSocket(NativeLookupService lookupService) {
    this.lookupService = lookupService;
  }

  @OnOpen
  public synchronized void onOpen(WebSocketSession session) {
    // @Secured authenticates before this handler; never accept anonymous sessions.
    user = session.getUserPrincipal().orElseThrow(
        () -> new IllegalStateException("unauthenticated WebSocket")).getName();
    chain = chain.thenCompose(ignored -> session.sendAsync(NativeProtocol.CAPABILITIES_JSON))
        .thenAccept(ignored -> {})
        .exceptionally(error -> closeOnFailure(session, error));
  }

  @OnMessage(maxPayloadLength = MAX_MESSAGE_BYTES)
  public synchronized void onMessage(WebSocketSession session, String message) {
    if (closed) return;
    chain = chain.thenCompose(ignored -> process(session, message))
        .exceptionally(error -> closeOnFailure(session, error));
  }

  private CompletableFuture<Void> process(WebSocketSession session, String message) {
    if (closed) return CompletableFuture.completedFuture(null);
    try {
      JsonNode request = JSON.readTree(message);
      if (request == null || !request.isObject())
        throw new IllegalArgumentException("invalid frame");

      return switch (phase) {
        case AWAIT_ACCEPT -> accept(session, request);
        case AWAIT_QUERY -> query(session, request);
        default -> throw new IllegalArgumentException("invalid protocol state");
      };
    } catch (java.io.IOException error) {
      throw new IllegalArgumentException("malformed JSON frame", error);
    }
  }

  private CompletableFuture<Void> accept(WebSocketSession session, JsonNode request) {
    if (!"accept".equals(string(request, "type"))
        || !NativeProtocol.VERSION.equals(string(request, "protocol")))
      throw new IllegalArgumentException("explicit native protocol acceptance required");

    JsonNode countNode = request.get("count");
    if (countNode == null || !countNode.isInt())
      throw new IllegalArgumentException("invalid lookup count");

    int count = countNode.intValue();
    phase = Phase.PREPARING;
    expectedCount = count;

    return lookupService.prepare(user, count)
        .thenCompose(token -> {
          ObjectNode response = JSON.createObjectNode();
          response.put("type", "token");
          response.put("token", HexFormat.of().formatHex(token));
          phase = Phase.AWAIT_QUERY;
          return session.sendAsync(response.toString());
        }).thenAccept(ignored -> {});
  }

  private CompletableFuture<Void> query(WebSocketSession session, JsonNode request) {
    if (!"query".equals(string(request, "type")))
      throw new IllegalArgumentException("expected query frame");

    String tokenHex = string(request, "token");
    if (tokenHex == null || !tokenHex.matches("[0-9a-fA-F]{64}"))
      throw new IllegalArgumentException("invalid token");

    JsonNode entries = request.get("entries");
    if (entries == null || !entries.isArray() || entries.size() != expectedCount)
      throw new IllegalArgumentException("query count mismatch");

    List<NativeLookupQuery> queries = new ArrayList<>(expectedCount);
    for (JsonNode entry : entries) {
      JsonNode number = entry.get("e164");
      if (number == null || !number.isIntegralNumber() || !number.canConvertToLong()
          || number.longValue() <= 0)
        throw new IllegalArgumentException("invalid E164");
      String uakHex = string(entry, "uak");
      byte[] uak = null;
      if (uakHex != null) {
        if (!uakHex.matches("[0-9a-fA-F]{32}"))
          throw new IllegalArgumentException("invalid UAK");
        uak = HexFormat.of().parseHex(uakHex);
      }
      queries.add(new NativeLookupQuery(number.longValue(), uak));
    }

    phase = Phase.DONE;
    return lookupService.lookup(user, HexFormat.of().parseHex(tokenHex), queries)
        .thenCompose(results -> {
          ObjectNode response = JSON.createObjectNode();
          response.put("type", "result");
          ArrayNode values = response.putArray("entries");
          for (NativeLookupResult result : results) {
            ObjectNode item = values.addObject();
            item.put("e164", result.e164());
            if (result.pni() == null) item.putNull("pni");
            else item.put("pni", HexFormat.of().formatHex(result.pni()));
            if (result.aci() == null) item.putNull("aci");
            else item.put("aci", HexFormat.of().formatHex(result.aci()));
          }
          return session.sendAsync(response.toString());
        }).thenAccept(ignored -> {
          closed = true;
          session.close(CloseReason.NORMAL);
        });
  }

  private static String string(JsonNode value, String field) {
    if (value == null || !value.isObject()) return null;
    JsonNode child = value.get(field);
    return child != null && child.isTextual() ? child.textValue() : null;
  }

  private Void closeOnFailure(WebSocketSession session, Throwable failure) {
    closed = true;
    Throwable cause = failure;
    while ((cause instanceof java.util.concurrent.CompletionException
        || cause instanceof java.util.concurrent.ExecutionException)
        && cause.getCause() != null) cause = cause.getCause();

    if (cause instanceof RateLimitExceededException exceeded) {
      session.close(new CloseReason(4008, "rate limit exceeded; retry after "
          + exceeded.getRetryDuration().toSeconds() + " seconds"));
    } else if (cause instanceof IllegalArgumentException) {
      session.close(new CloseReason(4003, "invalid native discovery request"));
    } else {
      // Avoid including phone numbers, tokens or UAK data in logs or close reasons.
      log.warn("Native discovery session failed: {}", cause.getClass().getSimpleName());
      session.close(new CloseReason(4013, "native discovery unavailable"));
    }
    return null;
  }

  @OnClose
  public synchronized void onClose(WebSocketSession session) {
    closed = true;
  }
}
