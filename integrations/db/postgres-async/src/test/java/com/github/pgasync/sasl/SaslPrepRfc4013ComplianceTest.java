package com.github.pgasync.sasl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class SaslPrepRfc4013ComplianceTest {

    @Test
    public void emptyString(){
        assertEquals("", SaslPrep.asQueryString(""));
    }

    @Test
    public void mappingToNothing1(){
        assertEquals("IX", SaslPrep.asQueryString("I\u00ADX"));
    }

    @Test
    public void noTransformationLowercase(){
        assertEquals("user", SaslPrep.asQueryString("user"));
    }

    @Test
    public void noTransformationUppercase(){
        assertEquals("USER", SaslPrep.asQueryString("USER"));
    }

    @Test
    public void ISO8859_1ToNFKC(){
        assertEquals("a", SaslPrep.asQueryString("\u00AA"));
    }

    @Test
    public void mappingToNFKC(){
        assertEquals("IX", SaslPrep.asQueryString("\u2168"));
    }

    @Test
    public void prohibitedCharacter(){
        assertThrows(IllegalStateException.class, () -> {
            SaslPrep.asQueryString("\u0007");
        });
    }

    @Test
    public void bidiViolation(){
        assertThrows(IllegalStateException.class, () -> {
            SaslPrep.asQueryString("\u0627\u0031");
        });
    }
}
