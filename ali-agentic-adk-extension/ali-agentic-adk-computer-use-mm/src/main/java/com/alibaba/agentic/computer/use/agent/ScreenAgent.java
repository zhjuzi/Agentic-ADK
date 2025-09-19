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

import com.alibaba.agentic.computer.use.tool.GridClickTool;
import com.alibaba.agentic.computer.use.tool.KeyboardTool;
import com.alibaba.fastjson.JSON;

import java.io.File;
import java.text.SimpleDateFormat;

import com.alibaba.langengine.core.chatmodel.BaseChatModel;
import com.alibaba.langengine.core.memory.impl.ConversationBufferMemory;
import com.alibaba.langengine.core.messages.*;
import com.alibaba.langengine.core.model.fastchat.completion.chat.ChatMessageContent;
import com.alibaba.langengine.core.model.fastchat.completion.chat.ChatMessageConstant;
import com.alibaba.langengine.core.model.fastchat.completion.chat.FunctionDefinition;
import com.alibaba.langengine.core.tool.ToolExecuteResult;
import com.alibaba.langengine.dashscope.model.DashScopeChatModel;
import com.alibaba.langengine.dashscope.DashScopeModelName;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.List;

/**
 * 基于屏幕截图的智能体
 * 每次调用前自动截取当前屏幕，使用多模态LLM进行屏幕理解和任务分析
 *
 * @author xiaoxuan.lp
 */
@Slf4j
public class ScreenAgent {

    private final BaseChatModel multimodalLlm;
    private final ConversationBufferMemory memory;
    private final Robot robot;
    private GridClickTool gridClickTool;
    private KeyboardTool keyboardTool;
    
    // 缓存的原始截图，避免重复截图
    private BufferedImage cachedOriginalScreenshot;
    private boolean screenshotCacheValid = false;

    private static String buildSystemPrompt() {
        String currentDate = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy年MM月dd日"));
        String osName = System.getProperty("os.name");
        String osVersion = System.getProperty("os.version");
        String osArch = System.getProperty("os.arch");
        
        return "你是一个基于屏幕截图的智能助手，能够理解和分析屏幕内容，特别擅长图标识别和定位。\n\n" +
            "当前环境信息:\n" +
            "- 日期: " + currentDate + "\n" +
            "- 操作系统: " + osName + " " + osVersion + " (" + osArch + ")\n\n" +
            "重要提示 - 网格坐标系统详解:\n" +
            "1. 屏幕截图中的红色网格线将屏幕分割成规则的格子\n" +
            "2. 蓝色数字标记表示网格坐标：\n" +
            "   - 顶部的数字(1,2,3...)表示列号(X坐标)，第0列在最左边没有标号\n" +
            "   - 左侧的数字(1,2,3...)表示行号(Y坐标)，第0行在最顶部没有标号\n" +
            "3. 坐标从左上角(0,0)开始计算，向右X增加，向下Y增加\n" +
            "4. 【绝对关键】坐标边界检查 - 使用前必须执行：\n" +
            "   - 第一步：仔细数一数截图顶部蓝色数字标记，找到最大的X坐标值\n" +
            "   - 第二步：仔细数一数截图左侧蓝色数字标记，找到最大的Y坐标值\n" +
            "   - 第三步：确保你选择的坐标不超过这些最大值\n" +
            "   - 例如：如果左侧最大数字是8，那么Y坐标绝对不能超过8\n" +
            "5. 【严格验证】在选择坐标前，必须明确说出：\n" +
            "   - '我看到X坐标最大是[数字]，Y坐标最大是[数字]'\n" +
            "   - '我选择的坐标是([X],[Y])，在有效范围内'\n" +
            "6. 底部dock区域通常在Y坐标的较大值附近\n" +
            "7. 点击坐标格式：grid_click(列号, 行号)\n" +
            "8. 【绝对禁止】任何超出网格范围的坐标都会导致系统崩溃\n" +
            "9. 【双重检查】选择坐标后，再次确认是否在截图标记的范围内\n\n" +
            "操作要求:\n" +
            "- 当用户请求需要屏幕操作时(如打开应用、点击按钮、选择菜单等)，优先使用grid_click工具\n" +
            "- 每次操作前必须仔细分析当前屏幕截图中的网格坐标\n" +
            "- 准确识别目标UI元素(按钮、链接、输入框等)所在的具体格子位置\n" +
            "- 使用grid_click工具格式：调用grid_click，参数包含gridX(列号)、gridY(行号)、buttonDescription(目标描述)\n" +
            "- 如果需要键盘输入(打字、按键、组合键)，可以使用keyboard_input工具\n" +
            "- keyboard_input工具格式：调用keyboard_input，参数包含action(type_text|key_press|key_combination)、text/key/keys(根据action类型)、description(操作描述)\n" +
            "- 点击后会自动获取新的屏幕状态，请根据结果判断操作是否成功\n" +
            "- 如果应用已经成功打开或目标已达成，请停止工具调用并返回结果，不要重复点击\n" +
            "- 如果点击位置不准确，请重新分析网格坐标并调整\n\n" +
            "文字识别要求:\n" +
            "- 当用户要求读取屏幕上的文字内容时，请直接描述你在截图中看到的文字\n" +
            "- 仔细观察屏幕上的文本、标题、按钮文字、菜单项等所有可见文字\n" +
            "- 不要说'需要OCR技术'，你已经具备直接读取屏幕文字的能力\n" +
            "- 如果文字较小或模糊，请尽力识别并说明可能不够清晰的部分\n\n" +
            "【关键要求】：\n" +
            "1. 对于任何需要点击、打开应用、选择菜单的请求，必须立即调用grid_click工具执行操作\n" +
            "2. 不要只是描述步骤或解释如何操作，必须直接调用工具\n" +
            "3. 分析完屏幕内容后，立即使用grid_click工具点击目标位置\n" +
            "4. 禁止输出类似'调用grid_click工具'的描述性文字，直接调用工具\n\n";    }

    private static final String SYSTEM_PROMPT = buildSystemPrompt();

    public ScreenAgent() {
        // 初始化多模态LLM (使用QWEN_VL_MAX支持图像理解)
        String dashscopeToken = Optional.ofNullable(System.getenv("DASHSCOPE_API_KEY"))
                .orElse(System.getProperty("DASHSCOPE_API_KEY"));
        if (dashscopeToken == null || dashscopeToken.trim().isEmpty()) {
            // 使用默认token进行测试
            dashscopeToken = "sk-79bafa20b12240b090eba4c9cd2b5dbbf";
        }
        
        // 直接创建DashScopeChatModel
        DashScopeChatModel chatModel = new DashScopeChatModel(dashscopeToken);
        chatModel.setModel(DashScopeModelName.QWEN_MAX);
        chatModel.setTemperature(0.1);
        chatModel.setMaxTokens(2048);

        this.multimodalLlm = chatModel; // 降低温度，提高工具调用准确性
        
        this.memory = new ConversationBufferMemory();
        // 初始化工具
        this.gridClickTool = new GridClickTool();
        this.keyboardTool = new KeyboardTool();
        
        // 初始化屏幕截图工具
        try {
            this.robot = new Robot();
            System.out.println("ScreenshotAgent初始化完成");
        } catch (AWTException e) {
            throw new RuntimeException("无法初始化屏幕截图功能", e);
        }
    }

    public List<FunctionDefinition> getFunctionDefinitions() {
        List<FunctionDefinition> functionDefinitions = new ArrayList<>();
        
        // 添加工具定义
        functionDefinitions.add(gridClickTool.toParams());
        functionDefinitions.add(keyboardTool.toParams());
        
        return functionDefinitions;
    }

    /**
     * 执行任务，自动截取屏幕并分析
     * @param userRequest 用户请求
     * @return 分析结果和建议
     */
    public String execute(String userRequest) {
        // 自动截取当前屏幕并添加网格
        clearScreenshotCache();
        String screenshotBase64 = captureScreenshotWithGrid();
        if (screenshotBase64 == null) {
            System.err.println("自动截图失败");
            return "截图失败";
        }
        System.out.println("屏幕截图成功");
        return executeWithScreenshot(userRequest, screenshotBase64);
    }

    /**
     * 执行任务，使用提供的截图（支持工具调用和多轮对话）
     * @param userRequest 用户请求
     * @param screenshotBase64 base64编码的截图
     * @return 分析结果和建议
     */
    public String executeWithScreenshot(String userRequest, String screenshotBase64) {
        int maxTurns = 8; // 最大迭代轮次，避免无限循环
        System.out.println("=== ScreenAgent开始执行任务 ===");
        System.out.println("用户请求: " + userRequest);
        System.out.println("最大轮次: " + maxTurns);

        for (int turn = 0; turn < maxTurns; turn++) {
            System.out.println("\n--- 第" + (turn + 1) + "轮开始 ---");
            try {
                // 获取两种格式的截图
                String currentScreenshot; // 用于工具执行（只有边缘坐标）
                String llmScreenshot;     // 用于LLM分析（包含格子内坐标）
                
                if (turn == 0) {
                    // 第一轮使用传入的截图作为工具截图，生成LLM分析截图
                    currentScreenshot = screenshotBase64;
                    llmScreenshot = captureScreenshotWithGridAndCoordinates();
                    System.out.println("使用传入的截图作为工具截图，长度: " + (screenshotBase64 != null ? screenshotBase64.length() : "null"));
                    System.out.println("生成LLM分析截图，长度: " + (llmScreenshot != null ? llmScreenshot.length() : "null"));
                } else {
                    // 后续轮次自动获取最新截图
                    currentScreenshot = captureScreenshotWithGrid();
                    llmScreenshot = captureScreenshotWithGridAndCoordinates();
                    System.out.println("获取工具执行截图，长度: " + (currentScreenshot != null ? currentScreenshot.length() : "null"));
                    System.out.println("获取LLM分析截图，长度: " + (llmScreenshot != null ? llmScreenshot.length() : "null"));
                }

                // 1. 构建包含截图的消息（使用LLM分析截图）
                List<BaseMessage> messages = buildConversationContext(userRequest, llmScreenshot, turn);
                System.out.println("构建对话上下文，消息数量: " + messages.size());

                // 2. 准备工具定义
                List<FunctionDefinition> functions = getFunctionDefinitions();

                // 3. 调用多模态LLM进行分析
                System.out.println("调用LLM分析，工具数量: " + functions.size());
                System.out.println("工具定义: " + functions);
                BaseMessage response = multimodalLlm.run(messages, functions, null, null, null);
                System.out.println("LLM response: " + response);
                System.out.println("LLM响应内容: " + (response.getContent() != null ? response.getContent().substring(0, Math.min(200, response.getContent().length())) + "..." : "null"));

                // 4. 保存对话历史（只在第一轮保存用户消息）
                if (turn == 0) {
                    memory.getChatMemory().addUserMessage(userRequest);
                }

                if (response.getContent() != null) {
                    memory.getChatMemory().getMessages().add(response);
                }



                // 5. 如果触发了工具调用，执行工具并记录结果，然后继续下一轮
                String toolExecResult = tryExecuteToolCalls(response, currentScreenshot);
                System.out.println("工具执行结果: " + (toolExecResult != null ? toolExecResult : "无工具调用"));
                if (toolExecResult != null && !toolExecResult.isEmpty()) {
                    System.out.println("有工具调用，继续下一轮");
                    continue;
                }
                
                System.out.println("无工具调用，返回响应内容");

                // 清理返回内容，移除图片链接
                String cleanContent = cleanResponseContent(response.getContent());
                System.out.println("清理后的返回内容: " + cleanContent);
                return cleanContent;

            } catch (Exception e) {
                System.err.println("ScreenshotAgent执行失败: " + e.getMessage());
                return "分析失败: " + e.getMessage();
            }
        }
        return "达到最大工具调用轮次限制，已停止。";
    }

    /**
     * 获取或更新缓存的原始截图
     */
    private BufferedImage getCachedOriginalScreenshot() {
        // 如果缓存无效，重新截图
        if (!screenshotCacheValid || cachedOriginalScreenshot == null) {
            try {
                Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
                Rectangle screenRect = new Rectangle(screenSize);
                cachedOriginalScreenshot = robot.createScreenCapture(screenRect);
                screenshotCacheValid = true;
                System.out.println("原始截图尺寸为：" + cachedOriginalScreenshot.getWidth() + "x" + cachedOriginalScreenshot.getHeight());
            } catch (Exception e) {
                System.err.println("截图失败: " + e.getMessage());
                return null;
            }
        }
        return cachedOriginalScreenshot;
    }

    /**
     * 清除截图缓存，下次调用时会重新截图
     */
    public void clearScreenshotCache() {
        screenshotCacheValid = false;
        cachedOriginalScreenshot = null;
    }

    /**
     * 捕获当前屏幕截图并添加网格（用于工具执行）
     * @return base64编码的带网格截图
     */
    public String captureScreenshotWithGrid() {
        try {
            // 获取缓存的原始截图
            BufferedImage screenshot = getCachedOriginalScreenshot();
            if (screenshot == null) {
                return null;
            }
            
            // 添加网格
            BufferedImage gridImage = addGridToImage(screenshot);
            
            // 转换为base64
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(gridImage, "png", baos);
            byte[] imageBytes = baos.toByteArray();
            
            return Base64.getEncoder().encodeToString(imageBytes);
        } catch (Exception e) {
            System.err.println("截图失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 捕获当前屏幕截图并添加网格和格子内坐标（用于LLM分析）
     * @return base64编码的带网格和格子内坐标的截图
     */
    public String captureScreenshotWithGridAndCoordinates() {
        try {
            // 获取缓存的原始截图
            BufferedImage screenshot = getCachedOriginalScreenshot();
            if (screenshot == null) {
                return null;
            }
            
            // 添加网格和格子内坐标
            BufferedImage gridImage = addGridWithCoordinatesToImage(screenshot);
            
            // 保存LLM分析截图到文件
            try {
                String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
                String filename = "/Users/zxj/Screenshots/llm_analysis_screenshot_" + timestamp + ".png";
                File outputFile = new File(filename);
                outputFile.getParentFile().mkdirs(); // 确保目录存在
                ImageIO.write(gridImage, "png", outputFile);
                System.out.println("LLM分析截图已保存到: " + filename);
            } catch (Exception saveException) {
                System.err.println("保存LLM分析截图失败: " + saveException.getMessage());
            }
            
            // 转换为base64
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(gridImage, "png", baos);
            byte[] imageBytes = baos.toByteArray();
            
            return Base64.getEncoder().encodeToString(imageBytes);
        } catch (Exception e) {
            System.err.println("LLM截图失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 在图片上绘制网格线和坐标
     */
    private BufferedImage addGridToImage(BufferedImage originalImage) {
        // 创建新的图片用于绘制网格
        BufferedImage gridImage = new BufferedImage(
            originalImage.getWidth(), 
            originalImage.getHeight(), 
            BufferedImage.TYPE_INT_RGB
        );

        // 绘制原图
        Graphics2D g2d = gridImage.createGraphics();
        g2d.drawImage(originalImage, 0, 0, null);

        // 计算网格尺寸
        Dimension gridSize = calculateGridSize(originalImage.getWidth(), originalImage.getHeight());
        System.out.println("网格尺寸为：" + gridSize);
        int gridWidth = gridSize.width;
        int gridHeight = gridSize.height;

        // 设置网格线样式
        g2d.setColor(Color.RED);
        g2d.setStroke(new BasicStroke(2.0f));

        // 绘制垂直网格线并添加标号
        int verticalLineIndex = 1;
        for (int x = gridWidth; x < originalImage.getWidth(); x += gridWidth) {
            g2d.drawLine(x, 0, x, originalImage.getHeight());
            
            // 在垂直线顶部添加标号
            g2d.setFont(new Font("Arial", Font.BOLD, 24));
            String label = String.valueOf(verticalLineIndex);
            FontMetrics fm = g2d.getFontMetrics();
            int labelWidth = fm.stringWidth(label);
            g2d.setColor(Color.BLUE);
            g2d.fillRect(x - labelWidth/2 - 2, 2, labelWidth + 4, fm.getHeight());
            g2d.setColor(Color.WHITE);
            g2d.drawString(label, x - labelWidth/2, fm.getAscent() + 2);
            g2d.setColor(Color.RED);
            
            verticalLineIndex++;
        }

        // 绘制水平网格线并添加标号
        int horizontalLineIndex = 1;
        for (int y = gridHeight; y < originalImage.getHeight(); y += gridHeight) {
            g2d.drawLine(0, y, originalImage.getWidth(), y);
            
            // 在水平线左侧添加标号
            g2d.setFont(new Font("Arial", Font.BOLD, 24));
            String label = String.valueOf(horizontalLineIndex);
            FontMetrics fm = g2d.getFontMetrics();
            int labelWidth = fm.stringWidth(label);
            g2d.setColor(Color.BLUE);
            g2d.fillRect(2, y - fm.getHeight()/2 - 2, labelWidth + 4, fm.getHeight());
            g2d.setColor(Color.WHITE);
            g2d.drawString(label, 4, y + fm.getAscent()/2 - 2);
            g2d.setColor(Color.RED);
            
            horizontalLineIndex++;
        }

        g2d.dispose();
        
        // 保存网格图片到本地文件
        saveGridImageToLocal(gridImage);
        
        return gridImage;
    }

    /**
     * 在图片上绘制网格线、边缘坐标和格子内坐标（用于LLM分析）
     */
    private BufferedImage addGridWithCoordinatesToImage(BufferedImage originalImage) {
        // 创建新的图片用于绘制网格
        BufferedImage gridImage = new BufferedImage(
            originalImage.getWidth(), 
            originalImage.getHeight(), 
            BufferedImage.TYPE_INT_RGB
        );

        // 绘制原图
        Graphics2D g2d = gridImage.createGraphics();
        g2d.drawImage(originalImage, 0, 0, null);

        // 计算网格尺寸
        Dimension gridSize = calculateGridSize(originalImage.getWidth(), originalImage.getHeight());
        int gridWidth = gridSize.width;
        int gridHeight = gridSize.height;

        // 设置网格线样式
        g2d.setColor(Color.RED);
        g2d.setStroke(new BasicStroke(2.0f));

        // 绘制垂直网格线并添加边缘标号
        int verticalLineIndex = 1;
        for (int x = gridWidth; x < originalImage.getWidth(); x += gridWidth) {
            g2d.drawLine(x, 0, x, originalImage.getHeight());
            
            // 在垂直线顶部添加标号
            g2d.setFont(new Font("Arial", Font.BOLD, 24));
            String label = String.valueOf(verticalLineIndex);
            FontMetrics fm = g2d.getFontMetrics();
            int labelWidth = fm.stringWidth(label);
            g2d.setColor(Color.BLUE);
            g2d.fillRect(x - labelWidth/2 - 2, 2, labelWidth + 4, fm.getHeight());
            g2d.setColor(Color.WHITE);
            g2d.drawString(label, x - labelWidth/2, fm.getAscent() + 2);
            g2d.setColor(Color.RED);
            
            verticalLineIndex++;
        }

        // 绘制水平网格线并添加边缘标号
        int horizontalLineIndex = 1;
        for (int y = gridHeight; y < originalImage.getHeight(); y += gridHeight) {
            g2d.drawLine(0, y, originalImage.getWidth(), y);
            
            // 在水平线左侧添加标号
            g2d.setFont(new Font("Arial", Font.BOLD, 24));
            String label = String.valueOf(horizontalLineIndex);
            FontMetrics fm = g2d.getFontMetrics();
            int labelWidth = fm.stringWidth(label);
            g2d.setColor(Color.BLUE);
            g2d.fillRect(2, y - fm.getHeight()/2 - 2, labelWidth + 4, fm.getHeight());
            g2d.setColor(Color.WHITE);
            g2d.drawString(label, 4, y + fm.getAscent()/2 - 2);
            g2d.setColor(Color.RED);
            
            horizontalLineIndex++;
        }

        // 在每个格子内添加坐标数字
        g2d.setFont(new Font("Arial", Font.BOLD, 16));
        g2d.setColor(Color.MAGENTA);
        
        int maxCols = originalImage.getWidth() / gridWidth + 1;
        int maxRows = originalImage.getHeight() / gridHeight + 1;
        
        for (int row = 0; row < maxRows; row++) {
            for (int col = 0; col < maxCols; col++) {
                int centerX = col * gridWidth + gridWidth / 2;
                int centerY = row * gridHeight + gridHeight / 2;
                
                // 确保坐标在图片范围内
                if (centerX < originalImage.getWidth() && centerY < originalImage.getHeight()) {
                    String coordText = "(" + col + "," + row + ")";
                    FontMetrics fm = g2d.getFontMetrics();
                    int textWidth = fm.stringWidth(coordText);
                    int textHeight = fm.getHeight();
                    
                    // 绘制半透明背景
                    g2d.setColor(new Color(255, 255, 255, 180));
                    g2d.fillRect(centerX - textWidth/2 - 2, centerY - textHeight/2 - 2, 
                               textWidth + 4, textHeight + 4);
                    
                    // 绘制坐标文字
                    g2d.setColor(Color.MAGENTA);
                    g2d.drawString(coordText, centerX - textWidth/2, centerY + fm.getAscent()/2);
                }
            }
        }

        g2d.dispose();
        return gridImage;
    }

    /**
     * 保存网格图片到本地文件
     */
    private void saveGridImageToLocal(BufferedImage gridImage) {
        try {
            // 创建保存目录
            String userHome = System.getProperty("user.home");
            File saveDir = new File(userHome, "Screenshots");
            if (!saveDir.exists()) {
                saveDir.mkdirs();
            }

            // 生成文件名（带时间戳）
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
            String timestamp = sdf.format(new Date());
            String fileName = "screenshot_grid_" + timestamp + ".png";
            File outputFile = new File(saveDir, fileName);

            // 保存图片
            ImageIO.write(gridImage, "PNG", outputFile);
            System.out.println("网格截图已保存到: " + outputFile.getAbsolutePath());

        } catch (IOException e) {
            System.err.println("保存网格图片失败: " + e.getMessage());
        }
    }

    /**
     * 根据图片比例计算网格尺寸
     */
    private Dimension calculateGridSize(int width, int height) {
        double ratio = (double) width / height;
        
        // 判断比例并设置网格尺寸
        if (Math.abs(ratio - 16.0/9.0) < 0.1) {
            // 16:9 比例
            return new Dimension(192, 108);
        } else if (Math.abs(ratio - 4.0/3.0) < 0.1) {
            // 4:3 比例
            return new Dimension(192, 144);
        } else if (Math.abs(ratio - 1.0) < 0.1) {
            // 1:1 比例
            return new Dimension(192, 192);
        } else {
            // 其他比例，按16:9处理
            return new Dimension(192, 108);
        }
    }

    /**
     * 构建对话上下文，包含系统提示、历史对话和当前截图
     */
    private List<BaseMessage> buildConversationContext(String userRequest, String currentScreenshot, int turn) {
        List<BaseMessage> messages = new ArrayList<>();
        
        // 添加系统提示
        messages.add(new SystemMessage(SYSTEM_PROMPT));
        
        // 添加历史对话（最近10条消息，避免上下文过长）
        List<BaseMessage> history = memory.getChatMemory().getMessages();
        System.out.println("当前memory中历史消息数量: " + history.size());
        int startIndex = Math.max(0, history.size() - 10);
        for (int i = startIndex; i < history.size(); i++) {
            messages.add(history.get(i));
            System.out.println("添加历史消息[" + i + "]: " + history.get(i).getClass().getSimpleName() + " - " + 
                (history.get(i).getContent() != null ? history.get(i).getContent().substring(0, Math.min(100, history.get(i).getContent().length())) + "..." : "null"));
        }
        
        if (turn == 0) {
            // 第一轮：添加包含截图的用户消息
            HumanMessage userMessage = createMultimodalMessage(userRequest, currentScreenshot);
            messages.add(userMessage);
            System.out.println("第一轮：添加用户请求消息");
        }
        return messages;
    }

    /**
     * 解析AI响应中的工具调用并执行，返回执行结果字符串；若无工具调用返回null
     */
    private String tryExecuteToolCalls(BaseMessage response, String currentScreenshot) {
        System.out.println("\n=== 开始检查工具调用 ===");
        System.out.println("响应类型: " + response.getClass().getSimpleName());
        if (!(response instanceof AIMessage)) {
            System.out.println("非AIMessage，无工具调用");
            return null;
        }
        AIMessage ai = (AIMessage) response;
        Map<String, Object> kwargs = ai.getAdditionalKwargs();
        System.out.println("AdditionalKwargs: " + (kwargs != null ? kwargs.keySet() : "null"));
        System.out.println("AIMessage内容: " + ai.getContent());
        System.out.println("完整AdditionalKwargs: " + kwargs);
        if (kwargs == null || kwargs.isEmpty()) {
            System.out.println("无AdditionalKwargs，无工具调用");
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
                System.out.println("调用工具: " + name + "，参数: " + arguments);
                String id = "func_" + UUID.randomUUID();
                String result = executeToolByName(name, arguments, currentScreenshot);
                System.out.println("工具[" + name + "]执行完成，结果: " + result);
                execSummary.append(formatToolResult(name, result)).append("\n");
                // 记录到记忆（ToolMessage）
                ToolMessage tm = new ToolMessage();
                tm.setTool_call_id(id);
                tm.setName(name);
                tm.setContent(result);
                memory.getChatMemory().getMessages().add(tm);
                System.out.println("工具结果已添加到memory，当前memory消息数: " + memory.getChatMemory().getMessages().size());
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
                        System.out.println("调用工具: " + name + "，参数: " + arguments);
                        String result = executeToolByName(name, arguments, currentScreenshot);
                        System.out.println("工具[" + name + "]执行完成，结果: " + result);
                        execSummary.append(formatToolResult(name, result)).append("\n");
                        // 记录到记忆（ToolMessage）
                        ToolMessage tm = new ToolMessage();
                        tm.setTool_call_id(id);
                        tm.setName(name);
                        tm.setContent(result);
                        memory.getChatMemory().getMessages().add(tm);
                        System.out.println("工具结果已添加到memory，当前memory消息数: " + memory.getChatMemory().getMessages().size());
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
    private String executeToolByName(String name, String argumentsJson, String currentScreenshot) {
        try {
           if ("grid_click".equals(name)) {
                // 解析grid_click参数，自动添加当前网格截图
                Map<String, Object> params = JSON.parseObject(argumentsJson, Map.class);
                
                // 使用LLM调用时的截图，而不是重新截图（避免屏幕状态变化）
                if (currentScreenshot != null) {
                    params.put("screenshot_base64", currentScreenshot);
                    argumentsJson = JSON.toJSONString(params);
                    System.out.println("使用LLM调用时的截图添加到grid_click调用");
                } else {
                    System.err.println("当前截图为空，无法添加到grid_click调用");
                }

                ToolExecuteResult result = gridClickTool.run(argumentsJson, null);
                return result.getOutput();
            } else if ("keyboard_input".equals(name)) {
                ToolExecuteResult result = keyboardTool.run(argumentsJson, null);
                return result.getOutput();
            }
            return "未知工具: " + name;
        } catch (Exception e) {
            return "工具执行失败: " + e.getMessage();
        }
    }

    /**
     * 清理响应内容，移除图片链接和无用格式
     */
    private String cleanResponseContent(String content) {
        if (content == null) {
            return null;
        }
        
        // 移除图片链接格式 ![](...)
        String cleaned = content.replaceAll("!\\[\\]\\([^)]+\\)", "");
        
        // 移除多余的换行和空格
        cleaned = cleaned.trim().replaceAll("\\n\\s*\\n", "\n");
        
        return cleaned;
    }

    private String formatToolResult(String name, String result) {
        return "工具[" + name + "]执行结果:\n" + result;
    }

    /**
     * 创建包含图像的多模态消息
     */
    private HumanMessage createMultimodalMessage(String text, String imageBase64) {
        HumanMessage message = new HumanMessage();
        message.setAdditionalKwargs(new HashMap<>());
        
        // 构建ChatMessageContent列表
        List<ChatMessageContent> chatMessageContents = new ArrayList<>();
        
        // 添加文本部分
        ChatMessageContent textContent = new ChatMessageContent();
        textContent.setType("text");
        textContent.setText(text + "\n\n请仔细分析当前屏幕截图的内容：\n" +
                      "1. 观察红色网格线将屏幕分割成的格子\n" +
                      "2. 顶部蓝色数字(0,1,2,3...)是列号(X坐标)，左侧蓝色数字(0,1,2,3...)是行号(Y坐标)\n" +
                      "3. 仔细确定目标应用/按钮位于哪个格子内：\n" +
                      "   - 看目标元素的水平位置对应哪个列号\n" +
                      "   - 看目标元素的垂直位置对应哪个行号\n" +
                      "4. 必须调用grid_click工具，参数格式：gridX=列号, gridY=行号, buttonDescription=目标描述\n" +
                      "5. 请特别注意dock区域通常在屏幕底部，对应较大的行号(Y坐标)");
        chatMessageContents.add(textContent);
        
        // 添加图像部分
        ChatMessageContent imageContent = new ChatMessageContent();
        imageContent.setType("image_url");
        Map<String, Object> imageUrl = new HashMap<>();
        imageUrl.put("url", "data:image/png;base64," + imageBase64);
        imageContent.setImageUrl(imageUrl);
        chatMessageContents.add(imageContent);
        
        // 使用CHAT_MESSAGE_CONTENTS_KEY设置多模态内容
        message.getAdditionalKwargs().put(ChatMessageConstant.CHAT_MESSAGE_CONTENTS_KEY, chatMessageContents);
        
        // 设置简单的文本内容作为主要内容
        message.setContent(text + "\n[包含屏幕截图进行分析]");
        
        return message;
    }

    /**
     * 获取当前屏幕截图（用于外部调用）
     * @return base64编码的带网格截图
     */
    public String getCurrentScreenshot() {
        try {
            return captureScreenshotWithGrid();
        } catch (Exception e) {
            System.err.println("截图失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 清除对话历史
     */
    public void clearMemory() {
        memory.getChatMemory().clear();
        System.out.println("对话历史已清除");
    }

    /**
     * 获取对话历史摘要
     */
    public String getConversationSummary() {
        List<BaseMessage> messages = memory.getChatMemory().getMessages();
        if (messages.isEmpty()) {
            return "暂无对话历史";
        }
        
        StringBuilder summary = new StringBuilder();
        summary.append("对话历史摘要 (共").append(messages.size()).append("条消息):\n");
        
        int count = 0;
        for (BaseMessage msg : messages) {
            if (count >= 5) break; // 只显示最近5条
            summary.append("- ").append(msg.getClass().getSimpleName())
                   .append(": ").append(truncateText(msg.getContent(), 100)).append("\n");
            count++;
        }
        
        return summary.toString();
    }

    private String truncateText(String text, int maxLength) {
        if (text == null) return "null";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    /**
     * 关闭agent，清理资源
     */
    public void shutdown() {
        clearMemory();
        System.out.println("ScreenshotAgent已关闭");
    }
}
