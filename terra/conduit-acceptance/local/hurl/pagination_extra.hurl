# Project-owned (NOT upstream): pagination coverage the upstream suite lacks. It pins WHICH article each page returns, for the unfiltered,
# tag, favorited and combined-filter listings and for /feed, so an ignored or clamped limit/offset turns a request red (#3236, finding A).
# The two articles are the newest in the database when the unfiltered pages are read; the runner executes files one at a time.
# Register lpa
POST {{host}}/api/users
{
  "user": {
    "username": "lpa_{{uid}}",
    "email": "lpa_{{uid}}@test.com",
    "password": "password123"
  }
}
HTTP 201
[Captures]
tok_a: jsonpath "$.user.token"

# Register lpf
POST {{host}}/api/users
{
  "user": {
    "username": "lpf_{{uid}}",
    "email": "lpf_{{uid}}@test.com",
    "password": "password123"
  }
}
HTTP 201
[Captures]
tok_f: jsonpath "$.user.token"

# Author creates article 1
POST {{host}}/api/articles
Authorization: Token {{tok_a}}
{
  "article": {
    "title": "Local Page 1 {{uid}}",
    "description": "Local page test 1",
    "body": "Local page body 1",
    "tagList": ["lp_{{uid}}"]
  }
}
HTTP 201
[Captures]
slug1: jsonpath "$.article.slug"

# Author creates article 2
POST {{host}}/api/articles
Authorization: Token {{tok_a}}
{
  "article": {
    "title": "Local Page 2 {{uid}}",
    "description": "Local page test 2",
    "body": "Local page body 2",
    "tagList": ["lp_{{uid}}"]
  }
}
HTTP 201
[Captures]
slug2: jsonpath "$.article.slug"

# Follower follows the author (feed)
POST {{host}}/api/profiles/lpa_{{uid}}/follow
Authorization: Token {{tok_f}}
HTTP 200

# Follower favorites article 1, then article 2
POST {{host}}/api/articles/{{slug1}}/favorite
Authorization: Token {{tok_f}}
HTTP 200

POST {{host}}/api/articles/{{slug2}}/favorite
Authorization: Token {{tok_f}}
HTTP 200

# Unfiltered, first page: the newest article
GET {{host}}/api/articles?limit=1
HTTP 200
[Asserts]
jsonpath "$.articles" count == 1
jsonpath "$.articlesCount" >= 2
jsonpath "$.articles[0].slug" == "{{slug2}}"

# Unfiltered, second page: the next newest
GET {{host}}/api/articles?limit=1&offset=1
HTTP 200
[Asserts]
jsonpath "$.articles" count == 1
jsonpath "$.articlesCount" >= 2
jsonpath "$.articles[0].slug" == "{{slug1}}"

# Tag filter, first page
GET {{host}}/api/articles?tag=lp_{{uid}}&limit=1
HTTP 200
[Asserts]
jsonpath "$.articles" count == 1
jsonpath "$.articlesCount" == 2
jsonpath "$.articles[0].slug" == "{{slug2}}"

# Tag filter, second page
GET {{host}}/api/articles?tag=lp_{{uid}}&limit=1&offset=1
HTTP 200
[Asserts]
jsonpath "$.articles" count == 1
jsonpath "$.articlesCount" == 2
jsonpath "$.articles[0].slug" == "{{slug1}}"

# Favorited filter, first page
GET {{host}}/api/articles?favorited=lpf_{{uid}}&limit=1
HTTP 200
[Asserts]
jsonpath "$.articles" count == 1
jsonpath "$.articlesCount" == 2
jsonpath "$.articles[0].slug" == "{{slug2}}"

# Favorited filter, second page
GET {{host}}/api/articles?favorited=lpf_{{uid}}&limit=1&offset=1
HTTP 200
[Asserts]
jsonpath "$.articles" count == 1
jsonpath "$.articlesCount" == 2
jsonpath "$.articles[0].slug" == "{{slug1}}"

# Combined tag+author filter, first page
GET {{host}}/api/articles?tag=lp_{{uid}}&author=lpa_{{uid}}&limit=1
HTTP 200
[Asserts]
jsonpath "$.articles" count == 1
jsonpath "$.articlesCount" == 2
jsonpath "$.articles[0].slug" == "{{slug2}}"

# Combined tag+author filter, second page
GET {{host}}/api/articles?tag=lp_{{uid}}&author=lpa_{{uid}}&limit=1&offset=1
HTTP 200
[Asserts]
jsonpath "$.articles" count == 1
jsonpath "$.articlesCount" == 2
jsonpath "$.articles[0].slug" == "{{slug1}}"

# Combined favorited+tag filter, second page
GET {{host}}/api/articles?favorited=lpf_{{uid}}&tag=lp_{{uid}}&limit=1&offset=1
HTTP 200
[Asserts]
jsonpath "$.articles" count == 1
jsonpath "$.articlesCount" == 2
jsonpath "$.articles[0].slug" == "{{slug1}}"

# Feed, first page
GET {{host}}/api/articles/feed?limit=1
Authorization: Token {{tok_f}}
HTTP 200
[Asserts]
jsonpath "$.articles" count == 1
jsonpath "$.articlesCount" == 2
jsonpath "$.articles[0].slug" == "{{slug2}}"

# Feed, second page
GET {{host}}/api/articles/feed?limit=1&offset=1
Authorization: Token {{tok_f}}
HTTP 200
[Asserts]
jsonpath "$.articles" count == 1
jsonpath "$.articlesCount" == 2
jsonpath "$.articles[0].slug" == "{{slug1}}"

# Cleanup
DELETE {{host}}/api/articles/{{slug1}}
Authorization: Token {{tok_a}}
HTTP 204

DELETE {{host}}/api/articles/{{slug2}}
Authorization: Token {{tok_a}}
HTTP 204
