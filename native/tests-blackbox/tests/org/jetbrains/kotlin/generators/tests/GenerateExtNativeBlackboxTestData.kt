/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.generators.tests

import org.jetbrains.kotlin.konan.blackboxtest.*
import org.jetbrains.kotlin.konan.blackboxtest.DEFAULT_FILE_NAME
import org.jetbrains.kotlin.konan.blackboxtest.PackageName
import org.jetbrains.kotlin.konan.blackboxtest.computePackageName
import org.jetbrains.kotlin.konan.blackboxtest.deleteRecursivelyWithLogging
import org.jetbrains.kotlin.konan.blackboxtest.getAbsoluteFile
import org.jetbrains.kotlin.konan.blackboxtest.mkdirsWithLogging
import org.jetbrains.kotlin.test.Directives
import org.jetbrains.kotlin.test.InTextDirectivesUtils.*
import org.jetbrains.kotlin.test.KotlinBaseTest
import org.jetbrains.kotlin.test.TargetBackend
import org.jetbrains.kotlin.test.TestFiles
import org.jetbrains.kotlin.test.services.JUnit5Assertions.assertTrue
import org.jetbrains.kotlin.test.services.JUnit5Assertions.fail
import org.jetbrains.kotlin.test.util.KtTestUtil
import java.io.File
import java.lang.StringBuilder

internal fun generateExtNativeBlackboxTestData(
    testDataSource: String,
    testDataDestination: String,
    sharedModules: String,
    init: ExtTestDataConfig.() -> Unit
) {
    val testDataConfig = ExtTestDataConfig(testDataSource, testDataDestination, sharedModules)
    testDataConfig.init()
    testDataConfig.generateTestData()
}

internal class ExtTestDataConfig(
    private val testDataSource: String,
    private val testDataDestination: String,
    private val sharedModules: String
) {
    private val includes = linkedSetOf<String>()
    private val excludes = hashSetOf<String>()

    fun include(testDataSubPath: String) {
        includes += testDataSubPath
    }

    fun exclude(testDataSubPath: String) {
        excludes += testDataSubPath
    }

    fun generateTestData() {
        val testDataSourceDir = getAbsoluteFile(testDataSource)
        assertTrue(testDataSourceDir.isDirectory) { "The directory with the source test data doe not exist: $testDataSourceDir" }

        val testDataDestinationDir = getAbsoluteFile(testDataDestination)
        testDataDestinationDir.deleteRecursivelyWithLogging()
        testDataDestinationDir.mkdirsWithLogging()

        val sharedModulesDir = getAbsoluteFile(sharedModules)
        sharedModulesDir.deleteRecursivelyWithLogging()
        sharedModulesDir.mkdirsWithLogging()

        val roots = if (includes.isNotEmpty())
            includes.map { testDataSourceDir.resolve(it) }
        else
            listOf(testDataSourceDir)

        val excludedItems = excludes.map { testDataSourceDir.resolve(it) }.toSet()
        val sharedModules = SharedTestModules()

        roots.forEach { root ->
            root.walkTopDown()
                .onEnter { directory -> directory !in excludedItems }
                .filter { file -> file.isFile && file.extension == "kt" && file !in excludedItems }
                .forEach { file ->
                    ExtTestDataFile(
                        testDataFile = file,
                        testDataSourceDir = testDataSourceDir,
                        testDataDestinationDir = testDataDestinationDir
                    ).generateNewTestDataFileIfNecessary(sharedModules)
                }
        }

        sharedModules.dumpToDir(sharedModulesDir)
    }
}

private class ExtTestDataFile(
    private val testDataFile: File,
    private val testDataSourceDir: File,
    private val testDataDestinationDir: File
) {
    private val structure = ExtTestDataFileStructure(testDataFile)

    private val settings = ExtTestDataFileSettings(
        languageSettings = structure.directives.listValues(LANGUAGE_DIRECTIVE)
            ?.filter { it != "+NewInference" /* It is already on by default, but passing it explicitly turns on a special "compatibility mode" in FE which is not desirable. */ }
            ?.toSet()
            .orEmpty(),
        experimentalSettings = structure.directives.listValues(USE_EXPERIMENTAL_DIRECTIVE)?.toSet().orEmpty(),
        expectActualLinker = EXPECT_ACTUAL_LINKER_DIRECTIVE in structure.directives,
        effectivePackageName = computePackageName(testDataDir = testDataSourceDir, testDataFile = testDataFile)
    )

    private fun shouldBeGenerated(): Boolean =
        isCompatibleTarget(TargetBackend.NATIVE, testDataFile) // Checks TARGET_BACKEND/DONT_TARGET_EXACT_BACKEND directives.
                && !isIgnoredTarget(TargetBackend.NATIVE, testDataFile) // Checks IGNORE_BACKEND directive.
                && settings.languageSettings.none { it in INCOMPATIBLE_LANGUAGE_SETTINGS }
                && INCOMPATIBLE_DIRECTIVES.none { it in structure.directives }
                && structure.directives[API_VERSION_DIRECTIVE] !in INCOMPATIBLE_API_VERSIONS
                && structure.directives[LANGUAGE_VERSION_DIRECTIVE] !in INCOMPATIBLE_LANGUAGE_VERSIONS

    private fun assembleFreeCompilerArgs(): List<String> {
        val args = mutableListOf<String>()
        settings.languageSettings.mapTo(args) { "-XXLanguage:$it" }
        settings.experimentalSettings.mapTo(args) { "-Xopt-in=$it" }
        if (settings.expectActualLinker) args += "-Xexpect-actual-linker"
        return args
    }

    fun generateNewTestDataFileIfNecessary(sharedTestModules: SharedTestModules) {
        if (!shouldBeGenerated()) return

        removeAllDirectives()
        removeDiagnosticParameters()
        stampPackageNames()
        patchFullyQualifiedNamesOfDeclarations()
        makeMutableObjects()
        val entryPointFunctionFQN = findEntryPointFunction()
        val optInAnnotations = findFileLevelOptInAnnotations()
        generateTestLauncher(entryPointFunctionFQN, optInAnnotations)

        val relativeFile = testDataFile.relativeTo(testDataSourceDir)
        val destinationFile = testDataDestinationDir.resolve(relativeFile)

        destinationFile.writeFileWithLogging(structure.generateTextExcludingSupportModule(assembleFreeCompilerArgs()))
        structure.generateSharedSupportModule(sharedTestModules::addFile)
    }

    /** Remove all directives from the text. */
    private fun removeAllDirectives() {
        structure.transformEachFileByLines { line ->
            if (line.parseDirectiveName() != null) null else line
        }
    }

    /** Remove all diagnostic parameters from the text. Examples: <!NO_TAIL_CALLS_FOUND!>, <!NON_TAIL_RECURSIVE_CALL!>, <!> */
    private fun removeDiagnosticParameters() {
        structure.transformEachFileByLines { line ->
            line.replace(DIAGNOSTIC_REGEX) { match -> match.groupValues[1] }
        }
    }

    /**
     * For every Kotlin file (*.kt) stored in this text:
     *
     * - If there is a "package" declaration, patch it to prepend unique package prefix.
     *   Example: package foo -> package codegen.box.annotations.genericAnnotations.foo
     *
     * - If there is no "package" declaration, add one with the package name equal to unique package prefix.
     *   Example (new line added): package codegen.box.annotations.genericAnnotations
     *
     * - All "import" declarations are patched to reflect appropriate changes in "package" declarations.
     *   Example: import foo.* -> import codegen.box.annotations.genericAnnotations.foo.*
     *
     * The "unique package prefix" is computed individually for every test file and reflects relative path to the test file.
     * Example: codegen/box/annotations/genericAnnotations.kt -> codegen.box.annotations.genericAnnotations
     *
     * Note that packages with fully-qualified name starting with "kotlin." are kept unchanged.
     * Examples: package kotlin.coroutines -> package kotlin.coroutines
     *           import kotlin.test.* -> import kotlin.test.*
     */
    private fun stampPackageNames() {
        var inMultilineComment = false

        structure.transformEachFileByLines(skipSupportModule = false) { line ->
            val trimmedLine = line.trim()
            when {
                inMultilineComment -> inMultilineComment = !trimmedLine.endsWith("*/")
                trimmedLine.startsWith("/*") -> {
                    if (!trimmedLine.endsWith("*/"))
                        inMultilineComment = true
                }
                trimmedLine.startsWith("//") -> Unit
                trimmedLine.startsWith("@file:") -> Unit
                trimmedLine.isEmpty() -> Unit
                else -> {
                    val buffer = StringBuilder()

                    if (!packageNameOfCurrentFile.isSet) {
                        val packageStatementMatch = PACKAGE_STATEMENT_REGEX.matchEntire(line)
                        if (packageStatementMatch != null) {
                            // If the first meaningful statement within the next file is a package declaration, then patch it.
                            val existingPackageName = packageStatementMatch.groupValues[1]
                            return@transformEachFileByLines if (existingPackageName.isKotlinPackageName() || existingPackageName.isHelpersPackageName()) {
                                packageNameOfCurrentFile.set(existingPackageName)
                                line
                            } else {
                                packageNameOfCurrentFile.set(existingPackageName, "${settings.effectivePackageName}.$existingPackageName")
                                line.insert(packageStatementMatch.groups[1]!!.range.first, "${settings.effectivePackageName}.")
                            }
                        } else {
                            // It might be anything else. Need to insert a package statement and continue.
                            packageNameOfCurrentFile.set("", settings.effectivePackageName)
                            buffer.appendLine("package ${settings.effectivePackageName}").appendLine()
                        }
                    }

                    // Check if the line contains import statement and patch it if necessary.
                    val importStatementMatch = IMPORT_STATEMENT_REGEX.matchEntire(line)
                    if (importStatementMatch != null) {
                        val importQualifier = importStatementMatch.groupValues[1]
                        if (importQualifier.isKotlinImportQualifier() || importQualifier.isHelpersImportQualifier())
                            buffer.append(line)
                        else
                            buffer.append(line.insert(importStatementMatch.groups[1]!!.range.first, "${settings.effectivePackageName}."))
                    } else
                        buffer.append(line)

                    return@transformEachFileByLines buffer.toString()
                }
            }

            line
        }
    }

    /** Finds the fully-qualified name of the entry point function (aka `fun box(): String`). */
    private fun findEntryPointFunction(): String {
        val entryPointFunctionFQNs = hashSetOf<String>()

        structure.forEachFile {
            val foundEntryPoints = ENTRY_POINT_FUNCTION_REGEX.findAll(textOfCurrentFile).map { match ->
                "${packageNameOfCurrentFile.patchedPackageName}.${match.groupValues[1]}"
            }.toList()

            if (foundEntryPoints.isNotEmpty()) {
                entryPointFunctionFQNs += foundEntryPoints
                moduleOfCurrentFile.markAsMain()
            }
        }

        return when (val size = entryPointFunctionFQNs.size) {
            1 -> entryPointFunctionFQNs.first()
            else -> fail { "Exactly one entry point function is expected in $testDataFile. But ${if (size == 0) "none" else size} were found." }
        }
    }

    /** Find all file-level opt-in annotations. */
    private fun findFileLevelOptInAnnotations(): Set<String> {
        val annotations = hashSetOf<String>()
        structure.forEachFile { FILE_LEVEL_OPT_IN_ANNOTATION_REGEX.findAll(textOfCurrentFile).mapTo(annotations) { it.groupValues[1] } }
        return annotations
    }

    /** Patch fully-qualified names of declarations to conform to  */
    private fun patchFullyQualifiedNamesOfDeclarations() {
        structure.forEachFile {
            val result = FULLY_QUALIFIED_REFERENCE_EXPRESSION_REGEX.replace(textOfCurrentFile) { match ->
                val fullyQualifiedName = match.groupValues[2]
                val fullyQualifiedNameParts = fullyQualifiedName.split('.')

                for (i in 1 until fullyQualifiedNameParts.size) {
                    val subPackageName = fullyQualifiedNameParts.subList(0, i)

                    val patchedPackageName = testCaseOfCurrentFile.patchedPackagesMapping[subPackageName]
                    if (patchedPackageName != null) {
                        val replacementOffset = subPackageName.sumOf { it.length + /* for dot character */ 1 }
                        val patchedFullyQualifiedName = patchedPackageName + "." + fullyQualifiedName.substring(replacementOffset)
                        val prefix = match.groupValues[1]
                        return@replace prefix + patchedFullyQualifiedName
                    }
                }

                match.value
            }

            textOfCurrentFile = result
        }
    }

    /** Annotate all objects and companion objects with [THREAD_LOCAL_ANNOTATION] to make them mutable. */
    private fun makeMutableObjects() {
        structure.transformEachFileByLines { line ->
            // Find object declarations and companion objects
            // FIXME: find only those that have vars inside
            if (OBJECT_REGEX.matches(line) || COMPANION_OBJECT_REGEX.matches(line))
                buildString {
                    append(line.computeIndentation()).append(THREAD_LOCAL_ANNOTATION).appendLine()
                    append(line)

                }
            else
                line
        }
    }

    /** Adds a wrapper to run it as Kotlin test. */
    private fun generateTestLauncher(entryPointFunctionFQN: String, optInAnnotations: Set<String>) {
        val fileText = buildString {
            if (optInAnnotations.isNotEmpty()) {
                optInAnnotations.forEach(this::appendLine)
                appendLine()
            }

            append("package ").appendLine(settings.effectivePackageName)
            appendLine()

            append(
                """
                    @kotlin.test.Test
                    fun runTest() {
                        val result = $entryPointFunctionFQN()
                        kotlin.test.assertEquals("OK", result, "Test failed with: ${'$'}result")
                    }
             
                """.trimIndent()
            )
        }

        structure.addFileToMainModule(fileName = "__launcher__.kt", text = fileText)
    }

    companion object {
        private val INCOMPATIBLE_DIRECTIVES = setOf("FULL_JDK", "JVM_TARGET", "DIAGNOSTICS")

        private const val API_VERSION_DIRECTIVE = "API_VERSION"
        private val INCOMPATIBLE_API_VERSIONS = setOf("1.4")

        private const val LANGUAGE_VERSION_DIRECTIVE = "LANGUAGE_VERSION"
        private val INCOMPATIBLE_LANGUAGE_VERSIONS = setOf("1.3", "1.4")

        private const val LANGUAGE_DIRECTIVE = "LANGUAGE"
        private val INCOMPATIBLE_LANGUAGE_SETTINGS = setOf(
            "-ProperIeee754Comparisons",                            // K/N supports only proper IEEE754 comparisons
            "-ReleaseCoroutines",                                   // only release coroutines
            "-DataClassInheritance",                                // old behavior is not supported
            "-ProhibitAssigningSingleElementsToVarargsInNamedForm", // Prohibit these assignments
            "-ProhibitDataClassesOverridingCopy",                   // Prohibit as no longer supported
            "-ProhibitOperatorMod",                                 // Prohibit as no longer supported
            "-UseBuilderInferenceOnlyIfNeeded",                     // Run only default one
            "-UseCorrectExecutionOrderForVarargArguments"           // Run only correct one
        )

        private const val USE_EXPERIMENTAL_DIRECTIVE = "USE_EXPERIMENTAL"
        private const val EXPECT_ACTUAL_LINKER_DIRECTIVE = "EXPECT_ACTUAL_LINKER"

        private fun String.parseDirectiveName(): String? = DIRECTIVE_REGEX.matchEntire(this)?.groupValues?.get(1)

        private val DIRECTIVE_REGEX = Regex("^// (!?[A-Z_]+)(:?\\s+.*|\\s*)$")
        private val DIAGNOSTIC_REGEX = Regex("<!.*?!>(.*?)<!>")
        private val PACKAGE_STATEMENT_REGEX = Regex("^\\s*package\\s+([^\\s;/]+)")
        private val IMPORT_STATEMENT_REGEX = Regex("^\\s*import\\s+([^\\s;/]+)")
        private val OBJECT_REGEX = Regex("^\\s*((private|public|internal)\\s+)?object\\s+[a-zA-Z_].*")
        private val COMPANION_OBJECT_REGEX = Regex("^\\s*((private|public|internal)\\s+)?companion\\s+object.*")
        private val ENTRY_POINT_FUNCTION_REGEX = Regex("(?m)^fun\\s+(box)\\s*\\(\\s*\\)")
        private val FILE_LEVEL_OPT_IN_ANNOTATION_REGEX = Regex("(?m)^(@file:OptIn\\([a-zA-Z0-9_\\.\\:]+\\))$")
        private val FULLY_QUALIFIED_REFERENCE_EXPRESSION_REGEX =
            Regex("(?m)([^a-zA-Z0-9_\\.])([a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)+)")

        private const val THREAD_LOCAL_ANNOTATION = "@kotlin.native.ThreadLocal"
    }
}

private fun String.isKotlinPackageName() = this == "kotlin" || startsWith("kotlin.")
private fun String.isHelpersPackageName() = this == "helpers" || startsWith("helpers.")

private fun String.isKotlinImportQualifier() = startsWith("kotlin.")
private fun String.isHelpersImportQualifier() = startsWith("helpers.")

private class ExtTestDataFileSettings(
    val languageSettings: Set<String>,
    val experimentalSettings: Set<String>,
    val expectActualLinker: Boolean,
    val effectivePackageName: PackageName
)

private class ExtTestDataFileStructure(originalTestDataFile: File) {
    private val originalTestDataFileRelativePath = originalTestDataFile.relativeTo(File(KtTestUtil.getHomeDirectory()))

    private val factory = TestFileFactory()
    private val modules = mutableMapOf<String, TestModule>()
    private val files = mutableListOf<TestFile>()

    private val patchedPackagesMapping: Map</* package name parts */ List<String>, PackageName> by lazy {
        files.associate { file ->
            val originalPackageName = file.originalPackageName
            val patchedPackageName = file.patchedPackageName

            if (originalPackageName == null || patchedPackageName == null)
                fail { "Package name not evaluated for file yet: $file" }

            originalPackageName to patchedPackageName
        }.mapKeys { (originalPackageName, _) ->
            originalPackageName.split('.')
        }
    }

    init {
        val generatedFiles = TestFiles.createTestFiles(DEFAULT_FILE_NAME, originalTestDataFile.readText(), factory)
        files += generatedFiles
        generatedFiles.map { it.module }.associateByTo(modules) { it.name }

        // Explicitly add support module to other modules' dependencies (as it is not listed there by default).
        val supportModule = modules[SUPPORT_MODULE_NAME]
        if (supportModule != null) {
            modules.forEach { (moduleName, module) ->
                if (moduleName != SUPPORT_MODULE_NAME && supportModule !in module.dependencies) {
                    module.dependencies += supportModule
                }
            }
        }
    }

    val directives: Directives get() = factory.directives

    inline fun forEachFile(skipSupportModule: Boolean = true, action: CurrentFileHandler.() -> Unit) {
        files.forEach { file ->
            if (!file.module.isSupport || !skipSupportModule) {
                val handler = object : CurrentFileHandler {
                    override var textOfCurrentFile
                        get() = file.text
                        set(value) {
                            file.text = value
                        }

                    override val packageNameOfCurrentFile = object : CurrentFileHandler.PackageNameHandler {
                        override val originalPackageName get() = file.originalPackageName ?: fail { "Package name not set yet" }
                        override val patchedPackageName get() = file.patchedPackageName ?: fail { "Package name not set yet" }
                        override val isSet get() = file.originalPackageName != null && file.patchedPackageName != null

                        override fun set(originalPackageName: PackageName, patchedPackageName: PackageName) {
                            file.originalPackageName = originalPackageName
                            file.patchedPackageName = patchedPackageName
                        }
                    }

                    override val moduleOfCurrentFile = object : CurrentFileHandler.ModuleHandler {
                        override fun markAsMain() {
                            file.module.isMain = true
                        }
                    }

                    override val testCaseOfCurrentFile = object : CurrentFileHandler.TestCaseHandler {
                        override val patchedPackagesMapping get() = this@ExtTestDataFileStructure.patchedPackagesMapping
                    }
                }

                handler.action()
            }
        }
    }

    inline fun transformEachFileByLines(skipSupportModule: Boolean = true, transform: CurrentFileHandler.(line: String) -> String?) {
        forEachFile(skipSupportModule) {
            textOfCurrentFile = textOfCurrentFile.transformByLines { line -> transform(line) }
        }
    }

    fun addFileToMainModule(fileName: String, text: String) {
        val foundModules = modules.values.filter { it.isMain }
        val mainModule = when (val size = foundModules.size) {
            1 -> foundModules.first()
            else -> fail { "Exactly one main module is expected. But ${if (size == 0) "none" else size} were found." }
        }

        files += factory.createFile(mainModule, fileName, text)
    }

    fun generateTextExcludingSupportModule(freeCompilerArgs: Collection<String>): String {
        checkModulesConsistency()

        val isStandaloneTest = files.any { it.originalPackageName?.isKotlinPackageName() == true }

        return buildString {
            appendAutogeneratedSourceCodeWarning()

            if (isStandaloneTest) appendLine("// KIND: STANDALONE")

            if (freeCompilerArgs.isNotEmpty()) {
                append("// FREE_COMPILER_ARGS: ")
                freeCompilerArgs.joinTo(this, separator = " ")
                appendLine()
            }

            modules.entries.sortedBy { it.key }.forEach { (_, module) ->
                if (module.isSupport) return@forEach // Skip support module.

                // MODULE line:
                append("// MODULE: ${module.name}")
                if (module.dependencies.isNotEmpty() || module.friends.isNotEmpty()) {
                    append('(')
                    module.dependencies.joinTo(this, separator = ",")
                    append(')')
                    if (module.friends.isNotEmpty()) {
                        append('(')
                        module.friends.joinTo(this, separator = ",")
                        append(')')
                    }
                }
                appendLine()

                module.files.forEach { file ->
                    // FILE line:
                    appendLine("// FILE: ${file.name}")

                    // FILE contents:
                    appendLine(file.text)
                }
            }
        }
    }

    fun generateSharedSupportModule(action: (moduleName: String, fileName: String, fileText: String) -> Unit) {
        modules[SUPPORT_MODULE_NAME]?.let { supportModule ->
            supportModule.files.forEach { file ->
                action(supportModule.name, file.name, file.text)
            }
        }
    }

    @Suppress("UNNECESSARY_SAFE_CALL", "UselessCallOnCollection")
    private fun checkModulesConsistency() {
        modules.values.forEach { module ->
            val unknownFriends = (module.friendsSymbols + module.friends.mapNotNull { it?.name }).toSet() - modules.keys
            assertTrue(unknownFriends.isEmpty()) { "Module $module has unknown friends: $unknownFriends" }

            val unknownDependencies = (module.dependenciesSymbols + module.dependencies.mapNotNull { it?.name }).toSet() - modules.keys
            assertTrue(unknownDependencies.isEmpty()) { "Module $module has unknown dependencies: $unknownDependencies" }

            assertTrue(module.files.isNotEmpty()) { "Module $module has no files" }
        }
    }

    private fun StringBuilder.appendAutogeneratedSourceCodeWarning() {
        appendLine(
            """
                /*
                 * This file was generated automatically.
                 * PLEASE DO NOT MODIFY IT MANUALLY.
                 *
                 * Generator tool: $GENERATOR_TOOL_NAME
                 * Original file: $originalTestDataFileRelativePath
                 */
                
            """.trimIndent()
        )
    }

    interface CurrentFileHandler {
        interface PackageNameHandler {
            val originalPackageName: PackageName
            val patchedPackageName: PackageName
            val isSet: Boolean
            fun set(originalPackageName: PackageName, patchedPackageName: PackageName = originalPackageName)
        }

        interface ModuleHandler {
            fun markAsMain()
        }

        interface TestCaseHandler {
            val patchedPackagesMapping: Map</* package name parts */ List<String>, PackageName>
        }

        var textOfCurrentFile: String
        val packageNameOfCurrentFile: PackageNameHandler
        val moduleOfCurrentFile: ModuleHandler
        val testCaseOfCurrentFile: TestCaseHandler
    }

    private class TestModule(
        name: String,
        dependencies: List<String>,
        friends: List<String>
    ) : KotlinBaseTest.TestModule(name, dependencies, friends) {
        val files = mutableListOf<TestFile>()

        val isSupport get() = name == SUPPORT_MODULE_NAME
        var isMain = false

        override fun equals(other: Any?) = (other as? TestModule)?.name == name
        override fun hashCode() = name.hashCode()
    }

    private class TestFile(
        val name: String,
        val module: TestModule,
        var text: String
    ) {
        var originalPackageName: PackageName? = null
        var patchedPackageName: PackageName? = null

        init {
            module.files += this
        }
    }

    private class TestFileFactory : TestFiles.TestFileFactory<TestModule, TestFile> {
        private val defaultModule by lazy { createModule(DEFAULT_MODULE_NAME, emptyList(), emptyList(), emptyList()) }
        private val supportModule by lazy { createModule(SUPPORT_MODULE_NAME, emptyList(), emptyList(), emptyList()) }

        lateinit var directives: Directives

        fun createFile(module: TestModule, fileName: String, text: String): TestFile =
            TestFile(getSanitizedFileName(fileName), module, text)

        override fun createFile(module: TestModule?, fileName: String, text: String, directives: Directives): TestFile {
            this.directives = directives
            return createFile(
                module = module ?: if (fileName == "CoroutineUtil.kt") supportModule else defaultModule,
                fileName = fileName,
                text = text
            )
        }

        override fun createModule(name: String, dependencies: List<String>, friends: List<String>, abiVersions: List<Int>): TestModule =
            TestModule(name, dependencies, friends)
    }

    companion object {
        private val GENERATOR_TOOL_NAME =
            "${::generateExtNativeBlackboxTestData.javaClass.`package`.name}.${::generateExtNativeBlackboxTestData.name}()"
    }
}

private class SharedTestModules {
    val modules = mutableMapOf<String, SharedTestModule>()

    fun addFile(moduleName: String, fileName: String, fileText: String) {
        modules.getOrPut(moduleName) { SharedTestModule() }.files.getOrPut(fileName) { hashSetOf() } += SharedTestFile(fileText)
    }

    fun dumpToDir(outputDir: File) {
        modules.forEach { (moduleName, module) ->
            val moduleDir = outputDir.resolve(moduleName)
            moduleDir.mkdirs()

            module.files.forEach { (fileName, files) ->
                val singleFileText: String = when (files.size) {
                    1 -> files.first().text
                    2 -> {
                        val (file1, file2) = files.toList()
                        tryToMerge(file1.text, file2.text)
                    }
                    else -> null
                } ?: fail { "Multiple variations of the same shared test file found: $fileName (${files.size})" }

                moduleDir.resolve(fileName).writeText(singleFileText)
            }
        }
    }

    // Try to merge two files (covers the most trivial case).
    private fun tryToMerge(text1: String, text2: String): String? {
        val trimmedText1 = text1.trim()
        val trimmedText2 = text2.trim()

        return when {
            trimmedText1.startsWith(trimmedText2) -> text1
            trimmedText2.startsWith(trimmedText1) -> text2
            else -> null
        }
    }
}

@JvmInline
private value class SharedTestModule(val files: MutableMap<String, MutableSet<SharedTestFile>>) {
    constructor() : this(mutableMapOf())
}

@JvmInline
private value class SharedTestFile(val text: String)
