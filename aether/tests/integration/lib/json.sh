#!/bin/bash
# json.sh — Minimal shell-based JSON utilities for integration tests
#
# These are NOT general-purpose JSON parsers. They handle the specific patterns
# produced by Aether management APIs — predictable, flat-ish JSON with known fields.
# For complex queries, use the Aether CLI with --format value --field <dotpath>.

# Extract a string or number value for a given key from JSON
# Handles: "key":"value", "key": "value", "key":123, "key": null
# Usage: json_value '{"name":"test","count":5}' "name" → test
json_value() {
    local json="$1" key="$2"
    # Match "key": "string_value" or "key": number_value
    local result
    result=$(printf '%s' "$json" | sed -n "s/.*\"${key}\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p")
    if [ -n "$result" ]; then
        printf '%s' "$result"
        return 0
    fi
    # Try number/boolean/null (unquoted value)
    result=$(printf '%s' "$json" | sed -n "s/.*\"${key}\"[[:space:]]*:[[:space:]]*\([^,}\"][ ]*[^,}\"]*\).*/\1/p" | sed 's/[[:space:]]*$//')
    printf '%s' "$result"
}

# Extract a nested value using dot-path notation
# Usage: json_path '{"a":{"b":"val"}}' "a.b" → val
json_path() {
    local json="$1" path="$2"
    # Split path and navigate
    local current="$json"
    IFS='.' read -ra parts <<< "$path"
    for part in "${parts[@]}"; do
        current=$(json_value "$current" "$part")
    done
    printf '%s' "$current"
}

# Count elements in a JSON array at a given key
# Usage: json_array_length '[1,2,3]' → 3
#        json_array_length '{"items":[1,2,3]}' "items" → 3
json_array_length() {
    local json="$1" key="${2:-}"
    local array_content
    if [ -n "$key" ]; then
        # Extract array for the given key
        array_content=$(printf '%s' "$json" | sed "s/.*\"${key}\"[[:space:]]*:[[:space:]]*\[//" | sed 's/\].*//')
    else
        # Top-level array
        array_content=$(printf '%s' "$json" | sed 's/^\[//' | sed 's/\]$//')
    fi
    if [ -z "$array_content" ] || [ "$array_content" = "$json" ]; then
        echo "0"
        return
    fi
    # Count elements by counting commas + 1 (handles nested objects by counting top-level commas)
    # Simple approach: count occurrences of },{ or "," between simple values
    local count
    count=$(printf '%s' "$array_content" | awk -F'},{' '{print NF}')
    echo "$count"
}

# Check if JSON contains a key with a specific string value
# Usage: json_contains '{"status":"ACTIVE"}' "status" "ACTIVE" → exit 0/1
json_contains() {
    local json="$1" key="$2" value="$3"
    printf '%s' "$json" | grep -q "\"${key}\"[[:space:]]*:[[:space:]]*\"${value}\""
}

# Check if JSON contains a key with a specific numeric value
json_contains_num() {
    local json="$1" key="$2" value="$3"
    printf '%s' "$json" | grep -q "\"${key}\"[[:space:]]*:[[:space:]]*${value}"
}

# Escape a string for safe embedding in JSON
# Usage: escape_json "hello \"world\"\nnewline" → hello \"world\"\\nnewline
escape_json() {
    local str="$1"
    printf '%s' "$str" | sed 's/\\/\\\\/g; s/"/\\"/g' | awk '{printf "%s\\n", $0}' | sed '$ s/\\n$//'
}

# Count occurrences of a value in a JSON array of objects
# Usage: json_count_matching '{"items":[{"s":"A"},{"s":"B"},{"s":"A"}]}' "items" "s" "A" → 2
json_count_matching() {
    local json="$1" array_key="$2" field="$3" value="$4"
    printf '%s' "$json" | grep -o "\"${field}\"[[:space:]]*:[[:space:]]*\"${value}\"" | wc -l | tr -d ' '
}
