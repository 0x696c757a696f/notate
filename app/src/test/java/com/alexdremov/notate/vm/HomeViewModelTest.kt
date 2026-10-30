package com.alexdremov.notate.vm

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class HomeViewModelTest {

    private lateinit var app: Application
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        viewModel = HomeViewModel(app)
    }

    @Test
    fun testFreshInstallOpensDefaultOnDeviceProject() {
        // A new user lands inside a ready-to-use project instead of an empty page
        val projects = viewModel.projects.value
        assertEquals(1, projects.size)
        assertEquals("My Notes", projects[0].name)
        assertEquals(projects[0], viewModel.currentProject.value)

        // Stored on the device, not behind a system folder picker
        val root = File(projects[0].uri)
        assertTrue("Default project should be an on-device folder", root.isAbsolute && root.isDirectory)
    }

    @Test
    fun testDefaultProjectIsOnlyCreatedOnce() {
        val defaultProject = viewModel.projects.value.single()
        viewModel.removeProject(defaultProject)
        assertTrue(viewModel.projects.value.isEmpty())

        // Relaunch: the user removed it on purpose, so it must not come back
        val relaunched = HomeViewModel(app)
        assertTrue(relaunched.projects.value.isEmpty())
        assertNull(relaunched.currentProject.value)
    }

    @Test
    fun testAddLocalProjectCreatesFolderAndOpensIt() {
        viewModel.addLocalProject("Physics")
        viewModel.addLocalProject("Physics")

        val physics = viewModel.projects.value.filter { it.name == "Physics" }
        assertEquals(2, physics.size)
        assertNotEquals("Each project gets its own folder", physics[0].uri, physics[1].uri)
        physics.forEach { assertTrue(File(it.uri).isDirectory) }
        assertEquals(physics[1], viewModel.currentProject.value)
    }

    @Test
    fun testLastOpenedProjectIsRestored() {
        viewModel.addLocalProject("Math")
        viewModel.addLocalProject("Chemistry")
        val math = viewModel.projects.value.first { it.name == "Math" }
        viewModel.openProject(math)

        val relaunched = HomeViewModel(app)
        assertEquals(math.id, relaunched.currentProject.value?.id)
    }

    @Test
    fun testNavigateUpAtProjectRootReturnsToTopLevel() {
        assertNotNull(viewModel.currentProject.value)

        viewModel.navigateUp()

        assertTrue(viewModel.isAtTopLevel())
        assertEquals("My Projects", viewModel.title.value)
        assertNull(viewModel.currentProject.value)
    }

    @Test
    fun testTagManagement() {
        val initialTags = viewModel.tags.value

        // Add a tag
        viewModel.addTag("Urgent", 0xFFFF0000.toInt())

        // Since tags are saved to SharedPreferences via PreferencesManager,
        // and Robolectric provides a working Context, this should update the state.
        val updatedTags = viewModel.tags.value
        assertTrue("Tags list should have grown", updatedTags.size > initialTags.size)

        val newTag = updatedTags.find { it.name == "Urgent" }
        assertNotNull("New tag should exist", newTag)
        assertEquals(0xFFFF0000.toInt(), newTag?.color)
    }
}
