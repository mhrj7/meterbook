package com.meterbook.identity.sso;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SSO settings.
 *
 * @param publicIssuer Keycloak's public realm URL; the SP URLs a tenant configures in their IdP are
 *     built from it
 * @param allowInsecureIdpUrls accept {@code http://} IdP URLs; only for local test IdPs
 */
@ConfigurationProperties("identity.sso")
public record SsoProperties(String publicIssuer, boolean allowInsecureIdpUrls) {}
