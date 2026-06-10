package com.msfg.rag.config;

import com.msfg.rag.pack.DomainPack;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    // --- ApplicationContextRunner tests: lock the @Value property names ---

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DomainPackConfig.class);

    @Test
    void bindsBrainPropertiesIntoTheBean() {
        contextRunner
                .withPropertyValues("brain.pack=packs/msfg-mortgage", "brain.slug=mortgage")
                .run(context -> {
                    assertNotNull(context.getBean(DomainPack.class));
                    assertEquals("mortgage", context.getBean(DomainPack.class).slug());
                });
    }

    @Test
    void bindsBrainSlugPropertyAndFailsStartupOnMismatch() {
        contextRunner
                .withPropertyValues("brain.pack=packs/msfg-mortgage", "brain.slug=roofing")
                .run(context -> {
                    assertTrue(context.getStartupFailure() != null,
                            "startup must fail on slug mismatch");
                    String chain = String.valueOf(context.getStartupFailure());
                    Throwable t = context.getStartupFailure();
                    boolean found = false;
                    while (t != null) {
                        if (String.valueOf(t.getMessage()).contains("roofing")) { found = true; break; }
                        t = t.getCause();
                    }
                    assertTrue(found, "failure chain must name the mismatched slug: " + chain);
                });
    }

    @Test
    void bindsBrainPackPropertyAndFailsStartupOnBadPath() {
        contextRunner
                .withPropertyValues("brain.pack=packs/does-not-exist", "brain.slug=mortgage")
                .run(context -> {
                    assertTrue(context.getStartupFailure() != null,
                            "startup must fail on missing pack");
                    Throwable t = context.getStartupFailure();
                    boolean found = false;
                    while (t != null) {
                        if (String.valueOf(t.getMessage()).contains("does-not-exist")) { found = true; break; }
                        t = t.getCause();
                    }
                    assertTrue(found, "failure chain must name the bad pack path");
                });
    }
}
