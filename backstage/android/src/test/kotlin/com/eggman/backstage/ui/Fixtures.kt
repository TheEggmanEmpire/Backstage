package com.eggman.backstage.ui

import com.eggman.backstage.agent.api.PendingQuestion
import com.eggman.backstage.agent.api.PlanStep
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.Target
import com.eggman.backstage.agent.api.TaskRequest
import com.eggman.backstage.agent.api.TaskSnapshot
import com.eggman.backstage.agent.api.TaskStatus

/** Test fixtures shared by  unit tests. */
object Fixtures {
    fun snapshot(
        id: String = "t1",
        prompt: String = "Open settings",
        status: TaskStatus = TaskStatus.RUNNING,
        source: Source = Source.CHAT,
        step: Int = 1,
        maxSteps: Int = 40,
        plan: List<PlanStep> = emptyList(),
        lastAction: String? = null,
        question: PendingQuestion? = null,
        result: String? = null,
        target: Target = Target.AUTO,
        resolvedTarget: Target? = null,
        shownActions: Int = 0,
    ) = TaskSnapshot(
        id = id,
        request = TaskRequest(prompt, source, target),
        status = status,
        step = step,
        maxSteps = maxSteps,
        plan = plan,
        lastAction = lastAction,
        question = question,
        result = result,
        startedAtMs = 1_000L,
        finishedAtMs = if (status in setOf(TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.CANCELLED)) 6_000L else null,
        resolvedTarget = resolvedTarget,
        shownActions = shownActions,
    )
}
