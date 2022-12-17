package org.cyber.utbot

import org.cyber.utbot.api.GenerateTestsSettings
import org.cyber.utbot.api.TestGenerator
import org.cyber.utbot.api.utils.toTestUnits
import org.utbot.common.PathUtil.toPath
import org.utbot.framework.UtSettings.useFuzzing
import org.utbot.framework.plugin.api.CodegenLanguage
import java.nio.file.Files


fun main() {
    val settings = GenerateTestsSettings("build/classes/java/main", codegenLanguage = CodegenLanguage.JAVA, withUtSettings = { useFuzzing = false })
    val generator = TestGenerator(settings)
    val tests = generator.run(mapOf("org.example.Loop" to "src/main/java/org/example/Loop.java").toTestUnits())
//    val tests = generator.runBunch("build/classes/java/main", "org.example.dir")
    tests.forEach { nameAndTest ->
        Files.write("src/test/java/org/example/${nameAndTest.key.takeLastWhile { it != '.' }}Test.java".toPath(), listOf(nameAndTest.value))
//        println("name=${nameAndTest.key}\n\n${nameAndTest.value}\n")
    }
}
