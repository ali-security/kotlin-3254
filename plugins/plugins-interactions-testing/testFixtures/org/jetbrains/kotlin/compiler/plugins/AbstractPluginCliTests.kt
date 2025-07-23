/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.compiler.plugins

import org.jetbrains.kotlin.cli.AbstractCliTest
import java.io.File

abstract class AbstractPluginCliTests : AbstractCliTest() {
    override fun doJvmTest(fileName: String) {
        val beforePlugin = File("plugins/test-plugins/before/build/libs").listFiles().orEmpty()
            .firstOrNull { it.name.startsWith("before") && it.extension == "jar" }
            ?: error("Jar with before plugin is not found")
        beforePlugin.copyTo(File(tmpdir, "plugin-before.jar"), overwrite = true)

        val middlePlugin = File("plugins/test-plugins/middle/build/libs").listFiles().orEmpty()
            .firstOrNull { it.name.startsWith("middle") && it.extension == "jar" }
            ?: error("Jar with middle plugin is not found")
        middlePlugin.copyTo(File(tmpdir, "plugin-middle.jar"), overwrite = true)

        val afterPlugin = File("plugins/test-plugins/after/build/libs").listFiles().orEmpty()
            .firstOrNull { it.name.startsWith("after") && it.extension == "jar" }
            ?: error("Jar with after plugin is not found")
        afterPlugin.copyTo(File(tmpdir, "plugin-after.jar"), overwrite = true)

        super.doJvmTest(fileName)
    }
}
