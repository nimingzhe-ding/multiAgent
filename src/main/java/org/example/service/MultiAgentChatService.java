package org.example.service;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.example.agent.tool.ReadDocumentTools;
import org.example.agent.tool.SkillTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * General-purpose multi-agent chat orchestration.
 *
 * <p>The normal chat agent keeps all tools in one place. This service splits
 * responsibilities so each agent only sees the tools it should use, while a
 * supervisor coordinates the task and the final answer.</p>
 */
@Service
public class MultiAgentChatService {

    private static final Logger logger = LoggerFactory.getLogger(MultiAgentChatService.class);

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private ReadDocumentTools readDocumentTools;

    @Autowired
    private QueryMetricsTools queryMetricsTools;

    @Autowired(required = false)
    private QueryLogsTools queryLogsTools;

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    @Lazy
    private SkillTools skillTools;

    public String execute(
            DashScopeChatModel chatModel,
            ToolCallback[] externalTools,
            String question,
            List<Map<String, String>> history) throws GraphRunnerException {

        ReactAgent knowledgeAgent = buildKnowledgeAgent(chatModel);
        ReactAgent opsAgent = buildOpsAgent(chatModel, externalTools);
        ReactAgent skillAgent = buildSkillAgent(chatModel);
        ReactAgent answerAgent = buildAnswerAgent(chatModel);

        SupervisorAgent supervisorAgent = SupervisorAgent.builder()
                .name("chat_supervisor")
                .description("Routes user questions to specialized agents and asks answer_agent to produce the final response.")
                .model(chatModel)
                .systemPrompt(buildSupervisorPrompt())
                .subAgents(List.of(knowledgeAgent, opsAgent, skillAgent, answerAgent))
                .build();

        String taskPrompt = buildTaskPrompt(question, history);
        logger.info("Starting multi-agent chat orchestration");
        Optional<OverAllState> stateOptional = supervisorAgent.invoke(taskPrompt);

        if (stateOptional.isEmpty()) {
            logger.warn("Multi-agent orchestration returned empty state");
            return "多 Agent 协作没有拿到有效结果，请稍后重试。";
        }

        return extractFinalAnswer(stateOptional.get());
    }

    private ReactAgent buildKnowledgeAgent(DashScopeChatModel chatModel) {
        return ReactAgent.builder()
                .name("knowledge_agent")
                .description("Searches uploaded files, OCR text, rendered web page captures, and internal documents.")
                .model(chatModel)
                .systemPrompt("""
                        You are knowledge_agent.
                        Your only job is to find evidence from the local knowledge base and readable documents.
                        Use queryInternalDocs for knowledge-base retrieval.
                        Use readDocument only when a local document path is provided or clearly needed.
                        Return concise Chinese output with:
                        - whether evidence was found
                        - source names or URLs if available
                        - the most relevant facts
                        Do not invent content that was not returned by tools.
                        """)
                .methodTools(new Object[]{internalDocsTools, readDocumentTools})
                .outputKey("knowledge_result")
                .build();
    }

    private ReactAgent buildOpsAgent(DashScopeChatModel chatModel, ToolCallback[] externalTools) {
        return ReactAgent.builder()
                .name("ops_agent")
                .description("Handles observability questions: alerts, metrics, logs, system time, and incident diagnosis.")
                .model(chatModel)
                .systemPrompt("""
                        You are ops_agent.
                        Your job is to collect operational evidence from monitoring, logs, alerts, and time tools.
                        Use only real tool results. If a tool is unavailable or returns no data, say so clearly.
                        Return concise Chinese output with evidence, tool result summary, and operational conclusion.
                        """)
                .methodTools(buildOpsMethodTools())
                .tools(externalTools == null ? new ToolCallback[0] : externalTools)
                .outputKey("ops_result")
                .build();
    }

    private ReactAgent buildSkillAgent(DashScopeChatModel chatModel) {
        return ReactAgent.builder()
                .name("skill_agent")
                .description("Searches reusable skills and historical solution patterns.")
                .model(chatModel)
                .systemPrompt("""
                        You are skill_agent.
                        Search the skill library only when the user asks for a solution pattern, runbook,
                        operational method, reusable workflow, or historical practice.
                        Return concise Chinese output. If no matching skill exists, say that directly.
                        """)
                .methodTools(new Object[]{skillTools})
                .outputKey("skill_result")
                .build();
    }

    private ReactAgent buildAnswerAgent(DashScopeChatModel chatModel) {
        return ReactAgent.builder()
                .name("answer_agent")
                .description("Produces the final answer using the specialized agents' outputs.")
                .model(chatModel)
                .systemPrompt("""
                        You are answer_agent.
                        Read the current user task {input}, knowledge result {knowledge_result},
                        operations result {ops_result}, and skill result {skill_result}.
                        Produce the final answer in Chinese.
                        Rules:
                        - Prefer evidence from specialized agents over assumptions.
                        - If evidence is missing, state what is missing.
                        - Do not expose internal orchestration details unless the user asks how agents worked.
                        - Keep the answer direct and useful.
                        """)
                .outputKey("final_answer")
                .build();
    }

    private Object[] buildOpsMethodTools() {
        List<Object> tools = new ArrayList<>();
        tools.add(dateTimeTools);
        tools.add(queryMetricsTools);
        if (queryLogsTools != null) {
            tools.add(queryLogsTools);
        }
        return tools.toArray();
    }

    private String buildSupervisorPrompt() {
        return """
                You are chat_supervisor, the coordinator for a multi-agent assistant.
                Choose specialized agents based on the user task:
                - Use knowledge_agent for uploaded files, knowledge base, documents, OCR images, web captures, manuals, or internal docs.
                - Use ops_agent for alerts, logs, metrics, Prometheus, CLS, incidents, failures, or time-sensitive operations data.
                - Use skill_agent for reusable procedures, runbooks, historical solutions, or best-practice patterns.
                - Always call answer_agent at the end to produce the final user-facing response.

                Coordination rules:
                - Do not ask every agent for every question. Call only the agents that can add evidence.
                - If multiple areas are involved, call the relevant agents first, then answer_agent.
                - If an agent reports no evidence, pass that fact to answer_agent.
                - Final output must come from answer_agent.
                """;
    }

    private String buildTaskPrompt(String question, List<Map<String, String>> history) {
        StringBuilder prompt = new StringBuilder();
        if (history != null && !history.isEmpty()) {
            prompt.append("Conversation history:\n");
            int start = Math.max(0, history.size() - 8);
            for (int i = start; i < history.size(); i++) {
                Map<String, String> message = history.get(i);
                prompt.append(message.getOrDefault("role", "unknown"))
                        .append(": ")
                        .append(message.getOrDefault("content", ""))
                        .append("\n");
            }
            prompt.append("\n");
        }
        prompt.append("Current user question:\n").append(question);
        return prompt.toString();
    }

    private String extractFinalAnswer(OverAllState state) {
        Optional<AssistantMessage> finalAnswer = state.value("final_answer")
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast);

        if (finalAnswer.isPresent() && finalAnswer.get().getText() != null && !finalAnswer.get().getText().isBlank()) {
            return finalAnswer.get().getText();
        }

        Optional<AssistantMessage> knowledgeResult = state.value("knowledge_result")
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast);
        Optional<AssistantMessage> opsResult = state.value("ops_result")
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast);
        Optional<AssistantMessage> skillResult = state.value("skill_result")
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast);

        StringBuilder fallback = new StringBuilder();
        knowledgeResult.map(AssistantMessage::getText).filter(text -> !text.isBlank())
                .ifPresent(text -> fallback.append(text).append("\n\n"));
        opsResult.map(AssistantMessage::getText).filter(text -> !text.isBlank())
                .ifPresent(text -> fallback.append(text).append("\n\n"));
        skillResult.map(AssistantMessage::getText).filter(text -> !text.isBlank())
                .ifPresent(text -> fallback.append(text).append("\n\n"));

        if (!fallback.isEmpty()) {
            return fallback.toString().trim();
        }

        logger.warn("No final_answer or specialist result found in multi-agent state");
        return "多 Agent 协作已完成，但没有生成可用答案。";
    }
}
