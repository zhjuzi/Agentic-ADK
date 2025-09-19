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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TODO管理工具
 * 支持新建、编辑、查看、删除TODO项目
 */
@Slf4j
public class TodoTool extends BaseTool {

    private static final Map<Long, TodoItem> todoStorage = new ConcurrentHashMap<>();
    private static final AtomicLong idGenerator = new AtomicLong(1);

    public TodoTool() {
        setName("todo_manage");
        setDescription("管理TODO项目，支持新建、编辑、查看、删除操作");
        setParameters("{\n" +
            "    \"type\": \"object\",\n" +
            "    \"properties\": {\n" +
            "        \"action\": {\n" +
            "            \"type\": \"string\",\n" +
            "            \"enum\": [\"create\", \"edit\", \"view\", \"list\", \"delete\"],\n" +
            "            \"description\": \"操作类型：create=新建，edit=编辑，view=查看单个，list=查看全部，delete=删除\"\n" +
            "        },\n" +
            "        \"id\": {\n" +
            "            \"type\": \"integer\",\n" +
            "            \"description\": \"TODO项目ID，edit/view/delete操作时必填\"\n" +
            "        },\n" +
            "        \"title\": {\n" +
            "            \"type\": \"string\",\n" +
            "            \"description\": \"TODO标题，create/edit操作时使用\"\n" +
            "        },\n" +
            "        \"description\": {\n" +
            "            \"type\": \"string\",\n" +
            "            \"description\": \"TODO描述，create/edit操作时使用\"\n" +
            "        },\n" +
            "        \"priority\": {\n" +
            "            \"type\": \"string\",\n" +
            "            \"enum\": [\"high\", \"medium\", \"low\"],\n" +
            "            \"description\": \"优先级，create/edit操作时使用\"\n" +
            "        },\n" +
            "        \"status\": {\n" +
            "            \"type\": \"string\",\n" +
            "            \"enum\": [\"pending\", \"in_progress\", \"completed\"],\n" +
            "            \"description\": \"状态，create/edit操作时使用\"\n" +
            "        }\n" +
            "    },\n" +
            "    \"required\": [\"action\"]\n" +
            "}");
    }

    @Override
    public ToolExecuteResult run(String toolInput, ExecutionContext executionContext) {
        System.out.println("开始执行TODO工具，输入参数: " + toolInput);
        onToolStart(this, toolInput, executionContext);
        
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = JSON.parseObject(toolInput, Map.class);
            String action = (String) params.get("action");
            
            System.out.println("解析参数完成 - 操作类型: [" + action + "]");
            
            Map<String, Object> result = new HashMap<>();
            
            switch (action) {
                case "create":
                    result = createTodo(params);
                    break;
                case "edit":
                    result = editTodo(params);
                    break;
                case "view":
                    result = viewTodo(params);
                    break;
                case "list":
                    result = listTodos();
                    break;
                case "delete":
                    result = deleteTodo(params);
                    break;
                default:
                    result.put("success", false);
                    result.put("message", "不支持的操作类型: " + action);
            }
            
            String output = JSON.toJSONString(result);
            System.out.println("TODO操作执行结果：" + output);
            
            ToolExecuteResult toolResult = new ToolExecuteResult(output);
            onToolEnd(this, toolInput, toolResult, executionContext);
            return toolResult;
            
        } catch (Exception e) {
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("success", false);
            errorResult.put("message", "执行失败: " + e.getMessage());
            
            ToolExecuteResult toolResult = new ToolExecuteResult(JSON.toJSONString(errorResult));
            System.err.println("TODO工具执行失败：" + e.getMessage());
            onToolError(this, e, executionContext);
            return toolResult;
        }
    }

    private Map<String, Object> createTodo(Map<String, Object> params) {
        String title = (String) params.get("title");
        String description = (String) params.get("description");
        String priority = (String) params.getOrDefault("priority", "medium");
        String status = (String) params.getOrDefault("status", "pending");
        
        if (title == null || title.trim().isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "标题不能为空");
            return result;
        }
        
        long id = idGenerator.getAndIncrement();
        TodoItem todo = new TodoItem(id, title.trim(), description, priority, status);
        todoStorage.put(id, todo);
        
        System.out.println("创建TODO成功，ID: " + id + ", 标题: " + title);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "TODO创建成功");
        result.put("todo", todo.toMap());
        return result;
    }

    private Map<String, Object> editTodo(Map<String, Object> params) {
        Object idObj = params.get("id");
        if (idObj == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "编辑操作需要提供TODO ID");
            return result;
        }
        
        long id = ((Number) idObj).longValue();
        TodoItem todo = todoStorage.get(id);
        if (todo == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "TODO不存在，ID: " + id);
            return result;
        }
        
        String title = (String) params.get("title");
        String description = (String) params.get("description");
        String priority = (String) params.get("priority");
        String status = (String) params.get("status");
        
        if (title != null && !title.trim().isEmpty()) {
            todo.setTitle(title.trim());
        }
        if (description != null) {
            todo.setDescription(description);
        }
        if (priority != null) {
            todo.setPriority(priority);
        }
        if (status != null) {
            todo.setStatus(status);
        }
        
        todo.setUpdatedAt(LocalDateTime.now());
        
        System.out.println("编辑TODO成功，ID: " + id);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "TODO编辑成功");
        result.put("todo", todo.toMap());
        return result;
    }

    private Map<String, Object> viewTodo(Map<String, Object> params) {
        Object idObj = params.get("id");
        if (idObj == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "查看操作需要提供TODO ID");
            return result;
        }
        
        long id = ((Number) idObj).longValue();
        TodoItem todo = todoStorage.get(id);
        if (todo == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "TODO不存在，ID: " + id);
            return result;
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("todo", todo.toMap());
        return result;
    }

    private Map<String, Object> listTodos() {
        List<Map<String, Object>> todos = new ArrayList<>();
        for (TodoItem todo : todoStorage.values()) {
            todos.add(todo.toMap());
        }
        
        // 按创建时间排序
        todos.sort((a, b) -> {
            String timeA = (String) a.get("createdAt");
            String timeB = (String) b.get("createdAt");
            return timeB.compareTo(timeA); // 降序
        });
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("count", todos.size());
        result.put("todos", todos);
        return result;
    }

    private Map<String, Object> deleteTodo(Map<String, Object> params) {
        Object idObj = params.get("id");
        if (idObj == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "删除操作需要提供TODO ID");
            return result;
        }
        
        long id = ((Number) idObj).longValue();
        TodoItem todo = todoStorage.remove(id);
        if (todo == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "TODO不存在，ID: " + id);
            return result;
        }
        
        System.out.println("删除TODO成功，ID: " + id + ", 标题: " + todo.getTitle());
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "TODO删除成功");
        result.put("deletedTodo", todo.toMap());
        return result;
    }

    /**
     * TODO项目数据类
     */
    public static class TodoItem {
        private long id;
        private String title;
        private String description;
        private String priority;
        private String status;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        
        public TodoItem(long id, String title, String description, String priority, String status) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.priority = priority;
            this.status = status;
            this.createdAt = LocalDateTime.now();
            this.updatedAt = LocalDateTime.now();
        }
        
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("id", id);
            map.put("title", title);
            map.put("description", description);
            map.put("priority", priority);
            map.put("status", status);
            map.put("createdAt", createdAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            map.put("updatedAt", updatedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            return map;
        }
        
        // Getters and Setters
        public long getId() { return id; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getPriority() { return priority; }
        public void setPriority(String priority) { this.priority = priority; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public LocalDateTime getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    }
}
