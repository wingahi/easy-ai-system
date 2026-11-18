# -*- coding: utf-8 -*-
"""eval_recall.py 的指标口径与门禁判定单测（仅标准库 unittest，无网络依赖）。

运行：python3 -m unittest discover -s rag-backend/eval -p "test_*.py"
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import eval_recall  # noqa: E402

FIXTURE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures", "retrieval_gate.json")


class MetricsTest(unittest.TestCase):
    """单条指标与聚合口径"""

    def test_recall_uses_relevant_set_as_denominator(self):
        """Recall 分母是标注相关集合大小，不是 topK"""
        m = eval_recall.metrics_for_item({"1:0", "1:1"}, ["1:0", "1:7"], 5)
        self.assertAlmostEqual(m["recall"], 0.5)
        self.assertAlmostEqual(m["precision"], 0.5)
        self.assertEqual(m["mrr"], 1.0)

    def test_no_hit_scores_zero(self):
        """有标注但 topK 内无相关时 Recall/MRR/NDCG 均为 0"""
        m = eval_recall.metrics_for_item({"2:3"}, ["2:9"], 5)
        self.assertEqual(m["recall"], 0.0)
        self.assertEqual(m["mrr"], 0.0)
        self.assertEqual(m["ndcg"], 0.0)
        self.assertFalse(m["hit"])

    def test_mrr_and_ndcg_hand_calculated(self):
        """MRR/NDCG 手算校验：相关在 rank2/3 时 MRR=0.5，NDCG=(1/log2(3)+1/log2(4))/(1+1/log2(3))"""
        m = eval_recall.metrics_for_item({"1:0", "1:2"}, ["1:1", "1:0", "1:2"], 3)
        self.assertEqual(m["mrr"], 0.5)
        self.assertAlmostEqual(m["ndcg"], 0.6934, places=4)

    def test_aggregate_uses_arithmetic_mean(self):
        """聚合指标为查询级算术平均"""
        items = [
            {"relevant": {"1:0"}, "retrieved": ["1:0"]},
            {"relevant": {"2:0"}, "retrieved": ["2:1", "2:0"]},
        ]
        summary = eval_recall.compute_summary(items, 2)
        self.assertEqual(summary["queryCount"], 2)
        self.assertEqual(summary["recallAtK"], 1.0)
        self.assertEqual(summary["mrr"], 0.75)
        self.assertEqual(summary["hitRate"], 1.0)


class GateTest(unittest.TestCase):
    """门禁阈值判定：通过侧与不通过侧"""

    def test_gate_passes_when_all_metrics_meet_threshold(self):
        """指标全部达到阈值时门禁通过"""
        summary = {"recallAtK": 0.9, "precisionAtK": 0.57, "mrr": 0.77, "ndcg": 0.83, "hitRate": 1.0}
        gates = {"recall": 0.85, "precision": 0.5, "mrr": 0.7, "ndcg": 0.8, "hit_rate": 0.95}
        self.assertEqual(eval_recall.gate_failures(summary, gates), [])

    def test_gate_fails_when_any_metric_below_threshold(self):
        """任一指标低于阈值时门禁不通过，并给出未达标明细"""
        summary = {"recallAtK": 0.9, "precisionAtK": 0.4, "mrr": 0.77, "ndcg": 0.83, "hitRate": 1.0}
        gates = {"recall": 0.85, "precision": 0.5}
        failures = eval_recall.gate_failures(summary, gates)
        self.assertEqual(len(failures), 1)
        self.assertIn("precision", failures[0])

    def test_gate_reports_missing_metric(self):
        """指标缺失时按不通过处理，不静默放过"""
        failures = eval_recall.gate_failures({}, {"recall": 0.85})
        self.assertEqual(len(failures), 1)
        self.assertIn("不可计算", failures[0])


class FixtureTest(unittest.TestCase):
    """固定 fixture 离线模式：不访问网络，可用阈值触发通过与失败两侧"""

    def test_fixture_metrics_match_expected_values(self):
        """fixture 指标与期望值一致（Recall 0.90 / Precision 0.5667 / MRR 0.7667 / NDCG 0.8262）"""
        summary, failures = eval_recall.run_fixture(FIXTURE, None, {})
        self.assertEqual(summary["queryCount"], 5)
        self.assertEqual(summary["recallAtK"], 0.9)
        self.assertEqual(summary["precisionAtK"], 0.5667)
        self.assertEqual(summary["mrr"], 0.7667)
        self.assertEqual(summary["ndcg"], 0.8262)
        self.assertEqual(summary["hitRate"], 1.0)
        self.assertEqual(failures, [])

    def test_fixture_gate_passes_with_ci_thresholds(self):
        """CI 默认阈值下 fixture 门禁通过（通过侧）"""
        gates = {"recall": 0.85, "precision": 0.5, "mrr": 0.7, "ndcg": 0.8, "hit_rate": 0.95}
        _, failures = eval_recall.run_fixture(FIXTURE, None, gates)
        self.assertEqual(failures, [])

    def test_fixture_gate_fails_with_stricter_threshold(self):
        """阈值抬高到 fixture 达不到时门禁不通过（失败侧）"""
        _, failures = eval_recall.run_fixture(FIXTURE, None, {"recall": 0.95})
        self.assertEqual(len(failures), 1)
        self.assertIn("recall", failures[0])


if __name__ == "__main__":
    unittest.main()
