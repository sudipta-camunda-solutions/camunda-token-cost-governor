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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Runs the demo cost-tracking loop process against a real (Testcontainers-backed) Zeebe engine.
 * The connector's own job type ({@code io.github.camunda:token-cost-governor:1}) is mocked here
 * rather than executed for real, since there's only one operation now - the mock always completes
 * with a canned {@code tokenCostResult}. This class verifies BPMN orchestration (the loop-back
 * gateway, the iteration counter, the accumulated cost, the optional PRICE_NOT_FOUND boundary
 * event); the connector's own local computation is already covered by {@code
 * GovernorConnectorTest}.
 */
@CamundaProcessTest
class GovernorProcessTest {

  private CamundaClient client;
  private CamundaProcessTestContext processTestContext;

  private static final String GOVERNOR_JOB_TYPE = "io.github.camunda:token-cost-governor:1";
  private static final String AGENT_JOB_TYPE = "demo.aiagent:task:1";
  private static final String LOG_JOB_TYPE = "demo.log:unknown-model-price:1";
  private static final long COST_MICROS_PER_CALL = 1_000_000L;

  private static Map<String, Object> agentResult() {
    Map<String, Object> tokenUsage = new HashMap<>();
    tokenUsage.put("inputTokens", 100);
    tokenUsage.put("outputTokens", 50);
    Map<String, Object> agentResult = new HashMap<>();
    agentResult.put("model", "claude-sonnet-5");
    agentResult.put("tokenUsage", tokenUsage);
    return agentResult;
  }

  private static Map<String, Object> tokenCostResult() {
    Map<String, Object> result = new HashMap<>();
    result.put("provider", "anthropic");
    result.put("model", "claude-sonnet-5");
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
  void singleIteration_completesViaDone() {
    client.newDeployResourceCommand().addResourceFromClasspath("bpmn/ai-agent-task-cost-tracking.bpmn").send().join();
    processTestContext.mockJobWorker(GOVERNOR_JOB_TYPE).withHandler(this::completeGovernorJob);
    processTestContext.mockJobWorker(AGENT_JOB_TYPE).thenComplete(Map.of("continueLoop", false, "agentResult", agentResult()));

    ProcessInstanceEvent instance =
        client.newCreateInstanceCommand().bpmnProcessId("ai-agent-task-cost-tracking").latestVersion().send().join();

    assertThat(instance)
        .isCompleted()
        .hasCompletedElements("EndEvent_Done")
        .hasVariable("iterationCount", 1L)
        .hasVariable("totalCostMicros", COST_MICROS_PER_CALL);
  }

  @Test
  void loopsTwiceThenCompletes() {
    client.newDeployResourceCommand().addResourceFromClasspath("bpmn/ai-agent-task-cost-tracking.bpmn").send().join();
    processTestContext.mockJobWorker(GOVERNOR_JOB_TYPE).withHandler(this::completeGovernorJob);

    AtomicInteger agentCallCount = new AtomicInteger(0);
    processTestContext
        .mockJobWorker(AGENT_JOB_TYPE)
        .withHandler(
            (jobClient, job) -> {
              boolean continueLoop = agentCallCount.incrementAndGet() < 2;
              jobClient.newCompleteCommand(job).variables(Map.of("continueLoop", continueLoop, "agentResult", agentResult())).send().join();
            });

    ProcessInstanceEvent instance =
        client.newCreateInstanceCommand().bpmnProcessId("ai-agent-task-cost-tracking").latestVersion().send().join();

    assertThat(instance)
        .isCompleted()
        .hasCompletedElements("EndEvent_Done")
        .hasVariable("iterationCount", 2L)
        .hasVariable("totalCostMicros", COST_MICROS_PER_CALL * 2);
  }

  @Test
  void unknownModelPrice_takesBoundaryEventBranchInsteadOfDone() {
    client.newDeployResourceCommand().addResourceFromClasspath("bpmn/ai-agent-task-cost-tracking.bpmn").send().join();
    processTestContext
        .mockJobWorker(GOVERNOR_JOB_TYPE)
        .withHandler(
            (jobClient, job) ->
                jobClient.newThrowErrorCommand(job).errorCode("PRICE_NOT_FOUND").errorMessage("No price for \"anthropic:unknown\"").send().join());
    processTestContext.mockJobWorker(AGENT_JOB_TYPE).thenComplete(Map.of("continueLoop", false, "agentResult", agentResult()));
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
