package com.persephone.finagent.agent;

import java.util.Map;

/**
 * A capability the agent may invoke.
 *
 * <p>Tools are the agent's whole action space, and keeping that space small and
 * typed is what makes the harness auditable. Note that no tool accepts or
 * returns a bare number: {@code fetchStatements} returns facts with provenance,
 * {@code buildComparison} returns a table whose cells carry citations. The model
 * chooses <i>which</i> tool to run and with what parameters; it never supplies
 * the data those tools produce.
 */
public interface AgentTool {

    /** Stable name used in plans and logs. */
    String name();

    /** One-line description shown in the agent's tool catalogue. */
    String description();

    /**
     * Execute with the given arguments.
     *
     * @param args tool-specific parameters
     * @return a result object; never null
     */
    ToolResult execute(Map<String, Object> args);

    /** Outcome of a tool invocation. */
    final class ToolResult {
        private final boolean success;
        private final String summary;
        private final Object payload;

        private ToolResult(boolean success, String summary, Object payload) {
            this.success = success;
            this.summary = summary;
            this.payload = payload;
        }

        public static ToolResult ok(String summary, Object payload) {
            return new ToolResult(true, summary, payload);
        }

        public static ToolResult fail(String summary) {
            return new ToolResult(false, summary, null);
        }

        public boolean isSuccess() { return success; }
        public String getSummary() { return summary; }
        public Object getPayload() { return payload; }

        @SuppressWarnings("unchecked")
        public <T> T payloadAs(Class<T> type) {
            return payload == null ? null : (T) payload;
        }
    }
}
