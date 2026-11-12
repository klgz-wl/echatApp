package yumo.achat.app.ui.imagevideo

import org.junit.Assert.*
import org.junit.Test

class AccountGenerationTaskStoreTest {
    private fun task(id: String) = TrackedGenerationTask(id, "Task", "video", "processing", null, "", null)
    @Test fun clearingDeletedAccountLeavesOtherAccountsIntactAndPreventsLateResurrection() {
        val disk = mutableMapOf<String, String>()
        fun store(user: String) = LocalGenerationTaskStore(user, { disk[it] }, { key, value -> disk[key] = value }, { disk.remove(it); Unit })
        val old = store("a")
        old.write(listOf(task("a-task")))
        store("b").write(listOf(task("b-task")))
        old.clear()
        old.write(listOf(task("late-a-task")))
        assertTrue(store("a").read().isEmpty())
        assertEquals(listOf("b-task"), store("b").read().map { it.taskId })
        assertEquals(1, disk.size)
    }
    @Test fun legacyUnownedTasksCannotBeAdoptedByNewAccount() {
        val disk = mutableMapOf("tracked_tasks" to encodeTrackedGenerationTasks(listOf(task("old-deleted-account"))))
        val current = LocalGenerationTaskStore("current-user", { disk[it] }, { key, value -> disk[key] = value })
        assertTrue(current.read().isEmpty())
        current.write(listOf(task("current-task")))
        assertEquals(listOf("current-task"), current.read().map { it.taskId })
        assertTrue(disk.containsKey("tracked_tasks"))
    }
    @Test fun accountSwitchAndRestartRestoreOnlyTheirOwnSnapshots() {
        val disk = mutableMapOf<String, String>()
        fun store(user: String) = LocalGenerationTaskStore(user, { disk[it] }, { key, value -> disk[key] = value })
        store("a").write(listOf(task("a-task")))
        assertTrue(store("b").read().isEmpty())
        store("b").write(listOf(task("b-task")))
        assertEquals(listOf("a-task"), store("a").read().map { it.taskId })
        assertEquals(listOf("b-task"), store("b").read().map { it.taskId })
    }
    @Test fun lateOldAccountWriterCannotOverwriteNewAccount() {
        val disk = mutableMapOf<String, String>()
        val old = LocalGenerationTaskStore("a", { disk[it] }, { key, value -> disk[key] = value })
        val current = LocalGenerationTaskStore("b", { disk[it] }, { key, value -> disk[key] = value })
        current.write(listOf(task("b-task")))
        old.write(listOf(task("late-a-task")))
        assertEquals(listOf("b-task"), current.read().map { it.taskId })
    }
    @Test fun blankAccountCannotOpenSharedFallbackCache() {
        assertThrows(IllegalArgumentException::class.java) { LocalGenerationTaskStore(" ", { null }, { _, _ -> }) }
    }
}
