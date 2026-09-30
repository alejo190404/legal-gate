# Gateway — Glossary

Ubiquitous language for the gateway context. Glossary only: no implementation detail, no spec.

## Auth Edge

The single public boundary in front of every business API. Nothing else in LegalGate is reachable
from a browser.

## Session Token

The signed, short-lived credential a browser presents to prove who is calling and on behalf of
which Tenant. Issued by the auth provider, never minted or stored by LegalGate.
_Avoid_: access token, JWT, bearer, id token

## Authorized Party

The exact browser origin a Session Token was issued for. A token minted for one origin is not
accepted from another, even when its signature and issuer are valid.

## Firm Administrator

The one role that may act on a Tenant's business data. A Session Token that names no Tenant, or
names one without this role, buys nothing beyond onboarding.
_Avoid_: firm_admin, org:admin, owner, superuser

## Service Token

The shared secret that proves a request to an internal service came from the Auth Edge rather
than from the public internet. Distinct from a Session Token: it carries no identity, only
provenance.

## Forwarded Identity

The caller's identity as the Auth Edge restates it for internal services — who they are, which
session, which Tenant, which role. Always derived from a validated Session Token, and always
stripped from inbound requests first, so an internal service can trust it without re-validating.
_Avoid_: identity headers, trusted headers
