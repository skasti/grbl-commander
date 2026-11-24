package no.grblcommander.machine.storage

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GrblStorageReportsTest {
    @Test
    fun `mounts report optional capacity and working directory`() {
        assertEquals(
            listOf(
                GrblStorageReports.Mount("fatfs", "/", "7.45 GB", "5.59 GB"),
                GrblStorageReports.Mount("littlefs", "/littlefs", null, null),
            ),
            GrblStorageReports.mounts(listOf("[FS:fatfs@/ size 7.45 GB, free 5.59 GB]", "[FS:littlefs@/littlefs]")),
        )
        assertEquals("/littlefs", GrblStorageReports.workingDirectory(listOf("[CWD:/littlefs/]")))
    }

    @Test
    fun `entry reports preserve names sizes and the unusable marker`() {
        assertEquals(
            listOf(
                GrblStorageReports.Entry("..", null, true, true),
                GrblStorageReports.Entry("sub directory", null, true, true),
                GrblStorageReports.Entry("/jobs/part.nc", 4_294_967_295, false, true),
                GrblStorageReports.Entry("a|SIZE:1]~.txt", 0, false, false),
            ),
            GrblStorageReports.entries(listOf(
                "[FILE:..|SIZE:-1]", "[FILE:sub directory|SIZE:-1]", "[FILE:/jobs/part.nc|SIZE:4294967295]",
                "[FILE:a|SIZE:1]~.txt|SIZE:0|UNUSABLE]", "[MSG:Unsolicited notification]",
            )),
        )
    }

    @Test
    fun `malformed records fail instead of producing an empty or partial result`() {
        assertFailsWith<IOException> { GrblStorageReports.entries(listOf("[FILE:a|SIZE:-2]")) }
        assertFailsWith<IOException> { GrblStorageReports.entries(listOf("[FILE:a|SIZE:9223372036854775808]")) }
        assertFailsWith<IOException> { GrblStorageReports.workingDirectory(emptyList()) }
        assertFailsWith<IOException> { GrblStorageReports.mounts(listOf("[FS:fatfs]")) }
    }
}
