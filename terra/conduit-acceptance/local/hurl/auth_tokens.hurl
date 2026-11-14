# Project-owned (NOT upstream): token-rejection requests the upstream suite lacks (#3236, finding B). The runner registers a user, then mints
# these from that user's real token and the signing secret (local/mint_tokens.py): forged_token (signature altered), none_token (alg=none,
# empty signature), expired_token (valid signature, exp in the past), fresh_token (valid signature, exp in the future: the control that proves
# the minting is accepted), real_token (as issued, for the wrong-scheme request).

# Control: a freshly minted, correctly signed token is accepted
GET {{host}}/api/user
Authorization: Token {{fresh_token}}
HTTP 200

# Forged signature on GET /api/user is 401
GET {{host}}/api/user
Authorization: Token {{forged_token}}
HTTP 401

# alg=none, empty signature on GET /api/user is 401
GET {{host}}/api/user
Authorization: Token {{none_token}}
HTTP 401

# Expired, validly signed on GET /api/user is 401
GET {{host}}/api/user
Authorization: Token {{expired_token}}
HTTP 401

# Wrong scheme: Bearer with a valid token on GET /api/user is 401
GET {{host}}/api/user
Authorization: Bearer {{real_token}}
HTTP 401

# Forged signature on the feed is 401
GET {{host}}/api/articles/feed
Authorization: Token {{forged_token}}
HTTP 401

# Forged signature on the optional-auth listing is 401, not anonymous
GET {{host}}/api/articles
Authorization: Token {{forged_token}}
HTTP 401

# Expired token on the optional-auth listing is 401, not anonymous
GET {{host}}/api/articles
Authorization: Token {{expired_token}}
HTTP 401
