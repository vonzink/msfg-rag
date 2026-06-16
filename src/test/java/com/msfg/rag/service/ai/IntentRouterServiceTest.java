package com.msfg.rag.service.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pure unit test for the deterministic, code-driven intent router. No Spring,
 * no mocks — {@code new IntentRouterService()}.
 */
class IntentRouterServiceTest {

    private final IntentRouterService router = new IntentRouterService();

    // --- neutral default ------------------------------------------------

    @Test
    void plainGuidelineQuestionIsGuidelineQuestion() {
        assertEquals(Intent.GUIDELINE_QUESTION,
                router.route("What is PMI?", null, null));
    }

    @Test
    void nullQuestionIsGuidelineQuestion() {
        assertEquals(Intent.GUIDELINE_QUESTION, router.route(null, null, null));
    }

    @Test
    void blankQuestionIsGuidelineQuestion() {
        assertEquals(Intent.GUIDELINE_QUESTION, router.route("   ", null, null));
    }

    // --- pageRoute wins -------------------------------------------------

    @Test
    void nonBlankPageRouteWinsOverEverything() {
        // The question contains a calculation cue ("monthly") AND an
        // external-reference cue ("handbook"), yet pageRoute takes precedence.
        assertEquals(Intent.PAGE_GUIDANCE,
                router.route("what is my monthly payment per the handbook?",
                        "/loan-options", null));
    }

    @Test
    void blankPageRouteDoesNotWin() {
        assertEquals(Intent.GUIDELINE_QUESTION,
                router.route("What is PMI?", "   ", null));
    }

    // --- calculation cues -----------------------------------------------

    @Test
    void calculationCueIsCalculation() {
        assertEquals(Intent.CALCULATION,
                router.route("How much will my monthly payment be?", null, null));
    }

    @Test
    void calculationCueIsCaseInsensitive() {
        assertEquals(Intent.CALCULATION,
                router.route("Calculate my DTI", null, null));
    }

    @Test
    void percentSignIsCalculation() {
        assertEquals(Intent.CALCULATION,
                router.route("Is 3% down enough?", null, null));
    }

    // --- external-reference cues ----------------------------------------

    @Test
    void externalReferenceCueIsExternalReference() {
        assertEquals(Intent.EXTERNAL_REFERENCE,
                router.route("Where can I find the official source?", null, null));
    }

    @Test
    void handbookCueIsExternalReference() {
        assertEquals(Intent.EXTERNAL_REFERENCE,
                router.route("Give me the FHA handbook link", null, null));
    }

    // --- calculation precedence over external-reference -----------------

    @Test
    void calculationIsCheckedBeforeExternalReference() {
        // Contains both "rate" (calc) and "handbook" (external) — calc wins.
        assertEquals(Intent.CALCULATION,
                router.route("what rate does the handbook list?", null, null));
    }

    // --- surface validation ---------------------------------------------

    @Test
    void validSurfaceIsAcceptedAndDoesNotAlterIntent() {
        assertEquals(Intent.GUIDELINE_QUESTION,
                router.route("What is PMI?", null, "PUBLIC"));
    }

    @Test
    void validSurfaceIsCaseInsensitiveAndTrimmed() {
        assertEquals(Intent.GUIDELINE_QUESTION,
                router.route("What is PMI?", null, "  internal  "));
    }

    @Test
    void nullSurfaceIsAccepted() {
        assertEquals(Intent.GUIDELINE_QUESTION,
                router.route("What is PMI?", null, null));
    }

    @Test
    void blankSurfaceIsAccepted() {
        assertEquals(Intent.GUIDELINE_QUESTION,
                router.route("What is PMI?", null, "   "));
    }

    @Test
    void badSurfaceThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> router.route("What is PMI?", null, "SIDEWAYS"));
    }

    @Test
    void badSurfaceThrowsEvenWhenPageRouteWouldWin() {
        // Surface is validated up front, independent of the winning branch.
        assertThrows(IllegalArgumentException.class,
                () -> router.route("What is PMI?", "/loan-options", "NOPE"));
    }
}
