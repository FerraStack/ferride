package dev.ferride.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ferride.scm.CredentialPrompt
import dev.ferride.scm.FakeScmProvider
import dev.ferride.scm.FakeScmRepo
import dev.ferride.scm.ScmOperation
import dev.ferride.workbench.FakeWorkspaceBackend
import dev.ferride.workbench.InMemorySettingsStore
import dev.ferride.workbench.SideView
import dev.ferride.workbench.WorkbenchState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CompletableFuture

/**
 * Source Control screens on the JVM with a fake repository (no native library): the view with staged and
 * unstaged changes, the branch quick pick, the "no repository" welcome, the sign-in prompt and a clone in
 * progress. Phone (Pixel 7) and tablet, dark and light.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScmScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shoot(
        name: String,
        dark: Boolean,
        repo: Boolean = true,
        saf: Boolean = false,
        setup: (WorkbenchState) -> Unit,
    ) {
        val backend = if (saf) FakeWorkspaceBackend(kind = dev.ferride.core.WorkspaceKind.SAF) else FakeWorkspaceBackend()
        val provider = FakeScmProvider(if (repo) FakeScmRepo(backend.root) else null)
        val state = WorkbenchState(
            backend,
            CoroutineScope(Dispatchers.Unconfined),
            Dispatchers.Unconfined,
            settingsStore = InMemorySettingsStore(),
            scmProvider = provider,
        )
        state.systemDark = dark
        state.scm.projectsDir = "/sample/projects"
        state.start("src/main.rs")
        setup(state)
        compose.setContent { FerrideWorkbench(state, imeVisible = false, hardwareKeyboard = false) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/${name}_${if (dark) "dark" else "light"}.png")
    }

    private fun view(s: WorkbenchState) {
        s.showView(SideView.SourceControl)
        s.scm.commitMessage = "Count words with a HashMap"
    }

    private fun branches(s: WorkbenchState) {
        s.scm.showBranchPicker()
    }

    private fun signIn(s: WorkbenchState) {
        s.scm.credentialPrompt = CredentialPrompt("https://github.com/ferrastack/sample.git", CompletableFuture()).apply {
            username = "ada"
        }
    }

    private fun cloning(s: WorkbenchState) {
        s.scm.operation = ScmOperation(
            "Cloning git repository 'https://github.com/ferrastack/sample.git'...",
            "Receiving objects",
            current = 1342,
            total = 2210,
        )
    }

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneScmDark() = shoot("phone_scm", true, setup = ::view)

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneScmLight() = shoot("phone_scm", false, setup = ::view)

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
    fun tabletScmDark() = shoot("tablet_scm", true, setup = ::view)

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
    fun tabletScmLight() = shoot("tablet_scm", false, setup = ::view)

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneScmBranchesDark() = shoot("phone_scm_branches", true, setup = ::branches)

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneScmBranchesLight() = shoot("phone_scm_branches", false, setup = ::branches)

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
    fun tabletScmBranchesDark() = shoot("tablet_scm_branches", true, setup = ::branches)

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
    fun tabletScmBranchesLight() = shoot("tablet_scm_branches", false, setup = ::branches)

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneScmSafDark() = shoot("phone_scm_saf", true, saf = true) { it.showView(SideView.SourceControl) }

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneScmSafLight() = shoot("phone_scm_saf", false, saf = true) { it.showView(SideView.SourceControl) }

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
    fun tabletScmSafDark() = shoot("tablet_scm_saf", true, saf = true) { it.showView(SideView.SourceControl) }

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
    fun tabletScmSafLight() = shoot("tablet_scm_saf", false, saf = true) { it.showView(SideView.SourceControl) }

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneScmEmptyDark() = shoot("phone_scm_empty", true, repo = false) { it.showView(SideView.SourceControl) }

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneScmEmptyLight() = shoot("phone_scm_empty", false, repo = false) { it.showView(SideView.SourceControl) }

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
    fun tabletScmEmptyDark() = shoot("tablet_scm_empty", true, repo = false) { it.showView(SideView.SourceControl) }

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
    fun tabletScmEmptyLight() = shoot("tablet_scm_empty", false, repo = false) { it.showView(SideView.SourceControl) }

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneScmSignInDark() = shoot("phone_scm_signin", true, setup = ::signIn)

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneScmSignInLight() = shoot("phone_scm_signin", false, setup = ::signIn)

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
    fun tabletScmSignInDark() = shoot("tablet_scm_signin", true, setup = ::signIn)

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
    fun tabletScmSignInLight() = shoot("tablet_scm_signin", false, setup = ::signIn)

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneScmCloneDark() = shoot("phone_scm_clone", true, setup = ::cloning)

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneScmCloneLight() = shoot("phone_scm_clone", false, setup = ::cloning)

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
    fun tabletScmCloneDark() = shoot("tablet_scm_clone", true, setup = ::cloning)

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
    fun tabletScmCloneLight() = shoot("tablet_scm_clone", false, setup = ::cloning)
}
