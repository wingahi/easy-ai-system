# -*- coding: utf-8 -*-
"""
离线召回率检测脚本（零依赖，仅用标准库）

口径：Recall@K = |检索结果 ∩ 标注相关 chunk| / |标注相关 chunk|
     分母是标注的相关 chunk 数，不是 topK（topK 作分母只是槽位填充率）。

标注来源（三选一）：
  1. 默认 --from-corpus：扫描知识库 chunk，用正则从 chunk 文本提取「问N：...？」作为评测查询，
     该 chunk 即为标注相关 chunk（适用于 qa-1000 这类 FAQ 语料，无需 PDF/第三方库）
  2. --queries file.txt：每行一个查询；相关 chunk = 语料中包含该查询核心文本（去标点）的全部 chunk
  3. --gt-json file.json：[{"queryText":"...","relevantChunks":["fileId:chunkIndex",...]}]，完全人工标注

用法：
  python3 eval_recall.py --kb 2 --k 5
  python3 eval_recall.py --kb 2 --k 5 --limit 10 --hybrid
  python3 eval_recall.py --kb 2 --queries my_queries.txt --save-gt   # 同时把标注写入 wgh_kb_ground_truth
"""
import argparse, json, math, re, sys, time, urllib.parse, urllib.request

BASE = "http://localhost:8080"


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


def main():
    ap = argparse.ArgumentParser(description="离线 Recall@K 检测")
    ap.add_argument("--kb", type=int, default=2, help="知识库 ID（默认 2）")
    ap.add_argument("--k", type=int, default=5, help="Recall@K 的 K（默认 5）")
    ap.add_argument("--limit", type=int, default=40, help="最多评测查询数（默认 40，0=全部）")
    ap.add_argument("--step", type=int, default=25, help="corpus 模式抽样步长（默认 25）")
    ap.add_argument("--pattern", default=r"问\d+[：:][^？\n]+？",
                    help="corpus 模式提取查询的正则（默认匹配「问N：...？」）")
    ap.add_argument("--queries", help="查询文件（每行一个查询），提供后不用 corpus 提取")
    ap.add_argument("--gt-json", help="人工标注 JSON：[{queryText, relevantChunks}]")
    ap.add_argument("--hybrid", action="store_true", help="开启混合检索（向量+关键词 RRF）")
    ap.add_argument("--save-gt", action="store_true", help="把标注写入 wgh_kb_ground_truth（在线 evaluate 可复用）")
    args = ap.parse_args()

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

    # 逐条检索并计算指标
    recall_sum = precision_sum = hit_sum = mrr_sum = ndcg_sum = 0.0
    per_query = []
    t0 = time.time()
    for i, it in enumerate(items):
        data = search(token, args.kb, it["query"], args.k, args.hybrid)
        results = data.get("results", []) or []
        retrieved = [chunk_key(r) for r in results[:args.k] if chunk_key(r)]
        hits = it["relevant"] & set(retrieved)

        recall = len(hits) / len(it["relevant"])              # 分母 = 标注相关 chunk 数
        precision = len(hits) / len(retrieved) if retrieved else 0.0
        rel = [1 if k in it["relevant"] else 0 for k in retrieved]
        rr = next((1.0 / (idx + 1) for idx, r in enumerate(rel) if r), 0.0)
        dcg = sum(r / math.log2(idx + 2) for idx, r in enumerate(rel))
        ideal = sum(1 / math.log2(idx + 2) for idx in range(min(sum(rel) or 1, args.k)))

        recall_sum += recall
        precision_sum += precision
        hit_sum += 1 if hits else 0
        mrr_sum += rr
        ndcg_sum += dcg / ideal if ideal else 0.0
        if recall < 1.0:
            per_query.append(f"  未全召回: {it['query'][:30]} recall={recall:.2f} 检索到{retrieved} 期望{sorted(it['relevant'])}")
        if (i + 1) % 10 == 0:
            print(f"  {i+1}/{len(items)} ...")

    n = len(items) or 1
    print(f"\n耗时 {time.time()-t0:.0f}s，K={args.k}，hybrid={args.hybrid}")
    print(f"Recall@{args.k}（chunk 级，分母=标注相关 chunk 数）: {recall_sum/n:.3f}")
    print(f"Precision@{args.k}: {precision_sum/n:.3f}")
    print(f"命中率（topK 至少 1 条相关）: {hit_sum/n:.1%}")
    print(f"MRR@{args.k}: {mrr_sum/n:.3f}")
    print(f"NDCG@{args.k}: {ndcg_sum/n:.3f}")
    if per_query:
        print("\n未全召回明细：")
        print("\n".join(per_query[:20]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
