/*
 * Copyright (c) 2024 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.agentic.computer.use.tool;

import com.alibaba.fastjson.JSON;
import com.alibaba.langengine.core.tool.BaseTool;
import com.alibaba.langengine.core.tool.ToolExecuteResult;
import com.alibaba.langengine.core.callback.ExecutionContext;
import lombok.extern.slf4j.Slf4j;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.Map;

/**
 * 键盘输入工具，支持文本输入、特殊按键和组合键操作
 */
@Slf4j
public class KeyboardTool extends BaseTool {

    private Robot robot;

    public KeyboardTool() {
        try {
            this.robot = new Robot();
            this.robot.setAutoDelay(50); // 设置按键间隔
        } catch (AWTException e) {
            System.err.println("初始化Robot失败: " + e.getMessage());
            throw new RuntimeException("无法初始化键盘控制", e);
        }
        
        // 设置工具基本信息
        setName("keyboard_input");
        setDescription("键盘输入工具，支持文本输入、特殊按键、组合键操作和滚动功能");
        setParameters("{\n" +
            "    \"type\": \"object\",\n" +
            "    \"properties\": {\n" +
            "        \"action\": {\n" +
            "            \"type\": \"string\",\n" +
            "            \"enum\": [\"type_text\", \"key_press\", \"key_combination\", \"scroll\"],\n" +
            "            \"description\": \"操作类型：type_text(输入文本)、key_press(按单键)、key_combination(组合键)、scroll(滚动)\"\n" +
            "        },\n" +
            "        \"text\": {\n" +
            "            \"type\": \"string\",\n" +
            "            \"description\": \"要输入的文本内容(action=type_text时使用)\"\n" +
            "        },\n" +
            "        \"key\": {\n" +
            "            \"type\": \"string\",\n" +
            "            \"description\": \"按键名称(action=key_press时使用)，如Enter、Tab、Backspace等\"\n" +
            "        },\n" +
            "        \"keys\": {\n" +
            "            \"type\": \"array\",\n" +
            "            \"items\": {\"type\": \"string\"},\n" +
            "            \"description\": \"组合键数组(action=key_combination时使用)，如[\\\"cmd\\\",\\\"c\\\"]表示复制\"\n" +
            "        },\n" +
            "        \"direction\": {\n" +
            "            \"type\": \"string\",\n" +
            "            \"enum\": [\"up\", \"down\", \"left\", \"right\"],\n" +
            "            \"description\": \"滚动方向(action=scroll时使用)：up(向上)、down(向下)、left(向左)、right(向右)\",\n" +
            "            \"default\": \"down\"\n" +
            "        },\n" +
            "        \"amount\": {\n" +
            "            \"type\": \"integer\",\n" +
            "            \"description\": \"滚动量(action=scroll时使用)，正数表示滚动步数，默认为3\",\n" +
            "            \"default\": 3\n" +
            "        },\n" +
            "        \"description\": {\n" +
            "            \"type\": \"string\",\n" +
            "            \"description\": \"操作描述\"\n" +
            "        }\n" +
            "    },\n" +
            "    \"required\": [\"action\"]\n" +
            "}");
    }

    @Override
    public String getName() {
        return "keyboard_input";
    }

    @Override
    public String getDescription() {
        return "键盘输入工具，支持文本输入、特殊按键和组合键操作。" +
                "参数：" +
                "- action: 操作类型 (type_text|key_press|key_combination)" +
                "- text: 要输入的文本内容 (action=type_text时使用)" +
                "- key: 按键名称 (action=key_press时使用，如Enter、Tab、Backspace等)" +
                "- keys: 组合键数组 (action=key_combination时使用，如[\"cmd\",\"c\"]表示复制)" +
                "- description: 操作描述";
    }

    @Override
    public ToolExecuteResult run(String toolInput, ExecutionContext executionContext) {
        try {
            onToolStart(this, toolInput, executionContext);
            
            @SuppressWarnings("unchecked")
            Map<String, Object> params = JSON.parseObject(toolInput, Map.class);
            String action = (String) params.get("action");
            String description = (String) params.getOrDefault("description", "键盘操作");
            
            System.out.println("执行键盘操作: " + action + ", 描述: " + description);
            
            String result = executeKeyboardAction(action, params);
            
            ToolExecuteResult executeResult = new ToolExecuteResult(result);
            onToolEnd(this, toolInput, executeResult, executionContext);
            return executeResult;
            
        } catch (Exception e) {
            String errorMsg = "键盘操作执行失败: " + e.getMessage();
            System.err.println(errorMsg + ": " + e.getMessage());
            onToolError(this, e, executionContext);
            return new ToolExecuteResult(errorMsg);
        }
    }

    private String executeKeyboardAction(String action, Map<String, Object> params) {
        switch (action) {
            case "type_text":
                return typeText((String) params.get("text"));
            case "key_press":
                return pressKey((String) params.get("key"));
            case "key_combination":
                return pressCombination(params.get("keys"));
            case "scroll":
                String direction = (String) params.getOrDefault("direction", "down");
                Integer amount = params.get("amount") != null ? 
                    ((Number) params.get("amount")).intValue() : 3;
                return performScroll(direction, amount);
            default:
                return "不支持的键盘操作: " + action;
        }
    }

    /**
     * 执行滚动操作
     */
    private String performScroll(String direction, int amount) {
        if (amount <= 0) {
            return "滚动量必须大于0";
        }
        
        try {
            // 根据方向执行滚动
            switch (direction.toLowerCase()) {
                case "up":
                    for (int i = 0; i < amount; i++) {
                        robot.mouseWheel(-1); // 向上滚动
                        Thread.sleep(50);
                    }
                    break;
                case "down":
                default:
                    for (int i = 0; i < amount; i++) {
                        robot.mouseWheel(1); // 向下滚动
                        Thread.sleep(50);
                    }
                    break;
                case "left":
                    // 水平滚动通过Shift+滚轮实现
                    robot.keyPress(KeyEvent.VK_SHIFT);
                    for (int i = 0; i < amount; i++) {
                        robot.mouseWheel(-1);
                        Thread.sleep(50);
                    }
                    robot.keyRelease(KeyEvent.VK_SHIFT);
                    break;
                case "right":
                    // 水平滚动通过Shift+滚轮实现
                    robot.keyPress(KeyEvent.VK_SHIFT);
                    for (int i = 0; i < amount; i++) {
                        robot.mouseWheel(1);
                        Thread.sleep(50);
                    }
                    robot.keyRelease(KeyEvent.VK_SHIFT);
                    break;
            }
            
            System.out.println("成功执行滚动: 方向=" + direction + ", 步数=" + amount);
            return String.format("成功执行滚动: 方向=%s, 步数=%d", direction, amount);
            
        } catch (Exception e) {
            System.err.println("滚动操作失败: " + direction + ", 错误: " + e.getMessage());
            return "滚动操作失败: " + e.getMessage();
        }
    }

    /**
     * 输入文本
     */
    private String typeText(String text) {
        if (text == null || text.isEmpty()) {
            return "文本内容为空";
        }
        
        try {
            // 逐字符输入文本
            for (char c : text.toCharArray()) {
                typeCharacter(c);
                Thread.sleep(10); // 短暂延迟确保输入稳定
            }
            
            System.out.println("成功输入文本: " + text);
            return String.format("成功输入文本: \"%s\"", text);
            
        } catch (Exception e) {
            System.err.println("输入文本失败: " + text + ", 错误: " + e.getMessage());
            return "输入文本失败: " + e.getMessage();
        }
    }

    /**
     * 按下单个按键
     */
    private String pressKey(String keyName) {
        if (keyName == null || keyName.isEmpty()) {
            return "按键名称为空";
        }
        
        try {
            int keyCode = getKeyCode(keyName);
            if (keyCode == -1) {
                return "不支持的按键: " + keyName;
            }
            
            robot.keyPress(keyCode);
            robot.keyRelease(keyCode);
            
            System.out.println("成功按下按键: " + keyName);
            return String.format("成功按下按键: %s", keyName);
            
        } catch (Exception e) {
            System.err.println("按键操作失败: " + keyName + ", 错误: " + e.getMessage());
            return "按键操作失败: " + e.getMessage();
        }
    }

    /**
     * 按下组合键
     */
    private String pressCombination(Object keysObj) {
        if (keysObj == null) {
            return "组合键参数为空";
        }
        
        try {
            java.util.List<String> keys;
            if (keysObj instanceof java.util.List) {
                @SuppressWarnings("unchecked")
                java.util.List<String> tempKeys = (java.util.List<String>) keysObj;
                keys = tempKeys;
            } else {
                return "组合键参数格式错误，应为数组";
            }
            
            if (keys.isEmpty()) {
                return "组合键列表为空";
            }
            
            // 按下所有按键
            int[] keyCodes = new int[keys.size()];
            for (int i = 0; i < keys.size(); i++) {
                keyCodes[i] = getKeyCode(keys.get(i));
                if (keyCodes[i] == -1) {
                    return "不支持的按键: " + keys.get(i);
                }
                robot.keyPress(keyCodes[i]);
            }
            
            // 释放所有按键（逆序）
            for (int i = keyCodes.length - 1; i >= 0; i--) {
                robot.keyRelease(keyCodes[i]);
            }
            
            String combination = String.join("+", keys);
            System.out.println("成功执行组合键: " + combination);
            return String.format("成功执行组合键: %s", combination);
            
        } catch (Exception e) {
            System.err.println("组合键操作失败: " + e.getMessage());
            return "组合键操作失败: " + e.getMessage();
        }
    }

    /**
     * 输入单个字符
     */
    private void typeCharacter(char c) {
        if (Character.isUpperCase(c)) {
            // 大写字母需要按住Shift
            robot.keyPress(KeyEvent.VK_SHIFT);
            robot.keyPress(Character.toUpperCase(c));
            robot.keyRelease(Character.toUpperCase(c));
            robot.keyRelease(KeyEvent.VK_SHIFT);
        } else if (Character.isLetter(c)) {
            // 小写字母
            robot.keyPress(Character.toUpperCase(c));
            robot.keyRelease(Character.toUpperCase(c));
        } else if (Character.isDigit(c)) {
            // 数字
            robot.keyPress(c);
            robot.keyRelease(c);
        } else {
            // 特殊字符
            typeSpecialCharacter(c);
        }
    }

    /**
     * 输入特殊字符
     */
    private void typeSpecialCharacter(char c) {
        switch (c) {
            case ' ':
                robot.keyPress(KeyEvent.VK_SPACE);
                robot.keyRelease(KeyEvent.VK_SPACE);
                break;
            case '\n':
                robot.keyPress(KeyEvent.VK_ENTER);
                robot.keyRelease(KeyEvent.VK_ENTER);
                break;
            case '\t':
                robot.keyPress(KeyEvent.VK_TAB);
                robot.keyRelease(KeyEvent.VK_TAB);
                break;
            case '.':
                robot.keyPress(KeyEvent.VK_PERIOD);
                robot.keyRelease(KeyEvent.VK_PERIOD);
                break;
            case ',':
                robot.keyPress(KeyEvent.VK_COMMA);
                robot.keyRelease(KeyEvent.VK_COMMA);
                break;
            case '!':
                robot.keyPress(KeyEvent.VK_SHIFT);
                robot.keyPress(KeyEvent.VK_1);
                robot.keyRelease(KeyEvent.VK_1);
                robot.keyRelease(KeyEvent.VK_SHIFT);
                break;
            case '?':
                robot.keyPress(KeyEvent.VK_SHIFT);
                robot.keyPress(KeyEvent.VK_SLASH);
                robot.keyRelease(KeyEvent.VK_SLASH);
                robot.keyRelease(KeyEvent.VK_SHIFT);
                break;
            case ':':
                robot.keyPress(KeyEvent.VK_SHIFT);
                robot.keyPress(KeyEvent.VK_SEMICOLON);
                robot.keyRelease(KeyEvent.VK_SEMICOLON);
                robot.keyRelease(KeyEvent.VK_SHIFT);
                break;
            case ';':
                robot.keyPress(KeyEvent.VK_SEMICOLON);
                robot.keyRelease(KeyEvent.VK_SEMICOLON);
                break;
            case '(':
                robot.keyPress(KeyEvent.VK_SHIFT);
                robot.keyPress(KeyEvent.VK_9);
                robot.keyRelease(KeyEvent.VK_9);
                robot.keyRelease(KeyEvent.VK_SHIFT);
                break;
            case ')':
                robot.keyPress(KeyEvent.VK_SHIFT);
                robot.keyPress(KeyEvent.VK_0);
                robot.keyRelease(KeyEvent.VK_0);
                robot.keyRelease(KeyEvent.VK_SHIFT);
                break;
            default:
                // 对于其他字符，尝试直接输入
                try {
                    robot.keyPress(c);
                    robot.keyRelease(c);
                } catch (Exception e) {
                    System.out.println("无法输入字符: " + c);
                }
                break;
        }
    }

    /**
     * 获取按键码
     */
    private int getKeyCode(String keyName) {
        switch (keyName.toLowerCase()) {
            case "enter": return KeyEvent.VK_ENTER;
            case "tab": return KeyEvent.VK_TAB;
            case "space": return KeyEvent.VK_SPACE;
            case "backspace": return KeyEvent.VK_BACK_SPACE;
            case "delete": return KeyEvent.VK_DELETE;
            case "escape": case "esc": return KeyEvent.VK_ESCAPE;
            case "shift": return KeyEvent.VK_SHIFT;
            case "ctrl": case "control": return KeyEvent.VK_CONTROL;
            case "alt": return KeyEvent.VK_ALT;
            case "cmd": case "command": return KeyEvent.VK_META;
            case "up": return KeyEvent.VK_UP;
            case "down": return KeyEvent.VK_DOWN;
            case "left": return KeyEvent.VK_LEFT;
            case "right": return KeyEvent.VK_RIGHT;
            case "home": return KeyEvent.VK_HOME;
            case "end": return KeyEvent.VK_END;
            case "pageup": return KeyEvent.VK_PAGE_UP;
            case "pagedown": return KeyEvent.VK_PAGE_DOWN;
            case "f1": return KeyEvent.VK_F1;
            case "f2": return KeyEvent.VK_F2;
            case "f3": return KeyEvent.VK_F3;
            case "f4": return KeyEvent.VK_F4;
            case "f5": return KeyEvent.VK_F5;
            case "f6": return KeyEvent.VK_F6;
            case "f7": return KeyEvent.VK_F7;
            case "f8": return KeyEvent.VK_F8;
            case "f9": return KeyEvent.VK_F9;
            case "f10": return KeyEvent.VK_F10;
            case "f11": return KeyEvent.VK_F11;
            case "f12": return KeyEvent.VK_F12;
            // 字母和数字
            case "a": return KeyEvent.VK_A;
            case "b": return KeyEvent.VK_B;
            case "c": return KeyEvent.VK_C;
            case "d": return KeyEvent.VK_D;
            case "e": return KeyEvent.VK_E;
            case "f": return KeyEvent.VK_F;
            case "g": return KeyEvent.VK_G;
            case "h": return KeyEvent.VK_H;
            case "i": return KeyEvent.VK_I;
            case "j": return KeyEvent.VK_J;
            case "k": return KeyEvent.VK_K;
            case "l": return KeyEvent.VK_L;
            case "m": return KeyEvent.VK_M;
            case "n": return KeyEvent.VK_N;
            case "o": return KeyEvent.VK_O;
            case "p": return KeyEvent.VK_P;
            case "q": return KeyEvent.VK_Q;
            case "r": return KeyEvent.VK_R;
            case "s": return KeyEvent.VK_S;
            case "t": return KeyEvent.VK_T;
            case "u": return KeyEvent.VK_U;
            case "v": return KeyEvent.VK_V;
            case "w": return KeyEvent.VK_W;
            case "x": return KeyEvent.VK_X;
            case "y": return KeyEvent.VK_Y;
            case "z": return KeyEvent.VK_Z;
            case "0": return KeyEvent.VK_0;
            case "1": return KeyEvent.VK_1;
            case "2": return KeyEvent.VK_2;
            case "3": return KeyEvent.VK_3;
            case "4": return KeyEvent.VK_4;
            case "5": return KeyEvent.VK_5;
            case "6": return KeyEvent.VK_6;
            case "7": return KeyEvent.VK_7;
            case "8": return KeyEvent.VK_8;
            case "9": return KeyEvent.VK_9;
            default: return -1;
        }
    }
}
