package com.msfg.rag.config;

import com.msfg.rag.pack.DomainPack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainPackConfigTest {

    private final DomainPackConfig config = new DomainPackConfig();

    @Test
    void loadsPackFromPathAndAcceptsMatchingSlug() {
        DomainPack pack = config.domainPack("packs/msfg-mortgage", "mortgage");
        assertEquals("mortgage", pack.slug());
    }

    @Test
    void rejectsSlugMismatchAtBoot() {
        var ex = assertThrows(IllegalStateException.class,
                () -> config.domainPack("packs/msfg-mortgage", "roofing"));
        assertTrue(ex.getMessage().contains("mortgage"), ex.getMessage());
        assertTrue(ex.getMessage().contains("roofing"), ex.getMessage());
    }
}
