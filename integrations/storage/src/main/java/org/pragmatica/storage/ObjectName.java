package org.pragmatica.storage;

import org.pragmatica.lang.Result;


/// A name an object can be bound to inside one namespace: 1 to 512 characters, none of them a control character.
/// A name is a label for listing and lookup; it never decides where bytes are stored.
public record ObjectName(String value) {
    private static final int MAX_LENGTH = 512;

    public static Result<ObjectName> objectName(String value) {
        return value.isEmpty() || value.length() > MAX_LENGTH || value.chars()
                                                                      .anyMatch(Character::isISOControl)
               ? ContentStoreError.InvalidIdentifier.invalidIdentifier("object name",
                                                                       "must be 1 to " + MAX_LENGTH
                                                                      + " characters with no control character").result()
               : Result.success(new ObjectName(value));
    }
}
