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

import com.alibaba.agentic.computer.use.agent.ScreenAgent;
import com.alibaba.fastjson.JSON;
import com.alibaba.langengine.core.callback.ExecutionContext;
import com.alibaba.langengine.core.tool.BaseTool;
import com.alibaba.langengine.core.tool.ToolExecuteResult;

import java.util.Map;

/**
 * 屏幕智能体工具
 * 简单封装ScreenAgent为function call，所有截图和分析逻辑都在ScreenAgent内部
 */
public class ScreenAgentTool extends BaseTool {

    private ScreenAgent screenAgent;

    public ScreenAgentTool() {
        setName("screen_agent");
        setDescription("屏幕智能体工具，能够理解屏幕内容并执行屏幕操作（非浏览器操作）");
        setParameters("{\n" +
            "    \"type\": \"object\",\n" +
            "    \"properties\": {\n" +
            "        \"request\": {\n" +
            "            \"type\": \"string\",\n" +
            "            \"description\": \"屏幕操作请求，例如：'分析当前屏幕内容'、'点击桌面上的某个图标'、'打开系统设置'等\"\n" +
            "        }\n"+
            "    },\n" +
            "    \"required\": [\"request\"]\n" +
            "}");
    }

    /**
     * 延迟初始化ScreenAgent
     */
    private void initializeScreenshotAgent() {
        if (screenAgent == null) {
            try {
                screenAgent = new ScreenAgent();
            } catch (Exception e) {
                throw new RuntimeException("初始化ScreenAgent失败: " + e.getMessage(), e);
            }
        }
    }


    @Override
    public ToolExecuteResult run(String toolInput, ExecutionContext executionContext) {
        try {
            // 解析输入参数
            Map<String, Object> params = JSON.parseObject(toolInput, Map.class);
            String request = (String) params.get("request");
            
            if (request == null || request.trim().isEmpty()) {
                return new ToolExecuteResult("错误：请求参数不能为空", false);
            }

            // 延迟初始化ScreenAgent
            initializeScreenshotAgent();

            // 直接调用ScreenAgent执行，所有截图和网格逻辑都在ScreenAgent内部处理
            String result = screenAgent.execute(request);
            System.out.println("ScreenAgent执行请求: " + request);
            System.out.println("ScreenAgent返回结果长度: " + (result != null ? result.length() : "null"));
            System.out.println("ScreenAgent返回结果内容: " + result);
    
            if (result == null || result.trim().isEmpty()) {
                return new ToolExecuteResult("屏幕分析未返回结果", false);
            }
            return new ToolExecuteResult(result, true);

        } catch (Exception e) {
            System.err.println("ScreenAgentTool执行失败: " + e.getMessage());
            e.printStackTrace();
            return new ToolExecuteResult("屏幕分析失败: " + e.getMessage(), false);
        }
    }


    /**
     * 关闭工具，清理资源
     */
    public void shutdown() {
        if (screenAgent != null) {
            screenAgent.shutdown();
            screenAgent = null;
        }
    }
}
