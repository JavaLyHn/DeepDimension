package com.lyhn.deepdimension.service;

import com.lyhn.deepdimension.entity.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DynamicTopKSelectorTest {

    private DynamicTopKSelector selector;

    @BeforeEach
    void setUp() {
        selector = new DynamicTopKSelector();
    }

    private SearchResult makeResult(String fileMd5, int chunkId, String text, double score) {
        return new SearchResult(fileMd5, chunkId, text, score, "user1", "tag1", true, "file_" + fileMd5 + ".txt");
    }

    private List<SearchResult> buildResults(double... scores) {
        List<SearchResult> list = new ArrayList<>();
        for (int i = 0; i < scores.length; i++) {
            list.add(makeResult("md5_" + i, i, "内容片段" + i, scores[i]));
        }
        return list;
    }

    @Nested
    @DisplayName("边界条件测试")
    class BoundaryTests {

        @Test
        @DisplayName("空列表返回空结果")
        void emptyList() {
            List<SearchResult> result = selector.select(Collections.emptyList());
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("null列表返回空结果")
        void nullList() {
            List<SearchResult> result = selector.select(null);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("单条结果直接返回")
        void singleResult() {
            List<SearchResult> input = buildResults(0.9);
            List<SearchResult> result = selector.select(input);
            assertEquals(1, result.size());
            assertEquals(0.9, result.get(0).getScore());
        }

        @Test
        @DisplayName("结果数量 <= minK 时全部返回")
        void resultsLessThanMinK() {
            List<SearchResult> input = buildResults(0.5, 0.3);
            List<SearchResult> result = selector.select(input, 3, 10);
            assertEquals(2, result.size());
        }
    }

    @Nested
    @DisplayName("肘部法则测试")
    class ElbowTests {

        @Test
        @DisplayName("明显肘部点：前3个高分，后面急剧下降")
        void clearElbowPoint() {
            List<SearchResult> input = buildResults(0.95, 0.90, 0.88, 0.45, 0.42, 0.40, 0.38);
            List<SearchResult> result = selector.select(input, 1, 10);
            assertTrue(result.size() <= 4, "应在肘部点截断，保留前3-4个高分结果，实际: " + result.size());
            assertTrue(result.size() >= 1, "至少保留1个结果");
            assertEquals(0.95, result.get(0).getScore(), "第一个结果应为最高分");
        }

        @Test
        @DisplayName("均匀分布：无明显肘部点，返回较多结果")
        void uniformDistribution() {
            List<SearchResult> input = buildResults(0.80, 0.78, 0.76, 0.74, 0.72, 0.70, 0.68, 0.66);
            List<SearchResult> result = selector.select(input, 1, 10);
            assertTrue(result.size() >= 3, "均匀分布时应保留较多结果，实际: " + result.size());
        }

        @Test
        @DisplayName("极端肘部：只有1个高分，其余极低")
        void extremeElbow() {
            List<SearchResult> input = buildResults(0.95, 0.20, 0.18, 0.15, 0.12);
            List<SearchResult> result = selector.select(input, 1, 10);
            assertTrue(result.size() <= 2, "极端肘部应只保留1-2个结果，实际: " + result.size());
        }

        @Test
        @DisplayName("两极分化：前几个高分，中间断崖，后几个中等分")
        void bimodalDistribution() {
            List<SearchResult> input = buildResults(0.95, 0.92, 0.90, 0.40, 0.38, 0.65, 0.62);
            List<SearchResult> result = selector.select(input, 1, 10);
            assertTrue(result.size() <= 4, "两极分化应在断崖处截断，实际: " + result.size());
        }
    }

    @Nested
    @DisplayName("分数阈值测试")
    class ThresholdTests {

        @Test
        @DisplayName("所有分数低于阈值时，仅保留 minK 个")
        void allBelowThreshold() {
            List<SearchResult> input = buildResults(0.25, 0.20, 0.15, 0.10);
            List<SearchResult> result = selector.select(input, 1, 10, 0.3, 1.0);
            assertEquals(1, result.size(), "所有分数低于阈值时应只保留 minK 个");
        }

        @Test
        @DisplayName("部分分数低于阈值时截断")
        void partialBelowThreshold() {
            List<SearchResult> input = buildResults(0.90, 0.80, 0.70, 0.25, 0.20);
            List<SearchResult> result = selector.select(input, 1, 10, 0.3, 1.0);
            assertTrue(result.size() <= 3, "低于阈值的应被截断，实际: " + result.size());
        }
    }

    @Nested
    @DisplayName("maxK 限制测试")
    class MaxKTests {

        @Test
        @DisplayName("结果数量超过 maxK 时截断到 maxK")
        void exceedMaxK() {
            List<SearchResult> input = buildResults(0.95, 0.93, 0.91, 0.89, 0.87, 0.85, 0.83, 0.81);
            List<SearchResult> result = selector.select(input, 1, 3);
            assertTrue(result.size() <= 3, "不应超过 maxK，实际: " + result.size());
            assertTrue(result.size() >= 1, "至少保留1个结果");
        }

        @Test
        @DisplayName("maxK=1 时只返回1个最高分结果")
        void maxKEqualsOne() {
            List<SearchResult> input = buildResults(0.95, 0.90, 0.85);
            List<SearchResult> result = selector.select(input, 1, 1);
            assertEquals(1, result.size());
            assertEquals(0.95, result.get(0).getScore());
        }
    }

    @Nested
    @DisplayName("排序验证测试")
    class SortingTests {

        @Test
        @DisplayName("输入无序时，输出应按分数降序排列")
        void unsortedInput() {
            List<SearchResult> input = buildResults(0.50, 0.95, 0.30, 0.80, 0.60);
            List<SearchResult> result = selector.select(input, 1, 10);
            for (int i = 1; i < result.size(); i++) {
                assertTrue(result.get(i - 1).getScore() >= result.get(i).getScore(),
                        "结果应按分数降序排列");
            }
            assertEquals(0.95, result.get(0).getScore(), "第一个应为最高分");
        }
    }

    @Nested
    @DisplayName("null 分数处理测试")
    class NullScoreTests {

        @Test
        @DisplayName("含 null 分数的结果应排在最后")
        void nullScoreHandling() {
            List<SearchResult> input = new ArrayList<>();
            input.add(makeResult("a", 1, "text1", 0.9));
            input.add(makeResult("b", 2, "text2", 0.7));
            input.add(new SearchResult("c", 3, "text3", null, "user1", "tag1", true, "file_c.txt"));

            List<SearchResult> result = selector.select(input, 1, 10);
            assertFalse(result.isEmpty());
            if (result.size() >= 2) {
                assertNotNull(result.get(0).getScore(), "高分结果不应有 null 分数");
            }
        }
    }

    @Nested
    @DisplayName("computeDynamicK 内部方法测试")
    class ComputeDynamicKTests {

        @Test
        @DisplayName("典型场景：清晰肘部 + 阈值截断")
        void typicalScenario() {
            List<SearchResult> sorted = buildResults(0.95, 0.90, 0.85, 0.40, 0.35, 0.30);
            int k = selector.computeDynamicK(sorted, 1, 10, 0.3, 1.0);
            assertTrue(k >= 1 && k <= 4, "应在肘部点或阈值处截断，k=" + k);
        }

        @Test
        @DisplayName("所有分数都很高：返回 maxK")
        void allHighScores() {
            List<SearchResult> sorted = buildResults(0.95, 0.94, 0.93, 0.92, 0.91, 0.90);
            int k = selector.computeDynamicK(sorted, 1, 4, 0.3, 1.0);
            assertEquals(4, k, "所有分数都高时应返回 maxK");
        }

        @Test
        @DisplayName("minK 兜底：即使分数很低也至少返回 minK 个")
        void minKGuarantee() {
            List<SearchResult> sorted = buildResults(0.10, 0.08, 0.05);
            int k = selector.computeDynamicK(sorted, 2, 10, 0.3, 1.0);
            assertEquals(2, k, "分数很低时也应保证 minK");
        }
    }

    @Nested
    @DisplayName("过召回计算测试")
    class OverRecallTests {

        @Test
        @DisplayName("默认过召回因子")
        void defaultOverRecall() {
            int recallK = selector.computeOverRecallTopK(5);
            assertEquals(15, recallK);
        }

        @Test
        @DisplayName("自定义过召回因子")
        void customOverRecall() {
            int recallK = selector.computeOverRecallTopK(5, 5);
            assertEquals(25, recallK);
        }
    }

    @Nested
    @DisplayName("端到端场景模拟测试")
    class EndToEndTests {

        @Test
        @DisplayName("场景1：用户查询高度相关，召回多个高分片段")
        void scenarioHighRelevance() {
            List<SearchResult> rawResults = buildResults(
                    0.95, 0.92, 0.89, 0.85, 0.82,
                    0.78, 0.75, 0.72, 0.68, 0.65,
                    0.60, 0.55, 0.50, 0.45, 0.40
            );
            List<SearchResult> selected = selector.select(rawResults, 1, 10);
            assertTrue(selected.size() >= 3, "高相关场景应保留足够多片段，实际: " + selected.size());
            assertTrue(selected.size() <= 10, "不应超过 maxK");
            System.out.println("场景1 - 高相关: 过召回 " + rawResults.size() + " 条 -> 筛选 " + selected.size() + " 条");
            selected.forEach(r -> System.out.printf("  score=%.4f file=%s%n", r.getScore(), r.getFileName()));
        }

        @Test
        @DisplayName("场景2：用户查询模糊，召回分数普遍较低")
        void scenarioLowRelevance() {
            List<SearchResult> rawResults = buildResults(
                    0.35, 0.32, 0.30, 0.28, 0.25,
                    0.22, 0.20, 0.18, 0.15, 0.12,
                    0.10, 0.08, 0.05, 0.03, 0.01
            );
            List<SearchResult> selected = selector.select(rawResults, 1, 10, 0.3, 1.0);
            assertTrue(selected.size() >= 1, "低相关场景至少保留1个片段");
            assertTrue(selected.size() <= 5, "低相关场景应大幅截断减少噪声，实际: " + selected.size());
            System.out.println("场景2 - 低相关: 过召回 " + rawResults.size() + " 条 -> 筛选 " + selected.size() + " 条");
            selected.forEach(r -> System.out.printf("  score=%.4f file=%s%n", r.getScore(), r.getFileName()));
        }

        @Test
        @DisplayName("场景3：混合质量，前几个非常相关，后面断崖式下降")
        void scenarioMixedQuality() {
            List<SearchResult> rawResults = buildResults(
                    0.96, 0.94, 0.91,
                    0.42, 0.40, 0.38,
                    0.35, 0.33, 0.30,
                    0.28, 0.25, 0.20,
                    0.15, 0.10, 0.05
            );
            List<SearchResult> selected = selector.select(rawResults, 1, 10);
            assertTrue(selected.size() <= 6, "混合质量应在断崖处截断，实际: " + selected.size());
            assertTrue(selected.get(0).getScore() > 0.9, "最高分结果应保留");
            System.out.println("场景3 - 混合质量: 过召回 " + rawResults.size() + " 条 -> 筛选 " + selected.size() + " 条");
            selected.forEach(r -> System.out.printf("  score=%.4f file=%s%n", r.getScore(), r.getFileName()));
        }

        @Test
        @DisplayName("场景4：所有片段都高度相关（如查询精确匹配）")
        void scenarioAllHighlyRelevant() {
            List<SearchResult> rawResults = buildResults(
                    0.98, 0.97, 0.96, 0.95, 0.94,
                    0.93, 0.92, 0.91, 0.90, 0.89,
                    0.88, 0.87, 0.86, 0.85, 0.84
            );
            List<SearchResult> selected = selector.select(rawResults, 1, 10);
            assertEquals(10, selected.size(), "所有片段都高度相关时应返回 maxK");
            System.out.println("场景4 - 全部高相关: 过召回 " + rawResults.size() + " 条 -> 筛选 " + selected.size() + " 条");
        }

        @Test
        @DisplayName("场景5：仅1个片段相关，其余完全不相关")
        void scenarioOnlyOneRelevant() {
            List<SearchResult> rawResults = buildResults(
                    0.92, 0.15, 0.12, 0.10, 0.08,
                    0.06, 0.05, 0.04, 0.03, 0.02,
                    0.02, 0.01, 0.01, 0.01, 0.01
            );
            List<SearchResult> selected = selector.select(rawResults, 1, 10);
            assertTrue(selected.size() <= 3, "仅1个相关时应大幅截断，实际: " + selected.size());
            System.out.println("场景5 - 仅1个相关: 过召回 " + rawResults.size() + " 条 -> 筛选 " + selected.size() + " 条");
            selected.forEach(r -> System.out.printf("  score=%.4f file=%s%n", r.getScore(), r.getFileName()));
        }

        @Test
        @DisplayName("Token 节省率估算")
        void tokenSavingsEstimation() {
            List<SearchResult> rawResults = buildResults(
                    0.95, 0.90, 0.85, 0.42, 0.38,
                    0.35, 0.30, 0.25, 0.20, 0.15,
                    0.12, 0.10, 0.08, 0.05, 0.02
            );
            int fixedK = 5;
            List<SearchResult> selected = selector.select(rawResults, 1, 10);

            int rawCount = rawResults.size();
            int fixedCount = fixedK;
            int dynamicCount = selected.size();

            double fixedSavings = (1 - (double) fixedCount / rawCount) * 100;
            double dynamicSavings = (1 - (double) dynamicCount / rawCount) * 100;

            System.out.println("Token 节省率对比:");
            System.out.printf("  过召回数量: %d 条%n", rawCount);
            System.out.printf("  固定 Top-%d 策略: 保留 %d 条, 节省 %.1f%%%n", fixedK, fixedCount, fixedSavings);
            System.out.printf("  Dynamic Top-K 策略: 保留 %d 条, 节省 %.1f%%%n", dynamicCount, dynamicSavings);
            System.out.printf("  Dynamic 相比固定策略额外节省: %.1f%%%n",
                    (1 - (double) dynamicCount / fixedCount) * 100);

            assertTrue(dynamicCount <= rawCount, "Dynamic Top-K 不应超过过召回数量");
        }
    }
}
