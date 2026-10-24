package com.meterbook.identity.scim;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code /scim/v2/Users} (RFC 7644 §3), for the tenant whose SCIM token made the request. Request
 * content types are checked while reading the body, so a wrong one still gets a SCIM error.
 */
@RestController
@RequestMapping(
    path = "/scim/v2/Users",
    produces = {ScimErrors.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
public class ScimUsersController {

  static final Set<String> FILTERABLE = Set.of("userName", "externalId");
  private static final MediaType SCIM = MediaType.parseMediaType(ScimErrors.MEDIA_TYPE);

  private final ScimUsers users;

  public ScimUsersController(ScimUsers users) {
    this.users = users;
  }

  @PostMapping
  ResponseEntity<ObjectNode> create(ScimAuthentication auth, @RequestBody JsonNode body) {
    ScimUser user = users.create(auth.tenantId(), ScimUser.Attributes.from(body));
    return ResponseEntity.created(URI.create("/scim/v2/Users/" + user.id()))
        .contentType(SCIM)
        .header(HttpHeaders.ETAG, user.etag())
        .body(user.toJson(List.of()));
  }

  @GetMapping("/{id}")
  ResponseEntity<ObjectNode> get(ScimAuthentication auth, @PathVariable UUID id) {
    return render(users.get(auth.tenantId(), id));
  }

  @GetMapping
  ResponseEntity<Map<String, Object>> list(
      ScimAuthentication auth,
      @RequestParam(name = "filter", required = false) String filter,
      @RequestParam(name = "startIndex", defaultValue = "1") int startIndex,
      @RequestParam(name = "count", defaultValue = "100") int count) {
    int start = Math.max(1, startIndex);
    int size = Math.clamp(count, 0, ScimDiscoveryController.MAX_RESULTS);
    var page = users.list(auth.tenantId(), ScimFilter.parse(filter, FILTERABLE), start, size);
    List<ObjectNode> resources =
        page.getValue().stream().map(u -> u.toJson(users.groupsOf(u))).toList();
    return ResponseEntity.ok()
        .contentType(SCIM)
        .body(
            Map.of(
                "schemas", List.of(ScimDiscoveryController.LIST_SCHEMA),
                "totalResults", page.getKey(),
                "startIndex", start,
                "itemsPerPage", resources.size(),
                "Resources", resources));
  }

  @PutMapping("/{id}")
  ResponseEntity<ObjectNode> replace(
      ScimAuthentication auth,
      @PathVariable UUID id,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody JsonNode body) {
    return render(users.replace(auth.tenantId(), id, ScimUser.Attributes.from(body), ifMatch));
  }

  @PatchMapping("/{id}")
  ResponseEntity<ObjectNode> patch(
      ScimAuthentication auth,
      @PathVariable UUID id,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody JsonNode body) {
    return render(users.patch(auth.tenantId(), id, ScimPatch.operations(body), ifMatch));
  }

  @DeleteMapping("/{id}")
  ResponseEntity<Void> delete(ScimAuthentication auth, @PathVariable UUID id) {
    users.delete(auth.tenantId(), id);
    return ResponseEntity.noContent().build();
  }

  private ResponseEntity<ObjectNode> render(ScimUser user) {
    return ResponseEntity.ok()
        .contentType(SCIM)
        .header(HttpHeaders.ETAG, user.etag())
        .body(user.toJson(users.groupsOf(user)));
  }
}
