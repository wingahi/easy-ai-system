# -*- coding: utf-8 -*-
"""
离线召回率检测脚本（零依赖，仅用标准库）

口径：Recall@K = |检索结果 ∩ 标注相关 chunk| / |标注相关 chunk|
     分母是标注的相关 chunk 数，不是 topK（topK 作分母只是槽位填充率）。

运行模式：
  1. --fixture file.json：确定性离线模式，fixture 内直接给出每条查询的
     relevant（标注相关 chunk）与 retrieved（检索结果顺序），不发任何网络请求，
     供 CI 门禁使用（不依赖真实大模型 / embedding 服务）
  2. 在线模式（默认）：调用本地后端接口跑检索

在线模式标注来源（三选一）：
  1. 默认 --from-corpus：扫描知识库 chunk，用正则从 chunk 文本提取「问N：...？」作为评测查询，
     该 chunk 即为标注相关 chunk（适用于 qa-1000 这类 FAQ 语料，无需 PDF/第三方库）
  2. --queries file.txt：每行一个查询；相关 chunk = 语料中包含该查询核心文本（去标点）的全部 chunk
  3. --gt-json file.json：[{"queryText":"...","relevantChunks":["fileId:chunkIndex",...]}]，完全人工标注

门禁阈值（两种模式都生效，未指定则不校验）：
  --gate-recall / --gate-precision / --gate-mrr / --gate-ndcg / --gate-hit-rate
  也可用环境变量 EVAL_GATE_RECALL / EVAL_GATE_PRECISION / EVAL_GATE_MRR /
  EVAL_GATE_NDCG / EVAL_GATE_HIT_RATE 提供默认值。
  指标低于阈值时退出码为 1，通过为 0。

用法：
  python3 eval_recall.py --fixture fixtures/retrieval_gate.json --gate-recall 0.85
  python3 eval_recall.py --kb 2 --k 5
  python3 eval_recall.py --kb 2 --k 5 --limit 10 --hybrid
  python3 eval_recall.py --kb 2 --queries my_queries.txt --save-gt   # 同时把标注写入 wgh_kb_ground_truth

fixture 格式：
  {
    "k": 5,
    "cases": [
      {"query": "...", "relevant": ["2:0"], "retrieved": ["2:0", "2:1"]}
    ]
  }
"""
import argparse, json, math, os, re, sys, time, urllib.parse, urllib.request

BASE = "http://localhost:8080"

# 门禁参数名 -> 汇总结果中的指标键
GATE_METRICS = {
    "recall": "recallAtK",
    "precision": "precisionAtK",
    "mrr": "mrr",
    "ndcg": "ndcg",
    "hit_rate": "hitRate",
}
GATE_ENV = {
    "recall": "EVAL_GATE_RECALL",
    "precision": "EVAL_GATE_PRECISION",
    "mrr": "EVAL_GATE_MRR",
    "ndcg": "EVAL_GATE_NDCG",
    "hit_rate": "EVAL_GATE_HIT_RATE",
}


def http(url, payload=None, token=None, method=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=json.dumps(payload).encode() if payload is not None else None,
                                 headers=headers, method=method)
    return json.loads(urllib.request.urlopen(req, timeout=120).read())


def login():
    d = http(f"{BASE}/api/auth/login", {"username": "admin", "password": "admin"})
    return d["data"]["token"]


def all_chunks(token, kb_id):
    """分页拉取知识库全部 chunk"""
    chunks, page = [], 1
    while True:
        d = http(f"{BASE}/api/kb/{kb_id}/chunks?page={page}&size=500", token=token)
        data = d.get("data", {}) or {}
        batch = data.get("chunks", [])
        chunks.extend(batch)
        if not batch or len(chunks) >= data.get("total", 0):
            break
        page += 1
    return chunks


def search(token, kb_id, query, k, hybrid):
    q = urllib.parse.quote(query)
    url = f"{BASE}/api/kb/{kb_id}/search?query={q}&topK={k}"
    if hybrid:
        url += "&hybrid=true"
    d = http(url, token=token)
    return d.get("data", {}) or {}


def norm(s):
    return re.sub(r"[\s，。？?（）()：:、；;！!]", "", s or "")


def chunk_key(c):
    fid, ci = c.get("fileId"), c.get("chunkIndex")
    return f"{fid}:{ci}" if fid is not None and ci is not None else None


def build_eval_set_from_corpus(corpus, pattern, step, limit):
    """从 chunk 文本提取评测查询，标注 = 来源 chunk"""
    rx = re.compile(pattern)
    items = []
    for c in corpus:
        m = rx.search(c.get("text") or "")
        if m and chunk_key(c):
            items.append({"query": m.group(0), "relevant": {chunk_key(c)}})
    items = items[::step]
    return items[:limit] if limit else items


def build_eval_set_from_queries(corpus, queries):
    """任意查询集：相关 chunk = 语料中包含查询核心文本的全部 chunk"""
    corpus_norm = [(chunk_key(c), norm(c.get("text"))) for c in corpus]
    corpus_norm = [(k, t) for k, t in corpus_norm if k]
    items, no_match = [], []
    for q in queries:
        core = norm(q)
        relevant = {k for k, t in corpus_norm if core and core in t}
        if relevant:
            items.append({"query": q, "relevant": relevant})
        else:
            no_match.append(q)
    return items, no_match


def metrics_for_item(relevant, retrieved, k):
    """单条查询指标：Recall 分母为标注相关集合，截断到前 k 条检索结果。"""
    relevant = set(relevant or [])
    ranked = list(retrieved or [])[:k]
    hits = relevant & set(ranked)
    recall = len(hits) / len(relevant) if relevant else 0.0
    precision = len(hits) / len(ranked) if ranked else 0.0
    rel = [1 if key in relevant else 0 for key in ranked]
    rr = next((1.0 / (idx + 1) for idx, flag in enumerate(rel) if flag), 0.0)
    dcg = sum(flag / math.log2(idx + 2) for idx, flag in enumerate(rel))
    ideal = sum(1 / math.log2(idx + 2) for idx in range(min(sum(rel) or 1, k)))
    ndcg = dcg / ideal if ideal else 0.0
    return {
        "recall": recall,
        "precision": precision,
        "hit": bool(hits),
        "mrr": rr,
        "ndcg": ndcg,
        "retrieved": ranked,
        "relevant": sorted(relevant),
    }


def compute_summary(items, k):
    """聚合指标：均为全部查询的算术平均（与在线评测接口口径一致）。"""
    if not items:
        raise ValueError("评测集为空，无法计算指标")
    recall_sum = precision_sum = hit_sum = mrr_sum = ndcg_sum = 0.0
    for it in items:
        m = metrics_for_item(it.get("relevant"), it.get("retrieved"), k)
        recall_sum += m["recall"]
        precision_sum += m["precision"]
        hit_sum += 1 if m["hit"] else 0
        mrr_sum += m["mrr"]
        ndcg_sum += m["ndcg"]
    n = len(items)
    return {
        "k": k,
        "queryCount": n,
        "recallAtK": round(recall_sum / n, 4),
        "precisionAtK": round(precision_sum / n, 4),
        "hitRate": round(hit_sum / n, 4),
        "mrr": round(mrr_sum / n, 4),
        "ndcg": round(ndcg_sum / n, 4),
    }


def load_fixture(path, k_override=None):
    """读取固定 fixture，返回 (items, k)；不做任何网络调用。"""
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    raw_cases = data.get("cases") or []
    if not raw_cases:
        raise ValueError(f"fixture 缺少 cases：{path}")
    k = k_override or int(data.get("k") or 5)
    items = []
    for case in raw_cases:
        relevant = case.get("relevant") or case.get("relevantChunks")
        retrieved = case.get("retrieved") or case.get("results") or []
        if not relevant:
            raise ValueError(f"fixture 用例缺少 relevant：{case.get('query')}")
        items.append({
            "query": case.get("query") or case.get("queryText") or "",
            "relevant": set(relevant),
            "retrieved": list(retrieved),
        })
    return items, k


def parse_gates(args):
    """解析门禁阈值：命令行优先，其次环境变量；未配置的指标不校验。"""
    gates = {}
    for name in GATE_METRICS:
        raw = getattr(args, f"gate_{name}", None)
        if raw is None:
            raw = os.environ.get(GATE_ENV[name])
        if raw is not None and str(raw).strip() != "":
            gates[name] = float(raw)
    return gates


def gate_failures(summary, gates):
    """返回未达阈值的门禁项描述，空列表表示全部通过。"""
    failures = []
    for name, threshold in gates.items():
        metric = GATE_METRICS[name]
        value = summary.get(metric)
        if value is None:
            failures.append(f"{name}: 指标不可计算（阈值 {threshold}）")
        elif value < threshold:
            failures.append(f"{name}: {value} < 阈值 {threshold}")
    return failures


def run_fixture(fixture_path, k_override=None, gates=None):
    """fixture 模式：计算指标并判定门禁，返回 (summary, failures)。"""
    items, k = load_fixture(fixture_path, k_override)
    summary = compute_summary(items, k)
    summary["mode"] = "fixture"
    summary["fixture"] = fixture_path
    failures = gate_failures(summary, gates or {})
    return summary, failures


def print_summary(summary, gates, failures):
    k = summary["k"]
    print(f"\nK={k}，查询数={summary['queryCount']}，模式={summary.get('mode', 'online')}")
    print(f"Recall@{k}（chunk 级，分母=标注相关 chunk 数）: {summary['recallAtK']:.3f}")
    print(f"Precision@{k}: {summary['precisionAtK']:.3f}")
    print(f"命中率（topK 至少 1 条相关）: {summary['hitRate']:.1%}")
    print(f"MRR@{k}: {summary['mrr']:.3f}")
    print(f"NDCG@{k}: {summary['ndcg']:.3f}")
    if gates:
        for name, threshold in gates.items():
            status = "PASS" if summary.get(GATE_METRICS[name]) >= threshold else "FAIL"
            print(f"门禁 {name}: {summary.get(GATE_METRICS[name])} "
                  f"{'>=' if status == 'PASS' else '<'} {threshold} → {status}")
    if failures:
        print("门禁未通过：")
        for item in failures:
            print("  - " + item)
    else:
        print("门禁结果：PASS" if gates else "未配置门禁阈值")


def write_json_out(path, summary, gates, failures):
    if not path:
        return
    payload = dict(summary)
    payload["gates"] = gates
    payload["gateFailures"] = failures
    payload["passed"] = not failures
    with open(path, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
    print(f"指标已写入 {path}")


def run_fixture_mode(args, gates):
    try:
        summary, failures = run_fixture(args.fixture, args.k, gates)
    except (OSError, ValueError) as e:
        print(f"[ERROR] fixture 读取/解析失败：{e}")
        return 2
    print(f"fixture 模式：{args.fixture}（不访问网络、不依赖真实模型）")
    print_summary(summary, gates, failures)
    write_json_out(args.json_out, summary, gates, failures)
    return 1 if failures else 0


def main():
    ap = argparse.ArgumentParser(description="离线 Recall@K 检测与 CI 门禁")
    ap.add_argument("--kb", type=int, default=2, help="知识库 ID（在线模式，默认 2）")
    ap.add_argument("--k", type=int, default=None, help="Recall@K 的 K（默认取 fixture 的 k 或 5）")
    ap.add_argument("--limit", type=int, default=40, help="在线模式最多评测查询数（默认 40，0=全部）")
    ap.add_argument("--step", type=int, default=25, help="corpus 模式抽样步长（默认 25）")
    ap.add_argument("--pattern", default=r"问\d+[：:][^？\n]+？",
                    help="corpus 模式提取查询的正则（默认匹配「问N：...？」）")
    ap.add_argument("--queries", help="查询文件（每行一个查询），提供后不用 corpus 提取")
    ap.add_argument("--gt-json", help="人工标注 JSON：[{queryText, relevantChunks}]")
    ap.add_argument("--hybrid", action="store_true", help="开启混合检索（向量+关键词 RRF）")
    ap.add_argument("--save-gt", action="store_true", help="把标注写入 wgh_kb_ground_truth（在线 evaluate 可复用）")
    # 确定性离线门禁
    ap.add_argument("--fixture", help="固定 fixture JSON（离线确定性模式，不发网络请求）")
    ap.add_argument("--json-out", help="把指标与门禁结果写入 JSON 文件")
    ap.add_argument("--gate-recall", type=float, dest="gate_recall", help="Recall@K 门禁阈值")
    ap.add_argument("--gate-precision", type=float, dest="gate_precision", help="Precision@K 门禁阈值")
    ap.add_argument("--gate-mrr", type=float, dest="gate_mrr", help="MRR@K 门禁阈值")
    ap.add_argument("--gate-ndcg", type=float, dest="gate_ndcg", help="NDCG@K 门禁阈值")
    ap.add_argument("--gate-hit-rate", type=float, dest="gate_hit_rate", help="查询命中率门禁阈值")
    args = ap.parse_args()

    gates = parse_gates(args)
    if args.fixture:
        return run_fixture_mode(args, gates)

    k = args.k or 5
    token = login()
    print(f"扫描知识库 {args.kb} 全量 chunk ...")
    corpus = all_chunks(token, args.kb)
    print(f"共 {len(corpus)} 个 chunk")

    # 构建评测集（查询 + 标注相关集合）
    skipped = []
    if args.gt_json:
        with open(args.gt_json, encoding="utf-8") as f:
            raw = json.load(f)
        items = [{"query": x["queryText"], "relevant": set(x["relevantChunks"])} for x in raw]
    elif args.queries:
        with open(args.queries, encoding="utf-8") as f:
            queries = [l.strip() for l in f if l.strip()]
        items, skipped = build_eval_set_from_queries(corpus, queries)
        if args.limit:
            items = items[:args.limit]
    else:
        items = build_eval_set_from_corpus(corpus, args.pattern, args.step, args.limit or None)
    print(f"评测查询 {len(items)} 条" + (f"，语料未命中跳过 {len(skipped)} 条" if skipped else ""))

    # 可选：标注落库，供在线 /evaluate 复用
    if args.save_gt:
        payload = [{"queryText": it["query"], "relevantChunks": sorted(it["relevant"])} for it in items]
        d = http(f"{BASE}/api/kb/{args.kb}/ground-truth/import", payload, token)
        print(f"标注已导入 ground truth 表：{d.get('data')} 条")

    # 逐条检索（检索结果按返回顺序写入 retrieved，指标统一由 compute_summary 计算）
    t0 = time.time()
    per_query = []
    for i, it in enumerate(items):
        data = search(token, args.kb, it["query"], k, args.hybrid)
        results = data.get("results", []) or []
        it["retrieved"] = [key for key in (chunk_key(r) for r in results) if key]
        m = metrics_for_item(it["relevant"], it["retrieved"], k)
        if m["recall"] < 1.0:
            per_query.append(f"  未全召回: {it['query'][:30]} recall={m['recall']:.2f} "
                             f"检索到{m['retrieved']} 期望{m['relevant']}")
        if (i + 1) % 10 == 0:
            print(f"  {i+1}/{len(items)} ...")

    summary = compute_summary(items, k)
    summary["mode"] = "online"
    print(f"\n耗时 {time.time()-t0:.0f}s，hybrid={args.hybrid}")
    failures = gate_failures(summary, gates)
    print_summary(summary, gates, failures)
    if per_query:
        print("\n未全召回明细：")
        print("\n".join(per_query[:20]))
    write_json_out(args.json_out, summary, gates, failures)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
