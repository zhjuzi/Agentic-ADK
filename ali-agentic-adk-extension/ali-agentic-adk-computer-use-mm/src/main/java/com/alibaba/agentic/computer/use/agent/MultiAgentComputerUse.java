/**
 * Copyright (C) 2024 AIDC-AI
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.agentic.computer.use.agent;

import com.alibaba.agentic.computer.use.tool.TerminalTool;
import com.alibaba.agentic.computer.use.tool.TodoTool;
import com.alibaba.agentic.computer.use.tool.OpenManusAgentTool;
import com.alibaba.agentic.computer.use.tool.ScreenAgentTool;
import com.alibaba.langengine.core.chatmodel.BaseChatModel;
import com.alibaba.langengine.core.memory.impl.ConversationBufferMemory;
import com.alibaba.langengine.core.messages.AIMessage;
import com.alibaba.langengine.core.messages.BaseMessage;
import com.alibaba.langengine.core.messages.ToolMessage;
import com.alibaba.langengine.core.messages.SystemMessage;
import com.alibaba.langengine.core.model.fastchat.completion.chat.FunctionDefinition;
import com.alibaba.langengine.core.tool.ToolExecuteResult;
import com.alibaba.langengine.dashscope.model.DashScopeChatModel;
import com.alibaba.langengine.dashscope.DashScopeModelName;
import lombok.extern.slf4j.Slf4j;
import java.util.*;

/**
 * 多智能体Computer Use系统
 * 规划Agent协调屏幕控制Agent和OpenManus浏览器Agent
 *
 * @author xiaoxuan.lp
 */
@Slf4j
public class MultiAgentComputerUse {

    private final TerminalTool terminalTool;
    private final TodoTool todoTool;
    private final OpenManusAgentTool openManusAgentTool;
    private final ScreenAgentTool screenshotAgentTool;


    private static String buildSystemPrompt() {
        String currentDate = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy年MM月dd日"));
        String osName = System.getProperty("os.name");
        String osVersion = System.getProperty("os.version");
        String osArch = System.getProperty("os.arch");
        
        return "你是一个Computer Use规划智能体，负责协调多个专门的工具来完成计算机自动化任务。\n\n" +
            "当前环境信息:\n" +
            "- 日期: " + currentDate + "\n" +
            "- 操作系统: " + osName + " " + osVersion + " (" + osArch + ")\n\n" +
            "你的职责:\n" +
            "- 分析用户请求，选择合适的工具来完成任务\n" +
            "- 如果需要执行系统命令，使用terminal_execute工具\n" +
            "- 如果需要管理TODO项目，使用todo_manage工具\n" +
            "- 如果需要浏览器操作、网页交互、文件处理或网络搜索，使用manus_agent工具\n" +
            "- 如果需要屏幕操作、桌面应用控制、系统界面交互，使用screen_agent工具\n" +
            "\n工具选择原则：\n" +
            "- 浏览器相关任务 → manus_agent\n" +
            "- 桌面/系统界面操作 → screen_agent\n" +
            "- 命令行操作 → terminal_execute\n" +
            "- 任务管理 → todo_manage\n" +
            "\n请根据用户请求选择合适的工具执行任务。";
    }

    private static final String PLANNING_SYSTEM_PROMPT = buildSystemPrompt();

    private final BaseChatModel planningLlm;
    private final ConversationBufferMemory memory;

    public MultiAgentComputerUse() {
        // 初始化规划LLM
        String dashscopeToken = "sk-79bafa20b12240b090eba4c9cd2b5dbb";
        if (dashscopeToken == null || dashscopeToken.trim().isEmpty()) {
            throw new IllegalStateException("缺少 DASHSCOPE_API_KEY，请在环境变量或 JVM 启动参数(-DDASHSCOPE_API_KEY=...) 中配置");
        }
        
        log.info("DashScope API配置信息:");
        log.info("- Token: {}...", dashscopeToken.substring(0, Math.min(8, dashscopeToken.length())));
        
        // 使用DashScopeChatModel新增的构造方法配置DashScope API
        try {
            DashScopeChatModel chatModel = new DashScopeChatModel(dashscopeToken);
            chatModel.setModel(DashScopeModelName.QWEN_MAX);
            chatModel.setTemperature(0.7);
            chatModel.setMaxTokens(2048); // 增加最大token数量
            this.planningLlm = chatModel;
            log.info("DashScope LLM初始化成功");
        } catch (Exception e) {
            log.error("DashScope LLM初始化失败", e);
            throw new RuntimeException("DashScope LLM初始化失败: " + e.getMessage(), e);
        }
        this.memory = new ConversationBufferMemory();
        
        // 初始化工具
        this.terminalTool = new TerminalTool();
        this.todoTool = new TodoTool();
        this.openManusAgentTool = new OpenManusAgentTool();
        this.screenshotAgentTool = new ScreenAgentTool();
        
        System.out.println("多智能体Computer Use系统初始化完成");
    }

    public String execute(String userRequest) {
        memory.getChatMemory().addUserMessage(userRequest);
        return executeStep(userRequest);
    }

    public String executeStep(String userRequest) {
        final int maxTurns = 10;

        for (int turn = 0; turn < maxTurns; turn++) {
            log.info("开始第{}轮对话", turn + 1);
            // 构建对话上下文
            List<BaseMessage> messages = buildConversationContext();
            log.info("构建对话上下文完成，消息数量: {}", messages.size());
            // 准备工具定义
            List<FunctionDefinition> functions = getFunctionDefinitions();
            log.info("准备工具定义完成，工具数量: {}", functions.size());
            // 获取LLM响应
            log.info("开始调用DashScope API...");
            log.info("发送消息数量: {}", messages.size());
            for (int i = 0; i < messages.size(); i++) {
                BaseMessage msg = messages.get(i);
                log.info("消息[{}]: 类型={}, 内容长度={}", i, msg.getClass().getSimpleName(), 
                    msg.getContent() != null ? msg.getContent().length() : 0);
            }
            log.info("工具定义数量: {}", functions.size());
            
            // 先尝试不带工具的调用来测试基础功能
            BaseMessage response;
            if (functions.isEmpty()) {
                response = planningLlm.run(messages);
            } else {
                // 启用工具调用
                log.info("启用工具调用，工具数量: {}", functions.size());
                response = planningLlm.run(messages, functions, null, null, null);
            }
            
            log.info("DashScope API调用成功，响应类型: {}", response.getClass().getSimpleName());
            log.info("响应内容: {}", response.getContent());
            log.info("响应额外属性: {}", response.getAdditionalKwargs());
            // 将AI响应（包含可能的tool_calls）写入记忆，便于下一轮继续
            memory.getChatMemory().getMessages().add(response);
            log.info("已将响应添加到记忆，当前记忆中消息数量: {}", memory.getChatMemory().getMessages().size());
            // 如果触发了工具调用，执行工具并记录结果，然后继续下一轮
            String toolExecResult = tryExecuteToolCalls(response);
            log.info("工具执行结果: {}", toolExecResult != null ? toolExecResult : "无工具调用");
            if (toolExecResult != null && !toolExecResult.isEmpty()) {
                log.info("继续下一轮对话...");
                continue;
            }
            String finalContent = response.getContent();
            log.info("返回最终内容: {}", finalContent);
            return finalContent;
        }
        return "达到最大工具调用轮次限制，已停止。";
    }

    private List<BaseMessage> buildConversationContext() {
        List<BaseMessage> messages = new ArrayList<>();
        // 添加系统提示
        messages.add(new SystemMessage(PLANNING_SYSTEM_PROMPT));
        // 添加对话历史
        messages.addAll(memory.getChatMemory().getMessages());
        return messages;
    }

    /**
     * 收集需要注册给LLM的工具定义
     */
    private List<FunctionDefinition> getFunctionDefinitions() {
        List<FunctionDefinition> list = new ArrayList<>();
        // 添加工具定义
        //list.add(terminalTool.toParams());
        list.add(todoTool.toParams());
        list.add(openManusAgentTool.toParams());
        list.add(screenshotAgentTool.toParams());
        return list;
    }

    /**
     * 解析AI响应中的工具调用并执行，返回执行结果字符串；若无工具调用返回null
     */
    private String tryExecuteToolCalls(BaseMessage response) {
        log.info("开始检查工具调用...");
        if (!(response instanceof AIMessage)) {
            log.info("响应不是AIMessage类型，无工具调用");
            return null;
        }
        AIMessage ai = (AIMessage) response;
        Map<String, Object> kwargs = ai.getAdditionalKwargs();
        log.info("额外属性: {}", kwargs);
        if (kwargs == null || kwargs.isEmpty()) {
            log.info("无额外属性，无工具调用");
            return null;
        }
        StringBuilder execSummary = new StringBuilder();
        // 1) OpenAI function_call 结构: {"function_call": {"name": "...", "arguments": "..."}}
        if (kwargs.containsKey("function_call")) {
            Object fcObj = kwargs.get("function_call");
            if (fcObj instanceof Map) {
                Map<?, ?> fc = (Map<?, ?>) fcObj;
                String name = String.valueOf(fc.get("name"));
                String arguments = String.valueOf(fc.get("arguments"));
                log.info("检测到function_call: name={}, arguments={}", name, arguments);
                System.out.println("调用工具: " + name + "，参数: " + arguments);

                String id = "func_" + UUID.randomUUID();
                String result = executeToolByName(name, arguments);
                execSummary.append(formatToolResult(name, result)).append("\n");
                // 记录到记忆（ToolMessage）
                ToolMessage tm = new ToolMessage();
                tm.setTool_call_id(id);
                tm.setName(name);
                tm.setContent(result);
                memory.getChatMemory().getMessages().add(tm);
            }
        }

        // 2) tool_calls 结构: {"tool_calls": [{"id": "...", "type": "function", "function": {"name": "...", "arguments": "..."}}]}
        if (kwargs.containsKey("tool_calls")) {
            Object tcObj = kwargs.get("tool_calls");
            if (tcObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) tcObj;
                for (Map<String, Object> call : toolCalls) {
                    Object funcObj = call.get("function");
                    String id = call.get("id") != null ? String.valueOf(call.get("id")) : ("tool_" + UUID.randomUUID());
                    if (funcObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> funcMap = (Map<String, Object>) funcObj;
                        String name = String.valueOf(funcMap.get("name"));
                        String arguments = String.valueOf(funcMap.get("arguments"));
                        log.info("检测到tool_calls: id={}, name={}, arguments={}", id, name, arguments);
                        System.out.println("调用工具: " + name + "，参数: " + arguments);
                        String result = executeToolByName(name, arguments);
                        execSummary.append(formatToolResult(name, result)).append("\n");
                        // 记录到记忆（ToolMessage）
                        ToolMessage tm = new ToolMessage();
                        tm.setTool_call_id(id);
                        tm.setName(name);
                        tm.setContent(result);
                        memory.getChatMemory().getMessages().add(tm);
                    }
                }
            }
        }

        String summary = execSummary.toString().trim();
        return summary.isEmpty() ? null : summary;
    }

    /**
     * 根据工具名执行对应工具
     */
    private String executeToolByName(String name, String argumentsJson) {
        log.info("执行工具: name={}, arguments={}", name, argumentsJson);
        try {
            if ("terminal_execute".equals(name)) {
                ToolExecuteResult result = terminalTool.run(argumentsJson, null);
                return result.getOutput();
            } else if ("todo_manage".equals(name)) {
                ToolExecuteResult result = todoTool.run(argumentsJson, null);
                return result.getOutput();
            } else if ("manus_agent".equals(name)) {
                ToolExecuteResult result = openManusAgentTool.run(argumentsJson, null);
                return result.getOutput();
            } else if ("screen_agent".equals(name)) {
                ToolExecuteResult result = screenshotAgentTool.run(argumentsJson, null);
                return result.getOutput();
            }
            return "未知工具: " + name;
        } catch (Exception e) {
            log.error("工具执行失败: name={}, error={}", name, e.getMessage(), e);
            return "工具执行失败: " + e.getMessage();
        }
    }

    private String formatToolResult(String name, String result) {
        return "工具[" + name + "]执行结果:\n" + result;
    }


    /**
     * 关闭系统，清理资源
     */
    public void shutdown() {
        System.out.println("关闭多智能体Computer Use系统");
        if (screenshotAgentTool != null) {
            screenshotAgentTool.shutdown();
        }
    }
}
