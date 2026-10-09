package io.github.psd2live.ui.state

import io.github.psd2live.ui.state.DesktopWorkspace
import io.github.psd2live.project.ProjectRepository
import io.github.psd2live.project.ProjectSaveCapture
import io.github.psd2live.project.ProjectStage
import io.github.psd2live.application.WorkspacePreviewProgress
import io.github.psd2live.i18n.tr
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import io.github.psd2live.application.WorkspaceJobCompletion
import io.github.psd2live.application.WorkspaceOperationOutput
import io.github.psd2live.application.lifecycleResult
import kotlinx.coroutines.currentCoroutineContext

/** Desktop lifecycle projection; all archive reading/writing is UI-independent. */
internal class ProjectController(private val viewModel: PSD2LiveViewModel) {
    private val repository = ProjectRepository()
    private val saves = Mutex()

    suspend fun save(workspace: DesktopWorkspace, path: Path, actor: String = "user"): String =
        saveCapture(workspace, path, actor).history.headNodeId

    /** Shows [key] for [path] in the status bar, [fraction] of the way from [from] to [to] of the whole open or save. */
    private fun progress(key: String, path: Path, from: Float, to: Float, fraction: Double = 0.0) =
        viewModel.reportProjectProgress(tr(key, path.fileName.toString()), from + (to - from) * fraction.toFloat())

    suspend fun saveCapture(workspace: DesktopWorkspace, path: Path, actor: String = "user"): DesktopWorkspace.ProjectCapture {
        var capturedState = viewModel.state.value
        val committed = java.util.concurrent.atomic.AtomicBoolean()
        val completion = currentCoroutineContext()[WorkspaceJobCompletion]
        viewModel.projectSaveStarted()
        progress("project.progress.capturing", path, 0f, 0.15f)
        try {
            // Recording an edit still in the fields may rebuild the model; that build paces this stage.
            val capture = withContext(WorkspacePreviewProgress { progress("project.progress.capturing", path, 0f, 0.15f, it.toDouble()) }) {
                workspace.captureProject("Save project", actor)
            }
            capturedState = capture.uiState
            return saves.withLock {
                workspace.flushProjectPersistence()
                val state = capture.uiState
                val originalPath = state.loadedInputPath ?: state.inputPath
                repository.save(ProjectSaveCapture(capture.projectId, capture.history,
                    WorkspaceStateCodec.encode(state), originalPath.takeIf(String::isNotBlank)?.let(Path::of),
                    capture.store, capture.spatial, capture.tasks, capture.auxiliary), path,
                    progress = { stage, fraction ->
                        when (stage) {
                            ProjectStage.STAGING -> progress("project.progress.staging", path, 0.15f, 0.4f, fraction)
                            ProjectStage.PACKING -> progress("project.progress.packing", path, 0.4f, 1f, fraction)
                            else -> Unit
                        }
                    }) {
                    committed.set(true)
                    completion?.committed(WorkspaceOperationOutput(workspace.savedResult(capture).lifecycleResult()))
                    workspace.projectSaved(capture)
                    viewModel.projectSaveFinished(path, capture.history.headNodeId, state)
                }
                capture
            }
        } catch (failure: Exception) {
            if (!committed.get()) viewModel.projectSaveFailed(failure, capturedState)
            throw failure
        } finally { viewModel.clearProjectProgress() }
    }

    suspend fun open(workspace: DesktopWorkspace, path: Path, discardUnsaved: Boolean = true) = saves.withLock {
        val expected = workspace.captureProjectOpen(discardUnsaved)
        val completion = currentCoroutineContext()[WorkspaceJobCompletion]
        try {
            repository.open(path) { stage, fraction ->
                when (stage) {
                    ProjectStage.EXTRACTING -> progress("project.progress.extracting", path, 0f, 0.45f, fraction)
                    ProjectStage.READING -> progress("project.progress.reading", path, 0.45f, 0.6f, fraction)
                    else -> Unit
                }
            }.use { opened ->
                val state = WorkspaceStateCodec.decode(opened.presentation)
                progress("project.progress.building", path, 0.6f, 1f)
                val installed = withContext(WorkspacePreviewProgress { progress("project.progress.building", path, 0.6f, 1f, it.toDouble()) }) {
                    workspace.installProject(opened.projectId, opened.file, opened.source, state,
                        opened.history, opened.store, expected, discardUnsaved, opened.presentation)
                }
                workspace.rememberProjectDirectory(opened.transferDirectory())
                completion?.committed(WorkspaceOperationOutput(workspace.openedResult(installed).lifecycleResult()))
                viewModel.refreshWorkspaceRenderer(installed.model)
                installed
            }
        } finally { viewModel.clearProjectProgress() }
    }
}
