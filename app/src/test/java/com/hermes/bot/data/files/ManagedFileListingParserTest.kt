package com.hermes.bot.data.files

import org.junit.Assert.*
import org.junit.Test

class ManagedFileListingParserTest {
    private fun listing(entries: String, path: String = "/workspace", parent: String = "/") =
        """{"path":"$path","parent":"$parent","locked_root":"/","can_change_path":true,"entries":[$entries]}"""

    @Test fun foldersSortFirstAndKeepServerPaths() {
        val body = listing("""{"name":"z.txt","path":"/workspace/z.txt","is_directory":false,"size":7},
            {"name":"a","path":"/workspace/a","is_directory":true,"size":null}""")
        val result = ManagedFileListingParser.parse(body)
        assertEquals(listOf("a", "z.txt"), result.entries.map { it.name })
        assertEquals("/", result.parent)
        assertEquals(7L, result.entries.last().size)
    }

    @Test fun refusesCrossFolderEntryAndParent() {
        val foreign = listing("""{"name":"x","path":"/other/x","is_directory":false}""")
        assertEquals(ManagedFileFailure.INVALID, assertThrows(ManagedFileException::class.java) {
            ManagedFileListingParser.parse(foreign)
        }.failure)
        assertEquals(ManagedFileFailure.INVALID, assertThrows(ManagedFileException::class.java) {
            ManagedFileListingParser.parse(listing("", parent = "/elsewhere"))
        }.failure)
        assertEquals(ManagedFileFailure.INVALID, assertThrows(ManagedFileException::class.java) {
            ManagedFileListingParser.parse(listing("").replace("\"locked_root\":\"/\"", "\"locked_root\":\"relative\""))
        }.failure)
    }

    @Test fun uploadNamesCannotEscapeOrTargetSensitiveFiles() {
        assertNull(ManagedFilePaths.child("/workspace", "../outside"))
        assertEquals("/workspace/credentials.json", ManagedFilePaths.child("/workspace", "credentials.json"))
        assertNull(ManagedFilePaths.child("relative", "ok.txt"))
        assertEquals("/workspace/ok.txt", ManagedFilePaths.child("/workspace", "ok.txt"))
    }
}
