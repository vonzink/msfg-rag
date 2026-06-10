package com.msfg.rag.pack;

import java.nio.file.Path;

/** Loads the real MSFG pack for tests (working dir = repo root under Gradle). */
public final class TestPacks {

    private static final DomainPack MSFG = new DomainPackLoader().load(Path.of("packs/msfg-mortgage"));

    private TestPacks() {}

    public static DomainPack msfg() {
        return MSFG;
    }
}
