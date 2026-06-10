package com.msfg.rag.pack;

import java.nio.file.Path;

/** Loads the real MSFG pack for tests (working dir = repo root under Gradle). */
public final class TestPacks {

    private static DomainPack msfg;

    private TestPacks() {}

    /** Lazy + memoized: a broken pack fails each test with the loader's own message. */
    public static synchronized DomainPack msfg() {
        if (msfg == null) {
            msfg = new DomainPackLoader().load(Path.of("packs/msfg-mortgage"));
        }
        return msfg;
    }
}
