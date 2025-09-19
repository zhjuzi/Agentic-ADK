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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Scanner;

/**
 * 多智能体Computer Use系统主入口
 *
 * @author zh_xiaoji
 */
public class ComputerUseMain {
    
    private static final Logger log = LoggerFactory.getLogger(ComputerUseMain.class);

    public static void main(String[] args) {
        log.info("启动多智能体Computer Use系统...");
        
        // 初始化多智能体系统
        MultiAgentComputerUse computerUse = new MultiAgentComputerUse();

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("请输入您的Computer Use请求 (输入 'quit' 退出): ");
                String userRequest = scanner.nextLine().trim();
                if (userRequest.isEmpty()) {
                    System.out.println("请输入有效的请求。");
                    continue;
                }
                if ("quit".equalsIgnoreCase(userRequest) || "q".equalsIgnoreCase(userRequest) ) {
                    break;
                }
                try {
                    String result = computerUse.execute(userRequest);
                    System.out.println(result);
                } catch (Exception e) {
                    log.error("处理请求时出错", e);
                    System.out.println("错误: " + e.getMessage() + "\n");
                }
            }

        } catch (Exception e) {
            log.error("系统运行出现意外错误", e);
            System.out.println("系统错误: " + e.getMessage());
        } finally {
            try {
                computerUse.shutdown();
            } catch (Exception e) {
                log.warn("清理资源时出错", e);
            }
            System.out.println("Computer Use系统已关闭。");
        }
    }
}
