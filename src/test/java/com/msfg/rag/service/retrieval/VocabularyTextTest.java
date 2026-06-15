package com.msfg.rag.service.retrieval;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VocabularyTextTest {

    @Test
    void parsesTermArrowExpansionLines() {
        Map<String, String> m = VocabularyText.parse("duplex => 2-unit 2-4 units\nowner occupied => principal residence");
        assertEquals("2-unit 2-4 units", m.get("duplex"));
        assertEquals("principal residence", m.get("owner occupied"));
        assertEquals(2, m.size());
    }

    @Test
    void parseSkipsBlankAndCommentLinesAndLowercasesTerms() {
        Map<String, String> m = VocabularyText.parse("# a comment\n\nDUPLEX => 2-unit\n   \n");
        assertEquals(Map.of("duplex", "2-unit"), m);
    }

    @Test
    void parseIsLenientAndSkipsMalformedLines() {
        Map<String, String> m = VocabularyText.parse("no arrow here\nduplex => 2-unit\n=> orphan\nterm =>");
        assertEquals(Map.of("duplex", "2-unit"), m);
    }

    @Test
    void serializeIsSortedAndRoundTrips() {
        Map<String, String> in = new LinkedHashMap<>();
        in.put("owner occupied", "principal residence");
        in.put("duplex", "2-unit");
        String text = VocabularyText.serialize(in);
        assertEquals("duplex => 2-unit\nowner occupied => principal residence", text);
        assertEquals(in, VocabularyText.parse(text));
    }

    @Test
    void validateRejectsEmpty() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> VocabularyText.validate("   ")).getMessage().toLowerCase().contains("empty"));
    }

    @Test
    void validateReportsLineNumberOnMissingArrow() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> VocabularyText.validate("duplex => 2-unit\nbroken line"));
        assertTrue(e.getMessage().contains("Line 2"), e.getMessage());
    }

    @Test
    void validateRejectsUppercaseTerm() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> VocabularyText.validate("Duplex => 2-unit"));
        assertTrue(e.getMessage().toLowerCase().contains("lowercase"), e.getMessage());
    }

    @Test
    void validateRejectsMissingExpansion() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> VocabularyText.validate("duplex =>"));
        assertTrue(e.getMessage().contains("Line 1"), e.getMessage());
    }

    @Test
    void validateAcceptsCommentsAndBlanksButRequiresAtLeastOneEntry() {
        assertThrows(IllegalArgumentException.class, () -> VocabularyText.validate("# only a comment\n\n"));
        VocabularyText.validate("# header\nduplex => 2-unit"); // does not throw
    }
}
