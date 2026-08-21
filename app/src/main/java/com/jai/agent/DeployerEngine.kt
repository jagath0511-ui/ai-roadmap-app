package com.jai.agent

import android.content.Context
import kotlinx.coroutines.delay

data class WorkflowStep(
    val actionCommand: String,
    val delayMs: Long = 1000L
)

object DeployerEngine {

    /**
     * Executes sequential multi-step automations safely with telemetry logging.
     */
    suspend fun executeSequence(context: Context, steps: List<WorkflowStep>): ActionResult {
        for ((index, step) in steps.withIndex()) {
            val result = JaiAgentService.executeCommand(context, step.actionCommand)
            if (result is ActionResult.Failure) {
                FailureLogger.log(context, "DeployerEngine", "Step $index failed: ${result.reason}")
                return ActionResult.Failure("Sequence stopped at step ${index + 1}: ${result.reason}")
            }
            if (step.delayMs > 0) {
                delay(step.delayMs)
            }
        }
        return ActionResult.Success("Sequence completed successfully.")
    }
}
