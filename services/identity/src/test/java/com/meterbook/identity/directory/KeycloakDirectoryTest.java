package com.meterbook.identity.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterbook.identity.support.KeycloakAdmin;
import com.meterbook.identity.support.KeycloakContainer;
import com.meterbook.identity.support.MutableClock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The Keycloak adapter against a real Keycloak running Meterbook's realm. */
@Testcontainers
class KeycloakDirectoryTest {

  @Container static final KeycloakContainer KEYCLOAK = new KeycloakContainer();

  private static final MutableClock CLOCK = new MutableClock();
  private static KeycloakDirectory directory;
  private static KeycloakAdmin admin;

  @BeforeAll
  static void connect() {
    directory = directory(KEYCLOAK.baseUrl(), KeycloakContainer.IDENTITY_CLIENT_SECRET);
    admin = new KeycloakAdmin(KEYCLOAK);
  }

  private static KeycloakDirectory directory(String baseUrl, String secret) {
    return new KeycloakDirectory(
        RestClient.builder(),
        new KeycloakProperties(
            baseUrl, "meterbook", "meterbook-identity", secret, Duration.ofSeconds(2)),
        CLOCK);
  }

  private static String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  @Test
  void creates_and_deletes_organizations() {
    String alias = unique("org");
    UUID id = directory.createOrganization("Org " + alias, alias, List.of(alias + ".test"));
    assertThat(admin.organizationExists(id)).isTrue();

    directory.deleteOrganization(id);
    directory.deleteOrganization(id); // deleting twice is fine
    assertThat(admin.organizationExists(id)).isFalse();
  }

  @Test
  void a_domain_or_name_already_in_use_is_a_conflict() {
    String alias = unique("dup");
    directory.createOrganization("Name " + alias, alias, List.of(alias + ".test"));

    assertThatThrownBy(
            () ->
                directory.createOrganization(
                    "Other " + alias, unique("x"), List.of(alias + ".test")))
        .isInstanceOf(Directory.ConflictException.class);
    assertThatThrownBy(
            () ->
                directory.createOrganization(
                    "Name " + alias, unique("y"), List.of(unique("z") + ".test")))
        .isInstanceOf(Directory.ConflictException.class);
  }

  @Test
  void manages_users_memberships_and_roles() {
    String alias = unique("acme");
    String email = "ada@" + alias + ".test";
    UUID organization = directory.createOrganization(alias, alias, List.of(alias + ".test"));

    assertThat(directory.findUserByEmail(email)).isEmpty();
    UUID user = directory.createUser(email, "Ada", "Lovelace");
    assertThat(directory.findUserByEmail(email)).contains(user);
    assertThatThrownBy(() -> directory.createUser(email, "", ""))
        .isInstanceOf(Directory.ConflictException.class);

    assertThat(directory.organizationsOf(user)).isEmpty();
    directory.addMember(organization, user);
    directory.addMember(organization, user); // already a member: no error
    assertThat(directory.organizationsOf(user)).containsExactly(organization);

    directory.grantRealmRole(user, "tenant-admin");
    directory.grantRealmRole(user, "tenant-admin"); // already granted: no error
    assertThat(admin.realmRolesOf(user)).contains("tenant-admin");
    assertThatThrownBy(() -> directory.grantRealmRole(user, "platform-admin"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(admin.realmRolesOf(user)).doesNotContain("platform-admin");

    directory.deleteUser(user);
    directory.deleteUser(user);
    assertThat(admin.userExists(user)).isFalse();
  }

  @Test
  void creates_a_signed_saml_provider_routed_by_the_organizations_domains() throws Exception {
    String alias = unique("acme");
    UUID organization = directory.createOrganization(alias, alias, List.of(alias + ".test"));
    String providerAlias = "sso-" + UUID.randomUUID();
    String spEntityId = KEYCLOAK.issuer() + "/sso/" + providerAlias;
    String certificate =
        new String(
                KeycloakDirectoryTest.class
                    .getResourceAsStream("/certs/idp-valid.crt")
                    .readAllBytes(),
                java.nio.charset.StandardCharsets.US_ASCII)
            .replace("-----BEGIN CERTIFICATE-----", "")
            .replace("-----END CERTIFICATE-----", "")
            .replaceAll("\\s", "");

    directory.createSamlProvider(
        organization,
        new Directory.SamlProvider(
            providerAlias,
            "Acme SSO",
            spEntityId,
            "https://idp.acme.test",
            "https://idp.acme.test/sso",
            certificate));

    var provider = admin.identityProvider(providerAlias);
    var config = provider.get("config");
    assertThat(provider.get("providerId").asString()).isEqualTo("saml");
    assertThat(provider.get("firstBrokerLoginFlowAlias").asString())
        .isEqualTo("meterbook first broker login");
    assertThat(provider.get("trustEmail").asBoolean()).isTrue();
    assertThat(config.get("validateSignature").asString()).isEqualTo("true");
    assertThat(config.get("wantAssertionsSigned").asString()).isEqualTo("true");
    assertThat(config.get("wantAuthnRequestsSigned").asString()).isEqualTo("true");
    assertThat(config.get("signingCertificate").asString()).isEqualTo(certificate);
    assertThat(config.get("entityId").asString()).isEqualTo(spEntityId);
    assertThat(admin.domainsOf(organization))
        .allSatisfy(
            d -> assertThat(d.get("identityProviderAlias").asString()).isEqualTo(providerAlias))
        .allSatisfy(d -> assertThat(d.get("autoRedirect").asBoolean()).isTrue());
    assertThat(admin.identityProvidersOf(organization)).containsExactly(providerAlias);

    String descriptor =
        new String(
            java.net
                .URI
                .create(KEYCLOAK.issuer() + "/broker/" + providerAlias + "/endpoint/descriptor")
                .toURL()
                .openStream()
                .readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
    assertThat(descriptor).contains("entityID=\"" + spEntityId + "\"");

    directory.deleteIdentityProvider(providerAlias);
    directory.deleteIdentityProvider(providerAlias);
    assertThat(admin.identityProviderExists(providerAlias)).isFalse();
  }

  @Test
  void keeps_the_keycloak_user_in_step_with_scim() throws Exception {
    String alias = unique("life");
    UUID organization = directory.createOrganization(alias, alias, List.of(alias + ".test"));
    UUID user = directory.createUser("ann@" + alias + ".test", "Ann", "A");
    directory.addMember(organization, user);
    admin.setPassword(user, "a-long-test-password");
    admin.createPasswordClient("test-cli-" + alias, List.of("basic", "roles"));
    var login =
        java.util.Map.of(
            "grant_type",
            "password",
            "client_id",
            "test-cli-" + alias,
            "username",
            "ann@" + alias + ".test",
            "password",
            "a-long-test-password");
    var tokens = admin.tokenResponse(login);
    assertThat(tokens.statusCode()).isEqualTo(200);
    String refresh =
        new tools.jackson.databind.json.JsonMapper()
            .readTree(tokens.body())
            .get("refresh_token")
            .asString();

    directory.updateUser(user, "annie@" + alias + ".test", "Annie", "B");
    var stored = admin.user(user);
    assertThat(stored.get("email").asString()).isEqualTo("annie@" + alias + ".test");
    assertThat(stored.get("username").asString()).isEqualTo("annie@" + alias + ".test");
    assertThat(stored.get("firstName").asString()).isEqualTo("Annie");

    directory.setEnabled(user, false);
    directory.logout(user);
    assertThat(admin.user(user).get("enabled").asBoolean()).isFalse();
    var refreshed =
        admin.tokenResponse(
            java.util.Map.of(
                "grant_type",
                "refresh_token",
                "client_id",
                "test-cli-" + alias,
                "refresh_token",
                refresh));
    assertThat(refreshed.statusCode()).isEqualTo(400); // the session is gone
    var again = new java.util.HashMap<>(login);
    again.put("username", "annie@" + alias + ".test");
    assertThat(admin.tokenResponse(again).statusCode()).isEqualTo(400); // disabled
    directory.setEnabled(user, true);
    assertThat(admin.tokenResponse(again).statusCode()).isEqualTo(200);

    directory.removeMember(organization, user);
    directory.removeMember(organization, user);
    assertThat(directory.organizationsOf(user)).isEmpty();
  }

  @Test
  void links_a_user_to_an_identity_provider_and_replaces_the_link() throws Exception {
    String alias = unique("link");
    UUID organization = directory.createOrganization(alias, alias, List.of(alias + ".test"));
    String provider = "sso-" + UUID.randomUUID();
    String certificate =
        new String(
                KeycloakDirectoryTest.class
                    .getResourceAsStream("/certs/idp-valid.crt")
                    .readAllBytes(),
                java.nio.charset.StandardCharsets.US_ASCII)
            .replace("-----BEGIN CERTIFICATE-----", "")
            .replace("-----END CERTIFICATE-----", "")
            .replaceAll("\\s", "");
    directory.createSamlProvider(
        organization,
        new Directory.SamlProvider(
            provider,
            "X",
            KEYCLOAK.issuer() + "/sso/x",
            "https://idp",
            "https://idp/sso",
            certificate));
    UUID user = directory.createUser("bob@" + alias + ".test", "", "");

    directory.linkIdentity(user, provider, "bob@" + alias + ".test");
    assertThat(admin.federatedIdentities(user))
        .containsExactly(java.util.Map.entry(provider, "bob@" + alias + ".test"));

    directory.linkIdentity(user, provider, "robert@" + alias + ".test");
    assertThat(admin.federatedIdentities(user))
        .containsExactly(java.util.Map.entry(provider, "robert@" + alias + ".test"));
  }

  @Test
  void renews_its_token_when_it_expires() {
    directory.findUserByEmail("nobody@example.test");
    CLOCK.advance(Duration.ofHours(1));

    assertThat(directory.findUserByEmail("nobody@example.test")).isEmpty();
  }

  @Test
  void an_unreachable_keycloak_is_unavailable_not_an_error() {
    KeycloakDirectory unreachable = directory("http://localhost:1", "x");

    assertThatThrownBy(() -> unreachable.findUserByEmail("a@b.test"))
        .isInstanceOf(Directory.UnavailableException.class);
  }

  @Test
  void wrong_credentials_are_a_configuration_error() {
    KeycloakDirectory misconfigured = directory(KEYCLOAK.baseUrl(), "wrong-secret");

    assertThatThrownBy(() -> misconfigured.findUserByEmail("a@b.test"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("401");
  }
}
