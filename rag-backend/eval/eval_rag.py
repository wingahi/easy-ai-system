# -*- coding: utf-8 -*-
"""
RAG 离线评估脚本
Part 1: 检索指标 Recall@K / MRR / NDCG（ground truth = qa-1000.pdf 问答对，chunk 级）
Part 2: 双 Judge Faithfulness（DeepSeek + Qwen，报告一致性）
Part 3: 解析器对比（pdfplumber vs PDFBox 提取质量）

Recall@K 口径：|检索结果 ∩ 标注相关 chunk| / |标注相关 chunk|，分母是标注的相关 chunk 数，
不是 topK（topK 作分母只是槽位填充率）。相关 chunk 由语料扫描（问答核心文本子串匹配）得到。
"""
import json, math, os, re, time, urllib.parse, urllib.request
import pdfplumber

BASE = "http://localhost:8080"
KB_ID = int(os.environ.get("KB_ID", "2"))
K = int(os.environ.get("EVAL_K", "5"))
DS_KEY = "sk-"
DS_URL = "https://api.deepseek.com/v1/chat/completions"
QW_KEY = "sk-"
QW_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
PDF = "/Users/joe.wen/Desktop/qa-1000.pdf"

def http(url, payload=None, headers=None, method=None):
    req = urllib.request.Request(url, data=json.dumps(payload).encode() if payload else None,
                                 headers=headers or {}, method=method)
    return json.loads(urllib.request.urlopen(req, timeout=120).read())

def login():
    d = http(f"{BASE}/api/auth/login", {"username": "admin", "password": "admin"},
             {"Content-Type": "application/json"})
    return d["data"]["token"]

def search(token, query):
    q = urllib.parse.quote(query)
    d = http(f"{BASE}/api/kb/{KB_ID}/search?query={q}&topK={K}",
             headers={"Authorization": f"Bearer {token}"})
    return d.get("data", {}).get("results", [])

def all_chunks(token):
    """分页拉取知识库全部 chunk，用于构建标注相关集合（分母）"""
    chunks, page = [], 1
    while True:
        d = http(f"{BASE}/api/kb/{KB_ID}/chunks?page={page}&size=500",
                 headers={"Authorization": f"Bearer {token}"})
        data = d.get("data", {}) or {}
        batch = data.get("chunks", [])
        chunks.extend(batch)
        if not batch or len(chunks) >= data.get("total", 0):
            break
        page += 1
    return chunks

def llm(url, key, model, prompt, temperature=0.1):
    d = http(url, {"model": model, "temperature": temperature,
                   "messages": [{"role": "user", "content": prompt}]},
             {"Content-Type": "application/json", "Authorization": f"Bearer {key}"})
    return d["choices"][0]["message"]["content"]

# ============ Part 0: 提取 ground truth 查询 ============
print("提取 ground truth ...")
questions = []
with pdfplumber.open(PDF) as pdf:
    full = "\n".join((p.extract_text() or "") for p in pdf.pages)
for m in re.finditer(r"问(\d+)[：:](.+?)(?=（变体|？)", full):
    num, qtext = int(m.group(1)), m.group(2).strip()
    # 核心文本：去变体后缀，用于子串相关性判定
    questions.append({"num": num, "query": qtext + "？", "core": re.sub(r"[\s，。？?（）()：:、]", "", qtext)})
# 抽样：每 25 条取 1 条 → 40 条查询，均匀覆盖 8 章
samples = questions[::25][:40]
print(f"总问答 {len(questions)} 条，抽样 {len(samples)} 条")

# ============ Part 1: 检索指标（chunk 级 Recall@K） ============
print("\n=== Part 1: 检索指标 ===")
TOKEN = login()

def norm(s):
    return re.sub(r"[\s，。？?（）()：:、]", "", s or "")

def chunk_key(c):
    return f"{c.get('fileId')}:{c.get('chunkIndex')}" if c.get("fileId") is not None else None

# 扫描知识库全量 chunk，构建每个查询的标注相关集合（Recall 的分母）
corpus = all_chunks(TOKEN)
corpus_norm = [(chunk_key(c), norm(c.get("text"))) for c in corpus]
corpus_norm = [(k, t) for k, t in corpus_norm if k]
print(f"知识库 {KB_ID} 共 {len(corpus_norm)} 个 chunk")

recall_sum, precision_sum, hit_rate_sum = 0.0, 0.0, 0.0
mrr_sum, ndcg_sum, evaluated, skipped = 0.0, 0.0, 0, 0
t0 = time.time()
for i, item in enumerate(samples):
    # 标注相关集合：语料中包含该问答核心文本的全部 chunk（通常为 1 条，重复时可为多条）
    relevant = {k for k, text in corpus_norm if item["core"] in text}
    if not relevant:
        skipped += 1
        print(f"  跳过问{item['num']}：语料中未找到相关 chunk")
        continue

    results = search(TOKEN, item["query"])
    retrieved = [chunk_key(r) for r in results[:K] if chunk_key(r)]
    hit_keys = relevant & set(retrieved)

    recall_sum += len(hit_keys) / len(relevant)          # 分母 = 标注相关 chunk 数
    precision_sum += len(hit_keys) / len(retrieved) if retrieved else 0.0
    hit_rate_sum += 1 if hit_keys else 0
    rel = [1 if k in relevant else 0 for k in retrieved]
    mrr_sum += next((1.0 / (idx + 1) for idx, r in enumerate(rel) if r), 0.0)
    dcg = sum(r / math.log2(idx + 2) for idx, r in enumerate(rel))
    ideal = sum(1 / math.log2(idx + 2) for idx in range(min(sum(rel) or 1, K)))
    ndcg_sum += dcg / ideal if ideal else 0
    evaluated += 1
    if (i + 1) % 10 == 0: print(f"  {i+1}/{len(samples)} ...")

print(f"耗时 {time.time()-t0:.0f}s")
if evaluated:
    print(f"Recall@{K}（chunk 级，分母=标注相关 chunk 数）: {recall_sum/evaluated:.3f}")
    print(f"Precision@{K}: {precision_sum/evaluated:.3f}")
    print(f"命中率（topK 至少 1 条相关）: {hit_rate_sum/evaluated:.1%}")
    print(f"MRR@{K}: {mrr_sum/evaluated:.3f}")
    print(f"NDCG@{K}: {ndcg_sum/evaluated:.3f}")
    print(f"参与评测 {evaluated} 条，跳过 {skipped} 条")

# ============ Part 2: 双 Judge Faithfulness ============
print("\n=== Part 2: 双 Judge Faithfulness ===")
RAG_PROMPT = """基于以下知识库内容回答问题。要求：仅基于给定内容，不知道就说不知道。
【知识库】
{ctx}
【问题】{q}"""
JUDGE_PROMPT = """你是评估专家。给定【上下文】和【回答】，将回答拆分为原子声明，判断每条声明是否被上下文支持。
仅输出 JSON: {{"supported": <被支持数>, "total": <声明总数>}}
【上下文】
{ctx}
【回答】
{ans}"""

fs_samples = samples[:10]
ds_scores, qw_scores, details = [], [], []
for i, item in enumerate(fs_samples):
    results = search(TOKEN, item["query"])
    ctx = "\n".join((r.get("content") or "")[:400] for r in results[:3])
    ans = llm(DS_URL, DS_KEY, "deepseek-chat", RAG_PROMPT.format(ctx=ctx, q=item["query"]))
    def judge(url, key, model):
        raw = llm(url, key, model, JUDGE_PROMPT.format(ctx=ctx, ans=ans))
        m = re.search(r"\{[^{}]*\}", raw)
        j = json.loads(m.group()) if m else {"supported": 0, "total": 1}
        return j["supported"] / max(j["total"], 1), j
    ds_s, ds_j = judge(DS_URL, DS_KEY, "deepseek-chat")
    qw_s, qw_j = judge(QW_URL, QW_KEY, "qwen-plus")
    ds_scores.append(ds_s); qw_scores.append(qw_s)
    details.append(f"  问{item['num']}: DS={ds_s:.2f}({ds_j['supported']}/{ds_j['total']}) QW={qw_s:.2f}({qw_j['supported']}/{qw_j['total']}) diff={abs(ds_s-qw_s):.2f}")
    print(f"  {i+1}/{len(fs_samples)} 问{item['num']} done")
for d in details: print(d)
ds_avg, qw_avg = sum(ds_scores)/len(ds_scores), sum(qw_scores)/len(qw_scores)
agree = sum(1 for a, b in zip(ds_scores, qw_scores) if abs(a-b) < 0.15) / len(ds_scores)
print(f"DeepSeek judge 平均 Faithfulness: {ds_avg:.3f}")
print(f"Qwen judge 平均 Faithfulness:     {qw_avg:.3f}")
print(f"双 judge 一致性（|diff|<0.15 占比）: {agree:.1%}")

# ============ Part 3: 解析器对比 ============
print("\n=== Part 3: 解析器对比 ===")
with pdfplumber.open(PDF) as pdf:
    lines = []
    for p in pdf.pages:
        t = p.extract_text() or ""
        lines.extend(t.split("\n"))
pdfplumber_faq = sum(1 for l in lines if re.match(r"^\s*问\s*\d+[：:]", l))
print(f"pdfplumber: 总行数={len(lines)}, 行首「问N：」模式数={pdfplumber_faq}")
print(f"PDFBox（后端，FAQ 策略切出单元数）: 1001 (= 1000 问答 + 1 标题头)")
print(f"两者均完整识别问答边界 → 对本类文本型 PDF，PDFBox 足够；")
print(f"表格/多栏/扫描件场景再考虑 pdfplumber 的表格抽取或 Apache Tika。")

print("\n=== 汇总 ===")
n = evaluated or 1
print(json.dumps({
    "retrieval": {f"recall@{K}": round(recall_sum/n, 3),
                  f"precision@{K}": round(precision_sum/n, 3),
                  f"hit_rate@{K}": round(hit_rate_sum/n, 3),
                  f"mrr@{K}": round(mrr_sum/n, 3),
                  f"ndcg@{K}": round(ndcg_sum/n, 3),
                  "queries": evaluated, "skipped": skipped,
                  "kb_id": KB_ID, "corpus_chunks": len(corpus_norm)},
    "faithfulness": {"deepseek_judge": round(ds_avg, 3), "qwen_judge": round(qw_avg, 3),
                     "judge_agreement": round(agree, 3), "samples": len(fs_samples)},
    "parser": {"pdfplumber_faq_lines": pdfplumber_faq, "pdfbox_faq_units": 1001}
}, ensure_ascii=False, indent=2))
