package com.wechatdigest.eval

import com.wechatdigest.core.calendar.IcsCalendarGenerator
import com.wechatdigest.core.llm.LanguageHelper
import com.wechatdigest.core.model.EventCard
import com.wechatdigest.core.model.EventCardStatus
import com.wechatdigest.core.model.EventCategory
import com.wechatdigest.core.model.EventPriority
import java.util.Locale

/**
 * Deterministic layered evaluation graders enforcing extraction correctness, grounding, fact recall,
 * target-language compliance, per-card source grounding, `.ics` date validity, and budget constraints.
 */
object EvalGraders {

    private val bannedPlaceholders = listOf(
        "refer to the official notification",
        "check details in the group",
        "pending updates"
    )

    private val sourceHeaderPrefixRegex = Regex("""(?m)^\[\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\]\s*[^:\n]{1,64}:\s*""")
    private val latinTokenRegex = Regex("""[A-Za-z][A-Za-z0-9_-]{3,}""")
    private val numericTokenRegex = Regex("""\d{1,2}:\d{2}|\d{2,}(?:-\d+)?""")
    private val cjkCharRegex = Regex("""[\u4E00-\u9FFF]""")
    private val genericEnglishStopwords = setOf(
        "notice", "deadline", "schedule", "reminder", "update", "event", "events",
        "submission", "submitted", "submit", "please", "must", "before", "after",
        "between", "required", "details", "group", "students", "student", "applicant",
        "applicants", "with", "from", "into", "your", "their", "will", "have", "been",
        "about", "more", "only", "also", "when", "where", "that", "this", "these",
        "those", "hall", "room", "building", "card", "copy", "copies", "form", "forms"
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
            findings.addAll(gradePerCardSourceGrounding(scenario, cards))
            findings.addAll(gradeEventDateIcsValidity(scenario, cards))
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

        if (exp.expectedStatuses.isNotEmpty()) {
            val actualStatuses = cards.map { it.status.name }
            val matches = exp.expectedStatuses.all { expected ->
                EventCardStatus.fromString(expected).name in actualStatuses
            }
            findings.add(
                GraderFinding(
                    grader = "expected_statuses",
                    passed = matches,
                    message = "Statuses=$actualStatuses, expected to include ${exp.expectedStatuses}"
                )
            )
        }

        if (exp.expectedSupersedesIds.isNotEmpty()) {
            val actualRefs = cards.mapNotNull { it.supersedesCardId }
            val matches = exp.expectedSupersedesIds.all { expected -> expected in actualRefs }
            findings.add(
                GraderFinding(
                    grader = "expected_supersedes_ids",
                    passed = matches,
                    message = "Supersedes=$actualRefs, expected to include ${exp.expectedSupersedesIds}"
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

        val allTextLower = buildCardCorpus(cards).lowercase()

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

    private fun gradePerCardSourceGrounding(
        scenario: EvalScenario,
        cards: List<EventCard>
    ): List<GraderFinding> {
        val exp = scenario.expectations
        if (cards.isEmpty() && exp.expectedPerCardGrounding.isEmpty()) return emptyList()

        val errors = mutableListOf<String>()

        for (expectation in exp.expectedPerCardGrounding) {
            val matched = cards.firstOrNull { it.title.contains(expectation.titleSubstring, ignoreCase = true) }
            if (matched == null) {
                errors.add("No card found matching title '${expectation.titleSubstring}'")
                continue
            }
            for (srcSub in expectation.requiredSourceSubstrings) {
                if (!matched.sourceMessages.contains(srcSub, ignoreCase = true)) {
                    errors.add("Card '${matched.title}' sourceMessages missing '$srcSub'")
                }
            }
            if (expectation.requiredContentSubstrings.isNotEmpty()) {
                val cardText = buildCardCorpus(listOf(matched))
                for (contentSub in expectation.requiredContentSubstrings) {
                    if (!cardText.contains(contentSub, ignoreCase = true)) {
                        errors.add("Card '${matched.title}' content missing '$contentSub'")
                    }
                }
            }
        }

        if (cards.size > 1) {
            val sourceBodies = cards.map { stripSourceHeaders(it.sourceMessages) }
            for ((idx, card) in cards.withIndex()) {
                val titleTokens = extractSalientLatinTokens(card.title)
                val titleCjk = extractCjkBigrams(card.title)
                val ownSource = sourceBodies[idx]
                val ownTitleOverlap = countMatchingTokens(titleTokens, ownSource) + countMatchingBigrams(titleCjk, ownSource)
                if (ownTitleOverlap == 0 && (titleTokens.isNotEmpty() || titleCjk.isNotEmpty())) {
                    val betterOtherIdx = sourceBodies.indices.firstOrNull { otherIdx ->
                        otherIdx != idx &&
                            (countMatchingTokens(titleTokens, sourceBodies[otherIdx]) +
                                countMatchingBigrams(titleCjk, sourceBodies[otherIdx])) > 0
                    }
                    if (betterOtherIdx != null) {
                        errors.add("Card[$idx] '${card.title}' has 0 title overlap with its own sourceMessages but matches card[$betterOtherIdx]'s sourceMessages")
                    }
                }
            }
        }

        for ((idx, card) in cards.withIndex()) {
            val sourceBody = stripSourceHeaders(card.sourceMessages)
            val cardCorpus = buildCardCorpus(listOf(card))
            val numTokens = extractNumericTokens(cardCorpus)
            val latinTokens = extractSalientLatinTokens(cardCorpus)
            val cjkBigrams = extractCjkBigrams(cardCorpus)

            val sourceHasLatin = extractSalientLatinTokens(sourceBody).isNotEmpty()
            val sourceHasCjk = cjkCharRegex.containsMatchIn(sourceBody)
            val sourceHasNumbers = extractNumericTokens(sourceBody).isNotEmpty()

            val comparableSignalExists =
                (latinTokens.isNotEmpty() && sourceHasLatin) ||
                    (cjkBigrams.isNotEmpty() && sourceHasCjk) ||
                    (numTokens.isNotEmpty() && sourceHasNumbers)

            if (comparableSignalExists) {
                val matchedCount = countMatchingTokens(numTokens, sourceBody) +
                    countMatchingTokens(latinTokens, sourceBody) +
                    countMatchingBigrams(cjkBigrams, sourceBody)
                if (matchedCount == 0) {
                    errors.add("Card[$idx] '${card.title}' shares zero grounded tokens with its own sourceMessages")
                }
            }
        }

        return listOf(
            GraderFinding(
                grader = "per_card_source_grounding",
                passed = errors.isEmpty(),
                message = if (errors.isEmpty()) {
                    "All ${cards.size} card(s) grounded cleanly to their own sourceMessages"
                } else {
                    errors.joinToString("; ")
                }
            )
        )
    }

    private fun gradeEventDateIcsValidity(
        scenario: EvalScenario,
        cards: List<EventCard>
    ): List<GraderFinding> {
        val exp = scenario.expectations
        val datedCards = cards.filter { !it.eventDate.isNullOrBlank() }
        if (!exp.requireValidEventDates && exp.minDatedCards <= 0 && datedCards.isEmpty()) {
            return emptyList()
        }

        val generator = IcsCalendarGenerator()
        val errors = mutableListOf<String>()

        for (card in datedCards) {
            val icsRes = runCatching { generator.generateIcs(card) }
            if (icsRes.isFailure) {
                errors.add("Card '${card.title}' invalid eventDate='${card.eventDate}': ${icsRes.exceptionOrNull()?.message}")
            }
        }
        if (exp.requireValidEventDates && datedCards.size < cards.size) {
            val undated = cards.filter { it.eventDate.isNullOrBlank() }.map { it.title }
            errors.add("Missing eventDate on card(s): $undated")
        }
        if (exp.minDatedCards > 0 && datedCards.size < exp.minDatedCards) {
            errors.add("Dated card count=${datedCards.size}, expected >= ${exp.minDatedCards}")
        }

        return listOf(
            GraderFinding(
                grader = "event_date_ics_validity",
                passed = errors.isEmpty(),
                message = if (errors.isEmpty()) {
                    "Validated ${datedCards.size}/${cards.size} card eventDate value(s) via IcsCalendarGenerator"
                } else {
                    errors.joinToString("; ")
                }
            )
        )
    }

    private fun stripSourceHeaders(sourceMessages: String): String =
        sourceMessages.replace(sourceHeaderPrefixRegex, "")

    private fun extractSalientLatinTokens(text: String): Set<String> =
        latinTokenRegex.findAll(text)
            .map { it.value.lowercase(Locale.ROOT) }
            .filter { it !in genericEnglishStopwords }
            .toSet()

    private fun extractNumericTokens(text: String): Set<String> =
        numericTokenRegex.findAll(text).map { it.value }.toSet()

    private fun extractCjkBigrams(text: String): Set<String> {
        val chars = cjkCharRegex.findAll(text).map { it.value }.toList()
        if (chars.size < 2) return emptySet()
        val out = LinkedHashSet<String>()
        for (i in 0 until chars.size - 1) {
            out.add(chars[i] + chars[i + 1])
        }
        return out
    }

    private fun countMatchingTokens(tokens: Set<String>, haystack: String): Int {
        if (tokens.isEmpty() || haystack.isBlank()) return 0
        val lower = haystack.lowercase(Locale.ROOT)
        return tokens.count { it in lower }
    }

    private fun countMatchingBigrams(bigrams: Set<String>, haystack: String): Int {
        if (bigrams.isEmpty() || haystack.isBlank()) return 0
        return bigrams.count { it in haystack }
    }

    private fun gradeFactRecall(
        scenario: EvalScenario,
        cards: List<EventCard>
    ): List<GraderFinding> {
        val requiredFacts = scenario.expectations.mustContainFacts
        if (requiredFacts.isEmpty()) return emptyList()

        val corpus = buildCardCorpus(cards)
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

    private fun buildCardCorpus(cards: List<EventCard>): String = cards.joinToString("\n") { card ->
        buildString {
            append(card.title).append('\n')
            append(card.translatedSummary).append('\n')
            append(card.summary).append('\n')
            card.sections.forEach { section ->
                append(section.heading).append('\n')
                section.bullets.forEach { bullet -> append(bullet).append('\n') }
                section.items.forEach { item -> append(item.label).append(": ").append(item.value).append('\n') }
                append(section.content).append('\n')
            }
        }
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
