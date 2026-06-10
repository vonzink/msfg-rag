package com.msfg.rag.pack;

import com.msfg.rag.service.ai.QuestionCategory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden regression lock for the MSFG domain pack.
 * Literals are transcribed from the Java service constants.
 * If a test fails: FIX THE YAML, never the literal.
 */
class MsfgGoldenPackTest {

    private static final DomainPack PACK = TestPacks.msfg();

    @Test
    void identity() {
        assertEquals("mortgage", PACK.slug());
        assertEquals("Mountain State Financial Group", PACK.companyName());
        assertEquals(
                "This answer is for general mortgage education only and is not a loan approval, "
                + "underwriting decision, legal advice, or tax advice.",
                PACK.disclaimer());
    }

    @Test
    void promptTemplateIsByteIdenticalToLegacyConstant() {
        String expected = """
                You are an AI mortgage education assistant for Mountain State Financial Group.

                You must answer ONLY using the approved source context provided below.

                Rules:
                1. Do not answer from general knowledge.
                2. Do not invent mortgage guidelines.
                3. Do not provide loan approval, legal advice, tax advice, or underwriting decisions.
                4. If the source context does not answer the question, say you cannot find enough information.
                5. Use careful wording such as "may," "generally," and "subject to full loan review."
                6. Include citations from the provided source context. The "citations"
                   array is REQUIRED and must contain at least one entry whenever
                   source context is provided above. Cite every [Source N] you relied
                   on to write the answer. NEVER return an empty "citations" array
                   when source context is present — if you used the sources to answer,
                   you must list them.
                7. Keep the answer clear and borrower-friendly.
                8. In citations, copy source_name, document_name, section, page_number, and
                   effective_date EXACTLY as given in the source context metadata. If a field is
                   not present for a source, set it to null. NEVER invent page numbers, section
                   names, or dates.
                9. Pay attention to which loan program each source covers (FHA, VA, conventional).
                   If the question is about one program, do not answer using a different
                   program's guideline. If no source covers the right program, say you cannot
                   find enough information.

                Approved Source Context:
                %s

                User Question:
                %s

                Return ONLY valid JSON in exactly this format, with no other text before or after it:

                {
                  "answer": "...",
                  "citations": [
                    {
                      "source_name": "...",
                      "document_name": "...",
                      "section": "...",
                      "page_number": "...",
                      "effective_date": "..."
                    }
                  ],
                  "confidence": 0.0,
                  "human_escalation_required": false,
                  "disclaimer": "%s"
                }
                """;
        assertEquals(expected, PACK.promptTemplate());
    }

    @Test
    void guardrailsMatchLegacyConstants() {
        List<String> expectedPhrases = List.of(
                "you qualify",
                "you are approved",
                "you're approved",
                "you will be approved",
                "guaranteed",
                "the underwriter must accept",
                "the underwriter will accept",
                "this will close",
                "this loan will close",
                "legal advice:",
                "as your lawyer",
                "as your tax advisor"
        );
        assertEquals(expectedPhrases, PACK.guardrails().prohibitedPhrases());
        assertEquals("you are eligible", PACK.guardrails().eligiblePhrase());

        DomainPack.CannedAnswers ca = PACK.guardrails().cannedAnswers();
        assertEquals(
                "I could not find enough information in the approved mortgage guidelines to answer "
                + "that confidently. Please contact a licensed loan officer for review.",
                ca.noSource());
        assertEquals(
                "This question depends on your full loan file and should be reviewed by a licensed "
                + "loan officer. I can explain the general guideline, but I cannot determine approval "
                + "or eligibility here.",
                ca.escalation());
        assertEquals(
                "I can't provide legal advice. For legal questions about your mortgage or lender, "
                + "please consult a licensed attorney. I'm happy to explain general mortgage "
                + "guidelines if that helps.",
                ca.legal());
        assertEquals(
                "I can't provide tax advice. A licensed tax professional can review your specific "
                + "situation. I'm happy to explain general mortgage guidelines if that helps.",
                ca.tax());
        assertEquals(
                "I don't have access to live rate data, and rates depend on your full loan scenario. "
                + "A licensed loan officer at Mountain State Financial Group can provide a current, "
                + "personalized quote.",
                ca.liveRates());
        assertEquals(
                "I can't help with that. Misrepresenting income, debts, or documents on a mortgage "
                + "application is fraud. If you have questions about what must be disclosed, a "
                + "licensed loan officer can walk you through the requirements.",
                ca.fraud());
    }

    @Test
    void classifierAndRetrievalShapesMatchLegacy() {
        // Classifier: 5 rules, FRAUD first with 5 patterns
        List<DomainPack.ClassifierRule> rules = PACK.classifierRules();
        assertEquals(5, rules.size());
        assertEquals(QuestionCategory.FRAUD, rules.get(0).category());
        assertEquals(5, rules.get(0).patterns().size());

        // Acronyms: 14 entries, spot-check pmi
        assertEquals(14, PACK.acronymExpansions().size());
        assertEquals("private mortgage insurance", PACK.acronymExpansions().get("pmi"));

        // Programs: 4 in order FHA, VA, USDA, CONVENTIONAL
        List<DomainPack.ProgramRule> programs = PACK.programRules();
        assertEquals(4, programs.size());
        assertEquals(List.of("FHA", "VA", "USDA", "CONVENTIONAL"),
                programs.stream().map(DomainPack.ProgramRule::program).toList());

        // VA has word-pattern \bva\b
        DomainPack.ProgramRule va = programs.get(1);
        assertTrue(va.wordPatterns().contains("\\bva\\b"),
                "VA wordPatterns should contain \\bva\\b");

        // FHA keywords
        assertEquals(List.of("fha", "hud", "4000.1"), programs.get(0).keywords());
    }
}
