package org.cyber.utbot.api

import mu.KotlinLogging
import org.cyber.utbot.api.utils.*
import org.utbot.common.PathUtil.toPath
import org.utbot.common.filterWhen
import org.utbot.engine.Mocker
import org.utbot.framework.UtSettings
import org.utbot.framework.codegen.ForceStaticMocking
import org.utbot.framework.codegen.StaticsMocking
import org.utbot.framework.codegen.TestFramework
import org.utbot.framework.plugin.api.*
import org.utbot.framework.plugin.api.util.UtContext
import org.utbot.framework.plugin.api.util.isAbstract
import org.utbot.framework.plugin.api.util.isSynthetic
import org.utbot.framework.plugin.api.util.withUtContext
import org.utbot.framework.util.isKnownImplicitlyDeclaredMethod
import org.utbot.sarif.SarifReport
import org.utbot.sarif.SourceFindingStrategyDefault
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.time.temporal.ChronoUnit


open class TestGenerator(private val settings: GenerateTestsSettings) : AbstractTestGenerator() {
    override val treatOverflowAsError: TreatOverflowAsError = settings.treatOverflowAsError
    override val mockStrategy: MockStrategyApi = settings.mockStrategy
    override val generationTimeout: Long = settings.generationTimeout
    override val forceStaticMocking: ForceStaticMocking = settings.forceStaticMocking
    override val staticsMocking: StaticsMocking = settings.mockStatics
    override val codegenLanguage: CodegenLanguage = settings.codegenLanguage
    override val testFramework: TestFramework = settings.testFrameworkGen.toTestFramework()

    private val logger = KotlinLogging.logger {}

    /**
     * @param testUnits: collection of [TestUnit]
     * @param classpath: the same parameter as in the [GenerateTestsSettings], if specified overrides the current launch
     * @return mapping from [TargetQualifiedName] to [GeneratedTests]
     */
    fun run(testUnits: Iterable<TestUnit>, classpath: String? = null): Map<TargetQualifiedName, GeneratedTests> {
        assert(testUnits.all {
            (it.source.endsWith(".java") || it.source.endsWith(".kt")) && Files.exists(Paths.get(it.source))
        }) { "source should have suffix \".java\" or  \".kt\"" }

        (classpath ?: settings.classpath)?.let{ updateClassLoader(it) } ?: throw Exception("classpath should be set")
        val targetToTests = mutableMapOf<TargetQualifiedName, GeneratedTests>()
        testUnits.forEach { testUnit ->
            targetToTests[testUnit.target] = runInside(testUnit.target, testUnit.source, testUnit.output)
        }
        return targetToTests
    }

    /**
     * @param source: [SourceCodeFileName] of directory for which you want to create tests
     * @param packageFilter: Specifies a string to generate tests only for classes that contains the substring in the fully qualified name
     * @param classpath: the same parameter as in the [GenerateTestsSettings], if specified overrides the current launch
     * @return mapping from [TargetQualifiedName] to [GeneratedTests]
     */
    fun runBunch(source: SourceCodeFileName, packageFilter: String? = null, classpath: String? = null): Map<TargetQualifiedName, GeneratedTests> {
        (classpath ?: settings.classpath)?.let{ updateClassLoader(it) } ?: throw Exception("classpath should be set")
        val targetToTests = mutableMapOf<TargetQualifiedName, GeneratedTests>()
        val classesFromPath = loadClassesFromPath(classLoader, source)
        classesFromPath.filterNot { it.java.isInterface }.filter { clazz ->
            clazz.qualifiedName != null
        }.
        filter { clazz ->
            packageFilter?.run {
                clazz.qualifiedName?.contains(this) ?: false
            } ?: true
        }.forEach {
            if (it.qualifiedName != null) {
                targetToTests[it.qualifiedName!!] = runInside(it.qualifiedName!!)
            } else {
                logger.info("No qualified name for $it")
            }
        }
        return targetToTests
    }

    private fun runInside(targetClassFqn: TargetQualifiedName, sourceCodeFile: SourceCodeFileName? = null, output: OutputFileName? = null): GeneratedTests {
        lateinit var testClassBody: String
        val started = now()
        val workingDirectory = getWorkingDirectory(targetClassFqn)
            ?: throw Exception("Cannot find the target class in the classpath")

        try {
            logger.debug { "Generating test for [$targetClassFqn] - started" }
            logger.debug { "Classpath to be used: $newlineSeparator ${settings.classpath} $newlineSeparator" }

            // utContext is used in `targetMethods`, `generate`, `generateTest`, `generateReport`
            withUtContext(UtContext(classLoader)) {
                val classIdUnderTest = ClassId(targetClassFqn)
                val targetMethods = classIdUnderTest.targetMethods()
                    .filterWhen(UtSettings.skipTestGenerationForSyntheticAndImplicitlyDeclaredMethods) {
                        !it.isSynthetic && !it.isKnownImplicitlyDeclaredMethod
                    }
                    .filterNot { it.isAbstract }
                val testCaseGenerator = initializeGenerator(workingDirectory)

                if (targetMethods.isEmpty()) {
                    throw Exception("Nothing to process. No methods were provided")
                }

                val testClassName = output?.toPath()?.toFile()?.nameWithoutExtension
                    ?: "${classIdUnderTest.simpleName}Test"
                val testSets = generateTestSets(
                    testCaseGenerator,
                    targetMethods,
                    sourceCodeFile?.let { Paths.get(it) },
                    searchDirectory = workingDirectory,
                    chosenClassesToMockAlways = (Mocker.defaultSuperClassesToMockAlwaysNames + settings.mockAlways)
                        .mapTo(mutableSetOf()) { ClassId(it) }
                )
                testClassBody = generateTest(classIdUnderTest, testClassName, testSets)
                testSets
                if (settings.sarifReport != null && sourceCodeFile != null) {
                    generateReport(targetClassFqn, testSets, testClassBody, sourceCodeFile, output)
                }
            }
        } catch (t: Throwable) {
            logger.error { "An error has occurred while generating test for snippet $targetClassFqn : $t" }
            throw t
        } finally {
            val duration = ChronoUnit.MILLIS.between(started, now())
            logger.debug { "Generating test for [$targetClassFqn] - completed in [$duration] (ms)" }
        }
        return testClassBody
    }

    private fun generateReport(classFqn: String, testSets: List<UtMethodTestSet>, testClassBody: String,
                               sourceCodeFile: String, testsFilePath: String?) = try {
        when {
            testsFilePath == null -> {
                println("The output file is required to generate a report. Please, specify \"output\" option in TestUnit.")
            }
            settings.projectRoot == null -> {
                println("The path to the project root is required to generate a report. Please, specify \"projectRoot\" option in GenerateTestsSettings.")
            }
            else -> {
                val sourceFinding =
                    SourceFindingStrategyDefault(classFqn, sourceCodeFile, testsFilePath, settings.projectRoot)
                val report = SarifReport(testSets, testClassBody, sourceFinding).createReport().toJson()
                saveToFile(report, settings.sarifReport)
                println("The report was saved to \"${settings.sarifReport}\".")
            }
        }
    } catch (t: Throwable) {
        logger.error { "An error has occurred while generating sarif report for snippet $classFqn : $t" }
        throw t
    }
}
