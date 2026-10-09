package io.github.anshuman2401.hermesmobile.data.diagnostics

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrivateDiagnosticsRepositoryBehaviorTest {
    @get:Rule val tempFolder = TemporaryFolder()

    @Test fun append_reopenAndRead_useOnlyFixedPrivateSeverityFilesInChronologicalOrder() = runBlocking {
        val filesDir = tempFolder.newFolder("app-files")
        val first = PrivateDiagnosticsRepository.forTesting(filesDir)
        first.emit(CatalogEvent.GATEWAY_READY)
        val firstRead = (first.read(DiagnosticSeverity.INFO) as PrivateLogResult.Success).value

        val second = PrivateDiagnosticsRepository.forTesting(filesDir)
        second.emit(CatalogEvent.PROFILE_SAVE_OK)
        val secondRead = (second.read(DiagnosticSeverity.INFO) as PrivateLogResult.Success).value

        val diagnosticDirectory = File(filesDir, "diagnostics").canonicalFile
        assertEquals(
            DiagnosticSeverity.entries.map { it.fileName }.toSet(),
            diagnosticDirectory.listFiles()!!.map { it.name }.toSet()
        )
        assertTrue(secondRead.linesNewestFirst.first().contains("profile_save_ok"))
        assertTrue(secondRead.linesNewestFirst.last().contains("gateway_ready"))
        assertEquals(1, firstRead.linesNewestFirst.size)
    }
}
