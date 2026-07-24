package org.futo.inputmethod.latin.uix.addons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AddonPackageTest {
    @Test
    fun validPackageExtracts() {
        withPackage(validManifest()) { archive, output ->
            val manifest = AddonPackage.extractAndValidate(archive, output, allowSystem = false)
            assertEquals("com.example.test", manifest.id)
            assertTrue(File(output, "action/index.html").isFile)
        }
    }

    @Test
    fun importedPackageCannotClaimSystem() {
        withPackage(validManifest(system = true)) { archive, output ->
            val result = runCatching {
                AddonPackage.extractAndValidate(archive, output, allowSystem = false)
            }
            assertTrue(result.exceptionOrNull()?.message?.contains("system tag") == true)
        }
    }

    @Test
    fun traversalIsRejected() {
        val root = Files.createTempDirectory("addon-test").toFile()
        try {
            val archive = File(root, "addon.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("../outside.txt"))
                zip.write("bad".toByteArray())
                zip.closeEntry()
            }
            val result = runCatching {
                AddonPackage.extractAndValidate(archive, File(root, "out"), allowSystem = false)
            }
            assertTrue(result.exceptionOrNull()?.message?.contains("unsafe path") == true)
            assertTrue(!File(root, "outside.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingEntrypointIsRejected() {
        withPackage(validManifest(), includeEntrypoint = false) { archive, output ->
            val result = runCatching {
                AddonPackage.extractAndValidate(archive, output, allowSystem = false)
            }
            assertTrue(result.exceptionOrNull()?.message?.contains("action entrypoint") == true)
        }
    }

    @Test
    fun duplicatePathsAreRejected() {
        val root = Files.createTempDirectory("addon-test").toFile()
        try {
            val archive = File(root, "addon.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("addon.json"))
                zip.write(validManifest().toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("folder/../addon.json"))
                zip.write(validManifest().toByteArray())
                zip.closeEntry()
            }
            val result = runCatching {
                AddonPackage.extractAndValidate(archive, File(root, "out"), allowSystem = false)
            }
            assertTrue(result.exceptionOrNull()?.message?.contains("overwrite the same file") == true)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun withPackage(
        manifest: String,
        includeEntrypoint: Boolean = true,
        block: (File, File) -> Unit,
    ) {
        val root = Files.createTempDirectory("addon-test").toFile()
        try {
            val archive = File(root, "addon.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                fun entry(name: String, contents: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(contents.toByteArray())
                    zip.closeEntry()
                }
                entry("addon.json", manifest)
                entry("icon.svg", """<svg viewBox="0 0 24 24"><path d="M0 0h1"/></svg>""")
                if (includeEntrypoint) entry("action/index.html", "<html></html>")
            }
            block(archive, File(root, "out"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun validManifest(system: Boolean = false) = """
        {
          "schemaVersion": 1,
          "id": "com.example.test",
          "name": "Test",
          "description": "Test add-on",
          "author": "Tests",
          "versionCode": 1,
          "versionName": "1.0",
          "icon": "icon.svg",
          "system": $system,
          "action": {"entrypoint": "action/index.html"},
          "settings": [],
          "permissions": {}
        }
    """.trimIndent()
}
