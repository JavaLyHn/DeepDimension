package com.lyhn.deepdimension.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyhn.deepdimension.client.DeepSeekClient;
import com.lyhn.deepdimension.config.properties.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class QueryRewriteService {

    private static final Logger logger = LoggerFactory.getLogger(QueryRewriteService.class);

    private static final int HISTORY_WINDOW = 6;

    private static final Pattern PRONOUN_PATTERN = Pattern.compile(
            "[它她他这那]个?|[它她他]们|前者|后者|其[中内]|以上|以下|该|此|本|啥|怎|多(少|大|长)",
            Pattern.UNICODE_CASE
    );

    @Autowired
    private DeepSeekClient deepSeekClient;

    @Autowired
    private AiProperties aiProperties;

    public String rewrite(String userQuery, List<Map<String, String>> history) {
        if (userQuery == null || userQuery.isBlank()) {
            logger.debug("查询为空，跳过重写");
            return userQuery;
        }

        if (history == null || history.isEmpty()) {
            logger.debug("无对话历史（首轮），跳过重写");
            return userQuery;
        }

        if (!needsRewrite(userQuery)) {
            logger.debug("查询不含指代词或省略，无需重写: {}", userQuery);
            return userQuery;
        }

        try {
            List<Map<String, String>> recentHistory = extractRecentHistory(history);
            String rewritten = callLLMForRewrite(userQuery, recentHistory);

            if (rewritten != null && !rewritten.isBlank() && !rewritten.equals(userQuery)) {
                logger.info("查询重写成功: '{}' -> '{}'", userQuery, rewritten);
                return rewritten.trim();
            }

            logger.debug("LLM 返回无效重写结果，使用原始查询");
            return userQuery;
        } catch (Exception e) {
            logger.error("查询重写失败，降级为原始查询: {}", e.getMessage());
            return userQuery;
        }
    }

    public boolean needsRewrite(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        Matcher matcher = PRONOUN_PATTERN.matcher(query);
        if (matcher.find()) {
            logger.debug("检测到指代词/省略表达: '{}'", matcher.group());
            return true;
        }

        if (isElliptical(query)) {
            logger.debug("检测到省略/口语化表达: {}", query);
            return true;
        }

        return false;
    }

    private boolean isElliptical(String query) {
        String trimmed = query.trim();
        int length = trimmed.length();

        if (length <= 3) {
            char lastChar = trimmed.charAt(trimmed.length() - 1);
            return lastChar == '？' || lastChar == '?' || lastChar == '呢' || lastChar == '吗'
                    || lastChar == '啊' || lastChar == '吧' || lastChar == '嘛';
        }

        Pattern[] ellipticalPatterns = {
                Pattern.compile("^[它她它这那].*?[呢吗啊吧嘛？?]$"),
                Pattern.compile("^(怎么|如何|哪个|多少|哪里|为啥)(.*)?$"),
                Pattern.compile("^(那|然后|还有|另外|所以).{0,4}[呢吗啊？?]?$"),
                Pattern.compile("^(关于|对于).*?(呢|吗|\\?)$")
        };

        for (Pattern p : ellipticalPatterns) {
            if (p.matcher(trimmed).find()) {
                Matcher shortMatcher = p.matcher(trimmed);
                if (shortMatcher.find() && shortMatcher.group(1) != null) {
                    String afterKeyword = shortMatcher.group(1).trim();
                    if (afterKeyword.length() <= 6) {
                        return true;
                    }
                } else {
                    return true;
                }
            }
        }

        return false;
    }

    private List<Map<String, String>> extractRecentHistory(List<Map<String, String>> history) {
        int windowSize = Math.min(HISTORY_WINDOW * 2, history.size());

        if (windowSize >= history.size()) {
            return new ArrayList<>(history);
        }

        return new ArrayList<>(history.subList(history.size() - windowSize, history.size()));
    }

    private String callLLMForRewrite(String userQuery, List<Map<String, String>> recentHistory) {
        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(userQuery, recentHistory);

        logger.debug("调用 LLM 进行查询重写, 原始查询: {}, 历史轮数: {}", userQuery, recentHistory.size() / 2);

        String response = deepSeekClient.completeResponse(systemPrompt, userPrompt, null);

        if (response == null || response.isBlank()) {
            logger.warn("LLM 查询重写返回空响应");
            return null;
        }

        return extractRewrittenQuery(response);
    }

    private String buildSystemPrompt() {
        AiProperties.QueryRewrite rewriteCfg = aiProperties.getQueryRewrite();
        if (rewriteCfg != null && rewriteCfg.getSystemPrompt() != null) {
            return rewriteCfg.getSystemPrompt();
        }

        return "你是一个专业的查询改写助手。你的任务是根据对话历史，将用户的查询进行改写，使其成为一个完整、独立、明确的查询。\n" +
                "\n" +
                "改写规则：\n" +
                "1. **指代消解**：将\"它\"、\"这个\"、\"那个\"、\"前者\"、\"后者\"、\"它们\"等代词替换为对话历史中的具体实体名称。\n" +
                "2. **语义补全**：将省略的、不完整的查询补充为完整的语义表达。例如\"那价格呢？\"应补全主语。\n" +
                "3. **保持原意**：不要改变用户查询的核心意图和语义方向。\n" +
                "4. **简洁输出**：只输出改写后的查询文本，不要添加任何解释、前缀或后缀。\n" +
                "5. **无需改写时原样返回**：如果查询已经足够明确和完整，直接返回原始查询。\n" +
                "6. **仅用中文作答**。";
    }

    private String buildUserPrompt(String userQuery, List<Map<String, String>> recentHistory) {
        StringBuilder sb = new StringBuilder();
        sb.append("【对话历史】\n");

        for (Map<String, String> msg : recentHistory) {
            String role = msg.getOrDefault("role", "unknown");
            String content = msg.getOrDefault("content", "");
            String roleLabel = "user".equals(role) ? "用户" : "assistant".equals(role) ? "助手" : role;
            sb.append(roleLabel).append(": ").append(content).append("\n");
        }

        sb.append("\n【当前用户查询】\n");
        sb.append(userQuery);

        sb.append("\n\n请根据上述对话历史，对【当前用户查询】进行改写，使其成为独立完整的查询。只输出改写后的结果:");

        return sb.toString();
    }

    private String extractRewrittenQuery(String llmResponse) {
        String cleaned = llmResponse.trim();

        Pattern[] patterns = {
                Pattern.compile("\"([^\"]+)\"", Pattern.CASE_INSENSITIVE),
                Pattern.compile("'([^']+)'"),
                Pattern.compile("「([^」]+)」"),
                Pattern.compile("【([^】]+)】"),
                Pattern.compile("改写[后为][：:]\\s*(.+)", Pattern.CASE_INSENSITIVE),
                Pattern.compile("重写[后为][：:]\\s*(.+)", Pattern.CASE_INSENSITIVE),
                Pattern.compile("结果[是为][：:]\\s*(.+)", Pattern.CASE_INSENSITIVE),
                Pattern.compile("(?:output|result)[是为][：:]\\s*(.+)", Pattern.CASE_INSENSITIVE)
        };

        for (Pattern p : patterns) {
            Matcher m = p.matcher(cleaned);
            if (m.find()) {
                String extracted = m.group(1).trim();
                if (!extracted.isEmpty()) {
                    logger.debug("从 LLM 响应中提取到改写结果: '{}'", extracted);
                    return extracted;
                }
            }
        }

        String[] prefixes = {"改写后：", "改写后:", "重写后：", "重写后:", "结果：", "结果:",
                "Rewritten:", "Result:", "Output:", "查询:", "答案:"};

        for (String prefix : prefixes) {
            if (cleaned.startsWith(prefix)) {
                String extracted = cleaned.substring(prefix.length()).trim();
                if (!extracted.isEmpty()) {
                    return extracted;
                }
            }
        }

        if (cleaned.length() > 200) {
            logger.warn("LLM 返回内容过长({}字符)，可能包含解释文本，尝试截取首行", cleaned.length());
            String firstLine = cleaned.split("\\r?\\n")[0].trim();
            if (!firstLine.isEmpty() && firstLine.length() <= 100) {
                return firstLine;
            }
        }

        return cleaned;
    }
}
