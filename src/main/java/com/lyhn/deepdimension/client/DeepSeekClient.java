package com.lyhn.deepdimension.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyhn.deepdimension.config.properties.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.function.Consumer;


@Service
public class DeepSeekClient {
    private final WebClient webClient;
    private final String apiKey;
    private final String model;
    private final AiProperties aiProperties;
    private static final Logger logger = LoggerFactory.getLogger(DeepSeekClient.class);

    public DeepSeekClient(@Value("${deepseek.api.url}") String apiUrl,
                          @Value("${deepseek.api.key}") String apiKey,
                          @Value("${deepseek.api.model}") String model,
                          AiProperties aiProperties) {
        WebClient.Builder webClientBuilder = WebClient.builder().baseUrl(apiUrl);
        if (apiKey != null && !apiKey.isEmpty()) {
            webClientBuilder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        this.webClient = webClientBuilder.build();
        this.apiKey = apiKey;
        this.model = model;
        this.aiProperties = aiProperties;
    }

    public void streamResponse(String userMessage,
                               String context,
                               List<Map<String, String>> history,
                               Consumer<String> onChunk,
                               Consumer<Throwable> onError) {
        Map<String, Object> request = buildRequest(userMessage, context, history);

        webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .subscribe(
                        chunk -> processChunk(chunk, onChunk),
                        onError
                );
    }

    // 构建发送给 DeepSeek API 的 HTTP 请求体 ，包含模型配置、消息列表、生成参数等信息
    private Map<String, Object> buildRequest(String userMessage,
                                             String context,
                                             List<Map<String, String>> history) {
        logger.info("构建请求，用户消息：{}，上下文长度：{}，历史消息数：{}",
                userMessage,
                context != null ? context.length() : 0,
                history != null ? history.size() : 0);
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("model", model);
        request.put("messages", buildMessages(userMessage, context, history));
        // 设置为流式响应
        request.put("stream", true);
        // 生成参数
        AiProperties.Generation gen = aiProperties.getGeneration();
        if (gen.getTemperature() != null) {
            request.put("temperature", gen.getTemperature());
        }
        if (gen.getTopP() != null) {
            request.put("top_p", gen.getTopP());
        }
        if (gen.getMaxTokens() != null) {
            request.put("max_tokens", gen.getMaxTokens());
        }
        return request;
    }

    private List<Map<String, String>> buildMessages(String userMessage,
                                                    String context,
                                                    List<Map<String, String>> history) {
        List<Map<String, String>> messages = new ArrayList<>();
        AiProperties.Prompt promptCfg = aiProperties.getPrompt();
        // 1. 构建统一的 system 指令（规则 + 参考信息）
        StringBuilder sysBuilder = new StringBuilder();
        String rules = promptCfg.getRules();
        if (rules != null) {
            sysBuilder.append(rules).append("\n\n");
        }

        String refStart = promptCfg.getRefStart() != null ? promptCfg.getRefStart() : "<<REF>>";
        String refEnd = promptCfg.getRefEnd() != null ? promptCfg.getRefEnd() : "<<END>>";
        sysBuilder.append(refStart).append("\n");

        // 添加上下文消息
        if (context != null && !context.isEmpty()) {
            sysBuilder.append(context);
        } else {
            String noResult = promptCfg.getNoResultText() != null ? promptCfg.getNoResultText() : "（本轮无检索结果）";
            sysBuilder.append(noResult).append("\n");
        }

        sysBuilder.append(refEnd);

        String systemContent = sysBuilder.toString();
        // 添加system消息到列表
        messages.add(Map.of(
                "role", "system",
                "content", systemContent
        ));
        logger.debug("添加了系统消息，长度: {}", systemContent.length());

        // 2. 追加历史消息（若有）支持多轮对话
        if (history != null && !history.isEmpty()) {
            messages.addAll(history);
        }

        // 3. 当前用户问题
        messages.add(Map.of(
                "role", "user",
                "content", userMessage
        ));

        return messages;
    }
    // 处理流式相应的分块数据，解析 JSON 格式的数据块，提取 AI 生成的内容，并通过回调函数传递给调用方
    private void processChunk(String chunk, Consumer<String> onChunk) {
        try {
            // 检查是否是结束标记
            if ("[DONE]".equals(chunk)) {
                logger.debug("对话结束");
                return;
            }

            // 直接解析 JSON
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(chunk);
            String content = node.path("choices")
                    .path(0)
                    .path("delta")
                    .path("content")
                    .asText("");

            // 如果内容不为空，调用回调函数
            if (!content.isEmpty()) {
                onChunk.accept(content);
            }
        } catch (Exception e) {
            logger.error("处理数据块时出错: {}", e.getMessage(), e);
        }
    }

    public String completeResponse(String systemPrompt, String userPrompt, List<Map<String, String>> history) {
        return completeResponse(systemPrompt, userPrompt, history, "query-rewrite");
    }

    public String completeResponse(String systemPrompt, String userPrompt,
                                    List<Map<String, String>> history, String taskType) {
        try {
            Map<String, Object> request = buildNonStreamRequest(systemPrompt, userPrompt, history, taskType);

            logger.debug("发送非流式请求到 LLM，用户提示长度: {}", userPrompt != null ? userPrompt.length() : 0);

            String responseBody = webClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();

            if (responseBody == null || responseBody.isEmpty()) {
                logger.warn("LLM 非流式响应为空");
                return null;
            }

            return parseCompleteResponse(responseBody);
        } catch (Exception e) {
            logger.error("LLM 非流式调用失败: {}", e.getMessage(), e);
            return null;
        }
    }

    private Map<String, Object> buildNonStreamRequest(String systemPrompt, String userPrompt,
                                                      List<Map<String, String>> history, String taskType) {
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("model", model);
        request.put("stream", false);

        List<Map<String, String>> messages = new ArrayList<>();

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }

        if (history != null && !history.isEmpty()) {
            messages.addAll(history);
        }

        if (userPrompt != null && !userPrompt.isBlank()) {
            messages.add(Map.of("role", "user", "content", userPrompt));
        }

        request.put("messages", messages);

        if ("cross-encoder".equals(taskType)) {
            AiProperties.CrossEncoder ceCfg = aiProperties.getCrossEncoder();
            if (ceCfg != null) {
                if (ceCfg.getTemperature() != null) {
                    request.put("temperature", ceCfg.getTemperature());
                }
                if (ceCfg.getMaxTokens() != null) {
                    request.put("max_tokens", ceCfg.getMaxTokens());
                }
            } else {
                request.put("temperature", 0.05);
                request.put("max_tokens", 512);
            }
        } else {
            AiProperties.QueryRewrite rewriteCfg = aiProperties.getQueryRewrite();
            if (rewriteCfg != null) {
                if (rewriteCfg.getTemperature() != null) {
                    request.put("temperature", rewriteCfg.getTemperature());
                }
                if (rewriteCfg.getMaxTokens() != null) {
                    request.put("max_tokens", rewriteCfg.getMaxTokens());
                }
            } else {
                request.put("temperature", 0.1);
                request.put("max_tokens", 256);
            }
        }

        return request;
    }

    private String parseCompleteResponse(String responseBody) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(responseBody);

            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode firstChoice = choices.get(0);
                String content = firstChoice.path("message").path("content").asText("");
                if (!content.isEmpty()) {
                    logger.debug("解析 LLM 响应成功，内容长度: {}", content.length());
                    return content;
                }
            }

            logger.warn("LLM 响应格式异常，无法提取内容: {}", responseBody.substring(0, Math.min(200, responseBody.length())));
            return null;
        } catch (Exception e) {
            logger.error("解析 LLM 非流式响应失败: {}", e.getMessage());
            return null;
        }
    }
}
