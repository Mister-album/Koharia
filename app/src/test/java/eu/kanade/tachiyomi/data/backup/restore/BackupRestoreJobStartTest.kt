package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import com.google.common.util.concurrent.Futures
import eu.kanade.tachiyomi.data.backup.BackupPasswordStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class BackupRestoreJobStartTest {
    @Test
    fun `ignored restore request removes saved password and directory bindings`() = runBlocking {
        val context = mockk<Context>()
        val uri = mockk<Uri>()
        val manager = mockk<WorkManager>()
        val operation = mockk<Operation> {
            every { result } returns Futures.immediateFuture(Operation.SUCCESS)
        }
        val bindings = mapOf("old" to "new")
        mockkObject(WorkManager.Companion, BackupPasswordStore, RestoreDirectoryBindings)
        try {
            every { WorkManager.getInstance(context) } returns manager
            every {
                manager.enqueueUniqueWork(any(), ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>())
            } returns operation
            every { manager.getWorkInfoById(any()) } returns Futures.immediateFuture(null)
            every { BackupPasswordStore.saveManual(context, "secret") } returns "password-id"
            every { RestoreDirectoryBindings.save(context, bindings) } returns "bindings-id"
            every { BackupPasswordStore.removeManual(context, "password-id") } returns Unit
            every { RestoreDirectoryBindings.remove(context, "bindings-id") } returns Unit

            assertFalse(
                BackupRestoreJob.start(
                    context,
                    uri,
                    RestoreOptions(),
                    password = "secret",
                    directoryBindings = bindings,
                ),
            )

            verify(exactly = 1) { BackupPasswordStore.removeManual(context, "password-id") }
            verify(exactly = 1) { RestoreDirectoryBindings.remove(context, "bindings-id") }
        } finally {
            unmockkObject(WorkManager.Companion, BackupPasswordStore, RestoreDirectoryBindings)
        }
    }
}
