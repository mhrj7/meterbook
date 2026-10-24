package com.meterbook.identity.scim;

import com.meterbook.identity.db.Tables;
import com.meterbook.identity.security.Role;
import com.meterbook.identity.security.TestCallers;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;

/** A tenant with one domain and a SCIM token, for SCIM API tests. */
record ScimTestSupport(UUID tenant, String domain, String token) {

  static ScimTestSupport tenant(DSLContext db, ScimTokens tokens) {
    UUID id = UUID.randomUUID();
    String domain = "t" + id.toString().substring(0, 8) + ".test";
    db.insertInto(Tables.TENANTS)
        .set(Tables.TENANTS.ID, id)
        .set(Tables.TENANTS.SLUG, "t-" + id.toString().substring(0, 8))
        .set(Tables.TENANTS.NAME, "Tenant")
        .set(Tables.TENANTS.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
        .execute();
    db.insertInto(Tables.TENANT_DOMAINS)
        .set(Tables.TENANT_DOMAINS.DOMAIN, domain)
        .set(Tables.TENANT_DOMAINS.TENANT_ID, id)
        .execute();
    String token =
        tokens
            .issue(
                TestCallers.tenantUser(UUID.randomUUID(), id, Role.TENANT_ADMIN).caller(), "test")
            .secret();
    return new ScimTestSupport(id, domain, token);
  }

  String email(String local) {
    return local + "@" + domain;
  }

  String user(String local) {
    return """
        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"%s","externalId":"ext-%s",
         "name":{"givenName":"Jane","familyName":"Doe"},"active":true,
         "emails":[{"value":"%s","type":"work","primary":true}],
         "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User":{"department":"Finance"}}"""
        .formatted(email(local), local, email(local));
  }
}
