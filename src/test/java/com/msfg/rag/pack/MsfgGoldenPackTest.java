package com.msfg.rag.pack;

import com.msfg.rag.service.ai.QuestionCategory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
    void classifierRulesMatchLegacy() {
        List<DomainPack.ClassifierRule> rules = PACK.classifierRules();
        assertEquals(5, rules.size());

        // Rule 0: FRAUD (must be first — priority order)
        assertEquals(QuestionCategory.FRAUD, rules.get(0).category());
        assertEquals(List.of(
                "\\b(hide|hiding|conceal)\\b.*\\b(income|debt|loan|liabilit|asset)",
                "\\b(fake|falsif|forge|doctor|alter)\\w*\\b.*\\b(document|paystub|pay stub|w-?2|bank statement|tax return)",
                "\\b(lie|lying)\\b.*\\b(lender|application|underwriter|loan)",
                "\\bnot (tell|report|disclose)\\b.*\\b(lender|debt|income|loan)",
                "\\bwithout (the )?(lender|bank) (knowing|finding out)"
        ), rules.get(0).patterns());

        // Rule 1: ELIGIBILITY
        assertEquals(QuestionCategory.ELIGIBILITY, rules.get(1).category());
        assertEquals(List.of(
                "\\b(do|would|will|can|could) i (pre-?)?(qualify|get (pre-?)?approved)\\b",
                "\\b(am i|are we) (eligible|approved|qualified)\\b",
                "\\bwill (i|we) (be )?(approved|denied|turned down)\\b",
                "\\b(approve|deny) (me|my loan|my application)\\b",
                "\\bhow much (house|home|mortgage|loan) (can|could) (i|we) (afford|get|qualify)\\b"
        ), rules.get(1).patterns());

        // Rule 2: LEGAL
        assertEquals(QuestionCategory.LEGAL, rules.get(2).category());
        assertEquals(List.of(
                "\\b(sue|suing|lawsuit|litigation)\\b",
                "\\b(is (it|this) legal|illegal)\\b",
                "\\b(lawyer|attorney)\\b.*\\b(need|should|hire)\\b",
                "\\b(need|should|hire)\\b.*\\b(lawyer|attorney)\\b",
                "\\bbreach of contract\\b"
        ), rules.get(2).patterns());

        // Rule 3: TAX
        assertEquals(QuestionCategory.TAX, rules.get(3).category());
        assertEquals(List.of(
                "\\b(should|how do|how should) (i|we) file\\b.*\\btax",
                "\\btax (strategy|advice|loophole)\\b",
                "\\b(write|writing) off\\b.*\\b(mortgage|interest|points)\\b",
                "\\b(deduct|deduction)\\b.*\\b(should|can) i\\b",
                "\\bclaim\\b.*\\bon (my|our) tax(es)?\\b"
        ), rules.get(3).patterns());

        // Rule 4: LIVE_RATES
        assertEquals(QuestionCategory.LIVE_RATES, rules.get(4).category());
        assertEquals(List.of(
                "\\bwhat('s| is| are)? (the |your |today)?\\w* ?rates? (can i get|today|right now|currently)\\b",
                "\\b(current|today'?s?) (interest )?rates?\\b",
                "\\bquote me\\b",
                "\\brate (quote|lock)\\b.*\\b(today|now|get)\\b",
                "\\bwhat rate\\b.*\\b(get|offer|give)\\b"
        ), rules.get(4).patterns());
    }

    @Test
    void retrievalRulesMatchLegacy() {
        // Full acronym map — 14 entries
        assertEquals(Map.ofEntries(
                Map.entry("pmi",   "private mortgage insurance"),
                Map.entry("mip",   "mortgage insurance premium"),
                Map.entry("dti",   "debt-to-income"),
                Map.entry("ltv",   "loan-to-value"),
                Map.entry("cltv",  "combined loan-to-value"),
                Map.entry("piti",  "principal interest taxes insurance"),
                Map.entry("arm",   "adjustable-rate mortgage"),
                Map.entry("heloc", "home equity line of credit"),
                Map.entry("hoa",   "homeowners association"),
                Map.entry("apr",   "annual percentage rate"),
                Map.entry("aus",   "automated underwriting system"),
                Map.entry("fha",   "Federal Housing Administration"),
                Map.entry("va",    "Veterans Affairs"),
                Map.entry("usda",  "United States Department of Agriculture")
        ), PACK.acronymExpansions());

        // Program rules — 4 in order
        List<DomainPack.ProgramRule> programs = PACK.programRules();
        assertEquals(4, programs.size());

        // FHA
        DomainPack.ProgramRule fha = programs.get(0);
        assertEquals("FHA", fha.program());
        assertEquals(List.of("fha", "hud", "4000.1"), fha.keywords());
        assertEquals(List.of(), fha.wordPatterns());

        // VA
        DomainPack.ProgramRule va = programs.get(1);
        assertEquals("VA", va.program());
        assertEquals(List.of("veteran"), va.keywords());
        assertEquals(List.of("\\bva\\b"), va.wordPatterns());

        // USDA
        DomainPack.ProgramRule usda = programs.get(2);
        assertEquals("USDA", usda.program());
        assertEquals(List.of("usda", "rural development"), usda.keywords());
        assertEquals(List.of(), usda.wordPatterns());

        // CONVENTIONAL
        DomainPack.ProgramRule conv = programs.get(3);
        assertEquals("CONVENTIONAL", conv.program());
        assertEquals(List.of("conventional", "fannie", "freddie", "conforming"), conv.keywords());
        assertEquals(List.of(), conv.wordPatterns());
    }
}
