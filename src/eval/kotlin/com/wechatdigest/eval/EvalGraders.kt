package com.wechatdigest.eval

import com.wechatdigest.core.llm.LanguageHelper
import com.wechatdigest.core.model.EventCard
import com.wechatdigest.core.model.EventCategory
import com.wechatdigest.core.model.EventPriority

/**
 * Deterministic layered evaluation graders enforcing extraction correctness, grounding, fact recall,
 * target-language compliance, and budget constraints without probabilistic judge variance.
 */
object EvalGraders {

    private val bannedPlaceholders = listOf(
        "refer to the official notification",
        "check details in the group",
        "pending updates"
    )

    fun gradeAll(
        scenario: EvalScenario,
        result: Result<List<EventCard>>,
        trace: StageTrace
    ): List<GraderFinding> {
        val findings = mutableListOf<GraderFinding>()
        findings.addAll(gradeExecutionAndSchema(scenario, result))

        val cards = result.getOrElse { emptyList() }
        if (!scenario.expectations.expectFailure) {
            findings.addAll(gradeGroundingAndAntiHallucination(scenario, cards))
            findings.addAll(gradeFactRecall(scenario, cards))
            findings.addAll(gradeLanguageCompliance(scenario, cards))
        }
        findings.addAll(gradeBudgets(scenario, trace))
        return findings
    }

    private fun gradeExecutionAndSchema(
        scenario: EvalScenario,
        result: Result<List<EventCard>>
    ): List<GraderFinding> {
        val exp = scenario.expectations
        if (exp.expectFailure) {
            val failedAsExpected = result.isFailure
            return listOf(
                GraderFinding(
                    grader = "execution_fail_closed",
                    passed = failedAsExpected,
                    message = if (failedAsExpected) {
                        "Failed closed as expected: ${result.exceptionOrNull()?.message}"
                    } else {
                        "Expected failure, but succeeded with ${result.getOrNull()?.size ?: 0} cards"
                    }
                )
            )
        }

        if (result.isFailure) {
            return listOf(
                GraderFinding(
                    grader = "execution_success",
                    passed = false,
                    message = "Pipeline threw unexpectedly: ${result.exceptionOrNull()?.message}"
                )
            )
        }

        val cards = result.getOrThrow()
        val findings = mutableListOf<GraderFinding>()
        val inCountRange = cards.size in exp.minCards..exp.maxCards
        findings.add(
            GraderFinding(
                grader = "card_count_range",
                passed = inCountRange,
                message = "Produced ${cards.size} card(s), expected [${exp.minCards}..${exp.maxCards}]"
            )
        )

        val schemaValid = cards.all { card ->
            card.title.isNotBlank() &&
                (card.translatedSummary.isNotBlank() || card.summary.isNotBlank()) &&
                card.sourceRoomId == scenario.roomId
        }
        findings.add(
            GraderFinding(
                grader = "schema_completeness",
                passed = schemaValid,
                message = if (schemaValid) "All cards contain non-blank title, summary, and roomId" else "Card missing title, summary, or roomId"
            )
        )

        if (exp.minSectionsOnFirstCard > 0) {
            val firstCount = cards.firstOrNull()?.sections?.size ?: 0
            findings.add(
                GraderFinding(
                    grader = "min_sections",
                    passed = firstCount >= exp.minSectionsOnFirstCard,
                    message = "First card has $firstCount section(s), expected >= ${exp.minSectionsOnFirstCard}"
                )
            )
        }

        if (exp.expectedCategories.isNotEmpty()) {
            val actualCats = cards.map { it.category.name }
            val matches = exp.expectedCategories.all { expected ->
                EventCategory.fromString(expected).name in actualCats
            }
            findings.add(
                GraderFinding(
                    grader = "expected_categories",
                    passed = matches,
                    message = "Categories=$actualCats, expected to include ${exp.expectedCategories}"
                )
            )
        }

        if (exp.expectedPriorities.isNotEmpty()) {
            val actualPrios = cards.map { it.priority.name }
            val matches = exp.expectedPriorities.all { expected ->
                EventPriority.fromString(expected).name in actualPrios
            }
            findings.add(
                GraderFinding(
                    grader = "expected_priorities",
                    passed = matches,
                    message = "Priorities=$actualPrios, expected to include ${exp.expectedPriorities}"
                )
            )
        }

        if (exp.expectedTitles.isNotEmpty()) {
            val actualTitles = cards.map { it.title }
            val matches = exp.expectedTitles.all { expected ->
                actualTitles.any { it.contains(expected, ignoreCase = true) }
            }
            findings.add(
                GraderFinding(
                    grader = "expected_titles",
                    passed = matches,
                    message = "Titles=$actualTitles, expected substrings ${exp.expectedTitles}"
                )
            )
        }
        return findings
    }

    private fun gradeGroundingAndAntiHallucination(
        scenario: EvalScenario,
        cards: List<EventCard>
    ): List<GraderFinding> {
        val exp = scenario.expectations
        val findings = mutableListOf<GraderFinding>()

        val sourceValid = cards.all { it.sourceMessages.isNotBlank() && !it.sourceMessages.contains("[id:") }
        findings.add(
            GraderFinding(
                grader = "source_attribution_integrity",
                passed = sourceValid,
                message = if (sourceValid) "All cards retain clean sourceMessages without [id:] leaks" else "Empty sourceMessages or raw [id:] tag leak"
            )
        )

        val allTextLower = cards.joinToString("\n") { card ->
            buildString {
                append(card.title).append('\n')
                append(card.translatedSummary).append('\n')
                append(card.summary).append('\n')
                card.sections.forEach { append(it.heading).append('\n').append(it.content).append('\n') }
            }
        }.lowercase()

        val foundPlaceholder = bannedPlaceholders.firstOrNull { it in allTextLower }
        findings.add(
            GraderFinding(
                grader = "no_vague_placeholders",
                passed = foundPlaceholder == null,
                message = if (foundPlaceholder == null) "No banned placeholder phrases found" else "Banned placeholder found: '$foundPlaceholder'"
            )
        )

        if (!exp.allowSlashMergedTitles) {
            val slashTitle = cards.firstOrNull { it.title.contains(" / ") }?.title
            findings.add(
                GraderFinding(
                    grader = "no_slash_merged_titles",
                    passed = slashTitle == null,
                    message = if (slashTitle == null) "No ' / ' merged titles" else "Unexpected merged title: '$slashTitle'"
                )
            )
        }

        if (exp.forbiddenTitleSubstrings.isNotEmpty()) {
            val violation = cards.firstNotNullOfOrNull { card ->
                exp.forbiddenTitleSubstrings.firstOrNull { sub -> card.title.contains(sub, ignoreCase = true) }
            }
            findings.add(
                GraderFinding(
                    grader = "forbidden_title_substrings",
                    passed = violation == null,
                    message = if (violation == null) "No forbidden title substrings" else "Forbidden title substring matched: '$violation'"
                )
            )
        }

        if (exp.forbiddenContentSubstrings.isNotEmpty()) {
            val violation = exp.forbiddenContentSubstrings.firstOrNull { sub -> allTextLower.contains(sub.lowercase()) }
            findings.add(
                GraderFinding(
                    grader = "forbidden_content_substrings",
                    passed = violation == null,
                    message = if (violation == null) "No forbidden content substrings" else "Forbidden content substring matched: '$violation'"
                )
            )
        }

        if (exp.forbiddenSourceSubstrings.isNotEmpty()) {
            val sourcesJoined = cards.joinToString("\n") { it.sourceMessages }
            val violation = exp.forbiddenSourceSubstrings.firstOrNull { sub -> sourcesJoined.contains(sub, ignoreCase = true) }
            findings.add(
                GraderFinding(
                    grader = "forbidden_source_substrings",
                    passed = violation == null,
                    message = if (violation == null) "No excluded sender/chatter attached to sourceMessages" else "Forbidden source text leaked: '$violation'"
                )
            )
        }
        return findings
    }

    private fun gradeFactRecall(
        scenario: EvalScenario,
        cards: List<EventCard>
    ): List<GraderFinding> {
        val requiredFacts = scenario.expectations.mustContainFacts
        if (requiredFacts.isEmpty()) return emptyList()

        val corpus = cards.joinToString("\n") { card ->
            buildString {
                append(card.title).append('\n')
                append(card.translatedSummary).append('\n')
                append(card.summary).append('\n')
                card.sections.forEach { append(it.heading).append('\n').append(it.content).append('\n') }
            }
        }
        val missing = requiredFacts.filterNot { fact -> corpus.contains(fact, ignoreCase = true) }
        val recall = (requiredFacts.size - missing.size).toDouble() / requiredFacts.size.toDouble()
        return listOf(
            GraderFinding(
                grader = "required_fact_recall",
                passed = missing.isEmpty(),
                message = if (missing.isEmpty()) {
                    "Matched all ${requiredFacts.size}/${requiredFacts.size} required facts (recall=100%)"
                } else {
                    "Matched ${requiredFacts.size - missing.size}/${requiredFacts.size} facts (recall=${"%.0f".format(recall * 100)}%); missing=$missing"
                }
            )
        )
    }

    private fun gradeLanguageCompliance(
        scenario: EvalScenario,
        cards: List<EventCard>
    ): List<GraderFinding> {
        if (!scenario.expectations.requireLanguageCompliance || cards.isEmpty()) return emptyList()
        val nonCompliant = cards.mapNotNull { card ->
            val compliance = LanguageHelper.checkCardCompliance(card, scenario.targetLanguage)
            if (compliance.isCompliant) null else "${card.title}: ${compliance.nonCompliantFields}"
        }
        return listOf(
            GraderFinding(
                grader = "target_language_compliance",
                passed = nonCompliant.isEmpty(),
                message = if (nonCompliant.isEmpty()) {
                    "All ${cards.size} card(s) compliant with targetLanguage='${scenario.targetLanguage}'"
                } else {
                    "Non-compliant card fields: ${nonCompliant.joinToString("; ")}"
                }
            )
        )
    }

    private fun gradeBudgets(
        scenario: EvalScenario,
        trace: StageTrace
    ): List<GraderFinding> {
        val exp = scenario.expectations
        val findings = mutableListOf<GraderFinding>()

        val inPrefilterRange = trace.afterPrefilterCount >= exp.minSurvivingPrefilter &&
            (exp.maxSurvivingPrefilter == null || trace.afterPrefilterCount <= exp.maxSurvivingPrefilter)
        if (exp.minSurvivingPrefilter > 0 || exp.maxSurvivingPrefilter != null) {
            findings.add(
                GraderFinding(
                    grader = "prefilter_survival_budget",
                    passed = inPrefilterRange,
                    message = "Prefilter surviving=${trace.afterPrefilterCount} (expected ${exp.minSurvivingPrefilter}..${exp.maxSurvivingPrefilter ?: "∞"})"
                )
            )
        }

        exp.maxLlmCalls?.let { maxCalls ->
            val ok = trace.llmCalls.size <= maxCalls
            findings.add(
                GraderFinding(
                    grader = "max_llm_calls",
                    passed = ok,
                    message = "LLM calls=${trace.llmCalls.size}, budget <= $maxCalls"
                )
            )
        }

        val okTranslationRepairs = trace.translationRepairCallCount >= exp.minTranslationRepairs &&
            (exp.maxTranslationRepairs == null || trace.translationRepairCallCount <= exp.maxTranslationRepairs)
        if (exp.minTranslationRepairs > 0 || exp.maxTranslationRepairs != null) {
            findings.add(
                GraderFinding(
                    grader = "translation_repair_budget",
                    passed = okTranslationRepairs,
                    message = "Translation repair calls=${trace.translationRepairCallCount}, expected ${exp.minTranslationRepairs}..${exp.maxTranslationRepairs ?: "∞"}"
                )
            )
        }

        if (exp.minJsonRepairs > 0) {
            val okRepairs = trace.jsonRepairCount >= exp.minJsonRepairs
            findings.add(
                GraderFinding(
                    grader = "json_repair_recovery",
                    passed = okRepairs,
                    message = "JSON syntax repairs=${trace.jsonRepairCount}, expected >= ${exp.minJsonRepairs}"
                )
            )
        }

        if (exp.requireZeroJsonParseFailures) {
            findings.add(
                GraderFinding(
                    grader = "zero_json_repairs",
                    passed = trace.jsonRepairCount == 0,
                    message = "JSON syntax repairs=${trace.jsonRepairCount} (expected 0)"
                )
            )
        }
        return findings
    }

    /** Compares summary metrics against an optional [EvalBaselineEntry] and emits drift warnings without blocking live improvements. */
    fun checkBaselineDrift(summary: ScenarioSummary, baseline: EvalBaselineEntry?): List<String> {
        if (baseline == null) return emptyList()
        val warnings = mutableListOf<String>()
        if (summary.avgLlmCalls > baseline.maxLlmCalls.toDouble()) {
            warnings.add("Avg LLM calls ${"%.1f".format(summary.avgLlmCalls)} exceeded baseline max ${baseline.maxLlmCalls}")
        }
        if (summary.avgTranslationRepairs > baseline.maxTranslationRepairs.toDouble()) {
            warnings.add("Avg translation repairs ${"%.1f".format(summary.avgTranslationRepairs)} exceeded baseline max ${baseline.maxTranslationRepairs}")
        }
        return warnings
    }
}
