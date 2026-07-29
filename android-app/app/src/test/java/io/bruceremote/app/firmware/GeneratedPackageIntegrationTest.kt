package io.bruceremote.app.firmware

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GeneratedPackageIntegrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun cardputerPackageVerifiesAndReconstructsControllerImage() {
        verifyPackage(
            packageDirectory = "cardputer-adv",
            baseName = "Bruce-Base-cardputer-adv.bin",
            expectedName = "Bruce-Remote-cardputer-adv.bin",
            expectedBoard = "cardputer-adv",
        )
    }

    @Test
    fun stickCPlus2PackageVerifiesAndReconstructsControllerImage() {
        verifyPackage(
            packageDirectory = "m5stack-cplus2",
            baseName = "Bruce-Base-m5stickc-plus2.bin",
            expectedName = "Bruce-Remote-m5stickc-plus2.bin",
            expectedBoard = "m5stack-cplus2",
        )
    }

    private fun verifyPackage(
        packageDirectory: String,
        baseName: String,
        expectedName: String,
        expectedBoard: String,
    ) {
        val root = findWorkspaceRoot()
        val packageRoot = File(root, "dist/packages/$packageDirectory")
        val key = File(root, "dist/keys/dev-public-key.der").readBytes()
        val verified = FirmwareManifestVerifier(
            TrustedKeyRing.fromX509SubjectPublicKeys(
                mapOf("dev-local-2026" to key),
            ),
        ).verify(
            rawManifestBytes = File(packageRoot, "manifest.json").readBytes(),
            signatureBytes = File(packageRoot, "manifest.sig").readBytes(),
            policy = ManifestVerificationPolicy(
                nowEpochSeconds = 1_800_000_000L,
                minimumCatalogVersion = 0,
            ),
        )

        val output = File(temporaryFolder.root, "$expectedBoard-patched.bin")
        val applied = FirmwarePackageEngine().apply(
            verifiedManifest = verified,
            inputFirmware = File(root, "dist/firmware/$baseName"),
            patchPayload = File(packageRoot, "payload.brp"),
            outputFirmware = output,
            expectedBoardId = expectedBoard,
        )
        val expected = File(root, "dist/firmware/$expectedName")

        assertEquals(expectedBoard, applied.profile.boardId)
        assertEquals(expected.length(), output.length())
        assertArrayEquals(expected.readBytes(), output.readBytes())
    }

    private fun findWorkspaceRoot(): File {
        val workingDirectory = System.getProperty("user.dir")
            ?: error("The JVM did not provide user.dir")
        return generateSequence(File(workingDirectory).canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "dist/packages").isDirectory }
            ?: error("Could not locate workspace dist/packages from $workingDirectory")
    }
}
