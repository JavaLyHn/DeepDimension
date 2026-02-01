package com.lyhn.deepdimension.controller;

import com.lyhn.deepdimension.handler.ChatHandler;
import com.lyhn.deepdimension.handler.ChatWebSocketHandler;
import com.lyhn.deepdimension.utils.LogUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;

@Component
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController extends TextWebSocketHandler {

    private final ChatHandler chatHandler;

    public ChatController(ChatHandler chatHandler) {
        this.chatHandler = chatHandler;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String userMessage = message.getPayload();
        String userId = session.getId(); // Use session ID as userId for simplicity

        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("WEBSOCKET_CHAT");
        try {
            LogUtils.logChat(userId, session.getId(), "USER_MESSAGE", userMessage.length());
            LogUtils.logBusiness("WEBSOCKET_CHAT", userId, "处理WebSocket聊天消息: messageLength=%d", userMessage.length());

            chatHandler.processMessage(userId, userMessage, session);

            LogUtils.logUserOperation(userId, "WEBSOCKET_CHAT", "message_processing", "SUCCESS");
            monitor.end("WebSocket消息处理成功");
        } catch (Exception e) {
            LogUtils.logBusinessError("WEBSOCKET_CHAT", userId, "WebSocket消息处理失败", e);
            monitor.end("WebSocket消息处理失败: " + e.getMessage());
            throw e;
        }
    }

    /**
     * 获取WebSocket停止指令Token
     */
    @GetMapping("/websocket-token")
    public ResponseEntity<?> getWebSocketToken() {
        try {
            String cmdToken = ChatWebSocketHandler.getInternalCmdToken();

            // 检查token是否有效
            if (cmdToken == null || cmdToken.trim().isEmpty()) {
                return ResponseEntity.status(500).body(Map.of(
                        "code", 500,
                        "message", "Token生成失败",
                        "data", null
                ));
            }

            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", "获取WebSocket停止指令Token成功",
                    "data", Map.of("cmdToken", cmdToken)
            ));

        } catch (Exception e) {
            LogUtils.logBusinessError("GET_WEBSOCKET_TOKEN", "system", "获取WebSocket Token失败", e);
            return ResponseEntity.status(500).body(Map.of(
                    "code", 500,
                    "message", "服务器内部错误：" + e.getMessage(),
                    "data", null
            ));
        }
    }
}