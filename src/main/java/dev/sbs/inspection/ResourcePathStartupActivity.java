package dev.sbs.inspection;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;

final class ResourcePathStartupActivity implements ProjectActivity {

    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        // Trigger the service to ensure it gets initialized
        project.getService(ResourcePathChangeService.class);
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

}
