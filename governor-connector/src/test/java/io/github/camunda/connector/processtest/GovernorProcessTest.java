package io.github.camunda.connector.processtest;

import static io.camunda.process.test.api.CamundaAssert.assertThat;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.client.api.worker.JobClient;
import io.camunda.process.test.api.CamundaProcessTest;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.github.camunda.connector.pricing.PriceEntry;
import io.github.camunda.connector.pricing.PriceTable;
import io.github.camunda.connector.pricing.Pricer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Runs the cost-tracking process against a real (Testcontainers-backed) Zeebe engine, deploying
 * {@code bpmn/ai-agent-task-cost-tracking.bpmn} exactly as shipped - including its real
 * {@code io.camunda.connectors.agenticai.aiagent.v1} AI Agent Task element (mocked here rather
 * than calling a real LLM), not a placeholder. The AI Agent Task's own job type is mocked with the
 * shape read from a real process instance on a live cluster:
 * {@code agent.context.metrics.tokenUsage.{inputTokenCount,outputTokenCount}}. An earlier version
 * of this project's BPMN, README and tests assumed {@code
 * agent.responseMessage.metadata.framework.tokenUsage} instead, which is not on that result; the
 * mocked connector job now also records what it was activated with, so a wrong FEEL path or a
 * shadowed variable fails here instead of only in production.
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
 * multi-iteration test here anymore; {@code business_totalCost}/{@code business_totalCostUsd} still exist and
 * are still asserted on, since a real AI Agent Task/Token Cost Reporter pair only ever needs one
 * pass through this file to prove the wiring is correct end to end.
 */
@CamundaProcessTest
class GovernorProcessTest {

  private CamundaClient client;
  private CamundaProcessTestContext processTestContext;

  /** The variables the connector job was activated with - i.e. what the input mappings produced. */
  private final AtomicReference<Map<String, Object>> receivedByConnector = new AtomicReference<>();

  /** Every Token cost job activation, in order (the sub-process demo calls the connector twice). */
  private final List<Map<String, Object>> connectorCalls = new CopyOnWriteArrayList<>();

  private static final String GOVERNOR_JOB_TYPE = "io.github.camunda:token-cost-governor:1";
  private static final String AGENT_JOB_TYPE = "io.camunda.agenticai:aiagent:1";
  private static final String AGENT_SUBPROCESS_JOB_TYPE = "io.camunda.agenticai:aiagent-job-worker:1";
  private static final String LOG_JOB_TYPE = "demo.log:unknown-model-price:1";
  // Deliberately not a whole-dollar amount: FEEL has a single numeric type (no separate
  // int/decimal), so a whole-number result like $1.00 round-trips through Zeebe as the Long `1`,
  // not the Java Double `1.0` - a $1.25/call fixture keeps business_totalCostUsd assertions unambiguous.
  private static final long COST_MICROS_PER_CALL = 1_250_000L;

  /**
   * Shape of the real AI Agent Task result, as read from a real process instance on a live cluster
   * (element template version 7, openaiCompatible provider): token usage lives at
   * agent.context.metrics.tokenUsage - NOT under responseMessage.metadata, which for this
   * provider only carries provider-specific keys.
   */
  private static Map<String, Object> agentResponse() {
    return agentResponse(100, 50);
  }

  private static Map<String, Object> agentResponse(int inputTokens, int outputTokens) {
    return agentResponse(inputTokens, outputTokens, 1);
  }

  /** modelCalls is how many LLM calls the agent made; one tool call happens between consecutive model calls. */
  private static Map<String, Object> agentResponse(int inputTokens, int outputTokens, int modelCalls) {
    Map<String, Object> tokenUsage = new HashMap<>();
    tokenUsage.put("inputTokenCount", inputTokens);
    tokenUsage.put("outputTokenCount", outputTokens);
    Map<String, Object> metrics = new HashMap<>();
    metrics.put("modelCalls", modelCalls);
    metrics.put("toolCalls", modelCalls - 1);
    metrics.put("tokenUsage", tokenUsage);
    Map<String, Object> context = new HashMap<>();
    context.put("state", "READY");
    context.put("metrics", metrics);
    Map<String, Object> responseMessage = new HashMap<>();
    responseMessage.put("role", "assistant");
    responseMessage.put("modelId", "anthropic.claude-haiku-4-5");
    responseMessage.put("metadata", Map.of("openai", Map.of("stopReason", "stop")));
    Map<String, Object> agent = new HashMap<>();
    agent.put("context", context);
    agent.put("responseMessage", responseMessage);
    agent.put("responseText", "ok");
    return agent;
  }

  private static Map<String, Object> tokenCostResult() {
    Map<String, Object> result = new HashMap<>();
    result.put("provider", "anthropic");
    result.put("model", "claude-haiku-4-5");
    result.put("agent", "task-agent");
    result.put("inputTokens", 100);
    result.put("outputTokens", 50);
    result.put("totalTokens", 150);
    result.put("costMicros", COST_MICROS_PER_CALL);
    result.put("costUsd", COST_MICROS_PER_CALL / 1_000_000.0);
    result.put("currency", "USD");
    result.put("priceSource", "DEFAULT_TABLE");
    return result;
  }

  /** The price the bundled default table gives anthropic / claude-haiku-4-5 - the same one the connector would use. */
  private static final PriceEntry HAIKU_PRICE =
      PriceTable.loadDefault().lookup("anthropic", "claude-haiku-4-5").orElseThrow();

  /** A tokenCostResult priced from the tokens actually received, exactly as the connector prices them. */
  private static Map<String, Object> tokenCostResult(String agent, long inputTokens, long outputTokens) {
    long costMicros = Pricer.priceMicros(inputTokens, outputTokens, HAIKU_PRICE);
    Map<String, Object> result = new HashMap<>();
    result.put("provider", "anthropic");
    result.put("model", "claude-haiku-4-5");
    result.put("agent", agent);
    result.put("inputTokens", inputTokens);
    result.put("outputTokens", outputTokens);
    result.put("totalTokens", inputTokens + outputTokens);
    result.put("costMicros", costMicros);
    result.put("costUsd", costMicros / 1_000_000.0);
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
        .hasVariable("business_totalCost", COST_MICROS_PER_CALL)
        .hasVariable("business_totalCostUsd", 1.25)
        .hasVariable("business_costAgent", "task-agent")
        .hasVariable("business_taskAgentCost", COST_MICROS_PER_CALL);

    // The point of this test: the connector must RECEIVE real token counts, model and agent name -
    // an earlier version passed a wrong FEEL path and the job failed validation with null tokens
    // while every assertion above still passed (the connector job itself is mocked).
    Map<String, Object> received = receivedByConnector.get();
    Assertions.assertNotNull(received, "the connector job was never activated");
    Assertions.assertEquals(100L, ((Number) received.get("inputTokens")).longValue());
    Assertions.assertEquals(50L, ((Number) received.get("outputTokens")).longValue());
    Assertions.assertEquals("anthropic", received.get("provider"));
    Assertions.assertEquals("claude-haiku-4-5", received.get("model"));
    // The name input must NOT be called "agent": the AI Agent Task's default result variable is
    // "agent", and an input of that name would shadow it for the token mappings that follow.
    Assertions.assertEquals("task-agent", received.get("agentName"));
    Assertions.assertTrue(received.get("agent") instanceof Map, "AI Agent result must stay visible");
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


  // Token counts the mocked agents report. Research Analyst has a long system prompt, a long brief and a context
  // window of 100, uses its tool and makes 3 model calls that each re-send the growing context; Quick Answer has a
  // one-line prompt, a window of 4 and answers in a single call.
  private static final int ANALYST_INPUT_TOKENS = 12_850;
  private static final int ANALYST_OUTPUT_TOKENS = 940;
  private static final int ANALYST_MODEL_CALLS = 3;
  private static final int QUICK_INPUT_TOKENS = 96;
  private static final int QUICK_OUTPUT_TOKENS = 42;

  /**
   * Runs bpmn/ai-agent-subprocess-cost-tracking.bpmn: two real AI Agent Sub-process elements (ad-hoc sub-processes
   * whose job is io.camunda.agenticai:aiagent-job-worker:1), each followed by its own Token cost task. Both job types
   * are mocked. The Research Analyst (big context) first asks for its tool, so the ad-hoc loop, the tool activity and
   * the outputElement mapping really run, then finishes with a large token count; the Quick Answer agent (small
   * context) finishes at once with a small one. The mocked connector prices whatever tokens it receives with the real
   * price table, so the two calls cost very different amounts and the accumulators prove real addition. Every Token
   * cost activation is recorded, so a wrong token path, a shadowed variable or a mis-typed agent name fails here.
   */
  @Test
  void subProcess_bigContextAndSmallContextAgents_eachCallIsPricedAndAccumulated() {
    client.newDeployResourceCommand().addResourceFromClasspath("bpmn/ai-agent-subprocess-cost-tracking.bpmn").send().join();

    AtomicBoolean toolRequested = new AtomicBoolean(false);
    processTestContext
        .mockJobWorker(AGENT_SUBPROCESS_JOB_TYPE)
        .withHandler(
            (jobClient, job) -> {
              boolean analyst = "Activity_1kyv3gj".equals(job.getElementId());
              if (analyst && toolRequested.compareAndSet(false, true)) {
                jobClient
                    .newCompleteCommand(job)
                    .withResult(
                        r ->
                            r.forAdHocSubProcess()
                                .activateElement("Tool_GetDateTime1")
                                .variable("toolCall", Map.of("_meta", Map.of("id", "call-1", "name", "Look_up_todays_date")))
                                .completionConditionFulfilled(false))
                    .send()
                    .join();
                return;
              }
              Map<String, Object> agent =
                  analyst
                      ? agentResponse(ANALYST_INPUT_TOKENS, ANALYST_OUTPUT_TOKENS, ANALYST_MODEL_CALLS)
                      : agentResponse(QUICK_INPUT_TOKENS, QUICK_OUTPUT_TOKENS, 1);
              jobClient
                  .newCompleteCommand(job)
                  .variables(Map.of("agent", agent, "agentContext", agent.get("context")))
                  .withResult(r -> r.forAdHocSubProcess().completionConditionFulfilled(true))
                  .send()
                  .join();
            });
    processTestContext
        .mockJobWorker(GOVERNOR_JOB_TYPE)
        .withHandler(
            (jobClient, job) -> {
              Map<String, Object> received = job.getVariablesAsMap();
              connectorCalls.add(received);
              Map<String, Object> result =
                  tokenCostResult(
                      (String) received.get("agentName"),
                      ((Number) received.get("inputTokens")).longValue(),
                      ((Number) received.get("outputTokens")).longValue());
              jobClient.newCompleteCommand(job).variables(Map.of("tokenCostResult", result)).send().join();
            });

    ProcessInstanceEvent instance =
        client.newCreateInstanceCommand().bpmnProcessId("ai-agent-subprocess-cost-tracking").latestVersion().send().join();

    long analystMicros = Pricer.priceMicros(ANALYST_INPUT_TOKENS, ANALYST_OUTPUT_TOKENS, HAIKU_PRICE);
    long quickMicros = Pricer.priceMicros(QUICK_INPUT_TOKENS, QUICK_OUTPUT_TOKENS, HAIKU_PRICE);
    Assertions.assertTrue(analystMicros > 50 * quickMicros, "the big-context agent must cost far more than the small one");

    assertThat(instance)
        .isCompleted()
        .hasCompletedElements(
            "Activity_1kyv3gj", "Tool_GetDateTime1", "Activity_0vkhe3f", "Activity_0alays1", "Activity_1vgqpep", "EndEvent_Done")
        .hasVariable("business_totalCost", analystMicros + quickMicros)
        .hasVariable("business_totalCostUsd", (analystMicros + quickMicros) / 1_000_000.0)
        .hasVariable("business_costAgent", "research-analyst, quick-answer")
        .hasVariable("business_researchAnalystCost", analystMicros)
        .hasVariable("business_quickAnswerCost", quickMicros);

    Assertions.assertEquals(2, connectorCalls.size(), "one Token cost call per agent");
    Map<String, Object> analystCall = connectorCalls.get(0);
    Map<String, Object> quickCall = connectorCalls.get(1);
    Assertions.assertEquals((long) ANALYST_INPUT_TOKENS, ((Number) analystCall.get("inputTokens")).longValue());
    Assertions.assertEquals((long) ANALYST_OUTPUT_TOKENS, ((Number) analystCall.get("outputTokens")).longValue());
    Assertions.assertEquals("research-analyst", analystCall.get("agentName"));
    Assertions.assertEquals((long) QUICK_INPUT_TOKENS, ((Number) quickCall.get("inputTokens")).longValue());
    Assertions.assertEquals((long) QUICK_OUTPUT_TOKENS, ((Number) quickCall.get("outputTokens")).longValue());
    Assertions.assertEquals("quick-answer", quickCall.get("agentName"));
    for (Map<String, Object> call : connectorCalls) {
      Assertions.assertEquals("anthropic", call.get("provider"));
      Assertions.assertEquals("claude-haiku-4-5", call.get("model"));
    }
  }

  private void completeGovernorJob(JobClient jobClient, ActivatedJob job) {
    receivedByConnector.set(job.getVariablesAsMap());
    jobClient.newCompleteCommand(job).variables(Map.of("tokenCostResult", tokenCostResult())).send().join();
  }
}
