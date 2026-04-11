package com.lyhn.deepdimension.service;

import com.lyhn.deepdimension.entity.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class DynamicTopKSelector {

    private static final Logger logger = LoggerFactory.getLogger(DynamicTopKSelector.class);

    private static final double DEFAULT_SCORE_THRESHOLD = 0.3;
    private static final double DEFAULT_ELBOW_SENSITIVITY = 1.0;
    private static final int DEFAULT_MIN_K = 1;
    private static final int DEFAULT_MAX_K = 10;
    private static final int DEFAULT_OVER_RECALL_FACTOR = 3;

    public List<SearchResult> select(List<SearchResult> results) {
        return select(results, DEFAULT_MIN_K, DEFAULT_MAX_K, DEFAULT_SCORE_THRESHOLD, DEFAULT_ELBOW_SENSITIVITY);
    }

    public List<SearchResult> select(List<SearchResult> results, int minK, int maxK) {
        return select(results, minK, maxK, DEFAULT_SCORE_THRESHOLD, DEFAULT_ELBOW_SENSITIVITY);
    }

    public List<SearchResult> select(List<SearchResult> results, int minK, int maxK,
                                     double scoreThreshold, double elbowSensitivity) {
        if (results == null || results.isEmpty()) {
            logger.debug("搜索结果为空，返回空列表");
            return Collections.emptyList();
        }

        List<SearchResult> sorted = new ArrayList<>(results);
        sorted.sort((a, b) -> {
            if (a.getScore() == null && b.getScore() == null) return 0;
            if (a.getScore() == null) return 1;
            if (b.getScore() == null) return -1;
            return Double.compare(b.getScore(), a.getScore());
        });

        int n = sorted.size();
        if (n <= minK) {
            logger.debug("结果数量 {} <= minK {}，全部返回", n, minK);
            return sorted;
        }

        int dynamicK = computeDynamicK(sorted, minK, maxK, scoreThreshold, elbowSensitivity);

        List<SearchResult> selected = sorted.subList(0, dynamicK);
        logger.info("Dynamic Top-K 选择: 输入 {} 条, 输出 {} 条 (minK={}, maxK={}, threshold={})",
                n, dynamicK, minK, maxK, scoreThreshold);

        for (int i = 0; i < dynamicK; i++) {
            SearchResult r = sorted.get(i);
            logger.debug("  [{}] score={:.4f} file={} chunk={}",
                    i + 1, r.getScore(), r.getFileName(), r.getChunkId());
        }

        return new ArrayList<>(selected);
    }

    public int computeOverRecallTopK(int desiredK) {
        return computeOverRecallTopK(desiredK, DEFAULT_OVER_RECALL_FACTOR);
    }

    public int computeOverRecallTopK(int desiredK, int factor) {
        int recallK = desiredK * factor;
        logger.debug("过召回计算: desiredK={}, factor={}, recallK={}", desiredK, factor, recallK);
        return recallK;
    }

    int computeDynamicK(List<SearchResult> sorted, int minK, int maxK,
                        double scoreThreshold, double elbowSensitivity) {
        int n = sorted.size();

        int thresholdCut = n;
        for (int i = 0; i < n; i++) {
            Double score = sorted.get(i).getScore();
            if (score != null && score < scoreThreshold) {
                thresholdCut = i;
                break;
            }
        }

        int elbowCut = findElbowPoint(sorted, elbowSensitivity);

        int dynamicK = Math.min(thresholdCut, elbowCut);
        dynamicK = Math.max(dynamicK, minK);
        dynamicK = Math.min(dynamicK, maxK);
        dynamicK = Math.min(dynamicK, n);

        logger.debug("Dynamic K 计算: thresholdCut={}, elbowCut={}, dynamicK={}",
                thresholdCut, elbowCut, dynamicK);

        return dynamicK;
    }

    int findElbowPoint(List<SearchResult> sorted, double sensitivity) {
        int n = sorted.size();
        if (n <= 2) return n;

        double[] scores = new double[n];
        for (int i = 0; i < n; i++) {
            scores[i] = sorted.get(i).getScore() != null ? sorted.get(i).getScore() : 0.0;
        }

        double[] gaps = new double[n - 1];
        for (int i = 0; i < n - 1; i++) {
            gaps[i] = scores[i] - scores[i + 1];
        }

        double meanGap = 0;
        for (double gap : gaps) {
            meanGap += gap;
        }
        meanGap /= gaps.length;

        double stdGap = 0;
        for (double gap : gaps) {
            stdGap += (gap - meanGap) * (gap - meanGap);
        }
        stdGap = Math.sqrt(stdGap / gaps.length);

        double elbowThreshold = meanGap + sensitivity * stdGap;

        for (int i = 0; i < gaps.length; i++) {
            if (gaps[i] > elbowThreshold && i > 0) {
                logger.debug("肘部点检测: 在位置 {} 发现显著间隙 (gap={:.4f} > threshold={:.4f})",
                        i + 1, gaps[i], elbowThreshold);
                return i + 1;
            }
        }

        double maxGap = 0;
        int maxGapIdx = 0;
        for (int i = 0; i < gaps.length; i++) {
            if (gaps[i] > maxGap) {
                maxGap = gaps[i];
                maxGapIdx = i;
            }
        }

        if (maxGapIdx > 0) {
            logger.debug("肘部点检测(回退): 最大间隙在位置 {} (gap={:.4f})", maxGapIdx + 1, maxGap);
            return maxGapIdx + 1;
        }

        logger.debug("肘部点检测: 未发现显著间隙，返回全部结果");
        return n;
    }
}
