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
 * tokenCostResult}. This class verifies BPMN orchestration (the loop-back gateway, the
 * accumulated cost, the optional PRICE_NOT_FOUND boundary event); the connector's own local
 * computation is already covered by {@code GovernorConnectorTest}.
 *
 * <p>{@code iterationCount} is deliberately NOT asserted at 1/2 the way it once was: the real AI
 * Agent Task element has no explicit {@code zeebe:output} of its own (unlike the old placeholder),
 * so nothing increments it - it stays at its StartEvent-initialized {@code 0} for the life of the
 * process. That's real, expected behavior with a real single-shot AI Agent Task wired in (see the
 * BPMN file's own top comment), not a test bug - {@link #singleIteration_completesViaDone}
 * asserts on this explicitly so it's a documented fact, not a silent gap. {@link
 * #loopsTwiceThenCompletes} still proves the loop-back sequence flow itself remains wired
 * correctly (by having the mock supply {@code continueLoop} directly, something a real AI Agent
 * Task never does on its own) and that cost correctly accumulates across iterations either way.
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
  void singleIteration_completesViaDone() {
    client.newDeployResourceCommand().addResourceFromClasspath("bpmn/ai-agent-task-cost-tracking.bpmn").send().join();
    processTestContext.mockJobWorker(GOVERNOR_JOB_TYPE).withHandler(this::completeGovernorJob);
    // No "continueLoop" here - a real AI Agent Task never sends one; the process reaches
    // EndEvent_Done via the gateway's default flow, exactly as it would in production.
    processTestContext.mockJobWorker(AGENT_JOB_TYPE).thenComplete(Map.of("agent", agentResponse()));

    ProcessInstanceEvent instance =
        client.newCreateInstanceCommand().bpmnProcessId("ai-agent-task-cost-tracking").latestVersion().send().join();

    assertThat(instance)
        .isCompleted()
        .hasCompletedElements("EndEvent_Done")
        .hasVariable("iterationCount", 0L)
        .hasVariable("totalCostMicros", COST_MICROS_PER_CALL)
        .hasVariable("totalCostUsd", 1.25);
  }

  @Test
  void loopsTwiceThenCompletes() {
    client.newDeployResourceCommand().addResourceFromClasspath("bpmn/ai-agent-task-cost-tracking.bpmn").send().join();
    processTestContext.mockJobWorker(GOVERNOR_JOB_TYPE).withHandler(this::completeGovernorJob);

    // continueLoop is injected by this mock, not by the real AI Agent Task (which has no such
    // field) - this proves the loop-back sequence flow/gateway still work structurally, and that
    // cost keeps accumulating correctly across iterations, for whoever wires their own signal
    // into continueLoop (e.g. chaining multiple AI Agent Task activations).
    AtomicInteger agentCallCount = new AtomicInteger(0);
    processTestContext
        .mockJobWorker(AGENT_JOB_TYPE)
        .withHandler(
            (jobClient, job) -> {
              boolean continueLoop = agentCallCount.incrementAndGet() < 2;
              Map<String, Object> variables = new HashMap<>();
              variables.put("continueLoop", continueLoop);
              variables.put("agent", agentResponse());
              jobClient.newCompleteCommand(job).variables(variables).send().join();
            });

    ProcessInstanceEvent instance =
        client.newCreateInstanceCommand().bpmnProcessId("ai-agent-task-cost-tracking").latestVersion().send().join();

    assertThat(instance)
        .isCompleted()
        .hasCompletedElements("EndEvent_Done")
        .hasVariable("totalCostMicros", COST_MICROS_PER_CALL * 2)
        .hasVariable("totalCostUsd", 2.5);
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
