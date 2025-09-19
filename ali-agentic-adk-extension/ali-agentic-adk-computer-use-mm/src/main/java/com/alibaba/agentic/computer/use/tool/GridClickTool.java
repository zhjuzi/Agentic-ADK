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
import com.alibaba.langengine.core.chatmodel.BaseChatModel;
import com.alibaba.langengine.core.messages.BaseMessage;
import com.alibaba.langengine.core.messages.HumanMessage;
import com.alibaba.langengine.core.model.fastchat.completion.chat.ChatMessageContent;
import com.alibaba.langengine.core.model.fastchat.completion.chat.ChatMessageConstant;
import com.alibaba.langengine.core.tool.BaseTool;
import com.alibaba.langengine.core.tool.ToolExecuteResult;
import com.alibaba.langengine.dashscope.model.DashScopeChatModel;
import com.alibaba.langengine.dashscope.DashScopeModelName;

import javax.imageio.ImageIO;
import java.io.File;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网格点击工具
 * 基于带网格线的截图和格子坐标进行精确点击操作
 * 执行流程：截取格子区域 -> 放大 -> 重新划线标坐标 -> LLM分析 -> 精确点击
 */
public class GridClickTool extends BaseTool {

    private Robot robot;
    private BaseChatModel multimodalLlm;
    private static final int SCALE_FACTOR = 4; // 放大倍数

    public GridClickTool() {
        setName("grid_click");
        setDescription("网格点击工具，根据带网格线的屏幕截图和格子坐标，精确点击指定的按钮或UI元素");
        setParameters("{\n" +
            "    \"type\": \"object\",\n" +
            "    \"properties\": {\n" +
            "        \"screenshot_base64\": {\n" +
            "            \"type\": \"string\",\n" +
            "            \"description\": \"带网格线和坐标标记的屏幕截图的base64编码\"\n" +
            "        },\n" +
            "        \"grid_x\": {\n" +
            "            \"type\": \"integer\",\n" +
            "            \"description\": \"目标格子的X坐标（从0开始计数）\"\n" +
            "        },\n" +
            "        \"grid_y\": {\n" +
            "            \"type\": \"integer\",\n" +
            "            \"description\": \"目标格子的Y坐标（从0开始计数）\"\n" +
            "        },\n" +
            "        \"button_description\": {\n" +
            "            \"type\": \"string\",\n" +
            "            \"description\": \"要点击的按钮或UI元素的描述，例如：'登录按钮'、'确定按钮'、'菜单项'等\"\n" +
            "        },\n" +
            "        \"click_type\": {\n" +
            "            \"type\": \"string\",\n" +
            "            \"enum\": [\"single\", \"double\", \"right\"],\n" +
            "            \"description\": \"点击类型：single(单击，默认)、double(双击)、right(右键点击)\",\n" +
            "            \"default\": \"single\"\n" +
            "        },\n" +
            "        \"offset_x\": {\n" +
            "            \"type\": \"integer\",\n" +
            "            \"description\": \"在格子内的X偏移量（像素），默认为格子中心\",\n" +
            "            \"default\": 0\n" +
            "        },\n" +
            "        \"offset_y\": {\n" +
            "            \"type\": \"integer\",\n" +
            "            \"description\": \"在格子内的Y偏移量（像素），默认为格子中心\",\n" +
            "            \"default\": 0\n" +
            "        }\n" +
            "    },\n" +
            "    \"required\": [\"screenshot_base64\", \"grid_x\", \"grid_y\", \"button_description\"]\n" +
            "}");
        
        // 初始化Robot
        try {
            this.robot = new Robot();
            this.robot.setAutoDelay(50); // 设置操作间隔
        } catch (AWTException e) {
            throw new RuntimeException("初始化Robot失败: " + e.getMessage(), e);
        }
    }

    /**
     * 延迟初始化多模态LLM
     */
    private void initializeMultimodalLLM() {
        if (multimodalLlm == null) {
            try {
                String doubaoToken = Optional.ofNullable("fa2c722a-ed68-406d-ac20-2640a6c4356f")
                        .orElse(System.getProperty("DASHSCOPE_API_KEY"));
                if (doubaoToken == null || doubaoToken.trim().isEmpty()) {
                    throw new IllegalStateException("缺少 DASHSCOPE_API_KEY，请在环境变量或 JVM 启动参数(-DDASHSCOPE_API_KEY=...) 中配置");
                }
                
                // 直接创建DashScopeChatModel并手动设置FastChatService以使用DashScope原生API
                DashScopeChatModel chatModel = new DashScopeChatModel(doubaoToken);
                chatModel.setModel(DashScopeModelName.QWEN_MAX);
                chatModel.setTemperature(0.1);
                chatModel.setMaxTokens(2048);
                this.multimodalLlm = chatModel;
            } catch (Exception e) {
                throw new RuntimeException("初始化多模态LLM失败: " + e.getMessage(), e);
            }
        }
    }

    /**
     * 截取指定格子区域的图片
     */
    private BufferedImage extractGridCell(String screenshotBase64, int gridX, int gridY) {
        try {
            // 解码截图
            byte[] imageBytes = Base64.getDecoder().decode(screenshotBase64);
            ByteArrayInputStream bis = new ByteArrayInputStream(imageBytes);
            BufferedImage originalImage = ImageIO.read(bis);
            bis.close();

            // 获取真实屏幕分辨率来计算网格尺寸
            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            int realScreenWidth = screenSize.width;
            int realScreenHeight = screenSize.height;
            System.out.println("屏幕真实边长，宽：" + realScreenWidth + ", 高：" + realScreenHeight);
            
            // 使用与ScreenAgent相同的网格计算逻辑
           // 使用与ScreenAgent相同的网格计算逻辑
            // 这里的值是每个格子的像素大小，不是格子数量
             double ratio = (double) realScreenWidth / realScreenHeight;
             int gridCellWidth, gridCellHeight;
             if (Math.abs(ratio - 16.0/9.0) < 0.1) {
                 // 16:9 比例
                 gridCellWidth = 192;
                 gridCellHeight = 108;
             } else if (Math.abs(ratio - 4.0/3.0) < 0.1) {
                 // 4:3 比例
                 gridCellWidth = 192;
                 gridCellHeight = 144;
             } else if (Math.abs(ratio - 1.0) < 0.1) {
                 // 1:1 比例
                 gridCellWidth = 192;
                 gridCellHeight = 192;
             } else {
                 // 其他比例，按16:9处理
                 gridCellWidth = 192;
                 gridCellHeight = 108;
             }

            // 计算目标格子的区域
            int cellLeft = gridX * gridCellWidth;
            int cellTop = gridY * gridCellHeight;
            
            // 额外的边界检查，确保计算出的像素坐标不超出屏幕
            if (cellLeft >= realScreenWidth || cellTop >= realScreenHeight) {
                throw new IllegalArgumentException("计算出的坐标超出屏幕边界: (" + cellLeft + "," + cellTop + 
                    ")，屏幕尺寸: " + realScreenWidth + "x" + realScreenHeight);
            }
            System.out.println("二次截图图片左侧边长像素" + cellLeft + ", 上侧边长像素：" + cellTop);
            int cellLeftSize = cellLeft + gridCellWidth;
            int cellTopSize = cellTop + gridCellHeight;
            System.out.println("二次截图初次计算图片右侧边长像素" + cellLeftSize + ", 上侧边长像素：" + cellTopSize);
            
            // 确保不超出图片边界
            int cellRight = Math.min(cellLeft + gridCellWidth, originalImage.getWidth());
            int cellBottom = Math.min(cellTop + gridCellHeight, originalImage.getHeight());
            System.out.println("二次截图确保不超出边界后图片右侧边长像素" + cellRight + ", 上侧边长像素：" + cellBottom);
            
            // 截取格子区域
            BufferedImage cellImage = originalImage.getSubimage(cellLeft, cellTop, cellRight - cellLeft, cellBottom - cellTop);
            
            // 保存裁剪后的调试图片
            saveDebugImage(cellImage, "cropped_cell");
            
            return cellImage;
            
        } catch (Exception e) {
            throw new RuntimeException("截取格子区域失败: " + e.getMessage(), e);
        }
    }

    /**
     * 放大图片
     */
    private BufferedImage scaleImage(BufferedImage originalImage, int scaleFactor) {
        int newWidth = originalImage.getWidth() * scaleFactor;
        int newHeight = originalImage.getHeight() * scaleFactor;
        
        // 使用Graphics2D进行缩放，更稳定
        BufferedImage scaledImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = scaledImage.createGraphics();
        
        // 设置高质量渲染
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        // 绘制缩放后的图像
        g2d.drawImage(originalImage, 0, 0, newWidth, newHeight, null);
        g2d.dispose();
        
        return scaledImage;
    }

    /**
     * 在放大的图片上重新绘制网格线和坐标标记
     */
    private String addFineGridToImage(BufferedImage scaledImage) {
        try {
            // 创建新的图片用于绘制网格
            BufferedImage gridImage = new BufferedImage(
                scaledImage.getWidth(), 
                scaledImage.getHeight(), 
                BufferedImage.TYPE_INT_RGB
            );

            // 绘制原图
            Graphics2D g2d = gridImage.createGraphics();
            g2d.drawImage(scaledImage, 0, 0, null);

            // 设置网格线样式
            g2d.setColor(Color.RED);
            g2d.setStroke(new BasicStroke(1.0f));
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // 细网格尺寸（每个小格子20像素）
            int fineGridSize = 60;
            
            // 绘制垂直网格线并添加标号
            int verticalIndex = 0;
            for (int x = 0; x <= scaledImage.getWidth(); x += fineGridSize) {
                g2d.drawLine(x, 0, x, scaledImage.getHeight());
                
                if (x > 0) {
                    // 在垂直线顶部添加标号
                    g2d.setFont(new Font("Arial", Font.BOLD, 24));
                    String label = String.valueOf(verticalIndex);
                    FontMetrics fm = g2d.getFontMetrics();
                    int labelWidth = fm.stringWidth(label);
                    g2d.setColor(Color.BLUE);
                    g2d.fillRect(x - labelWidth/2 - 2, 2, labelWidth + 4, fm.getHeight());
                    g2d.setColor(Color.WHITE);
                    g2d.drawString(label, x - labelWidth/2, fm.getAscent() + 2);
                    g2d.setColor(Color.RED);
                }
                verticalIndex++;
            }

            // 绘制水平网格线并添加标号
            int horizontalIndex = 0;
            for (int y = 0; y <= scaledImage.getHeight(); y += fineGridSize) {
                g2d.drawLine(0, y, scaledImage.getWidth(), y);
                
                if (y > 0) {
                    // 在水平线左侧添加标号
                    g2d.setFont(new Font("Arial", Font.BOLD, 24));
                    String label = String.valueOf(horizontalIndex);
                    FontMetrics fm = g2d.getFontMetrics();
                    int labelWidth = fm.stringWidth(label);
                    g2d.setColor(Color.BLUE);
                    g2d.fillRect(2, y - fm.getHeight()/2 - 2, labelWidth + 4, fm.getHeight());
                    g2d.setColor(Color.WHITE);
                    g2d.drawString(label, 4, y + fm.getAscent()/2 - 2);
                    g2d.setColor(Color.RED);
                }
                horizontalIndex++;
            }

            g2d.dispose();

            // 保存调试图片到本地
            saveDebugImage(gridImage, "scaled_with_grid");
            
            // 将绘制好网格的图片转换为base64
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(gridImage, "PNG", bos);
            byte[] gridImageBytes = bos.toByteArray();
            bos.close();

            return Base64.getEncoder().encodeToString(gridImageBytes);

        } catch (Exception e) {
            throw new RuntimeException("绘制细网格失败: " + e.getMessage(), e);
        }
    }

    /**
     * 使用LLM分析放大的格子图片，获取精确的点击坐标
     */
    private Point analyzeCellWithLLM(String cellImageBase64, String buttonDescription) {
        try {
            initializeMultimodalLLM();
            
            String analysisRequest = String.format(
                "请仔细分析这个放大的图片区域，找到'%s'的精确位置。" +
                "图片已经放大并重新划分了网格，每个小格子代表20像素。" +
                "图片左上角坐标为(0,0)。" +
                "重要提示：图片中的红色网格线和数字标记都是辅助标注，不是真实的屏幕元素。" +
                "请告诉我目标按钮/元素的中心点坐标，格式为：坐标(x,y)，其中x和y是具体的数字。" +
                "只需要给出最终的坐标数字，不需要其他解释。",
                buttonDescription
            );
            
            // 创建多模态消息
            HumanMessage message = createMultimodalMessage(analysisRequest, cellImageBase64);
            List<BaseMessage> messages = Arrays.asList(message);
            
            // 调用多模态LLM
            BaseMessage response = multimodalLlm.run(messages, null, null, null, null);
            String llmResponse = response.getContent();
            System.out.println("LLM分析结果: " + llmResponse);
            
            // 解析LLM返回的坐标
            return parseCoordinatesFromResponse(llmResponse);
            
        } catch (Exception e) {
            throw new RuntimeException("LLM分析失败: " + e.getMessage(), e);
        }
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
        textContent.setText(text);
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
        message.setContent(text + "\n[包含图片进行分析]");
        
        return message;
    }

    /**
     * 从LLM响应中解析坐标
     */
    private Point parseCoordinatesFromResponse(String response) {
        // 尝试多种坐标格式的正则表达式
        Pattern[] patterns = {
            Pattern.compile("坐标\\\\s*\\\\(\\\\s*(\\\\d+)\\\\s*,\\\\s*(\\\\d+)\\\\s*\\\\)"),
            Pattern.compile("\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)"),
            Pattern.compile("x\\s*[=:]\\s*(\\d+).*?y\\s*[=:]\\s*(\\d+)"),
            Pattern.compile("(\\d+)\\s*,\\s*(\\d+)")
        };
        
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(response);
            if (matcher.find()) {
                try {
                    int x = Integer.parseInt(matcher.group(1));
                    int y = Integer.parseInt(matcher.group(2));
                    System.out.println("解析得到的坐标: (" + x + ", " + y + ")");
                    return new Point(x, y);
                } catch (NumberFormatException e) {
                    continue;
                }
            }
        }
        
        // 如果无法解析，返回中心点作为默认值
        System.out.println("无法解析坐标，使用默认中心点");
        return new Point(50, 50); // 假设放大后的格子大小约为100x100
    }

    /**
     * 将格子内的相对坐标转换为屏幕绝对坐标
     */
    private Point convertCellCoordinateToScreen(String originalScreenshotBase64, int gridX, int gridY, Point cellPoint) {
        try {
            // 获取真实屏幕分辨率
            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            int realScreenWidth = screenSize.width;
            int realScreenHeight = screenSize.height;

           // 使用与ScreenAgent相同的网格计算逻辑
            // 这里的值是每个格子的像素大小，不是格子数量
            double ratio = (double) realScreenWidth / realScreenHeight;
            int gridCellWidth, gridCellHeight;
            if (Math.abs(ratio - 16.0/9.0) < 0.1) {
                // 16:9 比例
                gridCellWidth = 192;
                gridCellHeight = 108;
            } else if (Math.abs(ratio - 4.0/3.0) < 0.1) {
                // 4:3 比例
                gridCellWidth = 192;
                gridCellHeight = 144;
            } else if (Math.abs(ratio - 1.0) < 0.1) {
                // 1:1 比例
                gridCellWidth = 192;
                gridCellHeight = 192;
            }else {
                // 其他比例，按16:9处理
                gridCellWidth = 192;
                gridCellHeight = 108;
            }

            // 计算目标格子的左上角屏幕坐标
            int cellLeft = gridX * gridCellWidth;
            int cellTop = gridY * gridCellHeight;

            // 将格子内坐标转换为原始图片坐标（考虑放大倍数）
            int originalCellX = cellPoint.x / SCALE_FACTOR;
            int originalCellY = cellPoint.y / SCALE_FACTOR;
            
            // 计算最终的屏幕坐标
            int finalX = cellLeft + originalCellX;
            int finalY = cellTop + originalCellY;

            // 确保坐标在屏幕范围内
            finalX = Math.max(0, Math.min(finalX, realScreenWidth - 1));
            finalY = Math.max(0, Math.min(finalY, realScreenHeight - 1));

            System.out.println("坐标转换详情:");
            System.out.println("- 屏幕分辨率: " + realScreenWidth + "x" + realScreenHeight);
            System.out.println("- 网格大小: " + gridCellWidth + "x" + gridCellHeight);
            System.out.println("- 格子坐标: (" + gridX + ", " + gridY + ")");
            System.out.println("- 格子左上角: (" + cellLeft + ", " + cellTop + ")");
            System.out.println("- 格子内坐标: (" + originalCellX + ", " + originalCellY + ")");
            System.out.println("- 最终屏幕坐标: (" + finalX + ", " + finalY + ")");

            return new Point(finalX, finalY);

        } catch (Exception e) {
            throw new RuntimeException("坐标转换失败: " + e.getMessage(), e);
        }
    }

    /**
     * 执行鼠标点击操作
     */
    private void performClick(Point clickPoint, String clickType) {
        try {
            // 移动鼠标到目标位置
            robot.mouseMove(clickPoint.x, clickPoint.y);
            
            // 短暂延迟确保鼠标移动完成
            robot.delay(100);
            
            // 根据点击类型执行不同的操作
            switch (clickType.toLowerCase()) {
                case "single":
                default:
                    // 单击（按下并释放鼠标左键）
                    robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                    robot.delay(50);
                    robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                    break;
                    
                case "double":
                    // 双击
                    robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                    robot.delay(50);
                    robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                    robot.delay(100); // 双击间隔
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            robot.delay(50);
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                    break;
                    
                case "right":
                    // 右键点击
                    robot.mousePress(java.awt.event.InputEvent.BUTTON3_DOWN_MASK);
                    robot.delay(50);
                    robot.mouseRelease(java.awt.event.InputEvent.BUTTON3_DOWN_MASK);
                    break;
            }
            
            // 点击后短暂延迟
            robot.delay(100);
            
        } catch (Exception e) {
            throw new RuntimeException("执行点击操作失败: " + e.getMessage(), e);
        }
    }

    @Override
    public ToolExecuteResult run(String toolInput, ExecutionContext executionContext) {
        try {
            // 解析输入参数
            @SuppressWarnings("unchecked")
            Map<String, Object> params = JSON.parseObject(toolInput, Map.class);
            String screenshotBase64 = (String) params.get("screenshot_base64");
            Integer gridX = (Integer) params.get("grid_x");
            Integer gridY = (Integer) params.get("grid_y");
            String buttonDescription = (String) params.get("button_description");
            String clickType = (String) params.getOrDefault("click_type", "single");
            // 注意：offset参数在新的执行流程中不再使用，因为LLM会直接分析精确位置
            System.out.println("grid click 入参解析成功");
            // 参数验证
            if (screenshotBase64 == null || screenshotBase64.trim().isEmpty()) {
                return new ToolExecuteResult("错误：截图参数不能为空", false);
            }
            if (gridX == null || gridY == null) {
                return new ToolExecuteResult("错误：格子坐标参数不能为空", false);
            }
            if (gridX < 0 || gridY < 0) {
                return new ToolExecuteResult("错误：格子坐标不能为负数", false);
            }
            if (buttonDescription == null || buttonDescription.trim().isEmpty()) {
                return new ToolExecuteResult("错误：按钮描述不能为空", false);
            }

            System.out.println("开始精确点击流程:");
            System.out.println("- 格子坐标: (" + gridX + ", " + gridY + ")");
            System.out.println("- 按钮描述: " + buttonDescription);
            System.out.println("- 点击类型: " + clickType);

            // 步骤1: 截取指定格子区域
            System.out.println("步骤1: 截取格子区域...");
            BufferedImage cellImage = extractGridCell(screenshotBase64, gridX, gridY);
            
            // 步骤2: 放大图片
            System.out.println("步骤2: 放大图片 " + SCALE_FACTOR + " 倍...");
            BufferedImage scaledImage = scaleImage(cellImage, SCALE_FACTOR);
            
            // 步骤3: 重新绘制网格和坐标
            System.out.println("步骤3: 重新绘制网格和坐标...");
            String scaledImageBase64 = addFineGridToImage(scaledImage);
            
            // 步骤4: 使用LLM分析获取精确坐标
            System.out.println("步骤4: 使用LLM分析获取精确坐标...");
            Point cellPoint = analyzeCellWithLLM(scaledImageBase64, buttonDescription);
            
            // 步骤5: 转换为屏幕绝对坐标
            System.out.println("步骤5: 转换为屏幕绝对坐标...");
            Point screenPoint = convertCellCoordinateToScreen(screenshotBase64, gridX, gridY, cellPoint);
            System.out.println("- 最终屏幕坐标: (" + screenPoint.x + ", " + screenPoint.y + ")");

            // 步骤6: 执行点击操作
            System.out.println("步骤6: 执行点击操作...");
            performClick(screenPoint, clickType);

            String result = String.format("成功完成精确点击：格子(%d,%d) -> 格子内坐标(%d,%d) -> 屏幕坐标(%d,%d)，目标：'%s'", 
                                        gridX, gridY, cellPoint.x, cellPoint.y, screenPoint.x, screenPoint.y, buttonDescription);
            System.out.println("点击操作完成: " + result);

            return new ToolExecuteResult(result, true);

        } catch (Exception e) {
            String errorMsg = "GridClickTool执行失败: " + e.getMessage();
            System.err.println(errorMsg);
            e.printStackTrace();
            return new ToolExecuteResult(errorMsg, false);
        }
    }

    /**
     * 保存调试图片到本地
     */
    private void saveDebugImage(BufferedImage image, String suffix) {
        try {
            String homeDir = System.getProperty("user.home");
            String screenshotDir = homeDir + "/Screenshots";
            
            // 确保目录存在
            File dir = new File(screenshotDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            
            // 生成文件名
            String timestamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String filename = String.format("debug_%s_%s.png", suffix, timestamp);
            File file = new File(dir, filename);
            
            // 保存图片
            ImageIO.write(image, "PNG", file);
            System.out.println("调试图片已保存到: " + file.getAbsolutePath());
            
        } catch (Exception e) {
            System.err.println("保存调试图片失败: " + e.getMessage());
        }
    }

    /**
     * 获取工具状态信息
     */
    public String getStatus() {
        return "GridClickTool已就绪，Robot初始化完成";
    }
}
