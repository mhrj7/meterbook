package com.meterbook.identity.scim;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** A SCIM PatchOp request (RFC 7644 §3.5.2), parsed and validated before anything is changed. */
final class ScimPatch {

  static final String SCHEMA = "urn:ietf:params:scim:api:messages:2.0:PatchOp";

  /** One operation; {@code path} is null when the value names the attributes itself. */
  record Operation(String op, String path, JsonNode value) {}

  private ScimPatch() {}

  static List<Operation> operations(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw ScimException.invalidSyntax("the body must be a PatchOp object");
    }
    JsonNode schemas = ScimUser.field(body, "schemas");
    boolean patchOp = false;
    if (schemas != null && schemas.isArray()) {
      for (JsonNode schema : schemas) {
        patchOp |= SCHEMA.equals(schema.asString());
      }
    }
    if (!patchOp) {
      throw ScimException.invalidSyntax("schemas must contain " + SCHEMA);
    }
    JsonNode ops = ScimUser.field(body, "Operations");
    if (ops == null || !ops.isArray() || ops.isEmpty()) {
      throw ScimException.invalidSyntax("Operations must be a non-empty list");
    }
    List<Operation> out = new ArrayList<>();
    for (JsonNode op : ops) {
      String name = ScimUser.text(op, "op");
      if (name == null) {
        throw ScimException.invalidSyntax("every operation needs an op");
      }
      String normalised = name.toLowerCase(Locale.ROOT);
      if (!List.of("add", "replace", "remove").contains(normalised)) {
        throw ScimException.invalidSyntax("unknown op " + name);
      }
      String path = ScimUser.text(op, "path");
      JsonNode value = ScimUser.field(op, "value");
      if (!normalised.equals("remove") && value == null) {
        throw ScimException.invalidValue(name + " needs a value");
      }
      if (normalised.equals("remove") && path == null) {
        throw ScimException.invalidPath("remove needs a path");
      }
      out.add(new Operation(normalised, path, value));
    }
    return out;
  }

  /** Applies user operations to a copy of {@code current}. */
  static ScimUser.Attributes applyToUser(ScimUser.Attributes current, List<Operation> operations) {
    UserState state = new UserState(current);
    for (Operation op : operations) {
      if (op.path() == null) {
        if (!op.value().isObject()) {
          throw ScimException.invalidValue("an operation without a path needs an object value");
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = op.value().properties().iterator();
            it.hasNext(); ) {
          Map.Entry<String, JsonNode> e = it.next();
          state.set(e.getKey(), e.getValue());
        }
      } else if (op.op().equals("remove")) {
        state.remove(op.path());
      } else {
        state.set(op.path(), op.value());
      }
    }
    return state.toAttributes();
  }

  /** Mutable working copy while operations apply. */
  private static final class UserState {
    String userName;
    String externalId;
    String email;
    String givenName;
    String familyName;
    String displayName;
    boolean active;

    UserState(ScimUser.Attributes a) {
      userName = a.userName();
      externalId = a.externalId();
      email = a.email();
      givenName = a.givenName();
      familyName = a.familyName();
      displayName = a.displayName();
      active = a.active();
    }

    void set(String path, JsonNode value) {
      String p = path.toLowerCase(Locale.ROOT);
      switch (p) {
        case "active" -> active = ScimUser.bool(value, active);
        case "username" -> userName = requiredString(value, "userName");
        case "externalid" -> externalId = string(value, "externalId");
        case "displayname" -> displayName = string(value, "displayName");
        case "name.givenname" -> givenName = string(value, "name.givenName");
        case "name.familyname" -> familyName = string(value, "name.familyName");
        case "name" -> {
          if (!value.isObject()) {
            throw ScimException.invalidValue("name must be an object");
          }
          if (ScimUser.field(value, "givenName") != null) {
            givenName = ScimUser.text(value, "givenName");
          }
          if (ScimUser.field(value, "familyName") != null) {
            familyName = ScimUser.text(value, "familyName");
          }
        }
        case "emails" -> email = ScimUser.primaryEmail(value);
        case "emails[type eq \"work\"].value", "emails[primary eq true].value" ->
            email = string(value, "email");
        default -> {
          // Extension attributes (e.g. the enterprise User schema) are accepted and not stored.
          if (!p.startsWith("urn:")) {
            throw ScimException.invalidPath("cannot change " + path);
          }
        }
      }
    }

    void remove(String path) {
      switch (path.toLowerCase(Locale.ROOT)) {
        case "externalid" -> externalId = null;
        case "displayname" -> displayName = null;
        case "name.givenname" -> givenName = null;
        case "name.familyname" -> familyName = null;
        case "name" -> {
          givenName = null;
          familyName = null;
        }
        case "emails" -> email = null;
        case "username", "active" -> throw ScimException.mutability(path + " cannot be removed");
        default -> throw ScimException.invalidPath("cannot remove " + path);
      }
    }

    ScimUser.Attributes toAttributes() {
      return new ScimUser.Attributes(
              userName, externalId, email, givenName, familyName, displayName, active)
          .withDefaults();
    }

    private static String string(JsonNode value, String name) {
      if (value == null || value.isNull()) {
        return null;
      }
      if (!value.isString()) {
        throw ScimException.invalidValue(name + " must be a string");
      }
      return value.asString();
    }

    private static String requiredString(JsonNode value, String name) {
      String s = string(value, name);
      if (s == null || s.isBlank()) {
        throw ScimException.invalidValue(name + " is required");
      }
      return s.trim();
    }
  }
}
