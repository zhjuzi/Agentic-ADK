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
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 终端命令执行工具
 * 继承 BaseTool，支持在主机上执行shell命令
 */
@Slf4j
public class TerminalTool extends BaseTool {

    public TerminalTool() {
        setName("terminal_execute");
        setDescription("在主机上执行终端命令，返回标准输出、错误输出与退出码");
        setParameters("{\n" +
            "    \"type\": \"object\",\n" +
            "    \"properties\": {\n" +
            "        \"command\": {\n" +
            "            \"type\": \"string\",\n" +
            "            \"description\": \"要执行的shell命令，例如: ls -la\"\n" +
            "        },\n" +
            "        \"workingDir\": {\n" +
            "            \"type\": \"string\",\n" +
            "            \"description\": \"可选，工作目录\"\n" +
            "        },\n" +
            "        \"timeoutSec\": {\n" +
            "            \"type\": \"integer\",\n" +
            "            \"description\": \"可选，超时时间秒，默认60\"\n" +
            "        }\n" +
            "    },\n" +
            "    \"required\": [\"command\"]\n" +
            "}");
    }

    @Override
    public ToolExecuteResult run(String toolInput, ExecutionContext executionContext) {
        System.out.println("开始执行终端工具，输入参数: " + toolInput);
        onToolStart(this, toolInput, executionContext);
        
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = JSON.parseObject(toolInput, Map.class);
            String command = (String) params.get("command");
            String workingDir = (String) params.get("workingDir");
            Integer timeoutSec = params.get("timeoutSec") != null ? 
                ((Number) params.get("timeoutSec")).intValue() : 60;
            
            System.out.println("解析参数完成 - 命令: [" + command + "], 工作目录: [" + workingDir + "], 超时: " + timeoutSec + "秒");

            Map<String, Object> result = executeCommand(command, workingDir, timeoutSec);
            String output = JSON.toJSONString(result);
            System.out.println("命令执行结果：" + output);
            ToolExecuteResult toolResult = new ToolExecuteResult(output);
            System.out.println("工具[" + getName() + "]执行结果：" + toolResult);
            onToolEnd(this, toolInput, toolResult, executionContext);
            return toolResult;
            
        } catch (Exception e) {
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("exitCode", -1);
            errorResult.put("stdout", "");
            errorResult.put("stderr", "执行失败: " + e.getMessage());
            errorResult.put("timedOut", false);
            errorResult.put("durationMs", 0L);
            
            ToolExecuteResult toolResult = new ToolExecuteResult(JSON.toJSONString(errorResult));
            System.err.println("工具[" + getName() + "]执行失败：" + e.getMessage());
            onToolError(this, e, executionContext);
            return toolResult;
        }
    }

    private Map<String, Object> executeCommand(String command, String workingDir, Integer timeoutSec) {
        Map<String, Object> result = new HashMap<>();
        
        if (command == null || command.trim().isEmpty()) {
            System.out.println("警告：命令为空，直接返回错误结果");
            result.put("exitCode", -1);
            result.put("stdout", "");
            result.put("stderr", "命令不能为空");
            result.put("timedOut", false);
            result.put("durationMs", 0L);
            return result;
        }

        int timeout = timeoutSec == null || timeoutSec <= 0 ? 60 : timeoutSec;
        Process process = null;
        Instant start = Instant.now();
        boolean timedOut = false;
        int exitCode = -1;
        String stdout = "";
        String stderr = "";
        
        try {
            // 使用zsh -lc以支持管道/重定向等shell特性（macOS环境）
            ProcessBuilder pb = new ProcessBuilder("/bin/zsh", "-lc", command);
            if (workingDir != null && !workingDir.trim().isEmpty()) {
                pb.directory(new File(workingDir));
                System.out.println("设置工作目录: " + workingDir);
            }
            pb.redirectErrorStream(false);
            System.out.println("开始执行命令: [" + command + "]");
            process = pb.start();

            // 等待进程结束或超时
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                timedOut = true;
                System.out.println("警告：命令执行超时(" + timeout + "秒)，强制终止进程");
                process.destroyForcibly();
            }
            if (!timedOut) {
                exitCode = process.exitValue();
                System.out.println("命令执行完成，退出码: " + exitCode);
            }

            // 读取输出
            stdout = readStream(process.getInputStream());
            stderr = readStream(process.getErrorStream());
            System.out.println("标准输出长度: " + stdout.length() + ", 错误输出长度: " + stderr.length());
            
        } catch (Exception e) {
            System.err.println("命令执行异常: " + e.getMessage());
            stderr = e.getMessage();
        } finally {
            if (process != null) {
                try { process.getInputStream().close(); } catch (Exception ignored) {}
                try { process.getErrorStream().close(); } catch (Exception ignored) {}
                try { process.getOutputStream().close(); } catch (Exception ignored) {}
            }
        }
        
        long duration = Duration.between(start, Instant.now()).toMillis();

        result.put("exitCode", exitCode);
        result.put("stdout", stdout);
        result.put("stderr", stderr);
        result.put("timedOut", timedOut);
        result.put("durationMs", duration);
        return result;
    }

    private static String readStream(InputStream is) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
