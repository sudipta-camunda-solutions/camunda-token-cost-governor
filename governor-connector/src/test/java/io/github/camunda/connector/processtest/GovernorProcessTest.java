package io.github.camunda.connector.processtest;

import static io.camunda.process.test.api.CamundaAssert.assertThat;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.client.api.worker.JobClient;
import io.camunda.process.test.api.CamundaProcessTest;
import io.camunda.process.test.api.CamundaProcessTestContext;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Runs the cost-tracking process against a real (Testcontainers-backed) Zeebe engine, deploying
 * {@code bpmn/ai-agent-task-cost-tracking.bpmn} exactly as shipped - including its real
 * {@code io.camunda.connectors.agenticai.aiagent.v1} AI Agent Task element (mocked here rather
 * than calling a real LLM), not a placeholder. The AI Agent Task's own job type is mocked with the
 * {@code agent.responseMessage.metadata.framework.tokenUsage.{inputTokenCount,outputTokenCount}}
 * shape Camunda's own docs confirm is the real result shape - verified against
 * docs.camunda.io, not assumed (an earlier version of this project's demo BPMN/tests, and this
 * project's own README, incorrectly assumed a different, invented shape before this was checked
 * against a real deployment).
 *
 * <p>The connector's own job type ({@code io.github.camunda:token-cost-governor:1}) is mocked
 * too, since there's only one operation now - the mock always completes with a canned {@code
 * tokenCostResult}. This class verifies BPMN orchestration (the accumulated cost, the optional
 * PRICE_NOT_FOUND boundary event); the connector's own local computation is already covered by
 * {@code GovernorConnectorTest}.
 *
 * <p>The process is a single straight-line pass (Start -&gt; AI Agent Task -&gt; Token cost -&gt;
 * Done), not a loop - an earlier version of both this file and the BPMN had a "continue looping?"
 * gateway looping back to the AI Agent Task, but a real AI Agent Task has no {@code continueLoop}
 * concept of its own (it runs its own internal tool-call loop internally and completes once), so
 * that loop-back could never actually fire, and Modeler's own linter flags a task with multiple
 * incoming sequence flows and no explicit join as an error regardless - see the BPMN file's own
 * top comment for the full story. There is accordingly no {@code iterationCount} variable and no
 * multi-iteration test here anymore; {@code totalCostMicros}/{@code totalCostUsd} still exist and
 * are still asserted on, since a real AI Agent Task/Token Cost Reporter pair only ever needs one
 * pass through this file to prove the wiring is correct end to end.
 */
@CamundaProcessTest
class GovernorProcessTest {

  private CamundaClient client;
  private CamundaProcessTestContext processTestContext;

  private static final String GOVERNOR_JOB_TYPE = "io.github.camunda:token-cost-governor:1";
  private static final String AGENT_JOB_TYPE = "io.camunda.agenticai:aiagent:1";
  private static final String LOG_JOB_TYPE = "demo.log:unknown-model-price:1";
  // Deliberately not a whole-dollar amount: FEEL has a single numeric type (no separate
  // int/decimal), so a whole-number result like $1.00 round-trips through Zeebe as the Long `1`,
  // not the Java Double `1.0` - a $1.25/call fixture keeps totalCostUsd assertions unambiguous.
  private static final long COST_MICROS_PER_CALL = 1_250_000L;

  private static Map<String, Object> agentResponse() {
    Map<String, Object> tokenUsage = new HashMap<>();
    tokenUsage.put("inputTokenCount", 100);
    tokenUsage.put("outputTokenCount", 50);
    Map<String, Object> framework = new HashMap<>();
    framework.put("tokenUsage", tokenUsage);
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("framework", framework);
    Map<String, Object> responseMessage = new HashMap<>();
    responseMessage.put("metadata", metadata);
    Map<String, Object> agent = new HashMap<>();
    agent.put("responseMessage", responseMessage);
    return agent;
  }

  private static Map<String, Object> tokenCostResult() {
    Map<String, Object> result = new HashMap<>();
    result.put("provider", "anthropic");
    result.put("model", "claude-haiku-4-5");
    result.put("inputTokens", 100);
    result.put("outputTokens", 50);
    result.put("totalTokens", 150);
    result.put("costMicros", COST_MICROS_PER_CALL);
    result.put("costUsd", COST_MICROS_PER_CALL / 1_000_000.0);
    result.put("currency", "USD");
    result.put("priceSource", "DEFAULT_TABLE");
    return result;
  }

  @Test
  void singlePass_completesViaDone() {
    client.newDeployResourceCommand().addResourceFromClasspath("bpmn/ai-agent-task-cost-tracking.bpmn").send().join();
    processTestContext.mockJobWorker(GOVERNOR_JOB_TYPE).withHandler(this::completeGovernorJob);
    processTestContext.mockJobWorker(AGENT_JOB_TYPE).thenComplete(Map.of("agent", agentResponse()));

    ProcessInstanceEvent instance =
        client.newCreateInstanceCommand().bpmnProcessId("ai-agent-task-cost-tracking").latestVersion().send().join();

    assertThat(instance)
        .isCompleted()
        .hasCompletedElements("Activity_AiAgentTask", "Activity_TokenCost", "EndEvent_Done")
        .hasVariable("totalCostMicros", COST_MICROS_PER_CALL)
        .hasVariable("totalCostUsd", 1.25);
  }

  @Test
  void unknownModelPrice_takesBoundaryEventBranchInsteadOfDone() {
    client.newDeployResourceCommand().addResourceFromClasspath("bpmn/ai-agent-task-cost-tracking.bpmn").send().join();
    processTestContext
        .mockJobWorker(GOVERNOR_JOB_TYPE)
        .withHandler(
            (jobClient, job) ->
                jobClient.newThrowErrorCommand(job).errorCode("PRICE_NOT_FOUND").errorMessage("No price for \"anthropic:unknown\"").send().join());
    processTestContext.mockJobWorker(AGENT_JOB_TYPE).thenComplete(Map.of("agent", agentResponse()));
    processTestContext.mockJobWorker(LOG_JOB_TYPE).thenComplete();

    ProcessInstanceEvent instance =
        client.newCreateInstanceCommand().bpmnProcessId("ai-agent-task-cost-tracking").latestVersion().send().join();

    assertThat(instance)
        .isCompleted()
        .hasCompletedElements("BoundaryEvent_PriceNotFound", "Activity_LogUnknownModelPrice", "EndEvent_UnknownModelPrice")
        .hasNotActivatedElements("EndEvent_Done");
  }

  private void completeGovernorJob(JobClient jobClient, ActivatedJob job) {
    jobClient.newCompleteCommand(job).variables(Map.of("tokenCostResult", tokenCostResult())).send().join();
  }
}
