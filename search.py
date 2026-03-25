"""
Web 搜索服务 —— 使用百度（国内直接访问）
"""
import asyncio
import logging
import re

import httpx

logger = logging.getLogger(__name__)

_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0.0.0 Safari/537.36"
    ),
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
    "Referer": "https://www.baidu.com/",
}


async def baidu_search(query: str, max_results: int = 5) -> list[dict]:
    """百度搜索，返回 [{title, url, snippet}]"""
    try:
        async with httpx.AsyncClient(
            timeout=12, follow_redirects=True, headers=_HEADERS
        ) as client:
            resp = await client.get(
                "https://www.baidu.com/s",
                params={"wd": query, "rn": max_results * 2, "ie": "utf-8"},
            )
            logger.info(
                "Baidu [%s] status=%s html_len=%d",
                query[:30], resp.status_code, len(resp.text),
            )
            results = _parse_baidu_html(resp.text, max_results)
            logger.info("Baidu search [%s] → %d results", query[:30], len(results))
            return results
    except Exception as e:
        logger.warning("Baidu search failed [%s]: %s", query[:30], e)
        return []


def _parse_baidu_html(html: str, max_results: int) -> list[dict]:
    """从百度 HTML 中提取搜索结果"""
    results: list[dict] = []

    # 1. 取主结果区域 content_left
    m_area = re.search(r'id="content_left"[^>]*>(.*?)</div>\s*<div[^>]+id="content_right"', html, re.DOTALL)
    if not m_area:
        # 备用：取整个 body
        m_area = re.search(r'<body[^>]*>(.*?)</body>', html, re.DOTALL)
    area = m_area.group(1) if m_area else html

    # 2. 按结果块分割（class 包含 result 的 div）
    blocks = re.split(r'(?=<div\s[^>]*class="[^"]*\bresult\b)', area)
    result_blocks = [b for b in blocks if re.match(r'<div\s[^>]*class="[^"]*\bresult\b', b)]
    logger.info("Baidu: 找到 %d 个结果块", len(result_blocks))

    for block in result_blocks:
        # 跳过广告（mu 属性或 result-op 含 ec_pp）
        if 'data-tuiguang' in block or 'ec_pp' in block[:200]:
            continue

        # 标题：h3 > a
        m_title = re.search(r'<h3[^>]*>.*?<a[^>]*href="([^"]*)"[^>]*>(.*?)</a>', block, re.DOTALL)
        if not m_title:
            continue
        href = m_title.group(1).strip()
        title = _strip_tags(m_title.group(2))
        if not title:
            continue

        # URL：优先取 <span class="c-showurl"> 或 <span data-showurl>，否则用 href
        m_url = re.search(
            r'<span[^>]*(?:class="c-showurl"|data-showurl)[^>]*>(.*?)</span>',
            block, re.DOTALL
        )
        if m_url:
            show = _strip_tags(m_url.group(1)).split(" ")[0]  # 去掉尾部空格说明
            url = show if show.startswith("http") else "https://" + show
        else:
            url = href

        if not url.startswith("http"):
            continue

        # 摘要：c-abstract 或 content-right 区域的第一个 <span>/<p>
        m_snip = (
            re.search(r'class="c-abstract[^"]*"[^>]*>(.*?)</div>', block, re.DOTALL)
            or re.search(r'<span[^>]*class="[^"]*content[^"]*"[^>]*>(.*?)</span>', block, re.DOTALL)
            or re.search(r'<p[^>]*>(.*?)</p>', block, re.DOTALL)
        )
        snippet = _strip_tags(m_snip.group(1))[:200] if m_snip else ""

        results.append({"title": title, "url": url, "snippet": snippet})
        if len(results) >= max_results:
            break

    return results


def _strip_tags(html: str) -> str:
    text = re.sub(r"<[^>]+>", " ", html)
    return re.sub(r"\s+", " ", text).strip()


async def multi_search(queries: list[str], max_per_query: int = 4) -> list[dict]:
    """并发执行多个搜索词，结果去重后返回"""
    tasks = [baidu_search(q, max_per_query) for q in queries]
    results_list = await asyncio.gather(*tasks)

    seen: set[str] = set()
    unique: list[dict] = []
    for results in results_list:
        for r in results:
            if r["url"] not in seen:
                seen.add(r["url"])
                unique.append(r)
    return unique
