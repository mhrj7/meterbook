package com.meterbook.identity.scim;

import java.time.OffsetDateTime;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A SCIM user (RFC 7643 §4.1): what SCIM clients can set, plus server-held identity and version.
 */
record ScimUser(
    UUID id,
    UUID keycloakUserId,
    Attributes attributes,
    long version,
    OffsetDateTime created,
    OffsetDateTime lastModified) {

  /** The attributes a SCIM client sets. {@code email} defaults to {@code userName}. */
  record Attributes(
      String userName,
      String externalId,
      String email,
      String givenName,
      String familyName,
      String displayName,
      boolean active) {

    /** Reads a full User resource (POST, PUT); unknown attributes and extensions are ignored. */
    static Attributes from(JsonNode body) {
      if (body == null || !body.isObject()) {
        throw ScimException.invalidSyntax("the body must be a SCIM User object");
      }
      String userName = text(body, "userName");
      if (userName == null || userName.isBlank()) {
        throw ScimException.invalidValue("userName is required");
      }
      JsonNode name = field(body, "name");
      return new Attributes(
              userName.trim(),
              text(body, "externalId"),
              primaryEmail(field(body, "emails")),
              name == null ? null : text(name, "givenName"),
              name == null ? null : text(name, "familyName"),
              text(body, "displayName"),
              bool(field(body, "active"), true))
          .withDefaults();
    }

    Attributes withDefaults() {
      String mail = email == null || email.isBlank() ? userName : email.trim();
      return new Attributes(
          userName,
          externalId,
          mail.toLowerCase(Locale.ROOT),
          givenName,
          familyName,
          displayName,
          active);
    }

    String userNameKey() {
      return userName.toLowerCase(Locale.ROOT);
    }
  }

  String etag() {
    return "W/\"" + version + "\"";
  }

  ObjectNode toJson(Iterable<Map.Entry<UUID, String>> groups) {
    JsonNodeFactory f = JsonNodeFactory.instance;
    ObjectNode out = f.objectNode();
    out.putArray("schemas").add(ScimDiscoveryController.USER_SCHEMA);
    out.put("id", id.toString());
    if (attributes.externalId() != null) {
      out.put("externalId", attributes.externalId());
    }
    out.put("userName", attributes.userName());
    ObjectNode name = out.putObject("name");
    if (attributes.givenName() != null) {
      name.put("givenName", attributes.givenName());
    }
    if (attributes.familyName() != null) {
      name.put("familyName", attributes.familyName());
    }
    if (attributes.displayName() != null) {
      out.put("displayName", attributes.displayName());
    }
    out.put("active", attributes.active());
    ArrayNode emails = out.putArray("emails");
    emails.addObject().put("value", attributes.email()).put("type", "work").put("primary", true);
    ArrayNode memberOf = out.putArray("groups");
    for (Map.Entry<UUID, String> group : groups) {
      memberOf
          .addObject()
          .put("value", group.getKey().toString())
          .put("display", group.getValue())
          .put("$ref", "/scim/v2/Groups/" + group.getKey());
    }
    ObjectNode meta = out.putObject("meta");
    meta.put("resourceType", "User");
    meta.put("created", created.toString());
    meta.put("lastModified", lastModified.toString());
    meta.put("location", "/scim/v2/Users/" + id);
    meta.put("version", etag());
    return out;
  }

  // SCIM attribute names are case-insensitive (RFC 7643 §2.1).
  static JsonNode field(JsonNode node, String name) {
    for (Iterator<Map.Entry<String, JsonNode>> it = node.properties().iterator(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      if (e.getKey().equalsIgnoreCase(name)) {
        return e.getValue().isNull() ? null : e.getValue();
      }
    }
    return null;
  }

  static String text(JsonNode node, String name) {
    JsonNode value = field(node, name);
    if (value == null) {
      return null;
    }
    if (!value.isString()) {
      throw ScimException.invalidValue(name + " must be a string");
    }
    return value.asString();
  }

  /** SCIM booleans; Entra ID sends them as the strings "True" and "False". */
  static boolean bool(JsonNode value, boolean fallback) {
    if (value == null) {
      return fallback;
    }
    if (value.isBoolean()) {
      return value.asBoolean();
    }
    if (value.isString()
        && (value.asString().equalsIgnoreCase("true")
            || value.asString().equalsIgnoreCase("false"))) {
      return Boolean.parseBoolean(value.asString().toLowerCase(Locale.ROOT));
    }
    throw ScimException.invalidValue("active must be a boolean");
  }

  static String primaryEmail(JsonNode emails) {
    if (emails == null) {
      return null;
    }
    if (!emails.isArray()) {
      throw ScimException.invalidValue("emails must be a list");
    }
    String first = null;
    for (JsonNode email : emails) {
      String value = text(email, "value");
      if (value == null) {
        continue;
      }
      if (bool(field(email, "primary"), false)) {
        return value;
      }
      if (first == null) {
        first = value;
      }
    }
    return first;
  }
}
