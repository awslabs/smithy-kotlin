/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

package aws.smithy.kotlin.runtime.util

import java.io.File
import java.io.IOException
import java.util.*

public actual object Platform {
    /**
     * Get an environment variable or null
     */
    actual fun getenv(key: String): String? = System.getenv()[key]

    actual val isJvm: Boolean = true
    actual val isAndroid: Boolean by lazy { isAndroid() }
    actual val isBrowser: Boolean = false
    actual val isNode: Boolean = false
    actual val isNative: Boolean = false

    actual fun osInfo(): OperatingSystem = getOsInfo()

    /**
     * Read the contents of a file as a [String] or return null on any error.
     *
     * @param path fully qualified path encoded specifically to the target platform's filesystem.
     * @return contents of file or null if error (file does not exist, etc.)
     */
    actual fun readFile(path: String): String? = try {
        File(path).readText()
    } catch (e: IOException) {
        null
    }

    /**
     * Get a system property or null
     *
     * @param key name of environment variable
     * @return value of system property or null if undefined or platform does not support properties
     */
    actual fun getProperty(key: String): String? = System.getProperty(key)

    /**
     * return the platform-specific file path separator char.  Eg on Linux a path may be '/root` and the path
     * segment char is '/'.
     */
    actual val filePathSegment: String by lazy { File.separator }
}

private fun isAndroid(): Boolean = try {
    Class.forName("android.os.Build")
    true
} catch (ex: ClassNotFoundException) {
    false
}

private fun normalize(value: String): String = value.lowercase(Locale.US).replace(Regex("[^a-z0-9+]"), "")

private fun getOsInfo(): OperatingSystem {
    val name = normalize(System.getProperty("os.name"))

    val family = when {
        isAndroid() -> OsFamily.Android
        name.contains("windows") -> OsFamily.Windows
        name.contains("linux") -> OsFamily.Linux
        name.contains("macosx") -> OsFamily.MacOs
        else -> OsFamily.Unknown
    }

    val version = runCatching { System.getProperty("os.version") }.getOrNull()

    return OperatingSystem(family, version)
}
