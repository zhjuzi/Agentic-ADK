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
package com.alibaba.agentic.computer.use.tool;

import com.alibaba.fastjson.JSON;
import com.alibaba.langengine.core.callback.ExecutionContext;
import com.alibaba.langengine.core.tool.BaseTool;
import com.alibaba.langengine.core.tool.ToolExecuteResult;
import com.alibaba.langengine.openmanus.agent.ManusAgent;

import java.util.Map;

/**
 * OpenManus Agent工具
 * 封装ManusAgent的浏览器自动化和多工具协调能力
 */
public class OpenManusAgentTool extends BaseTool {

    private ManusAgent manusAgent;

    public OpenManusAgentTool() {
        setName("manus_agent");
        setDescription("浏览器自动化工具、支持浏览器操作、Python执行、文件保存、网络搜索等多种能力");
        setParameters("{\n" +
            "    \"type\": \"object\",\n" +
            "    \"properties\": {\n" +
            "        \"task\": {\n" +
            "            \"type\": \"string\",\n" +
            "            \"description\": \"要执行的任务描述，OpenManus Agent会自动选择合适的工具来完成任务\"\n" +
            "        },\n" +
            "        \"max_steps\": {\n" +
            "            \"type\": \"integer\",\n" +
            "            \"description\": \"最大执行步数，默认为10\",\n" +
            "            \"default\": 10\n" +
            "        }\n" +
            "    },\n" +
            "    \"required\": [\"task\"]\n" +
            "}");
        
        System.out.println("OpenManus Agent工具初始化完成");
    }
    
    /**
     * 延迟初始化ManusAgent，避免在构造函数中因API密钥问题导致失败
     */
    private synchronized ManusAgent getOrCreateManusAgent() throws Exception {
        if (manusAgent == null) {
            try {
                manusAgent = new ManusAgent();
                manusAgent.setName("OpenManus浏览器助手");
                manusAgent.setDescription("专门处理浏览器操作、网页交互、文件处理和网络搜索的智能助手");
                System.out.println("ManusAgent延迟初始化成功");
            } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
                throw new Exception("ManusAgent初始化失败，请检查DashScope API密钥配置。错误: " + e.getMessage(), e);
            }
        }
        return manusAgent;
    }

    @Override
    public ToolExecuteResult run(String toolInput, ExecutionContext executionContext) {
        try {
            System.out.println("=== OpenManus Agent工具开始执行 ===");
            System.out.println("输入参数: " + toolInput);
            
            // 解析输入参数
            @SuppressWarnings("unchecked")
            Map<String, Object> params = JSON.parseObject(toolInput, Map.class);
            String task = (String) params.get("task");
            Integer maxSteps = (Integer) params.getOrDefault("max_steps", 10);
            
            System.out.println("解析后的任务: " + task);
            System.out.println("最大步数: " + maxSteps);
            
            if (task == null || task.trim().isEmpty()) {
                System.err.println("错误：任务描述为空");
                return new ToolExecuteResult("错误：任务描述不能为空", false);
            }
            
            // 获取或创建ManusAgent实例
            System.out.println("正在获取ManusAgent实例...");
            ManusAgent agent = getOrCreateManusAgent();
            System.out.println("ManusAgent实例获取成功");
            
            // 设置最大步数
            System.out.println("设置最大步数为: " + maxSteps);
            agent.setMaxSteps(maxSteps);
            
            // 执行任务
            System.out.println("=== 开始执行OpenManus任务 ===");
            System.out.println("任务内容: " + task);
            
            String result = agent.run(task);
            
            System.out.println("=== OpenManus Agent任务执行完成 ===");
            System.out.println("执行结果: " + result);
            return new ToolExecuteResult(result, true);
            
        } catch (Exception e) {
            System.err.println("=== OpenManus Agent执行异常 ===");
            System.err.println("异常类型: " + e.getClass().getSimpleName());
            System.err.println("异常消息: " + e.getMessage());
            e.printStackTrace();
            
            String errorMsg;
            if (e.getMessage() != null && e.getMessage().contains("DashScope API密钥")) {
                errorMsg = "OpenManus Agent需要配置DashScope API密钥才能正常工作。请设置环境变量DASHSCOPE_API_KEY或在配置文件中配置相关密钥。";
            } else {
                errorMsg = "OpenManus Agent执行失败: " + e.getMessage() + " (异常类型: " + e.getClass().getSimpleName() + ")";
            }
            System.err.println("最终错误消息: " + errorMsg);
            return new ToolExecuteResult(errorMsg, false);
        }
    }

    /**
     * 获取ManusAgent实例，用于高级配置
     */
    public ManusAgent getManusAgent() {
        try {
            return getOrCreateManusAgent();
        } catch (Exception e) {
            System.err.println("获取ManusAgent失败: " + e.getMessage());
            return null;
        }
    }


}
