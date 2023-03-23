package org.cyber.utbot

import org.cyber.utbot.api.CYBER_MOCK_ALWAYS_DEFAULT
import org.cyber.utbot.api.GenerateTestsSettings
import org.cyber.utbot.api.MOCK_ALWAYS_DEFAULT
import org.cyber.utbot.api.TestGenerator
import org.cyber.utbot.api.abstraction.ReportItem
import org.cyber.utbot.api.utils.TestUnit
import org.cyber.utbot.api.utils.pretty
import org.cyber.utbot.api.utils.viewers.UTBotViewers
import org.cyber.utbot.api.utils.writeCsvFile
import org.utbot.framework.TestSelectionStrategyType
import org.utbot.framework.UtSettings
import org.utbot.framework.plugin.api.CodegenLanguage
import org.utbot.framework.plugin.api.MockStrategyApi

class ReportCreator(settings: GenerateTestsSettings, private val category: String? = null) {    // for one examples in class only!!!
    private val generator = TestGenerator(settings.also { it.testsIgnoreEmpty = true }) // work wrong without `testsIgnoreEmpty = true`

    constructor(classpath: String, basePaths: List<String>, category: String? = null, extraMocks: List<String> = emptyList()) : this(GenerateTestsSettings(classpath, codegenLanguage = CodegenLanguage.JAVA,
        mockAlways = MOCK_ALWAYS_DEFAULT + CYBER_MOCK_ALWAYS_DEFAULT + extraMocks, mockStrategy = MockStrategyApi.NO_MOCKS, withUtSettings = { UtSettings.useFuzzing = false;
            UtSettings.useDebugVisualization = true; UtSettings.testMinimizationStrategyType = TestSelectionStrategyType.DO_NOT_MINIMIZE_STRATEGY },
        utbotViewers = setOf(UTBotViewers.TERMINAL_STATISTIC_VIEWER), vulnerabilityCheckDirectories=basePaths, trustedLibraries=
        listOf()), category)

    private fun create(test: TestUnit): ReportItem {
        return try {
            val (tests, info) = generator.run(listOf(test))
             if (tests.isNotEmpty()) {
                ReportItem(test.target, success = true, category = category, testsRaw=tests[test.target], testsInfo=(info[UTBotViewers.TERMINAL_STATISTIC_VIEWER] as String).pretty())
            } else {
                ReportItem(test.target, success = false, category = category)
            }
        } catch (e: Exception) {
            ReportItem(test.target, success = false, category = category, failReason="utbot failed: ${e.message}")
        }
    }

    fun create(tests: List<TestUnit>, benchmark: String) = tests.map { create(it) }.run {
        writeCsvFile(this, ReportItem.schema, "$benchmark.csv")
    }
}
