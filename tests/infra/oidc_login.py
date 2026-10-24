"""Signs a user in to the Meterbook realm the way a browser does, and prints the access token.

Authorization code flow with PKCE against the public portal client: fetch the login page, submit the username form,
then the password form (organizations make Keycloak ask for the username first), stop at the redirect back to the
portal and exchange its code. No realm settings are changed and no password grant is needed.

Environment: KC (Keycloak base URL), PORTAL (portal base URL, a registered redirect), LOGIN_USER, LOGIN_PASSWORD
(not USERNAME: zsh treats that name as special and overrides it).
Standard library only: it runs in the verification tools image.
"""

import base64
import hashlib
import html
import http.cookiejar
import json
import os
import re
import secrets
import sys
import urllib.error
import urllib.parse
import urllib.request

REALM = "meterbook"
CLIENT = "meterbook-portal"


class StopAtRedirect(urllib.request.HTTPRedirectHandler):
    """Follows Keycloak's own redirects; stops at the one back to the portal, which is not running."""

    def __init__(self, target):
        self.target = target

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if newurl.startswith(self.target):
            raise Redirected(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class InClusterCookies(http.cookiejar.DefaultCookiePolicy):
    """Keycloak marks its login cookies Secure. In the cluster it is reached over plain HTTP, the hop the mesh
    encrypts (ADR-0011), so a browser-like client must still send them back."""

    def return_ok_secure(self, cookie, request):
        return True


class Redirected(Exception):
    def __init__(self, url):
        super().__init__(url)
        self.url = url


def form(page):
    """The login form's action and the names of its inputs."""
    match = re.search(r'<form[^>]*\baction="([^"]+)"', page)
    if not match:
        raise SystemExit("login: no form on the page: " + re.sub(r"\s+", " ", page)[:300])
    names = re.findall(r'<input[^>]*\bname="([^"]+)"', page)
    return html.unescape(match.group(1)), names


def main():
    kc, portal = os.environ["KC"].rstrip("/"), os.environ["PORTAL"].rstrip("/")
    username, password = os.environ["LOGIN_USER"], os.environ["LOGIN_PASSWORD"]
    redirect = portal + "/callback"

    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    opener = urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar(InClusterCookies())), StopAtRedirect(redirect))
    auth = f"{kc}/realms/{REALM}/protocol/openid-connect/auth?" + urllib.parse.urlencode({
        "client_id": CLIENT, "response_type": "code", "scope": "openid", "redirect_uri": redirect,
        "code_challenge": challenge, "code_challenge_method": "S256", "state": secrets.token_urlsafe(16)})

    try:
        page = opener.open(auth, timeout=10).read().decode()
        for _ in range(3):  # username form, then password form
            action, names = form(page)
            fields = {}
            if "username" in names:
                fields["username"] = username
            if "password" in names:
                fields["password"] = password
            if not fields:
                raise SystemExit("login: unexpected form with inputs " + ",".join(names))
            page = opener.open(action, urllib.parse.urlencode(fields).encode(), timeout=10).read().decode()
        error = re.search(r'class="[^"]*(?:alert-error|kc-feedback-text)[^"]*"[^>]*>(.*?)<', page, re.S)
        raise SystemExit("login: not signed in" + (": " + html.unescape(error.group(1)).strip() if error else ""))
    except Redirected as done:
        query = urllib.parse.parse_qs(urllib.parse.urlparse(done.url).query)
        if "code" not in query:
            raise SystemExit("login: redirected without a code: " + ",".join(query))
        code = query["code"][0]

    token = json.load(opener.open(f"{kc}/realms/{REALM}/protocol/openid-connect/token", urllib.parse.urlencode({
        "grant_type": "authorization_code", "client_id": CLIENT, "code": code, "redirect_uri": redirect,
        "code_verifier": verifier}).encode(), timeout=10))
    print(token["access_token"])


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as e:
        sys.exit(f"login: HTTP {e.code} from {e.url}")
