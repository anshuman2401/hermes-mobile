package io.github.anshuman2401.hermesmobile.feature.brain

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source checks supplement ViewModel behavior; Compose rendering is not proven here. */
class BrainSkillsScreenSourceContractTest {
    @Test
    fun pageHasBackProfileLoadingEmptyErrorAndReadyRowStates() {
        val source = source("io/github/anshuman2401/hermesmobile/feature/brain/BrainSkillsScreen.kt")
        assertTrue(source.contains("Text(\"Skills\""))
        assertTrue(source.contains("Profile: \$titleProfile"))
        assertTrue(source.contains("BrainSkillsLoadState.Loading"))
        assertTrue(source.contains("No installed skills"))
        assertTrue(source.contains("TextButton(onClick = viewModel::load)"))
        assertTrue(source.contains("LazyColumn("))
        assertTrue(source.contains("Switch("))
    }

    @Test
    fun rowSeparatesDetailCallbackFromSwitchAndExcludesDeferredActions() {
        val source = source("io/github/anshuman2401/hermesmobile/feature/brain/BrainSkillsScreen.kt")
        assertTrue(source.contains("onOpenSkill: (name: String, snapshotId: Long) -> Unit"))
        assertTrue(source.contains("onOpenSkill(skill.name, loadState.snapshot.snapshotId)"))
        assertTrue(source.contains("clickable(onClick = onOpen)"))
        assertTrue(source.contains("onCheckedChange = onToggle"))
        listOf(
            "Search",
            "Refresh",
            "Hub",
            "Install",
            "Update",
            "Edit",
            "Create",
            "Archive",
            "Uninstall",
            "Bulk",
            "Tools",
            "Memory",
            "Provider setup"
        ).forEach { label ->
            assertNoLabeledAction(source, label)
        }
        assertTrue(!source.contains("http://"))
        assertTrue(!source.contains("OkHttp"))
    }

    private fun assertNoLabeledAction(source: String, label: String) {
        val action = Regex(
            """(?s)(?:TextButton|Button|OutlinedButton|IconButton)\s*\([^)]*\)\s*\{[^}]*Text\("$label"\)"""
        )
        assertTrue("Unexpected deferred action label: $label", !action.containsMatchIn(source))
    }

    private fun source(relativePath: String): String {
        val direct = File("src/main/java/$relativePath")
        val fromRepoRoot = File("app/src/main/java/$relativePath")
        val file = if (direct.exists()) direct else fromRepoRoot
        assertTrue("Expected source file to exist: $relativePath", file.exists())
        return file.readText()
    }
}
