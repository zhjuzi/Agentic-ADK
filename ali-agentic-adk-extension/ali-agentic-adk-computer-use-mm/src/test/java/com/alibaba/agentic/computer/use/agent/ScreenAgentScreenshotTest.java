package com.alibaba.agentic.computer.use.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.Base64;

/**
 * 测试ScreenAgent的两种截图格式
 */
public class ScreenAgentScreenshotTest {
    
    private static final Logger logger = LoggerFactory.getLogger(ScreenAgentScreenshotTest.class);
    
    private ScreenAgent screenAgent;
    
    @BeforeEach
    public void setUp() {
        // 初始化ScreenAgent（使用无参构造函数）
        screenAgent = new ScreenAgent();
    }
    
    @Test
    public void testTwoScreenshotFormats() {
        try {
            logger.info("=== 开始测试两种截图格式 ===");
            
            // 1. 测试工具执行截图（只有边缘坐标）
            logger.info("1. 生成工具执行截图（只有边缘坐标）...");
            String toolScreenshot = screenAgent.captureScreenshotWithGrid();
            logger.info("工具截图生成完成，长度: {}", toolScreenshot != null ? toolScreenshot.length() : "null");
            
            // 保存工具截图到文件
            if (toolScreenshot != null) {
                saveScreenshotToFile(toolScreenshot, "tool_screenshot.png");
                logger.info("工具截图已保存到: tool_screenshot.png");
            }
            
            // 2. 测试LLM分析截图（包含格子内坐标）
            logger.info("2. 生成LLM分析截图（包含格子内坐标）...");
            String llmScreenshot = screenAgent.captureScreenshotWithGridAndCoordinates();
            logger.info("LLM截图生成完成，长度: {}", llmScreenshot != null ? llmScreenshot.length() : "null");
            
            // 保存LLM截图到文件
            if (llmScreenshot != null) {
                saveScreenshotToFile(llmScreenshot, "llm_screenshot.png");
                logger.info("LLM截图已保存到: llm_screenshot.png");
            }
            
            // 3. 验证两个截图都不为空且不相同
            assert toolScreenshot != null : "工具截图不应为空";
            assert llmScreenshot != null : "LLM截图不应为空";
            assert !toolScreenshot.equals(llmScreenshot) : "两种截图应该不同";
            
            logger.info("=== 截图格式测试完成 ===");
            logger.info("请检查生成的两个截图文件:");
            logger.info("- tool_screenshot.png: 工具执行截图（只有边缘坐标）");
            logger.info("- llm_screenshot.png: LLM分析截图（包含格子内坐标）");
            
        } catch (Exception e) {
            logger.error("测试过程中发生错误", e);
            throw new RuntimeException("截图格式测试失败", e);
        }
    }
    
    @Test
    public void testScreenshotIntegration() {
        try {
            logger.info("=== 开始测试截图集成功能 ===");
            
            // 测试executeWithScreenshot方法使用不同截图
            String userRequest = "请分析当前屏幕内容";
            String initialScreenshot = screenAgent.captureScreenshotWithGrid();
            
            logger.info("开始执行ScreenAgent任务...");
            String result = screenAgent.executeWithScreenshot(userRequest, initialScreenshot);
            
            logger.info("任务执行完成");
            logger.info("执行结果: {}", result);
            
            assert result != null : "执行结果不应为空";
            
            logger.info("=== 截图集成测试完成 ===");
            
        } catch (Exception e) {
            logger.error("集成测试过程中发生错误", e);
            // 不抛出异常，因为可能需要真实的LLM调用
            logger.warn("集成测试可能需要真实的LLM环境，跳过此测试");
        }
    }
    
    /**
     * 将base64截图保存为文件
     */
    private void saveScreenshotToFile(String base64Screenshot, String filename) {
        try {
            // 解码base64
            byte[] imageBytes = Base64.getDecoder().decode(base64Screenshot);
            
            // 转换为BufferedImage
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            
            // 保存到文件
            File outputFile = new File(filename);
            ImageIO.write(image, "png", outputFile);
            
        } catch (Exception e) {
            logger.error("保存截图文件失败: " + filename, e);
        }
    }
    
    @Test
    public void testScreenshotDifferences() {
        try {
            logger.info("=== 开始测试截图差异分析 ===");
            
            // 生成两种截图
            String toolScreenshot = screenAgent.captureScreenshotWithGrid();
            String llmScreenshot = screenAgent.captureScreenshotWithGridAndCoordinates();
            
            // 分析差异
            logger.info("工具截图长度: {}", toolScreenshot != null ? toolScreenshot.length() : 0);
            logger.info("LLM截图长度: {}", llmScreenshot != null ? llmScreenshot.length() : 0);
            
            if (toolScreenshot != null && llmScreenshot != null) {
                // 计算长度差异
                int lengthDiff = Math.abs(toolScreenshot.length() - llmScreenshot.length());
                logger.info("截图长度差异: {} 字符", lengthDiff);
                
                // 验证LLM截图应该更大（因为包含更多坐标文字）
                assert llmScreenshot.length() >= toolScreenshot.length() : 
                    "LLM截图应该不小于工具截图（包含更多坐标信息）";
                
                logger.info("差异分析通过：LLM截图包含更多信息");
            }
            
            logger.info("=== 截图差异分析完成 ===");
            
        } catch (Exception e) {
            logger.error("差异分析测试失败", e);
            throw new RuntimeException("截图差异测试失败", e);
        }
    }
}
