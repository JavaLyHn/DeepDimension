package com.lyhn.deepdimension.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyhn.deepdimension.client.DeepSeekClient;
import com.lyhn.deepdimension.config.properties.AiProperties;
import com.lyhn.deepdimension.entity.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CrossEncoderReranker {

    private static final Logger logger = LoggerFactory.getLogger(CrossEncoderReranker.class);

    private static final int DEFAULT_TOP_N = 20;
    private static final double DEFAULT_MIN_SCORE = 0.1;
    private static final int MAX_BATCH_SIZE = 5;

    @Autowired
    private DeepSeekClient deepSeekClient;

    @Autowired
    private AiProperties aiProperties;

    public List<SearchResult> rerank(String query, List<SearchResult> candidates) {
        return rerank(query, candidates, DEFAULT_TOP_N);
    }

    public List<SearchResult> rerank(String query, List<SearchResult> candidates, int topN) {
        if (query == null || query.isBlank()) {
            logger.debug("查询为空，跳过Cross-Encoder重排");
            return candidates;
        }
        if (candidates == null || candidates.isEmpty()) {
            logger.debug("候选结果为空，跳过Cross-Encoder重排");
            return Collections.emptyList();
        }
        if (candidates.size() <= 1) {
            logger.debug("候选结果仅1条，无需重排");
            return new ArrayList<>(candidates);
        }

        int effectiveTopN = Math.min(topN, candidates.size());
        List<SearchResult> toRerank = new ArrayList<>(candidates.subList(0, effectiveTopN));
        List<SearchResult> remaining = candidates.size() > effectiveTopN
                ? new ArrayList<>(candidates.subList(effectiveTopN, candidates.size()))
                : Collections.emptyList();

        try {
            Map<Integer, Double> scores = batchScore(query, toRerank);

            if (scores == null || scores.isEmpty()) {
                logger.warn("Cross-Encoder评分返回空，降级为原始排序");
                return candidates;
            }

            toRerank.sort((a, b) -> {
                Double scoreA = scores.getOrDefault(a.getChunkId(), 0.0);
                Double scoreB = scores.getOrDefault(b.getChunkId(), 0.0);
                return scoreB.compareTo(scoreA);
            });

            AiProperties.CrossEncoder ceCfg = aiProperties != null ? aiProperties.getCrossEncoder() : null;
            double minScore = (ceCfg != null && ceCfg.getMinScore() != null)
                    ? ceCfg.getMinScore() : DEFAULT_MIN_SCORE;

            List<SearchResult> filtered = new ArrayList<>();
            for (SearchResult r : toRerank) {
                double s = scores.getOrDefault(r.getChunkId(), 0.0);
                if (s >= minScore) {
                    r.setCrossEncoderScore(s);
                    filtered.add(r);
                } else {
                    logger.debug("过滤低分片段: chunkId={}, score={:.4f} < threshold={:.4f}",
                            r.getChunkId(), s, minScore);
                }
            }

            if (!remaining.isEmpty()) {
                filtered.addAll(remaining);
            }

            logger.info("Cross-Encoder重排完成: 输入 {} 条 -> 重排 {} 条 -> 输出 {} 条 (minScore={})",
                    candidates.size(), toRerank.size(), filtered.size(), minScore);

            for (int i = 0; i < Math.min(filtered.size(), 10); i++) {
                SearchResult r = filtered.get(i);
                double s = scores.getOrDefault(r.getChunkId(), 0.0);
                logger.debug("  [{}] CE_score={:.4f} ES_score={:.4f} file={} chunk={}",
                        i + 1, s, r.getScore(), r.getFileName(), r.getChunkId());
            }

            return filtered;
        } catch (Exception e) {
            logger.error("Cross-Encoder重排失败，降级为原始排序: {}", e.getMessage(), e);
            return candidates;
        }
    }

    Map<Integer, Double> batchScore(String query, List<SearchResult> documents) {
        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildBatchPrompt(query, documents);

        logger.debug("调用LLM进行Cross-Encoder批量评分, 文档数: {}", documents.size());

        String response = deepSeekClient.completeResponse(systemPrompt, userPrompt, null, "cross-encoder");

        if (response == null || response.isBlank()) {
            logger.warn("Cross-Encoder LLM响应为空");
            return null;
        }

        return parseScores(response, documents);
    }

    private String buildSystemPrompt() {
        AiProperties.CrossEncoder ceCfg = aiProperties.getCrossEncoder();
        if (ceCfg != null && ceCfg.getSystemPrompt() != null) {
            return ceCfg.getSystemPrompt();
        }

        return "你是一个专业的文档相关性评分专家。你的任务是对给定的(查询,文档)对进行精确的相关性评分。\n" +
                "\n" +
                "评分标准（0-10分制）：\n" +
                "- 9~10分：文档完美回答了查询，信息完整且高度相关\n" +
                "- 7~8分：文档与查询高度相关，提供了大部分所需信息\n" +
                "- 5~6分：文档部分相关，提供了一些有用信息但不够全面\n" +
                "- 3~4分：文档与查询弱相关，仅有少量边缘相关信息\n" +
                "- 0~2分：文档与查询不相关或几乎无有用信息\n" +
                "\n" +
                "评分规则：\n" +
                "1. 必须同时考虑查询意图和文档内容的语义匹配度\n" +
                "2. 专有名词、技术术语的精确匹配应给予较高分数\n" +
                "3. 长尾语义（隐含关联）也应适当考虑\n" +
                "4. 仅输出JSON格式的评分结果，不要添加任何解释\n" +
                "5. 输出格式严格遵循: {\"scores\": [{\"id\": 分块ID, \"score\": 分数}, ...]}";
    }

    private String buildBatchPrompt(String query, List<SearchResult> documents) {
        StringBuilder sb = new StringBuilder();
        sb.append("【用户查询】\n").append(query).append("\n\n");
        sb.append("【待评分文档列表】\n");

        for (int i = 0; i < documents.size(); i++) {
            SearchResult doc = documents.get(i);
            String content = doc.getTextContent();
            if (content.length() > 500) {
                content = content.substring(0, 500) + "...";
            }
            sb.append("文档#").append(doc.getChunkId()).append(":\n");
            sb.append(content).append("\n\n");
        }

        sb.append("请对上述每个文档与用户查询的相关性进行评分（0-10分），输出JSON格式。");
        return sb.toString();
    }

    private Map<Integer, Double> parseScores(String llmResponse, List<SearchResult> documents) {
        Map<Integer, Double> scores = new LinkedHashMap<>();

        if (llmResponse == null || llmResponse.isBlank()) {
            logger.warn("LLM响应为空，使用等分回退");
            return fallbackEqualScores(documents);
        }

        String jsonStr = extractJsonFromResponse(llmResponse);
        if (jsonStr == null || jsonStr.isBlank()) {
            logger.warn("无法从LLM响应中提取JSON: {}", llmResponse.substring(0, Math.min(200, llmResponse.length())));
            return fallbackEqualScores(documents);
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(jsonStr);

            JsonNode scoresArray;
            if (root.has("scores")) {
                scoresArray = root.get("scores");
            } else if (root.isArray()) {
                scoresArray = root;
            } else {
                logger.warn("LLM响应JSON缺少'scores'字段或不是数组");
                return fallbackEqualScores(documents);
            }

            if (!scoresArray.isArray()) {
                logger.warn("'scores'字段不是数组类型");
                return fallbackEqualScores(documents);
            }

            Set<Integer> scoredIds = new HashSet<>();

            for (JsonNode item : scoresArray) {
                int id = item.path("id").asInt(-1);
                double rawScore = item.path("score").asDouble(-1.0);

                if (id < 0 || rawScore < 0) {
                    continue;
                }

                double normalizedScore = rawScore / 10.0;
                scores.put(id, normalizedScore);
                scoredIds.add(id);
            }

            for (SearchResult doc : documents) {
                if (!scoredIds.contains(doc.getChunkId())) {
                    scores.put(doc.getChunkId(), 0.3);
                    logger.debug("文档 chunkId={} 未被LLM评分，使用默认分 0.3", doc.getChunkId());
                }
            }

            logger.debug("解析到 {} 个Cross-Encoder评分", scores.size());
            return scores;
        } catch (Exception e) {
            logger.error("解析Cross-Encoder评分JSON失败: {}", e.getMessage());
            return fallbackEqualScores(documents);
        }
    }

    private String extractJsonFromResponse(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }

        String trimmed = response.trim();

        Pattern jsonPattern = Pattern.compile("\\{[\\s\\S]*\"scores\"[\\s\\S]*\\}");
        Matcher matcher = jsonPattern.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group();
        }

        Pattern arrayPattern = Pattern.compile("\\[[\\s\\S]*\\]");
        matcher = arrayPattern.matcher(trimmed);
        if (matcher.find()) {
            String arrayStr = matcher.group();
            return "{\"scores\": " + arrayStr + "}";
        }

        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }

        Pattern codeBlockPattern = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```");
        matcher = codeBlockPattern.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        return null;
    }

    private Map<Integer, Double> fallbackEqualScores(List<SearchResult> documents) {
        Map<Integer, Double> fallback = new LinkedHashMap<>();
        for (SearchResult doc : documents) {
            fallback.put(doc.getChunkId(), 0.5);
        }
        logger.debug("使用等分回退策略，所有文档默认分数=0.5");
        return fallback;
    }
}
