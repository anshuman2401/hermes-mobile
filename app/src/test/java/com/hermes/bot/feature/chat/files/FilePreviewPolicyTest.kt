package com.hermes.bot.feature.chat.files

import com.hermes.bot.data.files.FileLimits
import org.junit.Assert.*
import org.junit.Test

class FilePreviewPolicyTest {
    @Test fun formatsAreInertAndUnknownFormatsStayExternal() {
        assertEquals("text", FilePreviewPolicy.kind("report.html")); assertEquals("text/plain", FilePreviewPolicy.mime("report.html"))
        assertEquals("external", FilePreviewPolicy.kind("vector.svg")); assertEquals("external", FilePreviewPolicy.kind("archive.zip"))
        assertEquals("image", FilePreviewPolicy.kind("cover.PNG")); assertEquals("pdf", FilePreviewPolicy.kind("brief.pdf"))
        assertEquals("application/octet-stream", FilePreviewPolicy.mime("unknown.xyz"))
    }
    @Test fun imageDimensionsBoundDecodedAllocation() {
        assertNull(FilePreviewPolicy.imageSample(0, 20)); assertNull(FilePreviewPolicy.imageSample(Int.MAX_VALUE, Int.MAX_VALUE))
        assertNull(FilePreviewPolicy.imageSample(8000, 8000))
        val sample = FilePreviewPolicy.imageSample(4000, 3000)!!
        assertTrue(4000L / sample * (3000 / sample) <= 2_000_000)
        assertEquals(1, FilePreviewPolicy.imageSample(800, 600))
    }
    @Test fun textIsBoundedAndBinaryRejected() {
        assertNull(FilePreviewPolicy.text(byteArrayOf(0, 1)).text)
        val preview = FilePreviewPolicy.text("a".repeat(FileLimits.TEXT_BYTES + 100).toByteArray())
        assertEquals(4096, preview.text!!.length); assertNotNull(preview.notice)
        assertEquals("<script>synthetic</script>", FilePreviewPolicy.text("<script>synthetic</script>".toByteArray()).text)
    }
}
