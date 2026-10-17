# JSONTestSuite parsing corpus

The `*.json` files are copied unchanged from `test_parsing/` of
[JSONTestSuite](https://github.com/nst/JSONTestSuite) by Nicolas Seriot, MIT License (see `LICENSE`),
at commit `1ef36fa01286573e846ac449e8683f8833c5b26a`.

File name prefixes:

- `y_`: valid JSON (RFC 8259). A parser must accept it.
- `n_`: invalid JSON. A parser must reject it.
- `i_`: the RFC leaves it to the implementation (huge numbers, lone surrogate escapes, invalid UTF-8,
  other encodings, deep nesting). Either outcome is allowed.

`JsonTestSuiteTest` runs every file through the reader's generic validation (`skipValue` + `finish`) and, where the
document is an array holding one scalar, through the matching typed reader as well.

Each `i_` file has an expectation pinned in `JsonTestSuiteTest.INFORMATIVE`. The expectation is Jackson 3.1's
(`JsonMapper.defaultJsonMapper()`, reading the bytes) except for these deliberate deviations:

| File | Ours | Jackson | Reason |
|---|---|---|---|
| `i_string_not_in_unicode_range.json` | reject | accept | Invalid UTF-8 is rejected: F4 BF.. encodes a code point above U+10FFFF (RFC 3629). |
| `i_string_overlong_sequence_2_bytes.json` | reject | accept | Invalid UTF-8 is rejected: C0 AF is an overlong form (RFC 3629). |
| `i_string_UTF-16LE_with_BOM.json` | reject | accept | Input must be UTF-8 (RFC 8259 section 8.1); no encoding detection. |
| `i_string_utf16BE_no_BOM.json` | reject | accept | Same: UTF-16 is not accepted. |
| `i_string_utf16LE_no_BOM.json` | reject | accept | Same: UTF-16 is not accepted. |
| `i_structure_UTF-8_BOM_empty_object.json` | reject | accept | A byte order mark is not skipped. RFC 8259 forbids sending one and only permits ignoring it. |
| `i_object_key_lone_2nd_surrogate.json` | accept | reject | A lone surrogate escape decodes to U+FFFD in keys as in values. Jackson accepts it in values but rejects it in keys. |

Two values differ while both parsers accept. First, the integer token `-0` read as a double is -0.0 here, as
`Double.parseDouble` reads it; Jackson's double path yields +0.0 (`JsonTestSuiteTest.NEGATIVE_ZERO_VALUES`). Second, a lone surrogate escape (`"\uDADA"`) decodes to U+FFFD here, as the
JDK's UTF-8 codec does. Jackson keeps the unpaired UTF-16 unit. `JsonTestSuiteTest.LONE_SURROGATE_VALUES` lists
the affected files.
