/*
 * Copyright 2018 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.bazel.kotlin.builder.tasks.jvm


import io.bazel.kotlin.builder.toolchain.CompilationStatusException
import io.bazel.kotlin.builder.toolchain.CompilationTaskExecutor
import io.bazel.kotlin.builder.utils.IS_JVM_SOURCE_FILE
import io.bazel.kotlin.builder.utils.ensureDirectories
import io.bazel.kotlin.builder.utils.expandWithSources
import io.bazel.kotlin.builder.utils.jars.SourceJarCreator
import io.bazel.kotlin.builder.utils.jars.SourceJarExtractor
import io.bazel.kotlin.model.JvmCompilationTask
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KotlinJvmTaskExecutor @Inject internal constructor(
    private val kotlinCompiler: KotlinJvmCompiler,
    private val outputSink: KotlinCompilerOutputSink,
    private val javaCompiler: JavaCompiler,
    private val jDepsGenerator: JDepsGenerator,
    private val outputJarCreator: OutputJarCreator
) : CompilationTaskExecutor<JvmCompilationTask>() {
    override fun execute(task: JvmCompilationTask): Result {
        val preprocessedTask = preprocessingSteps(task)
        // fix error handling
        try {
            val context = Context()
            val commandWithApSources = context.execute("kapt") {
                runAnnotationProcessors(preprocessedTask)
            }
            compileClasses(context, commandWithApSources)
            context.execute("create jar") {
                outputJarCreator.createOutputJar(commandWithApSources)
            }
            produceSourceJar(commandWithApSources)
            context.execute("generate jdeps") {
                jDepsGenerator.generateJDeps(commandWithApSources)
            }
            return Result(context.timings, commandWithApSources)

        } catch (ex: Throwable) {
            throw RuntimeException(ex)
        }
    }


    private fun preprocessingSteps(command: JvmCompilationTask): JvmCompilationTask {
        ensureDirectories(
            command.directories.classes,
            command.directories.temp,
            command.directories.generatedSources,
            command.directories.generatedClasses
        )
        return expandWithSourceJarSources(command)
    }

    /**
     * If any srcjars were provided expand the jars sources and create a new [JvmCompilationTask] with the
     * Java and Kotlin sources merged in.
     */
    private fun expandWithSourceJarSources(command: JvmCompilationTask): JvmCompilationTask =
        if (command.inputs.sourceJarsList.isEmpty()) {
            command
        } else {
            SourceJarExtractor(
                destDir = Paths.get(command.directories.temp).resolve("_srcjars"),
                fileMatcher = IS_JVM_SOURCE_FILE
            ).also {
                it.jarFiles.addAll(command.inputs.sourceJarsList.map { p -> Paths.get(p) })
                it.execute()
            }.let {
                command.expandWithSources(it.sourcesList.iterator())
            }
        }

    private fun produceSourceJar(command: JvmCompilationTask) {
        Paths.get(command.outputs.srcjar).also { sourceJarPath ->
            Files.createFile(sourceJarPath)
            SourceJarCreator(
                sourceJarPath
            ).also { creator ->
                // This check asserts that source jars were unpacked if present.
                check(
                    command.inputs.sourceJarsList.isEmpty() ||
                            Files.exists(Paths.get(command.directories.temp).resolve("_srcjars"))
                )
                listOf(
                    // Any (input) source jars should already have been expanded so do not add them here.
                    command.inputs.javaSourcesList.stream(),
                    command.inputs.kotlinSourcesList.stream()
                ).stream()
                    .flatMap { it.map { p -> Paths.get(p) } }
                    .also { creator.addSources(it) }
                creator.execute()
            }
        }
    }

    private fun runAnnotationProcessors(command: JvmCompilationTask): JvmCompilationTask =
        try {
            if (command.info.plugins.annotationProcessorsList.isNotEmpty()) {
                kotlinCompiler.runAnnotationProcessor(command)
                File(command.directories.generatedSources).walkTopDown()
                    .filter { it.isFile }
                    .map { it.path }
                    .iterator()
                    .let { command.expandWithSources(it) }
            } else {
                command
            }
        } catch (ex: CompilationStatusException) {
            ex.lines.also(outputSink::deliver)
            throw ex
        }

    private fun compileClasses(context: Context, command: JvmCompilationTask) {
        var kotlinError: CompilationStatusException? = null
        var result: List<String>? = null
        context.execute("kotlinc") {
            result = try {
                kotlinCompiler.compile(command)
            } catch (ex: CompilationStatusException) {
                kotlinError = ex
                ex.lines
            }
        }
        try {
            context.execute("javac") {
                javaCompiler.compile(command)
            }
        } finally {
            checkNotNull(result).also(outputSink::deliver)
            kotlinError?.also { throw it }
        }
    }

    internal class Context {
        val timings = mutableListOf<String>()
        inline fun <T> execute(name: String, task: () -> T): T {
            val start = System.currentTimeMillis()
            return try {
                task()
            } finally {
                val stop = System.currentTimeMillis()
                timings += "$name: ${stop - start} ms"
            }
        }
    }
}