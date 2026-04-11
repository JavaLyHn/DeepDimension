package com.lyhn.deepdimension.service;

import com.lyhn.deepdimension.entity.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CrossEncoderRerankerTest {

    private CrossEncoderReranker reranker;

    @BeforeEach
    void setUp() {
        reranker = new CrossEncoderReranker();
    }

    private SearchResult makeResult(int chunkId, double esScore, String text) {
        return new SearchResult("file_" + chunkId, chunkId, text, esScore,
                "user1", "tag1", true, "doc" + chunkId + ".txt");
    }

    private List<SearchResult> buildCandidates(double... scores) {
        List<SearchResult> list = new ArrayList<>();
        String[] contents = {
                "Spring Boot是一个快速开发框架，基于Spring框架，支持自动配置和起步依赖。",
                "Elasticsearch是一个分布式搜索引擎，支持全文检索和分析，常用于日志分析。",
                "Kafka是一个分布式流处理平台，用于构建实时数据管道和流式应用。",
                "Redis是一个内存数据结构存储系统，可用作数据库、缓存和消息代理。",
                "Docker是一个开源容器化平台，用于打包、分发和运行应用程序。"
        };
        for (int i = 0; i < scores.length; i++) {
            int idx = i % contents.length;
            list.add(makeResult(i, scores[i], contents[idx]));
        }
        return list;
    }

    @Nested
    @DisplayName("rerank 主流程 - 边界条件")
    class RerankBoundaryTests {

        @Test
        @DisplayName("空查询返回原始列表")
        void nullQuery() {
            List<SearchResult> candidates = buildCandidates(0.9, 0.8);
            List<SearchResult> result = reranker.rerank(null, candidates);
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("空候选列表返回空结果")
        void emptyCandidates() {
            List<SearchResult> result = reranker.rerank("test", Collections.emptyList());
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("null候选列表返回空结果")
        void nullCandidates() {
            List<SearchResult> result = reranker.rerank("test", null);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("单条结果直接返回")
        void singleCandidate() {
            List<SearchResult> candidates = buildCandidates(0.9);
            List<SearchResult> result = reranker.rerank("test", candidates);
            assertEquals(1, result.size());
            assertEquals(0, result.get(0).getChunkId());
        }
    }

    @Nested
    @DisplayName("parseScores - JSON评分解析")
    class ParseScoresTests {

        private Method getParseMethod() throws Exception {
            Method m = CrossEncoderReranker.class.getDeclaredMethod(
                    "parseScores", String.class, List.class);
            m.setAccessible(true);
            return m;
        }

        @SuppressWarnings("unchecked")
        private Map<Integer, Double> invokeParse(String json, List<SearchResult> docs) throws Exception {
            return (Map<Integer, Double>) getParseMethod().invoke(reranker, json, docs);
        }

        @Test
        @DisplayName("标准JSON格式解析成功")
        void standardJsonFormat() throws Exception {
            String json = "{\"scores\": [{\"id\": 0, \"score\": 9.5}, {\"id\": 1, \"score\": 7.2}, {\"id\": 2, \"score\": 4.0}]}";
            List<SearchResult> docs = buildCandidates(0.9, 0.8, 0.7);

            Map<Integer, Double> scores = invokeParse(json, docs);

            assertEquals(3, scores.size());
            assertEquals(0.95, scores.get(0), 0.001);
            assertEquals(0.72, scores.get(1), 0.001);
            assertEquals(0.40, scores.get(2), 0.001);
        }

        @Test
        @DisplayName("纯数组格式解析成功")
        void arrayFormat() throws Exception {
            String json = "[{\"id\": 0, \"score\": 8.0}, {\"id\": 1, \"score\": 6.5}]";
            List<SearchResult> docs = buildCandidates(0.9, 0.8);

            Map<Integer, Double> scores = invokeParse(json, docs);

            assertEquals(2, scores.size());
            assertEquals(0.80, scores.get(0), 0.001);
            assertEquals(0.65, scores.get(1), 0.001);
        }

        @Test
        @DisplayName("10分制自动归一化为0-1")
        void scoreNormalization() throws Exception {
            String json = "{\"scores\": [{\"id\": 0, \"score\": 10.0}]}";
            List<SearchResult> docs = buildCandidates(0.9);

            Map<Integer, Double> scores = invokeParse(json, docs);

            assertEquals(1.0, scores.get(0), 0.001);
        }

        @Test
        @DisplayName("缺失的文档ID使用默认分0.3")
        void missingDocDefaultScore() throws Exception {
            String json = "{\"scores\": [{\"id\": 0, \"score\": 9.0}]}";
            List<SearchResult> docs = buildCandidates(0.9, 0.8);

            Map<Integer, Double> scores = invokeParse(json, docs);

            assertEquals(2, scores.size());
            assertEquals(0.90, scores.get(0), 0.001);
            assertEquals(0.30, scores.get(1), 0.001);
        }

        @Test
        @DisplayName("无效分数（负数）被跳过，缺失文档使用默认分")
        void invalidNegativeScore() throws Exception {
            String json = "{\"scores\": [{\"id\": 0, \"score\": -1.0}, {\"id\": 1, \"score\": 8.0}]}";
            List<SearchResult> docs = buildCandidates(0.9, 0.8);

            Map<Integer, Double> scores = invokeParse(json, docs);

            assertEquals(2, scores.size());
            assertNotNull(scores.get(1), "有效分数应被保留");
            assertEquals(0.80, scores.get(1), 0.001);
            assertEquals(0.30, scores.get(0), 0.001, "无效分数的文档应使用默认回退分");
        }

        @Test
        @DisplayName("无效ID（-1）被跳过，缺失文档使用默认分")
        void invalidNegativeId() throws Exception {
            String json = "{\"scores\": [{\"id\": -1, \"score\": 9.0}, {\"id\": 1, \"score\": 7.0}]}";
            List<SearchResult> docs = buildCandidates(0.9, 0.8);

            Map<Integer, Double> scores = invokeParse(json, docs);

            assertEquals(2, scores.size(), "无效ID被跳过，缺失的doc使用默认分");
            assertNotNull(scores.get(1));
        }

        @Test
        @DisplayName("无效JSON返回等分回退")
        void invalidJson_fallback() throws Exception {
            String json = "这不是JSON格式的内容";
            List<SearchResult> docs = buildCandidates(0.9, 0.8, 0.7);

            Map<Integer, Double> scores = invokeParse(json, docs);

            assertNotNull(scores);
            assertEquals(3, scores.size());
            for (Map.Entry<Integer, Double> entry : scores.entrySet()) {
                assertEquals(0.5, entry.getValue(), 0.001);
            }
        }

        @Test
        @DisplayName("null响应返回等分回退")
        void nullResponse_fallback() throws Exception {
            List<SearchResult> docs = buildCandidates(0.9, 0.8);

            Map<Integer, Double> scores = invokeParse(null, docs);

            assertNotNull(scores);
            assertEquals(2, scores.size());
            for (Map.Entry<Integer, Double> entry : scores.entrySet()) {
                assertEquals(0.5, entry.getValue(), 0.001);
            }
        }
    }

    @Nested
    @DisplayName("extractJsonFromResponse - LLM响应提取")
    class ExtractJsonTests {

        private Method getExtractMethod() throws Exception {
            Method m = CrossEncoderReranker.class.getDeclaredMethod(
                    "extractJsonFromResponse", String.class);
            m.setAccessible(true);
            return m;
        }

        private String invokeExtract(String response) throws Exception {
            return (String) getExtractMethod().invoke(reranker, response);
        }

        @Test
        @DisplayName("标准JSON对象提取")
        void standardJsonObject() throws Exception {
            String response = "{\"scores\": [{\"id\": 0, \"score\": 9.0}]}";
            String extracted = invokeExtract(response);
            assertEquals(response, extracted);
        }

        @Test
        @DisplayName("带前后文本的JSON对象提取")
        void jsonWithSurroundingText() throws Exception {
            String response = "根据分析，各文档相关性评分如下:\n{\"scores\": [{\"id\": 0, \"score\": 8.5}]}\n以上是评分结果。";
            String extracted = invokeExtract(response);
            assertNotNull(extracted);
            assertTrue(extracted.contains("scores"));
        }

        @Test
        @DisplayName("纯数组提取并包装为scores格式")
        void pureArrayExtraction() throws Exception {
            String response = "[{\"id\": 0, \"score\": 9.0}, {\"id\": 1, \"score\": 7.0}]";
            String extracted = invokeExtract(response);
            assertNotNull(extracted);
            assertTrue(extracted.contains("\"scores\""));
        }

        @Test
        @DisplayName("代码块中的JSON提取")
        void codeBlockExtraction() throws Exception {
            String response = "```json\n{\"scores\": [{\"id\": 0, \"score\": 9.0}]}\n```";
            String extracted = invokeExtract(response);
            assertNotNull(extracted);
            assertTrue(extracted.contains("scores"));
            assertFalse(extracted.contains("```"));
        }

        @Test
        @DisplayName("无JSON返回null")
        void noJson_returnsNull() throws Exception {
            String response = "这是一段纯文本，没有任何JSON内容";
            String extracted = invokeExtract(response);
            assertNull(extracted);
        }

        @Test
        @DisplayName("空字符串返回null")
        void emptyString_returnsNull() throws Exception {
            String extracted = invokeExtract("");
            assertNull(extracted);
        }
    }

    @Nested
    @DisplayName("rerank 排序与过滤逻辑")
    class RerankSortAndFilterTests {

        @Test
        @DisplayName("topN限制只重排前N条")
        void topNLimit() throws Exception {
            Method batchMethod = CrossEncoderReranker.class.getDeclaredMethod(
                    "batchScore", String.class, List.class);
            batchMethod.setAccessible(true);

            List<SearchResult> candidates = buildCandidates(0.9, 0.8, 0.7, 0.6, 0.5);
            Map<Integer, Double> mockScores = Map.of(
                    0, 0.40, 1, 0.95, 2, 0.30, 3, 0.85, 4, 0.20
            );

            reranker = new CrossEncoderReranker() {
                @Override
                Map<Integer, Double> batchScore(String q, List<SearchResult> d) {
                    return mockScores;
                }
            };

            List<SearchResult> result = reranker.rerank("test", candidates, 3);

            assertEquals(5, result.size(), "topN=3只重排前3条，剩余2条追加到末尾");
            assertEquals(1, result.get(0).getChunkId(), "最高CE分的应排第一");
            assertEquals(0, result.get(1).getChunkId(), "第二高CE分");
            assertEquals(2, result.get(2).getChunkId(), "第三高CE分");
        }

        @Test
        @DisplayName("minScore过滤低分片段")
        void minScoreFiltering() throws Exception {
            reranker = new CrossEncoderReranker() {
                @Override
                Map<Integer, Double> batchScore(String q, List<SearchResult> d) {
                    return Map.of(0, 0.90, 1, 0.15, 2, 0.80, 3, 0.05);
                }
            };

            List<SearchResult> candidates = buildCandidates(0.9, 0.8, 0.7, 0.6);
            List<SearchResult> result = reranker.rerank("test", candidates);

            assertTrue(result.stream().allMatch(r ->
                    r.getCrossEncoderScore() == null || r.getCrossEncoderScore() >= 0.1),
                    "所有结果的crossEncoderScore应 >= minScore(0.1)");
        }

        @Test
        @DisplayName("按Cross-Encoder分数降序排列")
        void descendingOrderByCEScore() throws Exception {
            reranker = new CrossEncoderReranker() {
                @Override
                Map<Integer, Double> batchScore(String q, List<SearchResult> d) {
                    return Map.of(0, 0.50, 1, 0.95, 2, 0.70, 3, 0.30);
                }
            };

            List<SearchResult> candidates = buildCandidates(0.9, 0.8, 0.7, 0.6);
            List<SearchResult> result = reranker.rerank("test", candidates);

            for (int i = 1; i < result.size(); i++) {
                if (result.get(i - 1).getCrossEncoderScore() != null && result.get(i).getCrossEncoderScore() != null) {
                    assertTrue(result.get(i - 1).getCrossEncoderScore() >= result.get(i).getCrossEncoderScore(),
                            "结果应按CE分数降序排列");
                }
            }
        }

        @Test
        @DisplayName("LLM失败时降级为原始排序")
        void llmFailure_fallbackToOriginalOrder() throws Exception {
            reranker = new CrossEncoderReranker() {
                @Override
                Map<Integer, Double> batchScore(String q, List<SearchResult> d) {
                    return null;
                }
            };

            List<SearchResult> candidates = buildCandidates(0.9, 0.8, 0.7);
            List<SearchResult> result = reranker.rerank("test", candidates);

            assertEquals(3, result.size());
            assertEquals(0, result.get(0).getChunkId());
            assertEquals(1, result.get(1).getChunkId());
            assertEquals(2, result.get(2).getChunkId());
        }

        @Test
        @DisplayName("异常时降级为原始排序")
        void exception_fallbackToOriginalOrder() throws Exception {
            reranker = new CrossEncoderReranker() {
                @Override
                Map<Integer, Double> batchScore(String q, List<SearchResult> d) {
                    throw new RuntimeException("模拟LLM调用失败");
                }
            };

            List<SearchResult> candidates = buildCandidates(0.9, 0.8);
            List<SearchResult> result = reranker.rerank("test", candidates);

            assertEquals(2, result.size());
        }
    }

    @Nested
    @DisplayName("端到端场景模拟")
    class EndToEndScenarios {

        @Test
        @DisplayName("场景1：ES排序与CE排序差异显著 - CE纠正误排")
        void scenario_ceCorrectsEsMisordering() {
            System.out.println("\n=== 场景1 - Cross-Encoder 纠正 ES 误排 ===");

            reranker = new CrossEncoderReranker() {
                @Override
                Map<Integer, Double> batchScore(String q, List<SearchResult> d) {
                    return Map.of(
                            0, 0.35,
                            1, 0.92,
                            2, 0.88,
                            3, 0.15,
                            4, 0.75
                    );
                }
            };

            List<SearchResult> candidates = new ArrayList<>();
            candidates.add(makeResult(0, 0.92, "Python基础语法教程"));
            candidates.add(makeResult(1, 0.85, "Spring Boot自动配置原理详解"));
            candidates.add(makeResult(2, 0.80, "Spring Boot Starter机制分析"));
            candidates.add(makeResult(3, 0.78, "Python装饰器高级用法"));
            candidates.add(makeResult(4, 0.72, "Elasticsearch索引原理"));

            String query = "Spring Boot自动配置是如何工作的";

            System.out.println("  查询: " + query);
            System.out.println("  ES原始排序 (by BM25+KNN):");
            for (SearchResult r : candidates) {
                System.out.printf("    [chunk%d] ES_score=%.2f  content=%s%n",
                        r.getChunkId(), r.getScore(),
                        r.getTextContent().substring(0, Math.min(30, r.getTextContent().length())));
            }

            List<SearchResult> result = reranker.rerank(query, candidates);

            System.out.println("  CE重排后:");
            for (int i = 0; i < result.size(); i++) {
                SearchResult r = result.get(i);
                System.out.printf("    [%d] CE_score=%.2f ES_score=%.2f  content=%s%n",
                        i + 1,
                        r.getCrossEncoderScore() != null ? r.getCrossEncoderScore() : 0.0,
                        r.getScore(),
                        r.getTextContent().substring(0, Math.min(30, r.getTextContent().length())));
            }

            assertEquals(1, result.get(0).getChunkId(),
                    "CE应将最相关的'Spring Boot自动配置'排在第一");
            assertEquals(2, result.get(1).getChunkId(),
                    "CE应将次相关的'Starter机制'排在第二");
        }

        @Test
        @DisplayName("场景2：专有名词精确匹配提升")
        void scenario_properNounBoosting() {
            System.out.println("\n=== 场景2 - 专有名词精确匹配 ===");

            reranker = new CrossEncoderReranker() {
                @Override
                Map<Integer, Double> batchScore(String q, List<SearchResult> d) {
                    return Map.of(
                            0, 0.45,
                            1, 0.93,
                            2, 0.60,
                            3, 0.25
                    );
                }
            };

            List<SearchResult> candidates = new ArrayList<>();
            candidates.add(makeResult(0, 0.88, "各种数据库连接池的比较分析"));
            candidates.add(makeResult(1, 0.75, "HikariCP连接池配置参数详解与性能调优指南"));
            candidates.add(makeResult(2, 0.82, "Druid连接池监控功能使用说明"));
            candidates.add(makeResult(3, 0.70, "数据库性能优化最佳实践总结"));

            String query = "HikariCP连接池如何配置";
            List<SearchResult> result = reranker.rerank(query, candidates);

            assertEquals(1, result.get(0).getChunkId(),
                    "包含专有名词'HikariCP'的文档应被CE大幅提升");
            System.out.println("  查询: " + query);
            System.out.println("  CE将含'HikariCP'专有名词的文档从第2位提升至第1位");
        }

        @Test
        @DisplayName("场景3：长尾语义关联发现")
        void scenario_longTailSemanticDiscovery() {
            System.out.println("\n=== 场景3 - 长尾语义关联发现 ===");

            reranker = new CrossEncoderReranker() {
                @Override
                Map<Integer, Double> batchScore(String q, List<SearchResult> d) {
                    return Map.of(
                            0, 0.55,
                            1, 0.30,
                            2, 0.82,
                            3, 0.68
                    );
                }
            };

            List<SearchResult> candidates = new ArrayList<>();
            candidates.add(makeResult(0, 0.90, "微服务架构设计原则与服务拆分策略"));
            candidates.add(makeResult(1, 0.85, "单体应用向微服务迁移的步骤和方法"));
            candidates.add(makeResult(2, 0.65, "Service Mesh服务网格架构介绍与Istio实战"));
            candidates.add(makeResult(3, 0.60, "容器编排技术Kubernetes核心概念"));

            String query = "服务间通信和流量管理方案";
            List<SearchResult> result = reranker.rerank(query, candidates);

            assertEquals(2, result.get(0).getChunkId(),
                    "CE应识别出Service Mesh与'流量管理'的长尾语义关联");
            System.out.println("  查询: " + query);
            System.out.println("  CE发现: 'Service Mesh'(ES排名第3) 与查询存在长尾语义关联");
            System.out.println("           提升至第1位 (CE_score=0.82)");
        }

        @Test
        @DisplayName("场景4：完整检索流水线位置示意")
        void scenario_fullPipelinePosition() {
            System.out.println("\n=== 场景4 - 完整检索流水线 ===");
            System.out.println("");
            System.out.println("用户查询: \"HikariCP连接池超时配置\"");
            System.out.println("");
            System.out.println("┌──────────────────────────────────────────────────────┐");
            System.out.println("│  Stage 1: KNN 向量召回 (topK*30=450)                  │");
            System.out.println("│     召回所有语义相关文档                                │");
            System.out.println("│                                                      │");
            System.out.println("│  Stage 2: BM25 Rescore (ES内置)                      │");
            System.out.println("│     关键词加权 → 输出 topK=15 条                     │");
            System.out.println("│                                                      │");
            System.out.println("│  Stage 3: ★ Cross-Encoder 重排 ★                    │");
            System.out.println("│     LLM对(query, doc)成对精排                         │");
            System.out.println("│     过滤低分 → 输出最终结果                           │");
            System.out.println("└──────────────────────────────────────────────────────┘");
            System.out.println("");
            System.out.println("各阶段对比:");
            System.out.println("  ┌──────────┬────────┬──────────┬─────────────┐");
            System.out.println("  │ 文档      │ ES得分 │ CE得分   │ 最终排名    │");
            System.out.println("  ├──────────┼────────┼──────────┼─────────────┤");
            System.out.println("  │ HikariCP │  0.65  │  0.95 ★  │  第1名 ↑    │");
            System.out.println("  │ Druid    │  0.88  │  0.60    │  第3名 ↓    │");
            System.out.println("  │ DBCP2    │  0.82  │  0.35 ✗  │  被过滤     │");
            System.out.println("  └──────────┴────────┴──────────┴─────────────┘");
            System.out.println("");
            System.out.println("关键价值: CE能识别ES无法捕捉的深层语义相关性");
        }

        @Test
        @DisplayName("场景5：Token节省效果估算")
        void scenario_tokenSavingsEstimation() {
            reranker = new CrossEncoderReranker() {
                @Override
                Map<Integer, Double> batchScore(String q, List<SearchResult> d) {
                    return Map.of(
                            0, 0.92, 1, 0.88, 2, 0.85, 3, 0.12, 4, 0.08,
                            5, 0.05, 6, 0.03, 7, 0.02, 8, 0.01, 9, 0.00
                    );
                }
            };

            List<SearchResult> candidates = buildCandidates(
                    0.90, 0.85, 0.80, 0.78, 0.75,
                    0.72, 0.70, 0.68, 0.65, 0.62
            );
            List<SearchResult> result = reranker.rerank("test", candidates);

            int inputCount = candidates.size();
            int outputCount = result.size();

            System.out.println("\n场景5 - Token节省效果:");
            System.out.printf("  ES输出: %d 条 → CE过滤后: %d 条%n", inputCount, outputCount);
            System.out.printf("  节省率: %.1f%%%n", (1 - (double) outputCount / inputCount) * 100);
            System.out.println("  低质量上下文被有效过滤，减少Token消耗和幻觉风险");

            assertTrue(outputCount <= inputCount, "CE过滤后数量不应超过输入");
            assertTrue(outputCount <= 5, "低分文档应被minScore阈值过滤");
        }
    }
}
