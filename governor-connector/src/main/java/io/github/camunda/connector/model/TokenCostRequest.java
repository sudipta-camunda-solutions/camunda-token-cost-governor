package io.github.camunda.connector.model;

import io.camunda.connector.generator.java.annotation.FeelMode;
import io.camunda.connector.generator.java.annotation.TemplateProperty;
import io.camunda.connector.generator.java.annotation.TemplateProperty.PropertyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Input for computing the token count and cost of one LLM call. Token counts are expected to
 * come from wherever the process already has them - most naturally the AI Agent Task/Sub-process
 * element's own result (e.g. {@code =agent.context.metrics.tokenUsage.inputTokenCount} for an AI
 * Agent Task whose result variable is {@code agent}), since Camunda's native agentic-ai elements
 * already report usage as part of their own result.
 *
 * @param provider e.g. {@code =agentProvider}, or a literal like {@code anthropic} - combined
 *     with {@code model} as the price-table lookup key
 * @param model e.g. {@code =agentModel}
 * @param inputTokens e.g. {@code =agent.context.metrics.tokenUsage.inputTokenCount}
 * @param outputTokens e.g. {@code =agent.context.metrics.tokenUsage.outputTokenCount}
 * @param agentName optional label identifying which agent this call belongs to, so token count and
 *     cost can be broken down per agent. Low-cardinality by design (it is used as a Prometheus
 *     label): a handful of stable names, never a per-instance or per-customer value. Blank falls
 *     back to {@code bpmnProcessId:elementId} from the job context.
 *     Deliberately NOT named {@code agent}: the AI Agent Task's default result variable is
 *     {@code agent}, and a same-named input mapping shadows it for the token mappings after it.
 * @param priceTableSecret not user-configurable in Modeler at all (a {@code Hidden} template
 *     property) - always resolved from the {@code GOVERNOR_PRICE_TABLE} cluster secret, a JSON
 *     array of price rows maintained centrally for every developer using this connector. The
 *     secret must exist (referencing a cluster secret that was never created at all fails the
 *     job outright, before this connector's own code ever runs - this is how Camunda's secret
 *     resolution works, not a bug here); once it exists, leaving its value blank falls back to a
 *     small bundled illustrative default table, and a resolved-but-incomplete table falls back to
 *     that same default table on a per-model basis.
 */
public record TokenCostRequest(
    @NotBlank
        @TemplateProperty(group = "model", label = "Provider", feel = FeelMode.optional, description = "e.g. =agentProvider, or a literal like \"anthropic\"")
        String provider,
    @NotBlank
        @TemplateProperty(group = "model", label = "Model", feel = FeelMode.optional, description = "e.g. =agentModel")
        String model,
    @NotNull @PositiveOrZero
        @TemplateProperty(group = "usage", label = "Input tokens", type = PropertyType.Number, feel = FeelMode.optional, description = "e.g. =agent.context.metrics.tokenUsage.inputTokenCount (AI Agent Task with result variable agent)")
        Long inputTokens,
    @NotNull @PositiveOrZero
        @TemplateProperty(group = "usage", label = "Output tokens", type = PropertyType.Number, feel = FeelMode.optional, description = "e.g. =agent.context.metrics.tokenUsage.outputTokenCount (AI Agent Task with result variable agent)")
        Long outputTokens,
    @TemplateProperty(
            group = "agent",
            label = "Agent name",
            optional = true,
            feel = FeelMode.optional,
            description = "e.g. \"claims-triage\". Keep to a small, stable set of names - it becomes a Prometheus label, so never bind it to something per-instance or per-customer. Blank = processId:elementId.")
        String agentName,
    @TemplateProperty(
            label = "Price table",
            type = PropertyType.Hidden,
            feel = FeelMode.disabled,
            defaultValue = "{{secrets.GOVERNOR_PRICE_TABLE}}")
        String priceTableSecret) {}
