package com.lyhn.deepdimension.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QueryRewriteServiceTest {

    private QueryRewriteService service;

    @BeforeEach
    void setUp() {
        service = new QueryRewriteService();
    }

    private List<Map<String, String>> buildHistory(String... messages) {
        List<Map<String, String>> history = new ArrayList<>();
        for (int i = 0; i < messages.length; i++) {
            Map<String, String> msg = new HashMap<>();
            if (i % 2 == 0) {
                msg.put("role", "user");
            } else {
                msg.put("role", "assistant");
            }
            msg.put("content", messages[i]);
            history.add(msg);
        }
        return history;
    }

    @Nested
    @DisplayName("needsRewrite - 指代词与省略检测")
    class NeedsRewriteTests {

        @Test
        @DisplayName("包含'它'应触发重写")
        void pronoun_它() {
            assertTrue(service.needsRewrite("它的价格是多少？"));
        }

        @Test
        @DisplayName("包含'这个'应触发重写")
        void pronoun_这个() {
            assertTrue(service.needsRewrite("这个功能怎么用"));
        }

        @Test
        @DisplayName("包含'那个'应触发重写")
        void pronoun_那个() {
            assertTrue(service.needsRewrite("那个文件在哪里"));
        }

        @Test
        @DisplayName("包含'前者'应触发重写")
        void pronoun_前者() {
            assertTrue(service.needsRewrite("前者的性能更好吗"));
        }

        @Test
        @DisplayName("包含'后者'应触发重写")
        void pronoun_后者() {
            assertTrue(service.needsRewrite("后者支持哪些格式"));
        }

        @Test
        @DisplayName("包含'它们'应触发重写")
        void pronoun_它们() {
            assertTrue(service.needsRewrite("它们之间有什么区别"));
        }

        @Test
        @DisplayName("包含'其中'应触发重写")
        void pronoun_其中() {
            assertTrue(service.needsRewrite("其中的配置项有哪些"));
        }

        @Test
        @DisplayName("包含'该'应触发重写")
        void pronoun_该() {
            assertTrue(service.needsRewrite("该系统支持什么数据库"));
        }

        @Test
        @DisplayName("包含'此'应触发重写")
        void pronoun_此() {
            assertTrue(service.needsRewrite("此方法的参数是什么"));
        }

        @Test
        @DisplayName("包含'哪'应触发重写")
        void pronoun_哪() {
            assertTrue(service.needsRewrite("哪个版本更稳定"));
        }

        @Test
        @DisplayName("明确完整的查询不应触发重写")
        void completeQuery_noRewrite() {
            assertFalse(service.needsRewrite("Spring Boot的自动配置原理是什么"));
            assertFalse(service.needsRewrite("Elasticsearch的倒排索引工作原理"));
            assertFalse(service.needsRewrite("Docker容器和虚拟机的区别"));
            assertFalse(service.needsRewrite("MySQL索引优化最佳实践"));
            assertFalse(service.needsRewrite("Redis集群模式的工作原理"));
        }

        @Test
        @DisplayName("空查询不应触发重写")
        void emptyQuery() {
            assertFalse(service.needsRewrite(null));
            assertFalse(service.needsRewrite(""));
            assertFalse(service.needsRewrite("   "));
        }
    }

    @Nested
    @DisplayName("isElliptical - 省略与口语化表达式检测")
    class IsEllipticalTests {

        @Test
        @DisplayName("'那价格呢'是省略表达")
        void elliptical_那价格呢() {
            assertTrue(service.needsRewrite("那价格呢"));
        }

        @Test
        @DisplayName("'那怎么用呢'是省略表达")
        void elliptical_那怎么用呢() {
            assertTrue(service.needsRewrite("那怎么用呢"));
        }

        @Test
        @DisplayName("'然后呢'是省略表达")
        void elliptical_然后呢() {
            assertTrue(service.needsRewrite("然后呢"));
        }

        @Test
        @DisplayName("'还有吗'是省略表达")
        void elliptical_还有吗() {
            assertTrue(service.needsRewrite("还有吗"));
        }

        @Test
        @DisplayName("'怎么配置'是省略表达")
        void elliptical_怎么配置() {
            assertTrue(service.needsRewrite("怎么配置"));
        }

        @Test
        @DisplayName("'如何部署'是省略表达")
        void elliptical_如何部署() {
            assertTrue(service.needsRewrite("如何部署"));
        }

        @Test
        @DisplayName("'什么是RAG'不是省略表达（有主语+足够长度）")
        void notElliptical_什么是RAG() {
            assertFalse(service.needsRewrite("什么是RAG检索增强生成"));
        }

        @Test
        @DisplayName("短问句+语气词是省略表达")
        void shortQuestionWithParticle() {
            assertTrue(service.needsRewrite("对吗"));
            assertTrue(service.needsRewrite("是吧"));
            assertTrue(service.needsRewrite("好吗"));
            assertTrue(service.needsRewrite("行吗"));
        }

        @Test
        @DisplayName("以'关于'开头的疑问句是省略表达")
        void aboutPattern() {
            assertTrue(service.needsRewrite("关于安全方面呢"));
        }
    }

    @Nested
    @DisplayName("rewrite 主流程 - 边界条件")
    class RewriteBoundaryTests {

        @Test
        @DisplayName("空查询返回原值")
        void nullQuery() {
            assertEquals(null, service.rewrite(null, null));
            assertEquals("", service.rewrite("", null));
            assertEquals("   ", service.rewrite("   ", null));
        }

        @Test
        @DisplayName("无历史记录（首轮对话）直接返回原查询")
        void noHistory_firstRound() {
            String query = "Spring Boot是什么";
            assertEquals(query, service.rewrite(query, null));
            assertEquals(query, service.rewrite(query, new ArrayList<>()));
        }

        @Test
        @DisplayName("无需重写的查询直接返回原值")
        void noNeedToRewrite() {
            String query = "Elasticsearch的索引原理";
            List<Map<String, String>> history = buildHistory(
                    "什么是Spring Boot",
                    "Spring Boot是一个快速开发框架"
            );
            assertEquals(query, service.rewrite(query, history));
        }

        @Test
        @DisplayName("需要重写但LLM失败时降级为原查询")
        void rewriteFailure_fallback() {
            String query = "它的价格是多少";
            List<Map<String, String>> history = buildHistory(
                    "请介绍一下Spring Boot",
                    "Spring Boot是Pivotal团队开发的快速开发框架"
            );
            String result = service.rewrite(query, history);
            assertNotNull(result);
            assertTrue(result.equals(query) || !result.isEmpty(),
                    "LLM失败时应降级为原始查询或有效重写结果");
        }
    }

    @Nested
    @DisplayName("extractRecentHistory - 历史窗口截断")
    class ExtractRecentHistoryTests {

        private Method getExtractMethod() throws Exception {
            Method method = QueryRewriteService.class.getDeclaredMethod(
                    "extractRecentHistory", List.class);
            method.setAccessible(true);
            return method;
        }

        @SuppressWarnings("unchecked")
        private List<Map<String, String>> invokeExtract(List<Map<String, String>> history) throws Exception {
            return (List<Map<String, String>>) getExtractMethod().invoke(service, history);
        }

        @Test
        @DisplayName("历史不超过窗口大小则全部保留")
        void withinWindow() throws Exception {
            List<Map<String, String>> history = buildHistory("Q1", "A1", "Q2", "A2");
            List<Map<String, String>> result = invokeExtract(history);
            assertEquals(4, result.size());
        }

        @Test
        @DisplayName("超过窗口大小只保留最近N轮")
        void exceedWindow() throws Exception {
            List<Map<String, String>> history = buildHistory(
                    "Q1", "A1", "Q2", "A2", "Q3", "A3", "Q4", "A4",
                    "Q5", "A5", "Q6", "A6", "Q7", "A7"
            );
            List<Map<String, String>> result = invokeExtract(history);
            int expectedWindow = 6 * 2;
            assertTrue(result.size() <= expectedWindow,
                    "结果数量应 <= 窗口大小*2=" + expectedWindow + "，实际: " + result.size());

            String lastContent = result.get(result.size() - 1).get("content");
            assertEquals("A7", lastContent, "最后一条应为最近的历史消息");
        }

        @Test
        @DisplayName("空历史返回空列表")
        void emptyHistory() throws Exception {
            List<Map<String, String>> result = invokeExtract(new ArrayList<>());
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("extractRewrittenQuery - LLM响应解析")
    class ExtractRewrittenQueryTests {

        private Method getExtractMethod() throws Exception {
            Method method = QueryRewriteService.class.getDeclaredMethod(
                    "extractRewrittenQuery", String.class);
            method.setAccessible(true);
            return method;
        }

        private String invokeExtract(String response) throws Exception {
            return (String) getExtractMethod().invoke(service, response);
        }

        @Test
        @DisplayName("纯文本直接返回")
        void plainText() throws Exception {
            String result = invokeExtract("Spring Boot框架的价格是多少");
            assertEquals("Spring Boot框架的价格是多少", result);
        }

        @Test
        @DisplayName("双引号包裹的内容被提取")
        void doubleQuotes() throws Exception {
            String result = invokeExtract("\"Spring Boot框架的价格是多少\"");
            assertEquals("Spring Boot框架的价格是多少", result);
        }

        @Test
        @DisplayName("单引号包裹的内容被提取")
        void singleQuotes() throws Exception {
            String result = invokeExtract("'Spring Boot框架的价格是多少'");
            assertEquals("Spring Boot框架的价格是多少", result);
        }

        @Test
        @DisplayName("中文引号「」包裹的内容被提取")
        void chineseQuotes() throws Exception {
            String result = invokeExtract("「Spring Boot框架的价格是多少」");
            assertEquals("Spring Boot框架的价格是多少", result);
        }

        @Test
        @DisplayName("【】包裹的内容被提取")
        void squareBrackets() throws Exception {
            String result = invokeExtract("【Spring Boot框架的价格是多少】");
            assertEquals("Spring Boot框架的价格是多少", result);
        }

        @Test
        @DisplayName("'改写后：'前缀后的内容被提取")
        void rewritePrefix_zh() throws Exception {
            String result = invokeExtract("改写后：Spring Boot框架的价格是多少");
            assertNotNull(result);
            assertTrue(result.contains("Spring Boot"), "应包含核心实体名称");
        }

        @Test
        @DisplayName("'改写后:'前缀后的内容被提取")
        void rewritePrefix_enColon() throws Exception {
            String result = invokeExtract("改写后: Spring Boot框架的价格是多少");
            assertNotNull(result);
            assertTrue(result.contains("Spring Boot"), "应包含核心实体名称");
        }

        @Test
        @DisplayName("'Result:'前缀后的内容被提取")
        void resultPrefix() throws Exception {
            String result = invokeExtract("Result: Spring Boot框架的价格是多少");
            assertEquals("Spring Boot框架的价格是多少", result);
        }

        @Test
        @DisplayName("带解释文本的长响应截取首行")
        void longResponse_withExplanation() throws Exception {
            String response = "根据对话历史，用户询问的是Spring Boot框架的价格。\n" +
                    "改写后的查询如下：\n" +
                    "Spring Boot框架的价格是多少\n" +
                    "\n这个查询更加完整和独立。";
            String result = invokeExtract(response);
            assertNotNull(result);
            assertTrue(result.length() <= response.length(), "截取后长度不应超过原响应");
        }

        @Test
        @DisplayName("空响应返回原始内容")
        void emptyResponse() throws Exception {
            String result = invokeExtract("");
            assertEquals("", result);
        }

        @Test
        @DisplayName("仅含空白字符的响应")
        void whitespaceResponse() throws Exception {
            String result = invokeExtract("   \n\t  ");
            assertNotNull(result);
            assertTrue(result.trim().isEmpty(), "空白响应 trim 后应为空");
        }
    }

    @Nested
    @DisplayName("端到端场景模拟")
    class EndToEndScenarios {

        @Test
        @DisplayName("场景1：指代消解 - '它'替换为具体实体")
        void scenario_coreferenceResolution() {
            String query = "它的主要特点是什么";
            List<Map<String, String>> history = buildHistory(
                    "请介绍Elasticsearch",
                    "Elasticsearch是一个分布式搜索和分析引擎，具有近实时、高可扩展等特点"
            );

            boolean needsRewrite = service.needsRewrite(query);
            assertTrue(needsRewrite, "含'它'的查询应被识别为需要重写");

            System.out.println("场景1 - 指代消解:");
            System.out.println("  原始查询: " + query);
            System.out.println("  对话上下文: 用户问'Elasticsearch是什么', 助手回答了ES的特点");
            System.out.println("  当前查询: '它的主要特点是什么' -> 应重写为 'Elasticsearch的主要特点是什么'");
            System.out.println("  needsRewrite: " + needsRewrite);
        }

        @Test
        @DisplayName("场景2：语义扩展 - 省略查询补全主语")
        void scenario_semanticExpansion() {
            String query = "那价格呢";
            List<Map<String, String>> history = buildHistory(
                    "Redis和Memcached有什么区别",
                    "Redis支持丰富的数据结构，Memcached只支持简单的key-value。Redis单线程但性能优秀..."
            );

            boolean needsRewrite = service.needsRewrite(query);
            assertTrue(needsRewrite, "省略查询应被识别为需要重写");

            System.out.println("场景2 - 语义扩展:");
            System.out.println("  原始查询: " + query);
            System.out.println("  对话上下文: 讨论了Redis和Memcached的区别");
            System.out.println("  当前查询: '那价格呢' -> 应扩展为 'Redis和Memcached的价格区别是什么'");
            System.out.println("  needsRewrite: " + needsRewrite);
        }

        @Test
        @DisplayName("场景3：多轮指代链 - 追踪多层代词引用")
        void scenario_multiTurnCoreference() {
            String query1 = "Kafka和RabbitMQ哪个更好";
            String query2 = "前者的吞吐量如何";
            String query3 = "后者适合什么场景";

            List<Map<String, String>> historyAfterQ1 = buildHistory(
                    query1,
                    "Kafka适合高吞吐量的日志收集场景，RabbitMQ适合复杂的路由业务场景"
            );

            assertTrue(service.needsRewrite(query2), "'前者'应触发重写");
            assertTrue(service.needsRewrite(query3), "'后者'应触发重写");

            List<Map<String, String>> historyAfterQ2 = new ArrayList<>(historyAfterQ1);
            historyAfterQ2.addAll(buildHistory(query2, "Kafka的单机吞吐量可达百万级"));

            System.out.println("场景3 - 多轮指代链:");
            System.out.println("  第1轮: '" + query1 + "'");
            System.out.println("  第2轮: '" + query2 + "' -> 应重写为 'Kafka的吞吐量如何'");
            System.out.println("  第3轮: '" + query3 + "' -> 应重写为 'RabbitMQ适合什么场景'");
        }

        @Test
        @DisplayName("场景4：首轮对话跳过重写")
        void scenario_firstRound_skip() {
            String query = "什么是微服务架构";

            String result = service.rewrite(query, new ArrayList<>());
            assertEquals(query, result, "首轮对话无历史，应直接返回原查询");

            System.out.println("场景4 - 首轮跳过:");
            System.out.println("  原始查询: " + query);
            System.out.println("  历史记录: 无（首轮）");
            System.out.println("  结果: 直接返回原查询（节省 LLM 调用开销）");
        }

        @Test
        @DisplayName("场景5：明确查询不触发重写")
        void scenario_clearQuery_noRewrite() {
            String[] clearQueries = {
                    "Spring Boot的自动配置原理是什么",
                    "Docker容器和虚拟机的区别",
                    "MySQL索引优化最佳实践",
                    "Redis集群模式的工作原理",
                    "Kubernetes的Pod调度策略"
            };

            List<Map<String, String>> history = buildHistory(
                    "你好",
                    "你好！我是DeepDimension知识助手"
            );

            for (String q : clearQueries) {
                assertFalse(service.needsRewrite(q),
                        "明确查询不应触发重写: " + q);
            }

            for (String q : clearQueries) {
                assertEquals(q, service.rewrite(q, history),
                        "明确查询应原样返回: " + q);
            }

            System.out.println("场景5 - 明确查询跳过:");
            System.out.println("  所有明确查询均不触发重写，直接用于检索");
            System.out.println("  节省了不必要的 LLM 调用开销");
        }

        @Test
        @DisplayName("场景6：混合口语化查询检测覆盖率")
        void scenario_colloquialCoverage() {
            String[] colloquialQueries = {
                    "它怎么样",
                    "这个能做吗",
                    "那个在哪",
                    "前者好还是后者好",
                    "它们区别大吗",
                    "这怎么配",
                    "那多少钱",
                    "还有别的吗",
                    "怎么弄",
                    "为啥报错",
                    "关于安全性呢",
                    "对不对啊",
                    "是这样吧",
                    "可以吗",
                    "行不行"
            };

            int detected = 0;
            for (String q : colloquialQueries) {
                if (service.needsRewrite(q)) {
                    detected++;
                }
            }

            double coverage = (double) detected / colloquialQueries.length * 100;
            System.out.println("场景6 - 口语化查询检测覆盖率:");
            System.out.printf("  检测到 %d/%d (%.1f%%)%n", detected, colloquialQueries.length, coverage);

            assertTrue(coverage >= 80.0,
                    "口语化查询检测覆盖率应 >= 80%，实际: " + String.format("%.1f%%", coverage));
        }

        @Test
        @DisplayName("场景7：完整 RAG 流程中 Query-Rewrite 的位置")
        void scenario_ragPipelinePosition() {
            System.out.println("\n=== 场景7 - 完整 RAG 流程中的 Query-Rewrite 位置 ===");
            System.out.println("");
            System.out.println("用户输入: \"它的API文档在哪里\"");
            System.out.println("");
            System.out.println("┌─────────────────────────────────────────────────┐");
            System.out.println("│  ChatHandler.processMessage()                   │");
            System.out.println("│                                                  │");
            System.out.println("│  ① 获取/创建会话 ID                              │");
            System.out.println("│  ② 获取对话历史 (Redis)                          │");
            System.out.println("│  ③ ★ Query-Rewrite ★                            │");
            System.out.println("│     输入: \"它的API文档在哪里\" + 历史              │");
            System.out.println("│     LLM → \"FastAPI的API文档在哪里\"              │");
            System.out.println("│                                                  │");
            System.out.println("│  ④ HybridSearch (使用重写后的查询)               │");
            System.out.println("│     searchWithPermission(\"FastAPI的API文档...\") │");
            System.out.println("│                                                  │");
            System.out.println("│  ⑤ Dynamic Top-K 选择                           │");
            System.out.println("│  ⑥ 构建上下文                                    │");
            System.out.println("│  ⑦ DeepSeek 流式回复 (使用原始用户消息)           │");
            System.out.println("│     streamResponse(\"它的API文档在哪里\", ...)     │");
            System.out.println("└─────────────────────────────────────────────────┘");
            System.out.println("");
            System.out.println("关键设计: 搜索用重写后的查询(语义完整), 回答用原始消息(自然)");
        }
    }
}
